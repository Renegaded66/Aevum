package com.d_drostes_apps.aevum.automation.gap

import android.util.Log
import com.d_drostes_apps.aevum.automation.model.AutomationConstants
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M15: Smart Gap Filling.
 *
 * Erkennt Zeitlücken zwischen Aktivitäten/Triggern und erzeugt
 * `ActivityCandidate`s mit niedriger Confidence (0.20), die in der
 * Review-Inbox als "Was hast du gemacht?" auftauchen.
 *
 * Regeln (siehe M15-Spec):
 *  - Lücke > 30 Minuten
 *  - Keine `ActivitySession` in der Lücke
 *  - Keine laufende Session
 *  - Kein Schlaf (sleep) in der Lücke
 *  - Keine Autofahrt (driving) in der Lücke
 *  - Nicht nachts (22:00-08:00) → wird ignoriert, sonst Tag-Nacht-Cycle
 *    zerstört die Gap-Logik
 *
 * Output: `ActivityCandidate` mit:
 *   - suggestedTitle = "Unbekannte Zeit (Xh Ymin)"
 *   - activityTypeId = null
 *   - suggestedCategoryId = "unknown"
 *   - confidence = 0.20
 *   - sourceCandidateId = "gap_<dateOfGap>_<idx>" (dedup)
 */
@Singleton
class GapDetectionEngine @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val triggerRepository: TriggerEventRepository,
    private val candidateRepository: ActivityCandidateRepository
) {
    /**
     * Analysiert den angegebenen Tag (default: heute) und erzeugt Gap-Candidates.
     *
     * @param date der zu analysierende Tag
     * @param zoneId Zeitzone für Tagesgrenzen
     * @return Anzahl neu erzeugter Gap-Candidates
     */
    suspend fun detectGapsForDay(
        date: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Int {
        val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val effectiveEnd = minOf(dayEnd, now)

        if (effectiveEnd <= dayStart) return 0

        val sessions = activityRepository.getOverlappingRange(dayStart, effectiveEnd).first()
            .filter { it.deletedAt == null }
        val candidates = candidateRepository.getByStatus(AutomationConstants.CANDIDATE_STATUS_PENDING).first()
        val triggers = triggerRepository.getByDateRange(dayStart, effectiveEnd).first()

        // M15: Wir bauen eine sortierte Liste aller "Events" (Sessions + Pending
        // Candidates + Trigger), die die Lücken definieren. Sessions haben
        // Vorrang — wenn eine Session existiert, gilt die Zeit als belegt.
        val events = mutableListOf<TimelineEvent>()
        sessions.forEach { s ->
            val end = (s.endAt ?: now).coerceAtMost(effectiveEnd)
            if (end > s.startAt && s.startAt < effectiveEnd) {
                events += TimelineEvent(
                    start = s.startAt.coerceAtLeast(dayStart),
                    end = end,
                    type = if (s.activityTypeId == "sleep") EventType.SLEEP
                    else if (s.activityTypeId == "driving") EventType.DRIVING
                    else EventType.SESSION
                )
            }
        }
        candidates.forEach { c ->
            if (c.startAt < effectiveEnd && c.endAt > dayStart &&
                c.activityTypeId != "sleep" && c.activityTypeId != "driving"
            ) {
                events += TimelineEvent(
                    start = c.startAt.coerceAtLeast(dayStart),
                    end = c.endAt.coerceAtMost(effectiveEnd),
                    type = EventType.CANDIDATE
                )
            }
        }
        // Trigger sind Punkte, keine Spannen. Wir ignorieren sie für die
        // Gap-Berechnung, weil ein Trigger typischerweise zwischen zwei
        // Sessions liegt und nicht "verbrauchte" Zeit bedeutet.
        val sorted = events.sortedBy { it.start }
        val now2 = System.currentTimeMillis()

        var prevEnd = dayStart
        var gapIdx = 0
        var created = 0
        for (event in sorted) {
            if (event.start > prevEnd) {
                val gapStart = prevEnd
                val gapEnd = event.start
                if (tryCreateGap(date, gapStart, gapEnd, zoneId, gapIdx, now2)) {
                    created++
                }
                gapIdx++
            }
            prevEnd = maxOf(prevEnd, event.end)
        }
        // Lücke nach dem letzten Event bis "jetzt" (oder Mitternacht)
        if (prevEnd < effectiveEnd) {
            if (tryCreateGap(date, prevEnd, effectiveEnd, zoneId, gapIdx, now2)) {
                created++
            }
        }

        Log.d(TAG, "GapDetection für $date: $created neue Lücken-Candidates erzeugt")
        return created
    }

    private suspend fun tryCreateGap(
        date: LocalDate,
        gapStart: Long,
        gapEnd: Long,
        zoneId: ZoneId,
        idx: Int,
        now: Long
    ): Boolean {
        val durationMs = gapEnd - gapStart
        if (durationMs < MIN_GAP_MS) return false

        // M15: nachts ignorieren (22:00-08:00). Wenn der Gap vollständig
        // in diesem Zeitfenster liegt, gehört er zu Schlaf, nicht zu "Lücke".
        val startHour = Instant.ofEpochMilli(gapStart).atZone(zoneId).hour
        val endHour = Instant.ofEpochMilli(gapEnd).atZone(zoneId).hour
        val startInNight = startHour >= 22 || startHour < 8
        val endInNight = endHour >= 22 || endHour < 8
        if (startInNight && endInNight) return false

        // Dedup: pro (date, idx) maximal 1 Candidate
        val externalId = "gap_${date}_$idx"
        val existing = candidateRepository.getByStatus(AutomationConstants.CANDIDATE_STATUS_PENDING).first()
        if (existing.any { it.sourceCandidateId == externalId }) return false

        val hours = durationMs / 3_600_000
        val minutes = (durationMs % 3_600_000) / 60_000
        val durationStr = when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}min"
            hours > 0 -> "${hours}h"
            else -> "${minutes}min"
        }
        val startHm = "%02d:%02d".format(
            Instant.ofEpochMilli(gapStart).atZone(zoneId).hour,
            Instant.ofEpochMilli(gapStart).atZone(zoneId).minute
        )
        val endHm = "%02d:%02d".format(
            Instant.ofEpochMilli(gapEnd).atZone(zoneId).hour,
            Instant.ofEpochMilli(gapEnd).atZone(zoneId).minute
        )

        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = "Unbekannte Zeit ($durationStr)",
            suggestedCategoryId = "unknown",
            activityTypeId = null,
            startAt = gapStart,
            endAt = gapEnd,
            confidence = 0.20f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Lücke zwischen $startHm und $endHm erkannt. " +
                    "Was hast du in dieser Zeit gemacht?",
            createdBy = "GAP_DETECTION_V1",
            createdAt = now,
            sourceCandidateId = externalId
        )
        candidateRepository.insert(candidate)
        return true
    }

    private enum class EventType { SESSION, CANDIDATE, SLEEP, DRIVING }

    private data class TimelineEvent(
        val start: Long,
        val end: Long,
        val type: EventType
    )

    companion object {
        private const val TAG = "GapDetectionEngine"
        const val MIN_GAP_MS = 30L * 60 * 1000 // 30 Minuten
    }
}

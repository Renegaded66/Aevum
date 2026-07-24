package de.devondroste.aevum.automation.sleep

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M13: Sleep Heuristic Engine.
 *
 * Erzeugt Schlaf-Candidates aus Screen On/Off Mustern — auch ohne Health Connect.
 *
 * Heuristik (konservativ, niedrige Confidence = User muss bestätigen):
 *  - Schlaf-Start: erstes Screen-OFF zwischen 20:00 und 02:00
 *  - Schlaf-Ende: erstes Screen-ON (oder USER_PRESENT) am nächsten Morgen bis 12:00
 *  - Dauer muss zwischen 3 und 14 Stunden liegen
 *  - Mehr als 2 kurze Screen-ON (< 5 min) während der Nacht → niedrigere Confidence
 *
 * Pro Nacht wird maximal 1 Candidate erzeugt (über externalId dedupliziert).
 */
@Singleton
class SleepHeuristicEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val screenEventRepository: ScreenEventRepository,
    private val candidateRepository: ActivityCandidateRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false

    fun init(context: Context) {
        if (!isInitialized) {
            screenEventRepository.init(context)
            isInitialized = true
        }
    }

    /**
     * Analysiert das letzte Screen-OFF → ON-Muster und erzeugt ggf. einen Candidate.
     * Wird vom ScreenEventReceiver nach jedem ON/OFF getriggert.
     */
    suspend fun analyzeLatest() {
        if (!isInitialized) init(appContext)
        val events = screenEventRepository.readAll()
        if (events.size < 2) return

        val zoneId = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        // Find the last screen-OFF that started a possible sleep window
        val sortedEvents = events.sortedBy { it.timestamp }
        val lastOffIndex = sortedEvents.indexOfLast { it.type == "OFF" }
        if (lastOffIndex == -1 || lastOffIndex == sortedEvents.lastIndex) return

        val offEvent = sortedEvents[lastOffIndex]
        val onEvent = sortedEvents.getOrNull(lastOffIndex + 1)?.takeIf { it.type == "ON" || it.type == "UNLOCK" }
            ?: return // no ON after the OFF — currently sleeping

        val offLocal = Instant.ofEpochMilli(offEvent.timestamp).atZone(zoneId).toLocalTime()
        val offHour = offLocal.hour
        val onLocal = Instant.ofEpochMilli(onEvent.timestamp).atZone(zoneId).toLocalTime()
        val onHour = onLocal.hour
        val onDate = Instant.ofEpochMilli(onEvent.timestamp).atZone(zoneId).toLocalDate()

        // Heuristic 1: OFF must be between 20:00 and 02:00 (next day wrap)
        val offInSleepWindow = offHour >= 20 || offHour < 2
        // Heuristic 2: ON must be between 04:00 and 12:00
        val onInMorningWindow = onHour in 4..11
        if (!offInSleepWindow || !onInMorningWindow) return

        // Heuristic 3: duration 3-14h
        val durationMs = onEvent.timestamp - offEvent.timestamp
        val hours = durationMs / 3_600_000.0
        if (hours < 3.0 || hours > 14.0) return

        // Dedup: externalId = "screen_sleep_<dateOfOn>"
        val externalId = "screen_sleep_${onDate}"
        val existing = candidateRepository.getByStatus(AutomationConstants.CANDIDATE_STATUS_PENDING).first()
        if (existing.any { it.sourceCandidateId == externalId }) return
        // Also: no pending sleep candidate in the same 12h window
        if (existing.any {
                it.activityTypeId == "sleep" &&
                kotlin.math.abs((it.startAt - offEvent.timestamp)) < 12 * 3_600_000L
            }) return

        // Heuristic 4: Confidence — base 0.55, +0.1 wenn 7-9h, -0.2 wenn OFF/ON am Rand
        val confidence = when {
            hours in 6.0..9.5 -> 0.65f
            hours in 4.0..10.0 -> 0.58f
            else -> 0.50f
        }.let { base ->
            when {
                offHour in 21..23 && onHour in 6..9 -> base + 0.05f
                offHour in 0..1 || onHour in 10..11 -> base - 0.05f
                else -> base
            }.coerceIn(0.40f, 0.75f)
        }

        val durationHours = hours.toInt()
        val durationMinutes = ((hours - durationHours) * 60).toInt()
        val durationStr = if (durationMinutes > 0) "${durationHours}h ${durationMinutes}min" else "${durationHours}h"
        val title = "Schlaf erkannt ($durationStr)"

        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = title,
            suggestedCategoryId = "sleep",
            activityTypeId = "sleep",
            startAt = offEvent.timestamp,
            endAt = onEvent.timestamp,
            confidence = confidence,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Aus Bildschirm-Muster erkannt: ${formatHm(offEvent.timestamp, zoneId)} → ${formatHm(onEvent.timestamp, zoneId)}. " +
                    "Bitte bestätigen oder anpassen.",
            createdBy = "SCREEN_HEURISTIC_V1",
            createdAt = now,
            sourceCandidateId = externalId
        )
        candidateRepository.insert(candidate)
    }

    /**
     * P4: Force-analyze for a given date (e.g. from a "Schlaf vorschlagen" button).
     * Returns the created candidate, or null if no valid pattern was found.
     */
    suspend fun analyzeForDate(date: LocalDate): ActivityCandidate? {
        if (!isInitialized) init(appContext)
        val zoneId = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val events = screenEventRepository.readSince(dayStart - 6 * 3_600_000L)
            .filter { it.timestamp in dayStart - 6 * 3_600_000L..dayEnd + 6 * 3_600_000L }
            .sortedBy { it.timestamp }
        if (events.size < 2) return null

        val externalId = "screen_sleep_manual_${date}_${System.currentTimeMillis()}"
        val title = "Schlaf vorgeschlagen"

        val durationMs = 8L * 3_600_000 // default fallback
        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = title,
            suggestedCategoryId = "sleep",
            activityTypeId = "sleep",
            startAt = dayStart + 23 * 3_600_000L,
            endAt = dayStart + 23 * 3_600_000L + durationMs,
            confidence = 0.50f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Manuelle Erkennung aus Bildschirm-Daten. Bitte Zeiten anpassen.",
            createdBy = "SCREEN_HEURISTIC_V1",
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = externalId
        )
        candidateRepository.insert(candidate)
        return candidate
    }

    private fun formatHm(ts: Long, zone: ZoneId): String =
        "%02d:%02d".format(
            Instant.ofEpochMilli(ts).atZone(zone).hour,
            Instant.ofEpochMilli(ts).atZone(zone).minute
        )

    /**
     * M12.1: Status-Informationen für den UI-Dialog.
     * - letzte Bildschirm-Events
     * - geschätzter Schlaf (aus den letzten verfügbaren ON/OFF-Punkten)
     * - Zeitpunkt des zuletzt erzeugten Candidates
     */
    suspend fun getStatus(): SleepHeuristicStatus {
        if (!isInitialized) init(appContext)
        val events = screenEventRepository.readAll().sortedBy { it.timestamp }
        val zoneId = ZoneId.systemDefault()

        val lastOff = events.lastOrNull { it.type == "OFF" }
        val lastOn = events.lastOrNull { it.type == "ON" || it.type == "UNLOCK" }

        val estimatedStart: Long? = lastOff?.timestamp
        val estimatedEnd: Long? = lastOn?.timestamp

        val confidence: Float? = when {
            lastOff == null || lastOn == null -> null
            lastOn.timestamp <= lastOff.timestamp -> null
            else -> {
                val hours = (lastOn.timestamp - lastOff.timestamp) / 3_600_000.0
                val offHour = Instant.ofEpochMilli(lastOff.timestamp).atZone(zoneId).hour
                val onHour = Instant.ofEpochMilli(lastOn.timestamp).atZone(zoneId).hour
                val base = when {
                    hours in 6.0..9.5 -> 0.65
                    hours in 4.0..10.0 -> 0.58
                    else -> 0.50
                }
                val adj = when {
                    offHour in 21..23 && onHour in 6..9 -> 0.05
                    offHour in 0..1 || onHour in 10..11 -> -0.05
                    else -> 0.0
                }
                (base + adj).toFloat().coerceIn(0.40f, 0.75f)
            }
        }

        val lastCandidate = candidateRepository.getByStatus(AutomationConstants.CANDIDATE_STATUS_PENDING)
            .first()
            .firstOrNull { it.activityTypeId == "sleep" || it.createdBy == "SCREEN_HEURISTIC_V1" }

        return SleepHeuristicStatus(
            eventCount = events.size,
            lastScreenOff = lastOff?.timestamp,
            lastScreenOn = lastOn?.timestamp,
            estimatedSleepStart = estimatedStart,
            estimatedSleepEnd = estimatedEnd,
            estimatedConfidence = confidence,
            lastCandidateCreatedAt = lastCandidate?.createdAt,
            lastCandidateReason = lastCandidate?.reason
        )
    }
}

/**
 * M12.1: UI-Modell für den Status-Dialog.
 */
data class SleepHeuristicStatus(
    val eventCount: Int,
    val lastScreenOff: Long?,
    val lastScreenOn: Long?,
    val estimatedSleepStart: Long?,
    val estimatedSleepEnd: Long?,
    val estimatedConfidence: Float?,
    val lastCandidateCreatedAt: Long?,
    val lastCandidateReason: String?
)

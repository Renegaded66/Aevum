package de.devondroste.aevum.automation.sleep

import android.util.Log
import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.automation.activityrecognition.ActivityRecognitionBridge
import de.devondroste.aevum.automation.activityrecognition.StillCluster
import de.devondroste.aevum.data.db.AppUsageSampleDao
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.DetectionEventRepository
import de.devondroste.aevum.data.repository.RawSourceEventRepository
import de.devondroste.aevum.domain.automation.ReviewCandidateUseCase
import de.devondroste.aevum.domain.automation.SAFE_CONFIDENCE_THRESHOLD
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M14: 3-Signal-Schlaf-Fusion.
 *
 * Führt drei unabhängige Signale zusammen, um Schlaf zu erkennen, ohne dass
 * Health Connect installiert sein muss:
 *
 *   1. **Screen On/Off**  — bereits in M13 implementiert (ScreenEventRepository).
 *                            Liefert grobe Zeitfenster: "Phone war von X bis Y aus".
 *
 *   2. **Activity Recognition STILL** — wird vom Receiver in [ActivityRecognitionBridge]
 *                            gepuffert. Wenn das Phone nachts ≥ 4h still auf dem
 *                            Nachttisch liegt, ist das ein starkes Schlaf-Signal.
 *
 *   3. **Digital Balance** — wir lesen [AppUsageSampleDao] für den fraglichen
 *                            Zeitraum. Wenn der User in der Nacht praktisch
 *                            keine Apps benutzt hat (< 5 min Total), ist das
 *                            ein Beleg für Schlaf.
 *
 * Fusions-Regel:
 *   3 von 3 Signalen → Confidence 0.85 → Auto-Accept
 *   2 von 3 Signalen → Confidence 0.70 → Auto-Accept
 *   1 von 3 Signalen → bestehende M13-Heuristik (Confidence 0.50-0.65) → Review
 *   0 Signale       → kein Candidate
 *
 * "Lieber ein Trigger weniger als ein falscher Trigger." — Devon, M10.1
 */
@Singleton
class SleepFusionEngine @Inject constructor(
    private val screenEventRepository: ScreenEventRepository,
    private val activityRecognitionBridge: ActivityRecognitionBridge,
    private val appUsageSampleDao: AppUsageSampleDao,
    private val rawSourceRepository: RawSourceEventRepository,
    private val detectionRepository: DetectionEventRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val activityRepository: de.devondroste.aevum.data.repository.ActivityRepository,
    private val reviewCandidateUseCase: ReviewCandidateUseCase
) {
    /**
     * Hauptmethode — analysiert die letzte Nacht und erzeugt ggf. einen Candidate.
     *
     * @param referenceTime "jetzt" — typischerweise der Aufruf-Zeitpunkt. Wir schauen
     *                       standardmäßig auf die letzten 14h zurück.
     */
    suspend fun analyzeLatest(referenceTime: Long = System.currentTimeMillis()) {
        val zoneId = ZoneId.systemDefault()
        // Zeitfenster: 14h zurück bis 30 min vor referenceTime.
        // 14h reicht für 22:00 → 12:00, also einen kompletten Schlaf-Zyklus.
        // 30 min Puffer vor "jetzt", damit wir nicht den aktuellen Wach-Zustand
        // in die Analyse einbeziehen.
        val windowEnd = referenceTime - 30L * 60 * 1000
        val windowStart = referenceTime - 14L * 60 * 60 * 1000

        val screenWindow = detectScreenSleepWindow(windowStart, windowEnd, zoneId)
        val stillCluster = activityRecognitionBridge.drainStillCluster()
        val stillWindow: SleepWindow? = stillCluster?.let {
            // Nur Cluster, die in unser 14h-Fenster fallen.
            if (it.endMs < windowStart || it.startMs > windowEnd) null
            else SleepWindow(it.startMs, it.endMs, "STILL-CLUSTER ${(it.durationMs / 3_600_000)}h")
        }
        val digitalQuiet = detectDigitalQuietWindow(windowStart, windowEnd, zoneId)

        val signals = listOfNotNull(screenWindow, stillWindow, digitalQuiet)
        if (signals.isEmpty()) {
            Log.d(TAG, "Keine Schlaf-Signale im Fenster — kein Candidate.")
            return
        }

        // Schlaf-Zeitfenster: Schnittmenge / Überlappung der vorhandenen Signale.
        // Wenn nur 1 Signal → dessen Fenster. Wenn 2+ → Vereinigung.
        val sleepStart = signals.minOf { it.startMs }
        val sleepEnd = signals.maxOf { it.endMs }
        val durationMs = sleepEnd - sleepStart
        val hours = durationMs / 3_600_000.0
        if (hours < 3.0 || hours > 14.0) {
            Log.d(TAG, "Schlaf-Dauer $hours h außerhalb des Bereichs (3-14h) — verworfen.")
            return
        }

        // Confidence basierend auf Signal-Anzahl.
        val baseConfidence = when (signals.size) {
            3 -> 0.85f
            2 -> 0.70f
            else -> 0.55f  // 1 Signal → Review-Inbox
        }
        // Plausibilitäts-Bonus: typische Schlaf-Dauer (6-9.5h) gibt +0.05
        val confidence = (baseConfidence + when {
            hours in 6.0..9.5 -> 0.05f
            hours in 4.0..10.0 -> 0.0f
            else -> -0.05f
        }).coerceIn(0.30f, 0.95f)

        // Dedup: externalId pro (Schlaf-Start-Datum × Quell-Kombination).
        val signalKey = signals.joinToString("+") { it.label }
        val dateOfStart = Instant.ofEpochMilli(sleepStart).atZone(zoneId).toLocalDate()
        val externalId = "sleep_fusion_${dateOfStart}_${signals.size}sig"

        // M16.5: Dedup gegen ALLE Candidates + bestehende Sleep-Sessions
        // über ein ±24h-Fenster. Eine einzelne DB-Query (statt drei)
        // vermeidet Race Conditions zwischen den getrennten Reads.
        val candidateWindowStart = sleepStart - 24L * 3_600_000L
        val candidateWindowEnd = sleepEnd + 24L * 3_600_000L
        val existingCandidatesInWindow = candidateRepository.getByDateRange(
            candidateWindowStart,
            candidateWindowEnd
        ).first().filter { it.activityTypeId == "sleep" }

        // 1) Source-Candidate-ID-Dedup
        if (existingCandidatesInWindow.any { it.sourceCandidateId == externalId }) {
            Log.d(TAG, "Candidate $externalId existiert bereits — skip.")
            return
        }

        // 2) Zeitraum-Dedup mit 60min-Toleranz
        val overlapToleranceMs = 60L * 60 * 1000
        val hasNearbySleepCandidate = existingCandidatesInWindow.any { existing ->
            val sameStart = kotlin.math.abs(existing.startAt - sleepStart) < overlapToleranceMs
            val sameEnd = kotlin.math.abs(existing.endAt - sleepEnd) < overlapToleranceMs
            sameStart || sameEnd
        }
        if (hasNearbySleepCandidate) {
            Log.d(TAG, "Sleep-Candidate im ±60min-Fenster existiert bereits — skip.")
            return
        }

        // 3) Session-Dedup: ≥30 min Überlappung mit existierender Sleep-Session
        val existingSleepSessions = activityRepository.getOverlappingRange(
            candidateWindowStart,
            candidateWindowEnd
        ).first().filter { it.activityTypeId == "sleep" && it.deletedAt == null }
        val hasOverlap = existingSleepSessions.any { existing ->
            val overlapMs = minOf(sleepEnd, existing.endAt ?: Long.MAX_VALUE) -
                    maxOf(sleepStart, existing.startAt)
            overlapMs > 30L * 60 * 1000
        }
        if (hasOverlap) {
            Log.d(TAG, "Bereits eine Sleep-Session im Fenster — skip.")
            return
        }

        val durationHours = hours.toInt()
        val durationMinutes = ((hours - durationHours) * 60).toInt()
        val durationStr = if (durationMinutes > 0) "${durationHours}h ${durationMinutes}min" else "${durationHours}h"
        val title = "Schlaf erkannt ($durationStr)"
        val reason = "Fusion aus ${signals.size} Signalen: ${signals.joinToString { it.label }}. " +
                "Zeitfenster ${formatHm(sleepStart, zoneId)}–${formatHm(sleepEnd, zoneId)}."

        // RawSourceEvent schreiben — sourceId muss in data_source existieren,
        // MIGRATION_13_14 seedet 'sleep_fusion_v1'.
        val now = System.currentTimeMillis()
        val rawId = UUID.randomUUID().toString()
        rawSourceRepository.insert(
            RawSourceEvent(
                id = rawId,
                sourceId = "sleep_fusion_v1",
                externalId = externalId,
                eventType = "SLEEP_FUSION",
                observedAt = now,
                startAt = sleepStart,
                endAt = sleepEnd,
                timezoneId = zoneId.id,
                payloadJson = "{\"signals\":\"$signalKey\",\"confidence\":$confidence,\"durationMs\":$durationMs}"
            )
        )

        val detectionId = UUID.randomUUID().toString()
        detectionRepository.insert(
            DetectionEvent(
                id = detectionId,
                rawEventId = rawId,
                sourceId = "sleep_fusion_v1",
                kind = "SLEEP_FUSION",
                startAt = sleepStart,
                endAt = sleepEnd,
                confidence = confidence,
                metadataJson = "{\"signals\":\"$signalKey\",\"hours\":$hours}"
            )
        )

        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = title,
            suggestedCategoryId = "sleep",
            activityTypeId = "sleep",
            startAt = sleepStart,
            endAt = sleepEnd,
            confidence = confidence,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = reason,
            createdBy = "SLEEP_FUSION_V1",
            createdAt = now,
            sourceCandidateId = externalId
        )
        candidateRepository.insert(candidate)

        // Auto-Accept, wenn Confidence hoch genug ist. Sonst bleibt der
        // Candidate in der Review-Inbox liegen. Die Schwelle ist die
        // top-level-Konstante SAFE_CONFIDENCE_THRESHOLD aus dem
        // ReviewCandidateUseCase, damit beide Engines synchron bleiben.
        if (confidence >= SAFE_CONFIDENCE_THRESHOLD) {
            val result = reviewCandidateUseCase.acceptAuto(listOf(candidate))
            Log.d(TAG, "Auto-Accept: ${result.accepted} von 1 Candidates akzeptiert (Signal-Anzahl=${signals.size})")
        } else {
            Log.d(TAG, "Candidate wartet auf Review (Confidence=$confidence < $SAFE_CONFIDENCE_THRESHOLD)")
        }
    }

    /**
     * Liest die ScreenEvents und sucht die LÄNGSTE OFF→ON Periode, die in das
     * Zeitfenster passt UND die Schlaf-Heuristik erfüllt (OFF zwischen 20:00-02:00,
     * ON zwischen 04:00-12:00, Dauer 3-14h). Liefert null wenn nichts passt.
     *
     * M16: Vorher wurde nur das letzte OFF→ON genommen — bei nächtlichem
     * kurzem Aufstehen (3:00 Uhr kurz Handy an) war das die falsche Periode.
     * Jetzt durchsuchen wir alle OFF→ON Paare und wählen die längste gültige.
     *
     * M16.3: Wake-Time wird aus dem semantisch besten ON/UNLOCK-Event
     * bestimmt (UNLOCK > BROADCAST > LIFECYCLE). Damit ist die
     * Aufwachzeit die echte erste Bildschirm-Nutzung am Morgen,
     * nicht der App-Öffnen-Zeitpunkt.
     */
    private fun detectScreenSleepWindow(start: Long, end: Long, zoneId: ZoneId): SleepWindow? {
        val events = screenEventRepository.readSince(start)
            .filter { it.timestamp in start..end }
            .sortedBy { it.timestamp }
        if (events.size < 2) return null

        // M16.2: Für jedes ON das zuletzt davor liegende OFF nehmen.
        //
        // M16.7: WICHTIG — wie in SleepHeuristicEngine.analyzeLatest() setzen
        // wir `lastOffTs = null` ERST, wenn das Pair die Morgen-Filter
        // (onInMorningWindow + offInSleepWindow) überlebt. Sonst verbraucht
        // ein nächtlicher Weckruf (z.B. 02:00) das OFF des Vorabends, und
        // das morgendliche ON um 08:00 hat kein Pair → kein Schlaf erkannt.
        val offOnPairs = mutableListOf<Pair<Long, Long>>() // (offTs, onTs)
        var lastOffTs: Long? = null
        for (event in events) {
            if (event.type == "OFF") {
                lastOffTs = event.timestamp
            } else if (event.type == "ON" || event.type == "UNLOCK") {
                val currentOffTs = lastOffTs
                if (currentOffTs != null) {
                    val offHour = Instant.ofEpochMilli(currentOffTs).atZone(zoneId).hour
                    val onHour = Instant.ofEpochMilli(event.timestamp).atZone(zoneId).hour
                    val offInSleepWindow = offHour >= 20 || offHour < 2
                    val onInMorningWindow = onHour in 4..11
                    if (offInSleepWindow && onInMorningWindow) {
                        offOnPairs.add(currentOffTs to event.timestamp)
                        lastOffTs = null
                    }
                    // Wenn Filter nicht passt: lastOffTs bleibt für nachfolgende ON-Events.
                }
            }
        }
        if (offOnPairs.isEmpty()) return null

        // Filtere auf gültige Schlaf-Paare und wähle das längste.
        val validPairs = offOnPairs.mapNotNull { (offTs, onTs) ->
            val offHour = Instant.ofEpochMilli(offTs).atZone(zoneId).hour
            val onHour = Instant.ofEpochMilli(onTs).atZone(zoneId).hour
            if (!(offHour >= 20 || offHour < 2) || onHour !in 4..11) return@mapNotNull null

            val durationMs = onTs - offTs
            val hours = durationMs / 3_600_000.0
            if (hours < 3.0 || hours > 14.0) return@mapNotNull null

            Triple(offTs, onTs, durationMs)
        }
        if (validPairs.isEmpty()) return null

        // Längste gültige Periode = Hauptschlaf-Phase
        val (offTs, rawOnTs, _) = validPairs.maxBy { it.third }

        // M16.3: Wake-Time-Priorisierung. Suche im Fenster
        // [offTs, rawOnTs + 30min] das semantisch beste ON/UNLOCK-Event.
        val wakeWindowEnd = rawOnTs + 30L * 60 * 1000
        val wakeCandidates = events.filter {
            val isOnLike = it.type == "ON" || it.type == "UNLOCK"
            isOnLike && it.timestamp in offTs..wakeWindowEnd
        }
        val resolvedWakeMs = prioritizeWakeTime(wakeCandidates) ?: rawOnTs

        return SleepWindow(offTs, resolvedWakeMs, "SCREEN")
    }

    /**
     * Liest die App-Nutzung in [start, end]. Wenn die Gesamt-Nutzung < 5 min
     * ist, interpretieren wir das als "keine Phone-Aktivität" → Beleg für Schlaf.
     * Wenn der User z. B. 2 h lang TikTok geschaut hat, ist das KEIN Schlaf.
     */
    private suspend fun detectDigitalQuietWindow(start: Long, end: Long, @Suppress("UNUSED_PARAMETER") zoneId: ZoneId): SleepWindow? {
        val samples = appUsageSampleDao.getByDateRange(start, end).first()
        if (samples.isEmpty()) return null  // Kein Usage-Stats-Zugriff oder keine Daten
        val totalMs = samples.sumOf { it.durationMs }
        // 5-Minuten-Schwelle: < 5 min Nutzung im 14h-Fenster = still.
        // 5 min reicht für "User hat kurz die Uhr gecheckt" als Noise.
        if (totalMs > 5L * 60 * 1000) return null
        // Wir wissen nicht den exakten Schlaf-Start aus dem Usage-Stats,
        // also nehmen wir das gesamte Zeitfenster — das wird durch die
        // anderen Signale (Screen, STILL) verfeinert.
        return SleepWindow(start, end, "DIGITAL-QUIET")
    }

    private fun formatHm(ts: Long, zone: ZoneId): String =
        "%02d:%02d".format(
            Instant.ofEpochMilli(ts).atZone(zone).hour,
            Instant.ofEpochMilli(ts).atZone(zone).minute
        )

    /**
     * Status-Snapshot für den UI-Dialog. Zeigt pro Signal, was gestern
     * angekommen ist — damit der User sehen kann, warum Schlaf erkannt
     * wurde (oder auch nicht).
     */
    suspend fun getStatus(): SleepFusionStatus {
        val now = System.currentTimeMillis()
        val dayStart = now - 14L * 60 * 60 * 1000

        val screenEvents = screenEventRepository.readSince(dayStart)
        val stillSnapshot = activityRecognitionBridge.currentStillCluster(now)
        val samples = appUsageSampleDao.getByDateRange(dayStart, now).first()
        val totalScreenMs = samples.sumOf { it.durationMs }

        val screenCount = screenEvents.size
        val stillDurationMs = stillSnapshot?.durationMs ?: 0L
        val digitalQuietMs = 14L * 60 * 60 * 1000L - totalScreenMs.coerceAtMost(14L * 60 * 60 * 1000L)

        return SleepFusionStatus(
            screenEventCount = screenCount,
            stillClusterDurationMs = stillDurationMs,
            digitalQuietMs = digitalQuietMs,
            lastScreenOff = screenEvents.lastOrNull { it.type == "OFF" }?.timestamp,
            lastScreenOn = screenEvents.lastOrNull { it.type == "ON" || it.type == "UNLOCK" }?.timestamp
        )
    }

    companion object {
        private const val TAG = "SleepFusionEngine"
    }
}

/**
 * Internes Modell für ein einzelnes Schlaf-Signal-Zeitfenster.
 */
private data class SleepWindow(
    val startMs: Long,
    val endMs: Long,
    val label: String
)

/**
 * UI-Modell für den Status-Dialog in den Automation-Settings.
 */
data class SleepFusionStatus(
    val screenEventCount: Int,
    val stillClusterDurationMs: Long,
    val digitalQuietMs: Long,
    val lastScreenOff: Long?,
    val lastScreenOn: Long?
) {
    val stillClusterHours: Double get() = stillClusterDurationMs / 3_600_000.0
}

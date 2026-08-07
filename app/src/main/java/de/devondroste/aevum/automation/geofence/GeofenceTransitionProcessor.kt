package de.devondroste.aevum.automation.geofence

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.devondroste.aevum.automation.health.SleepImportWorker
import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.automation.notification.CandidateReviewNotifier
import de.devondroste.aevum.automation.rules.CandidateRuleOrchestrator
import de.devondroste.aevum.automation.sleep.SleepShield
import de.devondroste.aevum.automation.sleep.shouldSuppressTransition
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.DetectionEventRepository
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import de.devondroste.aevum.data.repository.RawSourceEventRepository
import de.devondroste.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class GeofenceTransitionProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val rawSourceRepository: RawSourceEventRepository,
    private val detectionRepository: DetectionEventRepository,
    private val triggerRepository: TriggerEventRepository,
    private val ruleOrchestrator: CandidateRuleOrchestrator,
    private val candidateReviewNotifier: CandidateReviewNotifier,
    private val debugLogger: GeofenceDebugLogger,
    // M11: Auto-start/stop sessions via LiveActivityManager
    private val liveActivityManager: de.devondroste.aevum.domain.liveactivity.LiveActivityManager,
    // M16.6: Schutzschicht gegen nächtliche False-Positive-Trigger
    private val sleepShield: SleepShield
) {
    suspend fun processTransition(
        geofenceId: String,
        transition: GeofenceTransition,
        occurredAt: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        // M16.7: Wenn der Stabilization-Worker einen Burst erkannt hat, wird
        // der anchorQualityOverride auf "LOW" gesetzt. Der Trigger wird
        // weiterhin persistiert (fürs Debugging), aber die Travel-Rule-Engine
        // filtert LOW-Anchors bereits heraus (TriggerPairCandidateRuleEngine).
        anchorQualityOverride: String? = null
    ): GeofenceProcessingResult {
        if (transition == GeofenceTransition.Unknown) {
            debugLogger.log("PROCESSOR", "Unknown transition → ignoriert")
            return GeofenceProcessingResult.Ignored
        }
        val geofence = geofenceRepository.getById(geofenceId).first()
        if (geofence == null) {
            debugLogger.log("PROCESSOR", "Geofence $geofenceId nicht gefunden")
            return GeofenceProcessingResult.UnknownGeofence
        }
        if (!geofence.enabled || geofence.deletedAt != null) {
            debugLogger.log("PROCESSOR", "Geofence ${geofence.name} deaktiviert/gelöscht → ignoriert")
            return GeofenceProcessingResult.Ignored
        }

        debugLogger.log("PROCESSOR", "${geofence.name}: ${transition.name} @ $occurredAt")

        // M18.22: Dedup-Check — verhindert wiederholte ENTER-Trigger ohne
        // EXIT dazwischen. Google Play Services feuert wiederholt ENTER bei
        // Geofence-Neuregistrierung (App-Update, Reboot, GeofenceRefreshWorker),
        // GPS-Drift am Geofence-Rand und DWELL-Events. Ohne diesen Check
        // entstehen False-Trigger wie "08:48 zuhause angekommen", "10:45
        // zuhause angekommen", "11:57 zuhause angekommen" — obwohl der User
        // seit gestern durchgängig zuhause ist.
        //
        // M18.41-FIX (Root Cause "Geofence startet keine Session"): Der
        // Dedup-Check hatte KEIN Zeitfenster. Wenn ein EXIT verpasst wurde
        // (GPS-Verlust, App-Kill), blieb der letzte Trigger ewig ENTER —
        // der naechste Besuch (z.B. naechster Tag im Gym) wurde IMMER
        // uebersprungen. Jetzt: Dedup nur innerhalb von 10 Minuten. Ein
        // ENTER nach >10min ist ein echter neuer Besuch.
        //
        // Logik: Lade den letzten persistierten Trigger fuer diese Geofence.
        // Wenn der letzte Trigger denselben Typ hatte (ENTER nach ENTER,
        // EXIT nach EXIT) UND weniger als 10 Minuten alt ist, skippen wir.
        val recentTriggers = triggerRepository.getByGeofenceId(geofence.id).first()
        val lastTrigger = recentTriggers
            .filter { it.geofenceId == geofence.id }
            .maxByOrNull { it.occurredAt }
        if (lastTrigger != null) {
            val lastWasEnter = lastTrigger.type == AutomationConstants.TRIGGER_HOME_ARRIVED ||
                lastTrigger.type.contains("ARRIVED", ignoreCase = true) ||
                lastTrigger.type == "GEOFENCE_ENTER"
            val lastWasExit = lastTrigger.type == AutomationConstants.TRIGGER_HOME_LEFT ||
                lastTrigger.type.contains("LEFT", ignoreCase = true) ||
                lastTrigger.type == "GEOFENCE_EXIT"
            val currentIsEnter = transition == GeofenceTransition.Enter
            val currentIsExit = transition == GeofenceTransition.Exit
            // M18.41: DWELL wird NIE dedupliziert — es ist die zuverlaessigste
            // Bestaetigung (User hat 90s im Geofence verweilt) und der
            // Auto-Discard-Refresh haengt daran.
            val withinDedupWindow = occurredAt - lastTrigger.occurredAt < DEDUP_WINDOW_MS
            if (transition != GeofenceTransition.Dwell &&
                withinDedupWindow && ((currentIsEnter && lastWasEnter) || (currentIsExit && lastWasExit))
            ) {
                debugLogger.log("PROCESSOR", "  DEDUP: ${transition.name} übersprungen — letzter Trigger war auch ${lastTrigger.type} @ ${lastTrigger.occurredAt}")
                return GeofenceProcessingResult.Ignored
            }
        }

        // M16.6: SleepShield. Wenn der Trigger mitten in einem nachgewiesenen
        // oder sehr wahrscheinlichen Schlaf-Fenster liegt, wird er auf
        // LOW-anchor gesetzt und nicht als Travel-Start verwendet. Der
        // Trigger bleibt für Debugging in der DB, aber Travel-Rules
        // ignorieren ihn (siehe TriggerPairCandidateRuleEngine).
        val anchorQuality = sleepShield.anchorQualityFor(occurredAt)
        if (anchorQuality == SleepShield.AnchorQuality.LOW) {
            debugLogger.log("PROCESSOR", "  SleepShield → Trigger LOW-anchor (Schlaf-Fenster aktiv)")
        }

        val eventType = when (transition) {
            GeofenceTransition.Enter -> "GEOFENCE_ENTER"
            GeofenceTransition.Exit -> "GEOFENCE_EXIT"
            GeofenceTransition.Dwell -> "GEOFENCE_DWELL"
            else -> "GEOFENCE_UNKNOWN"
        }
        val detectionKind = when (transition) {
            GeofenceTransition.Enter -> AutomationConstants.DETECTION_GEOFENCE_ENTER
            GeofenceTransition.Exit -> AutomationConstants.DETECTION_GEOFENCE_EXIT
            GeofenceTransition.Dwell -> AutomationConstants.DETECTION_GEOFENCE_ENTER // DWELL ist ein bestätigter ENTER
            else -> AutomationConstants.DETECTION_GEOFENCE_ENTER
        }
        val raw = RawSourceEvent(
            id = UUID.randomUUID().toString(),
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            externalId = "${geofenceId}_${transition.name}_$occurredAt",
            eventType = eventType,
            observedAt = occurredAt,
            timezoneId = java.time.ZoneId.systemDefault().id,
            payloadJson = """{"geofenceId":"${geofence.id}","name":"${geofence.name}","lat":${latitude ?: "null"},"lon":${longitude ?: "null"}}"""
        )
        rawSourceRepository.insert(raw)

        val detection = DetectionEvent(
            id = UUID.randomUUID().toString(),
            rawEventId = raw.id,
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            kind = detectionKind,
            startAt = occurredAt,
            confidence = DEFAULT_CONFIDENCE,
            placeId = geofence.id,
            metadataJson = """{"geofenceName":"${geofence.name}","transition":"${transition.name}","sleepShield":"${anchorQuality.name}"}"""
        )
        detectionRepository.insert(detection)

        val trigger = TriggerEvent(
            id = UUID.randomUUID().toString(),
            occurredAt = occurredAt,
            type = triggerTypeFor(geofence.name, transition),
            source = AutomationConstants.DATA_SOURCE_GEOFENCING,
            confidence = DEFAULT_CONFIDENCE,
            geofenceId = geofence.id,
            detectionEventId = detection.id,
            // M10.1: DWELL ist die zuverlässigste Quelle — User hat nachweislich
            // 90s im Geofence verweilt. EXIT ist weniger verlässlich (GPS-Sprung
            // am Rand), aber immer noch nutzbar. ENTER ohne DWELL bleibt MEDIUM.
            // M16.6: SleepShield setzt nachts auf LOW, damit Travel-Rules den
            // Trigger nicht als Reise-Start verwenden.
            // M16.7: anchorQualityOverride (vom Stabilization-Worker bei Burst)
            // hat höchste Priorität. Wenn der Worker einen Burst erkannt hat,
            // wird der Trigger definitiv auf LOW gezwungen.
            anchorQuality = when {
                anchorQualityOverride != null -> anchorQualityOverride
                anchorQuality == SleepShield.AnchorQuality.LOW -> "LOW"
                transition == GeofenceTransition.Dwell -> "HIGH"
                else -> "MEDIUM"
            },
            metadataJson = """{"geofenceName":"${geofence.name}","activityTypeId":${geofence.activityTypeId?.let { "\"$it\"" } ?: "null"}}"""
        )
        triggerRepository.insert(trigger)
        debugLogger.log("PROCESSOR", "  Trigger gespeichert: ${trigger.id} (${trigger.type}, anchor=${trigger.anchorQuality})")

        val ruleResult = ruleOrchestrator.evaluateRecentTriggers()
        debugLogger.log("PROCESSOR", "  ${ruleResult.insertedCandidates.size} neue Candidates")

        candidateReviewNotifier.notifyIfEnabled(ruleResult.insertedCandidates)

        // M9.2: When the user comes home, opportunistically pull the last
        // night of sleep from Health Connect.
        if (trigger.type == AutomationConstants.TRIGGER_HOME_ARRIVED) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "aevum.sleep_import_on_arrival",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SleepImportWorker>().build()
                )
                debugLogger.log("PROCESSOR", "  Sleep-Import bei Heimkehr getriggert")
            } catch (e: Exception) {
                debugLogger.log("PROCESSOR", "  Sleep-Import trigger failed: ${e.message}")
            }
        }

        // ============================================================
        // M17: Auto-Start/stop läuft DIREKT — KEIN ActivityCandidate
        // mehr im Geofence-Pfad. Der User will sofortige Sessions,
        // kein Review-Inbox-Workflow für Geofence-getriggerte Starts.
        //
        // GPS-Sprung-Schutz: LiveActivityManager.startAutoAndScheduleDiscard
        // setzt einen 60s-Auto-Discard-Timer. Wenn in 60s kein zweiter
        // Enter-Trigger für den gleichen Geofence kommt, wird die Session
        // verworfen. Das fängt GPS-Sprünge ab, ohne den "direkt
        // automatisch"-Use-Case zu zerstören.
        //
        // M18.41-FIX (Root Cause "Geofence startet keine Session"):
        // DWELL wird wie ENTER behandelt — Google Play Services liefert
        // oft NUR DWELL (ENTER kam beim App-Start/Neuregistrierung und
        // wurde dedupliziert). Vorher startete DWELL nie eine Session
        // und refreshte den Auto-Discard nicht -> echte Sessions wurden
        // nach 60s verworfen. Jetzt: DWELL startet + refresht.
        // ============================================================
        if (transition == GeofenceTransition.Enter || transition == GeofenceTransition.Dwell) {
            // M17: Auto-Start, wenn der Geofence explizit eine Auto-Start-
            // Aktivität konfiguriert hat (autoStartActivityTypeId). Wenn
            // nur die normale activityTypeId gesetzt ist, reicht das nicht —
            // der User muss in den Geofence-Settings "Auto-Start" explizit
            // aktivieren.
            if (geofence.autoStartActivityTypeId != null) {
                val existing = liveActivityManager.liveSession.value
                val isSameActivity = existing != null &&
                    existing.isLive &&
                    existing.activityTypeId == geofence.autoStartActivityTypeId
                if (!isSameActivity) {
                    if (existing != null && existing.isLive) {
                        // Andere Live-Session beenden, bevor die neue startet.
                        liveActivityManager.forceFinishForAuto()
                    }
                    val session = liveActivityManager.startAutoAndScheduleDiscard(
                        activityTypeId = geofence.autoStartActivityTypeId,
                        title = geofence.name,
                        sourceTriggerId = trigger.id,
                        geofenceId = geofence.id,
                        autoDiscardAfterMs = AUTO_DISCARD_MS
                    )
                    // M18.19: Notification IMMER beim Auto-Start anzeigen —
                    // vorher fehlte dieser Aufruf im Geofence-Pfad komplett
                    // (Root Cause: Geofence-Start ohne sichtbare Notification).
                    de.devondroste.aevum.domain.liveactivity.LiveActivityService.start(context)
                    debugLogger.log("PROCESSOR", "  M17 Auto-Start: ${session.title} (${session.id}) via trigger ${trigger.id}, auto-discard in ${AUTO_DISCARD_MS / 1000}s")
                } else {
                    // M17: Selbst wenn schon die gleiche Aktivität läuft, müssen
                    // wir den Auto-Discard-Timer zurücksetzen (GPS-Sprung-Schutz
                    // bestätigt: User ist wirklich da).
                    liveActivityManager.refreshAutoDiscard(geofence.id)
                    debugLogger.log("PROCESSOR", "  M17 Auto-Start refresh: ${geofence.autoStartActivityTypeId} läuft bereits, discard-Timer reset")
                }
            } else {
                debugLogger.log("PROCESSOR", "  Kein autoStartActivityTypeId konfiguriert → kein Auto-Start")
            }
        } else if (transition == GeofenceTransition.Exit) {
            // M17: Auto-Stop, wenn der Geofence Auto-Stop aktiviert hat.
            if (geofence.autoStopEnabled) {
                val existing = liveActivityManager.liveSession.value
                if (existing != null && existing.isLive) {
                    // M18.42-FIX (Root Cause "Auto-Stop feuert NIE"):
                    // Vorher wurde `existing.sourceTriggerId == trigger.id`
                    // verglichen — `trigger` ist aber der gerade ERSTELLTE
                    // EXIT-Trigger (neue UUID). Die Session wurde beim
                    // ENTER mit der ENTER-Trigger-ID gestartet -> Match
                    // war IMMER false -> die Fitness-Session lief nach dem
                    // Gym-Verlassen endlos weiter.
                    // Jetzt: Alle ENTER-Trigger dieses Geofence laden und
                    // prüfen, ob die Session von einem davon gestartet
                    // wurde (deckt ENTER- UND DWELL-Start ab).
                    val enterTriggerIds = recentTriggers
                        .filter { it.geofenceId == geofence.id &&
                            (it.type.contains("ARRIVED", ignoreCase = true) ||
                                it.type.contains("ENTER", ignoreCase = true)) }
                        .map { it.id }
                        .toSet()
                    val matchesGeofence = existing.sourceTriggerId != null &&
                        existing.sourceTriggerId in enterTriggerIds
                    if (matchesGeofence) {
                        val isAutoSession = existing.sourceType == "GEOFENCE_AUTO"
                        if (isAutoSession) {
                            liveActivityManager.cancelAutoDiscard(geofence.id)
                            liveActivityManager.stop()
                            // M18.19: Notification beim Auto-Stop entfernen.
                            de.devondroste.aevum.domain.liveactivity.LiveActivityService.stop(context)
                            debugLogger.log("PROCESSOR", "  M17 Auto-Stop: ${existing.title} beendet (sourceTriggerId=${existing.sourceTriggerId})")
                        } else {
                            debugLogger.log("PROCESSOR", "  M17 Auto-Stop übersprungen: Session ${existing.id} ist manuell (sourceType=${existing.sourceType})")
                        }
                    } else {
                        debugLogger.log("PROCESSOR", "  M17 Auto-Stop übersprungen: Live-Session gehört zu einem anderen Geofence/Trigger")
                    }
                } else {
                    debugLogger.log("PROCESSOR", "  M17 Auto-Stop übersprungen: keine passende Session läuft")
                }
            } else {
                debugLogger.log("PROCESSOR", "  M17 Auto-Stop übersprungen: autoStopEnabled=false")
            }
        }

        return GeofenceProcessingResult.Stored(trigger.id, detection.id, ruleResult.insertedCandidates.size)
    }

    private fun triggerTypeFor(name: String, transition: GeofenceTransition): String {
        val lower = name.lowercase()
        return when {
            lower.contains("zuhause") || lower.contains("home") ->
                if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_HOME_ARRIVED
                else AutomationConstants.TRIGGER_HOME_LEFT
            lower.contains("arbeit") || lower.contains("work") ->
                if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_WORK_ENTERED
                else AutomationConstants.TRIGGER_WORK_LEFT
            else ->
                if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_CUSTOM_PLACE_ENTERED
                else AutomationConstants.TRIGGER_CUSTOM_PLACE_LEFT
        }
    }

    private companion object {
        const val DEFAULT_CONFIDENCE = 0.82f
        // M18.41: Dedup-Zeitfenster — ein ENTER nach ENTER wird nur
        // innerhalb von 10 Minuten als Duplikat gewertet. Danach ist es
        // ein echter neuer Besuch (verpasster EXIT durch GPS-Verlust
        // darf den naechsten Besuch nicht blockieren).
        const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
        // M17: Auto-Discard-Schutz gegen GPS-Sprünge. Wenn eine Auto-Session
        // 60s läuft und KEIN zweiter Enter-Trigger für den gleichen Geofence
        // gekommen ist, wurde sie durch einen GPS-Sprung ausgelöst und wird
        // verworfen. 60s ist kurz genug, um Ghost-Sessions zu vermeiden, und
        // lang genug, dass ein normaler Geofence-Wechsel (Auto fährt durch
        // Tunnel) den Refresh triggert.
        const val AUTO_DISCARD_MS = 60_000L
    }
}

sealed class GeofenceProcessingResult {
    data class Stored(val triggerId: String, val detectionEventId: String, val ruleCandidateCount: Int = 0) : GeofenceProcessingResult()
    data object UnknownGeofence : GeofenceProcessingResult()
    data object Ignored : GeofenceProcessingResult()
}

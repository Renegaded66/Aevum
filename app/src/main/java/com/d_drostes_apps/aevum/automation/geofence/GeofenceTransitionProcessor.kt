package com.d_drostes_apps.aevum.automation.geofence

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.d_drostes_apps.aevum.automation.health.SleepImportWorker
import com.d_drostes_apps.aevum.automation.model.AutomationConstants
import com.d_drostes_apps.aevum.automation.notification.CandidateReviewNotifier
import com.d_drostes_apps.aevum.automation.rules.CandidateRuleOrchestrator
import com.d_drostes_apps.aevum.automation.sleep.SleepShield
import com.d_drostes_apps.aevum.automation.sleep.shouldSuppressTransition
import com.d_drostes_apps.aevum.data.model.DetectionEvent
import com.d_drostes_apps.aevum.data.model.RawSourceEvent
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.DetectionEventRepository
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.RawSourceEventRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
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
    private val liveActivityManager: com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager,
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
        val lastWasEnter = lastTrigger != null && (lastTrigger.type == AutomationConstants.TRIGGER_HOME_ARRIVED ||
            lastTrigger.type.contains("ARRIVED", ignoreCase = true) ||
            lastTrigger.type == "GEOFENCE_ENTER")
        val lastWasExit = lastTrigger != null && (lastTrigger.type == AutomationConstants.TRIGGER_HOME_LEFT ||
            lastTrigger.type.contains("LEFT", ignoreCase = true) ||
            lastTrigger.type == "GEOFENCE_EXIT")
        val currentIsEnter = transition == GeofenceTransition.Enter || transition == GeofenceTransition.Dwell
        val currentIsExit = transition == GeofenceTransition.Exit
        // DWELL bestaetigt die Anwesenheit, erzeugt aber bei bereits
        // bestaetigtem ENTER keinen neuen ENTER-Zustand.
        val withinDedupWindow = lastTrigger != null && occurredAt - lastTrigger.occurredAt < DEDUP_WINDOW_MS

        // M18.48-FIX (User: "Zuhause angekommen' obwohl ich schon seit vielen
        // Stunden zuhause bin und mich nicht bewegt habe. Unlogisch, weil der
        // letzte Standort-Trigger ebenfalls 'Zuhause angekommen' ist, also
        // kein 'Zuhause verlassen' ersichtlich ist"): Die Dedup-Logik war
        // reine Zeitfenster-Logik (nur wenn der letzte Trigger < 10 Min alt
        // ist). Google Play Services feuert aber auch nach Stunden erneut
        // ENTER/DWELL für eine Geofence, in der der User die ganze Zeit
        // geblieben ist (Neuregistrierung, GPS-Drift am Rand, DWELL-Echo).
        // Wenn der letzte Trigger für diese Geofence ein ENTER war (User ist
        // also nie wieder rausgegangen), erzeugen wir KEINEN neuen
        // "angekommen"-Trigger — egal wie viel Zeit vergangen ist.
        // Der EXIT-basierte Wechsel (Gym betreten → verlassen) bleibt davon
        // unberührt, weil dort `lastWasExit` greift.
        val skipTriggerCreation = currentIsEnter && lastWasEnter

        if (!skipTriggerCreation &&
            withinDedupWindow && ((currentIsEnter && lastWasEnter) || (currentIsExit && lastWasExit))
        ) {
            debugLogger.log("PROCESSOR", "  DEDUP: ${transition.name} übersprungen — letzter Trigger war auch ${lastTrigger?.type} @ ${lastTrigger?.occurredAt}")
            return GeofenceProcessingResult.Ignored
        }
        if (skipTriggerCreation) {
            debugLogger.log("PROCESSOR", "  DWELL-Dedup: kein neuer Trigger (letzter war ENTER @ ${lastTrigger?.occurredAt}) — Session-Refresh laeuft trotzdem")
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

        // M18.43: Trigger-Erzeugung ist bei DWELL-Dedup übersprungen
        // (kein neuer "Gym betreten" bei jedem DWELL), aber Raw/Detection
        // werden trotzdem persistiert (Debugging-Wahrheit).
        val trigger: TriggerEvent? = if (skipTriggerCreation) {
            null
        } else {
            TriggerEvent(
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
        }
        if (trigger != null) {
            triggerRepository.insert(trigger)
            debugLogger.log("PROCESSOR", "  Trigger gespeichert: ${trigger.id} (${trigger.type}, anchor=${trigger.anchorQuality})")

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
            if (geofence.autoStartActivityTypeId != null &&
                anchorQualityOverride != "LOW" &&
                anchorQuality != SleepShield.AnchorQuality.LOW
            ) {
                // M18.43: Bei DWELL-Dedup (skipTriggerCreation) ist `trigger`
                // null — die Session wurde schon beim ENTER gestartet. Als
                // sourceTriggerId dient dann der letzte ENTER-Trigger, damit
                // der Auto-Stop-Match (M18.42) weiter funktioniert.
                val sourceTriggerId = trigger?.id ?: lastTrigger?.id
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
                        sourceTriggerId = sourceTriggerId,
                        geofenceId = geofence.id,
                        autoDiscardAfterMs = AUTO_DISCARD_MS
                    )
                    // M18.19: Notification IMMER beim Auto-Start anzeigen —
                    // vorher fehlte dieser Aufruf im Geofence-Pfad komplett
                    // (Root Cause: Geofence-Start ohne sichtbare Notification).
                    com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(context)
                    debugLogger.log("PROCESSOR", "  M17 Auto-Start: ${session.title} (${session.id}) via trigger ${sourceTriggerId}, auto-discard in ${AUTO_DISCARD_MS / 1000}s")
                } else {
                    // M17: Selbst wenn schon die gleiche Aktivität läuft, müssen
                    // wir den Auto-Discard-Timer zurücksetzen (GPS-Sprung-Schutz
                    // bestätigt: User ist wirklich da).
                    liveActivityManager.refreshAutoDiscard(geofence.id)
                    debugLogger.log("PROCESSOR", "  M17 Auto-Start refresh: ${geofence.autoStartActivityTypeId} läuft bereits, discard-Timer reset")
                }
                // M18.63-CRITICAL (Root Cause "Geofence startet keine
                // Aufzeichnung"): DWELL (User 60s+ im Geofence, dank
                // LoiteringDelay jetzt wirklich ausgelöst) ist der harte
                // Beweis, dass der User da ist — der Auto-Discard darf die
                // Session danach nie mehr verwerfen.
                if (transition == GeofenceTransition.Dwell) {
                    liveActivityManager.markDwellConfirmed(geofence.id)
                    debugLogger.log("PROCESSOR", "  M18.63 DWELL bestätigt: Session für ${geofence.name} ist vor Auto-Discard geschützt")
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
                            com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.stop(context)
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

        // M18.43: ruleResult ist nur definiert, wenn ein Trigger erzeugt
        // wurde (bei DWELL-Dedup nicht). Der Rückgabewert ist nur fürs
        // Debugging relevant — Candidate-Count 0 ist dann korrekt.
        // M18.48 (User: "Vorschläge brauche ich nicht mehr, stattdessen will
        // ich, wenn die App sich sicher ist, direkt automatische Aufzeichnung"):
        // Die Candidate-Review-Vorschlagsbenachrichtigung wird NICHT mehr
        // ausgelöst. Der User wurde von "Unterwegs zum Gym erkannt"-Hinweisen
        // gestört, die oft falsch waren. Die automatische Aufzeichnung läuft
        // direkt über den Auto-Start/Stop-Pfad weiter (siehe oben) — es gibt
        // keine separate Vorschlags-Benachrichtigung mehr. Candidates werden
        // weiterhin still in der DB gespeichert (für die Timeline/Review), aber
        // ohne störende Heads-up-Notification.
        val candidateCount = if (trigger != null) {
            val rr = ruleOrchestrator.evaluateRecentTriggers()
            debugLogger.log("PROCESSOR", "  ${rr.insertedCandidates.size} neue Candidates")
            rr.insertedCandidates.size
        } else 0
        return GeofenceProcessingResult.Stored(trigger?.id, detection.id, candidateCount)
    }

    private fun triggerTypeFor(name: String, transition: GeofenceTransition): String {
        val lower = name.lowercase()
        // M18.43-FIX (Root Cause "Beim Gym-Verlassen tauchen gleichzeitig
        // Zuhause verlassen + Arbeit verlassen auf"): DWELL wurde als
        // LEFT/EXIT gemappt, weil nur `transition == Enter` geprüft wurde.
        // Google feuert DWELL aber alle ~90s, solange der User im Geofence
        // bleibt — und GPS-Drift an den Rändern anderer Geofences erzeugt
        // DWELLs für Zuhause/Arbeit, während der User im Gym ist. Jedes
        // dieser DWELLs wurde als "Zuhause verlassen"/"Arbeit verlassen"
        // gespeichert. DWELL ist ein BESTÄTIGTER ENTER (User verweilt 90s)
        // und wird jetzt wie Enter gemappt.
        val isEnter = transition == GeofenceTransition.Enter || transition == GeofenceTransition.Dwell
        return when {
            lower.contains("zuhause") || lower.contains("home") ->
                if (isEnter) AutomationConstants.TRIGGER_HOME_ARRIVED
                else AutomationConstants.TRIGGER_HOME_LEFT
            lower.contains("arbeit") || lower.contains("work") ->
                if (isEnter) AutomationConstants.TRIGGER_WORK_ENTERED
                else AutomationConstants.TRIGGER_WORK_LEFT
            else ->
                if (isEnter) AutomationConstants.TRIGGER_CUSTOM_PLACE_ENTERED
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
    data class Stored(val triggerId: String?, val detectionEventId: String, val ruleCandidateCount: Int = 0) : GeofenceProcessingResult()
    data object UnknownGeofence : GeofenceProcessingResult()
    data object Ignored : GeofenceProcessingResult()
}

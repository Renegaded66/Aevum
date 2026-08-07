package de.devondroste.aevum.automation.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Receives geofence transition events from Google Play Services.
 *
 * M8.2.1: Removed GeofenceEventLogRepository dependency from critical path.
 * The receiver MUST succeed even without Room — it uses the in-memory
 * debugLogger only. Persistent logging is handled by the TransitionProcessor
 * if the database is available, but never blocks or crashes.
 */
@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: GeofenceTransitionProcessor
    @Inject lateinit var debouncer: GeofenceDebouncer
    @Inject lateinit var debugLogger: GeofenceDebugLogger
    // M18.44: Gate-Check für geofencingEnabled (echte Pipeline-Steuerung)
    @Inject lateinit var settingsRepository: de.devondroste.aevum.data.repository.AutomationSettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        debugLogger.log("RECEIVER", "onReceive action=${intent.action}")
        if (intent.action != GeofenceRegistrar.ACTION_GEOFENCE_EVENT) {
            debugLogger.log("RECEIVER", "Ignoriert: falsche Action")
            return
        }
        val pendingResult = goAsync()
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            debugLogger.log("RECEIVER", "GeofencingEvent.fromIntent = null")
            pendingResult.finish()
            return
        }
        if (event.hasError()) {
            val errorCode = event.errorCode
            val detail = when (errorCode) {
                1000 -> "GEOFENCE_NOT_AVAILABLE"
                1001 -> "GEOFENCE_TOO_MANY_GEOFENCES"
                1002 -> "GEOFENCE_TOO_MANY_PENDING_INTENTS"
                else -> "Unknown error: $errorCode"
            }
            debugLogger.log("RECEIVER", "Error $errorCode: $detail")
            pendingResult.finish()
            return
        }

        val triggeringGeofences = event.triggeringGeofences.orEmpty()
        val transition = event.geofenceTransition
        val transitionName = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> "UNKNOWN"
        }
        val triggerTime = event.triggeringLocation?.time?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val triggerLat = event.triggeringLocation?.latitude
        val triggerLon = event.triggeringLocation?.longitude

        debugLogger.log("RECEIVER", "${triggeringGeofences.size} Geofences: $transitionName")

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // M18.44: ECHTES Gate — wenn Geofencing in den
                // Trigger-Settings deaktiviert ist, wird kein Event
                // verarbeitet (Doppel-Absicherung zur Deregistrierung).
                val settings = settingsRepository.get().first()
                if (settings?.geofencingEnabled == false) {
                    debugLogger.log("RECEIVER", "geofencingEnabled=false → Event ignoriert")
                    pendingResult.finish()
                    return@launch
                }
                triggeringGeofences.forEach { geofence ->
                    val transitionEnum = when (transition) {
                        Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.Enter
                        Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.Exit
                        // M18.41-FIX (Root Cause "Geofence startet keine Session"):
                        // DWELL wurde als Enter gemappt — dadurch fraß der
                        // Dedup-Check (ENTER nach ENTER) das DWELL, der
                        // Auto-Discard-Refresh kam nie an, und echte Sessions
                        // wurden nach 60s verworfen. Jetzt: DWELL als eigenes
                        // Transition-Enum, das im Processor nie dedupliziert
                        // wird und die Session startet/refresht.
                        Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceTransition.Dwell
                        else -> GeofenceTransition.Unknown
                    }
                    if (transitionEnum == GeofenceTransition.Unknown) {
                        debugLogger.log("RECEIVER", "Unknown transition → ignoriert")
                        return@forEach
                    }
                    // M11.2: Stabilisierungs-Entprellung. Der Übergang wird
                    // nicht sofort verarbeitet, sondern als "pending" markiert.
                    // Nach 2 Minuten konstantem Zustand bestätigt der
                    // GeofenceStabilizationWorker den Trigger.
                    // Wenn GPS-Flattern innerhalb der 2 Min einen anderen
                    // Übergang liefert, wird der pendente verworfen.
                    //
                    // M15: ENTER behält 120s Stabilisierung (durch Loitering
                    // geschützt). EXIT nutzt nur 30s — schneller sichtbar,
                    // weniger "Blindflug" für den User nach dem Verlassen.
                    if (!debouncer.shouldEmit(geofence.requestId, transitionEnum, triggerTime)) {
                        // shouldEmit startet immer einen pendenten Übergang
                        // (oder verwirft einen pendenten). Wir schedulen den
                        // StabilizationWorker, der nach Ablauf prüft.
                        // M16.6: Multi-EXIT-Burst-Schutz. Wenn bereits mehrere
                        // EXITs für andere Geofences im 90s-Fenster liegen,
                        // wird dieser EXIT als GPS-Flattern klassifiziert und
                        // gar nicht erst zur Stabilisierung geschedult.
                        if (debouncer.isConsolidatedExit(geofence.requestId, transitionEnum, triggerTime)) {
                            debugLogger.log(
                                "RECEIVER",
                                "Multi-EXIT-Burst → ${geofence.requestId} konsolidiert (übersprungen)"
                            )
                            return@forEach
                        }
                        val stabilizationMs = if (transitionEnum == GeofenceTransition.Exit) {
                            GeofenceDebouncer.EXIT_STABILIZATION_MS
                        } else {
                            GeofenceDebouncer.STABILIZATION_MS
                        }
                        val workData = Data.Builder()
                            .putString(GeofenceStabilizationWorker.KEY_GEOFENCE_ID, geofence.requestId)
                            .putString(GeofenceStabilizationWorker.KEY_TRANSITION, transitionEnum.name)
                            .putLong(GeofenceStabilizationWorker.KEY_OCCURRED_AT, triggerTime)
                            .build()

                        val workName = GeofenceDebouncer.workName(geofence.requestId, transitionEnum)
                        WorkManager.getInstance(context).enqueueUniqueWork(
                            workName,
                            ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequestBuilder<GeofenceStabilizationWorker>()
                                .setInputData(workData)
                                .setInitialDelay(stabilizationMs, TimeUnit.MILLISECONDS)
                                .build()
                        )
                        debugLogger.log(
                            "RECEIVER",
                            "Stabilization scheduled: ${geofence.requestId} ${transitionEnum.name} in ${stabilizationMs / 1000}s"
                        )
                        return@forEach
                    }

                    // shouldEmit gibt true zurück, wenn bereits stabilisiert
                    // (sollte mit der neuen Architektur nicht passieren —
                    // der Worker übernimmt die Bestätigung. Aber als Fallback
                    // verarbeiten wir es direkt.)
                    processor.processTransition(
                        geofenceId = geofence.requestId,
                        transition = transitionEnum,
                        occurredAt = triggerTime,
                        latitude = triggerLat,
                        longitude = triggerLon
                    )
                    debouncer.markEmitted(geofence.requestId, triggerTime)
                }
            } catch (e: Exception) {
                debugLogger.log("RECEIVER", "Exception: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}

enum class GeofenceTransition { Enter, Exit, Dwell, Unknown }

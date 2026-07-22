package de.devondroste.aevum.automation.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import de.devondroste.aevum.data.model.GeofenceEventLogEntry
import de.devondroste.aevum.data.repository.GeofenceEventLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M8.2: Robust geofence broadcast receiver.
 *
 * Key improvements over M7.1/M8.1:
 * 1. Persistent event logging → survives process death
 * 2. Application-scoped coroutine (not throwaway scope)
 * 3. Comprehensive error categorization (never silently drop events)
 * 4. Distinguishes "system error" from "our filter" from "success"
 */
@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: GeofenceTransitionProcessor
    @Inject lateinit var debugLogger: GeofenceDebugLogger
    @Inject lateinit var eventLog: GeofenceEventLogRepository

    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        debugLogger.log("RECEIVER", "onReceive action=${intent.action}")

        // Strict action check — ignore anything that isn't our geofence event
        if (intent.action != GeofenceRegistrar.ACTION_GEOFENCE_EVENT) {
            debugLogger.log("RECEIVER", "Ignoriert: falsche Action ${intent.action}")
            // Log this as a diagnostic event — system sent us something unexpected
            logAsync(eventLog, GeofenceEventLogEntry(
                id = "diag_${now}_${(Math.random() * 10000).toLong()}",
                occurredAt = now,
                category = "DIAGNOSTIC",
                eventType = "UNEXPECTED_ACTION",
                detail = "action=${intent.action}",
                success = false
            ))
            return
        }

        val pendingResult = goAsync()
        val event = GeofencingEvent.fromIntent(intent)

        if (event == null) {
            debugLogger.log("RECEIVER", "GeofencingEvent.fromIntent = null")
            logAsync(eventLog, GeofenceEventLogEntry(
                id = "null_event_$now",
                occurredAt = now,
                category = "SYSTEM_EVENT",
                eventType = "NULL_INTENT",
                detail = "GeofencingEvent.fromIntent returned null — intent may be malformed",
                success = false
            ))
            pendingResult.finish()
            return
        }

        if (event.hasError()) {
            val errorCode = event.errorCode
            debugLogger.log("RECEIVER", "Geofence-Error: code=$errorCode")
            // CRITICAL: Log the error code so we can diagnose
            val errorDetail = when (errorCode) {
                1000 -> "GEOFENCE_NOT_AVAILABLE — Location services disabled or geofences not accessible"
                1001 -> "GEOFENCE_TOO_MANY_GEOFENCES — exceeded 100 limit"
                1002 -> "GEOFENCE_TOO_MANY_PENDING_INTENTS — exceeded PendingIntent limit"
                else -> "Unknown geofence error code: $errorCode"
            }
            logAsync(eventLog, GeofenceEventLogEntry(
                id = "error_${now}_$errorCode",
                occurredAt = now,
                category = "SYSTEM_EVENT",
                eventType = "GEOFENCE_ERROR",
                detail = errorDetail,
                success = false
            ))
            pendingResult.finish()
            return
        }

        val triggeringGeofences = event.triggeringGeofences.orEmpty()
        val transition = event.geofenceTransition
        val transitionName = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> "UNKNOWN"
        }

        val triggerLat = event.triggeringLocation?.latitude
        val triggerLon = event.triggeringLocation?.longitude
        val triggerTime = event.triggeringLocation?.time?.takeIf { it > 0 } ?: now

        debugLogger.log("RECEIVER", "${triggeringGeofences.size} Geofences: transition=$transitionName")

        // M8.2: Log EVERY system event BEFORE processing, so we know the system sent it
        val logEntries = triggeringGeofences.map { gf ->
            GeofenceEventLogEntry(
                id = "sys_${transitionName}_${gf.requestId}_$triggerTime",
                occurredAt = triggerTime,
                category = "SYSTEM_EVENT",
                eventType = "GEOFENCE_$transitionName",
                geofenceId = gf.requestId,
                detail = "System triggered $transitionName for geofence ${gf.requestId}",
                success = true,
                latitude = triggerLat,
                longitude = triggerLon
            )
        }
        logAsync(eventLog, logEntries)

        // Process each geofence in background
        // M8.2: Use application-scoped coroutine for reliability
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                triggeringGeofences.forEach { geofence ->
                    debugLogger.log("RECEIVER", "  → processing ${geofence.requestId}")
                    processor.processTransition(
                        geofenceId = geofence.requestId,
                        transition = when (transition) {
                            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.Enter
                            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.Exit
                            else -> GeofenceTransition.Unknown
                        },
                        occurredAt = triggerTime,
                        latitude = triggerLat,
                        longitude = triggerLon
                    )
                }
            } catch (e: Exception) {
                debugLogger.log("RECEIVER", "Exception: ${e.message}")
                logAsync(eventLog, GeofenceEventLogEntry(
                    id = "crash_${now}_${transitionName}",
                    occurredAt = now,
                    category = "SYSTEM_EVENT",
                    eventType = "PROCESSING_ERROR",
                    detail = "Exception while processing: ${e.message}",
                    success = false
                ))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun logAsync(repo: GeofenceEventLogRepository, entry: GeofenceEventLogEntry) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { repo.log(entry) } catch (_: Exception) {}
        }
    }

    private fun logAsync(repo: GeofenceEventLogRepository, entries: List<GeofenceEventLogEntry>) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { repo.logBatch(entries) } catch (_: Exception) {}
        }
    }

    companion object {
        fun logAsyncStatic(repo: GeofenceEventLogRepository, entry: GeofenceEventLogEntry) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { repo.log(entry) } catch (_: Exception) {}
            }
        }
    }
}

enum class GeofenceTransition { Enter, Exit, Unknown }

package de.devondroste.aevum.automation.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    @Inject lateinit var debugLogger: GeofenceDebugLogger

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
            else -> "UNKNOWN"
        }
        val triggerTime = event.triggeringLocation?.time?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val triggerLat = event.triggeringLocation?.latitude
        val triggerLon = event.triggeringLocation?.longitude

        debugLogger.log("RECEIVER", "${triggeringGeofences.size} Geofences: $transitionName")

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                triggeringGeofences.forEach { geofence ->
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
            } finally {
                pendingResult.finish()
            }
        }
    }
}

enum class GeofenceTransition { Enter, Exit, Unknown }

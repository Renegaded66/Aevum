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

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var processor: GeofenceTransitionProcessor
    @Inject lateinit var debugLogger: GeofenceDebugLogger

    override fun onReceive(context: Context, intent: Intent) {
        debugLogger.log("RECEIVER", "onReceive: action=${intent.action}")
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
            debugLogger.log("RECEIVER", "Geofence-Error-Code: ${event.errorCode}")
            pendingResult.finish()
            return
        }

        val geofences = event.triggeringGeofences.orEmpty()
        val transition = event.geofenceTransition
        debugLogger.log("RECEIVER", "${geofences.size} Geofences: transition=$transition")

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                geofences.forEach { geofence ->
                    debugLogger.log("RECEIVER", "  → ${geofence.requestId}")
                    processor.processTransition(
                        geofenceId = geofence.requestId,
                        transition = when (transition) {
                            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.Enter
                            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.Exit
                            else -> GeofenceTransition.Unknown
                        },
                        occurredAt = event.triggeringLocation?.time?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        latitude = event.triggeringLocation?.latitude,
                        longitude = event.triggeringLocation?.longitude
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

enum class GeofenceTransition { Enter, Exit, Unknown }

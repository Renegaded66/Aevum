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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeofenceRegistrar.ACTION_GEOFENCE_EVENT) return
        val pendingResult = goAsync()
        val event = GeofencingEvent.fromIntent(intent) ?: run {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!event.hasError()) {
                    event.triggeringGeofences.orEmpty().forEach { geofence ->
                        processor.processTransition(
                            geofenceId = geofence.requestId,
                            transition = when (event.geofenceTransition) {
                                Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.Enter
                                Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.Exit
                                else -> GeofenceTransition.Unknown
                            },
                            occurredAt = event.triggeringLocation?.time?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            latitude = event.triggeringLocation?.latitude,
                            longitude = event.triggeringLocation?.longitude
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

enum class GeofenceTransition { Enter, Exit, Unknown }

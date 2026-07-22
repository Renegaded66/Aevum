package de.devondroste.aevum.automation.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-registers geofences after device reboot.
 *
 * Android clears all geofence registrations on boot.
 * This receiver ensures geofencing is restored without user interaction.
 *
 * M8.1: Critical reliability fix — without this, geofences stop working
 * after every device restart.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var registrar: GeofenceRegistrar
    @Inject lateinit var debugLogger: GeofenceDebugLogger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        debugLogger.log("BOOT", "Gerät gestartet — registriere Geofences neu")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (val result = registrar.refreshRegisteredGeofences()) {
                    is GeofenceRegistrationResult.Registered ->
                        debugLogger.log("BOOT", "${result.count} Geofences nach Boot registriert")
                    else ->
                        debugLogger.log("BOOT", "Boot-Registrierung: ${result.javaClass.simpleName}")
                }
            } catch (_: Exception) {
                debugLogger.log("BOOT", "Fehler bei Boot-Registrierung")
            }
        }
    }
}

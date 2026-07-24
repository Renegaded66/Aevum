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
        // M9.2: listen to all reliable boot signals (Locked Boot fires before
        // the user unlocks, MY_PACKAGE_REPLACED fires on app updates)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                debugLogger.log("BOOT", "Trigger ${intent.action} — registriere Geofences neu")
                handleBoot(context)
            }
            else -> return
        }
    }

    private fun handleBoot(context: Context) {
        // M9.2: Ensure foreground service is up so geofences can fire reliably on Android 14+
        try {
            GeofenceForegroundService.start(context)
        } catch (e: Exception) {
            debugLogger.log("BOOT", "ForegroundService start failed: ${e.message}")
        }
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

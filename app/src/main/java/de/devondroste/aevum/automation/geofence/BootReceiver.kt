package de.devondroste.aevum.automation.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import de.devondroste.aevum.automation.activityrecognition.InitialActivitySnapshotScheduler
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
 *
 * M17.4: Erweitert um den Initial-Activity-Snapshot — wenn der User im
 * Auto sitzt und das Handy neu startet, soll die Fahrt trotzdem erkannt
 * werden (Play Services feuert keine rückwirkenden Transitions).
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
                debugLogger.log("BOOT", "Trigger ${intent.action} — registriere Geofences neu + AR-Snapshot")
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
        // M17.4: Initial-Activity-Snapshot enqueuen — 30s nach Boot prüft
        // der Worker, ob der User gerade IN_VEHICLE ist, und startet ggf.
        // eine Auto-Session. Eigene Unique-Work, damit Doppel-Aufrufe aus
        // BootReceiver + Application.onCreate idempotent sind.
        try {
            InitialActivitySnapshotScheduler.schedule(context)
            debugLogger.log("BOOT", "InitialActivitySnapshotScheduler nach Boot enqueued")
        } catch (e: Exception) {
            debugLogger.log("BOOT", "InitialActivitySnapshotScheduler schedule failed: ${e.message}")
        }
    }
}

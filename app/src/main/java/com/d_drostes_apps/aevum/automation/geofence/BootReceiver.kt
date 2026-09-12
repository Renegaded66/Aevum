package com.d_drostes_apps.aevum.automation.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import com.d_drostes_apps.aevum.automation.activityrecognition.InitialActivitySnapshotScheduler
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
        // Android 15 verbietet es BOOT_COMPLETED-Receivern, einen Location-FGS
        // zu starten. Die Geofence-Registrierung bei Google Play Services ist
        // davon unabhängig und bleibt nach dem Reboot weiterhin möglich. Der
        // Service startet erst wieder bei einer Nutzerinteraktion bzw. beim
        // regulären Öffnen der App.
        debugLogger.log("BOOT", "Location-FGS nach Boot bewusst übersprungen (Android-15-Richtlinie)")

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
        // M18.112: Continuous-AR-Samples nach Boot (neu-)registrieren —
        // Android räumt AR-Registrierungen beim Boot ab (wie Geofences).
        // Ohne diesen Aufruf wäre der schnelle Verdachts-Pfad erst nach
        // dem nächsten App-Start wieder aktiv.
        try {
            com.d_drostes_apps.aevum.automation.activityrecognition.ActivityContinuousSamplesRequester.register(context)
            debugLogger.log("BOOT", "ActivityContinuousSamples nach Boot registriert")
        } catch (e: Exception) {
            debugLogger.log("BOOT", "ActivityContinuousSamples register failed: ${e.message}")
        }
        // M18.104 (Akku-Redesign): Nach Boot KEIN Dauer-GPS-Stream mehr —
        // der Initial-Activity-Snapshot (AR-Sensor, 60s) + die normalen
        // Transitions übernehmen die Erkennung; GPS-Bursts starten erst
        // bei echten Verdachts-Momenten. Lief eine Auto-/Wanderungs-
        // Session über den Reboot (Session lebt in der DB), restauriert
        // der Restore-Pfad des DriveDetectionService den Track-Stream.
        try {
            if (registrar.hasForegroundLocation()) {
                com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService.start(
                    context,
                    com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService.ACTION_TRACK_RESTORE
                )
                debugLogger.log("BOOT", "DriveDetectionService TRACK_RESTORE enqueued")
            }
        } catch (e: Exception) {
            debugLogger.log("BOOT", "DriveDetectionService restore failed: ${e.message}")
        }
        // M18.124 (User: "Autofahrt-Aufzeichnung startet erst nach ca.
        // 5 Minuten"): DriveProbeWorker-Takt nach Boot (neu-)starten —
        // WorkManager-Räumungen beim Boot töten sonst den 2-Min-GPS-
        // Fallback genauso wie die AR-Samples (gleiche Lektion wie oben).
        // Der Worker plant sich selbst weiter und stoppt bei deaktivierter
        // Fahr-Erkennung (Gate im doWork).
        try {
            com.d_drostes_apps.aevum.automation.activityrecognition.DriveProbeWorker.schedule(context)
            debugLogger.log("BOOT", "DriveProbeWorker nach Boot enqueued")
        } catch (e: Exception) {
            debugLogger.log("BOOT", "DriveProbeWorker schedule failed: ${e.message}")
        }
    }
}

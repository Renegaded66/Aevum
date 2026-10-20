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
        // M20: 60-Sekunden-Partial-WakeLock über die Boot-Verarbeitung.
        // Aggressive OEM-ROMs (Samsung „Sleeping/Deep sleeping apps") können
        // den Prozess zwischen BOOT_COMPLETED-Broadcast und etabliertem
        // Foreground-Service beenden — der Wakelock hält den Prozess in
        // genau diesem Fenster wach. Zeitablauf released automatisch
        // (kein Leck-Risiko); Muster wie bei etablierten Standort-Apps.
        acquireBootWakelock(context)
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
        // M26: Anruf-Erkennung nach Boot / App-Update wieder hochfahren.
        // Der Dienst beendet sich selbst, wenn der Schalter aus ist oder
        // READ_PHONE_STATE fehlt — hier wird nur angestoßen (idempotent).
        try {
            com.d_drostes_apps.aevum.automation.call.CallDetectionWatchdogWorker.schedule(context)
            com.d_drostes_apps.aevum.automation.call.CallDetectionService.ensureAlive(context, "boot")
            debugLogger.log("BOOT", "Anruf-Erkennung: Dienst/Watchdog angestoßen")
        } catch (e: Exception) {
            debugLogger.log("BOOT", "Anruf-Erkennung konnte nicht gestartet werden: ${e.message}")
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
        // M19.2/M20 (User-Report: „… wird einfach weiterhin nur Digital
        // aufgezeichnet — die zugewiesene Activity startet nicht mehr"):
        // App-Aufzeichnung nach Boot/Update neu anstoßen. Der Service hatte
        // bisher nur Start-Pfade beim App-Prozess-Start und in den
        // Settings — nach Kill/Reboot/APK-Update blieb er sonst tot,
        // während „Digital“ über den Manifest-Receiver weiterlief (gleiche
        // Lektion wie DriveProbeWorker/AR-Samples oben: nach Boot/Update
        // neu anstoßen). MY_PACKAGE_REPLACED läuft ebenfalls hier ein
        // (Intent-Filter im AndroidManifest, siehe dort).
        //   (a) Sofort-Start: Der FGS ist specialUse und darf aus dem
        //       Boot-Pfad gestartet werden (Android-Ausnahmenliste:
        //       BOOT_COMPLETED/MY_PACKAGE_REPLACED); schlägt das fehl, holen
        //       Watchdog und Boot-Check nach.
        //   (b) Watchdog-Takt (neu-)einplanen — KEEP macht es idempotent.
        //   (c) M20: Einmal-Check nach 60 s — belegt/repariert den Zustand
        //       auch dann, wenn der Prozess zwischen Broadcast und
        //       FGS-Etablierung von einem OEM-Manager beendet wurde
        //       (Samsung „Sleeping apps"; vorher unsichtbar).
        //   (d) M20: 2,5 s nach dem Start nachsehen und EINMAL nachfassen —
        //       der Boot-Broadcast ist der einzige Startpfad, der ganz ohne
        //       Nutzerinteraktion auskommen muss; ein zweiter Versuch im
        //       selben (ausnahmefähigen) Receiver-Kontext kostet nichts.
        try {
            com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService.start(context, "boot")
            com.d_drostes_apps.aevum.automation.apptracking.AppTrackingWatchdogWorker.schedule(context)
            com.d_drostes_apps.aevum.automation.apptracking.AppTrackingBootCheckWorker.schedule(context)
            debugLogger.log("BOOT", "App-Aufzeichnung nach Boot/Update angestoßen (M20)")
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    kotlinx.coroutines.delay(2_500L)
                    if (com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService.isAlive()) {
                        com.d_drostes_apps.aevum.automation.apptracking.AppTrackingLog.i(
                            context, "Boot-Verifikation: Service lebt"
                        )
                    } else {
                        com.d_drostes_apps.aevum.automation.apptracking.AppTrackingLog.w(
                            context, "Boot-Verifikation: Service NICHT gestartet — ein Nachfassen"
                        )
                        com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService
                            .start(context, "boot-retry")
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            debugLogger.log("BOOT", "AppTracking revival failed: ${e.message}")
        }
        // M18.129: Kalender-Sync + Auto-Start-Takt nach Boot neu anstoßen.
        // WorkManager räumt beim Boot geplante Arbeiten ab — ohne diesen
        // Aufruf liefe der Kalender-Sync erst nach dem nächsten App-Start
        // wieder (gleiche Lektion wie DriveProbeWorker/AR-Samples oben).
        // Beide Scheduler prüfen ihre Gates selbst (Feature-Schalter,
        // Berechtigung) — der Aufruf hier ist immer sicher.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, CalendarBootDeps::class.java
                )
                deps.calendarSyncScheduler().schedule()
                val settings = deps.automationSettingsDao().getSettingsSync()
                val enabled = settings?.calendarSyncEnabled == true
                if (enabled) {
                    com.d_drostes_apps.aevum.automation.calendar.CalendarAutoRunScheduler
                        .scheduleNext(context)
                }
                debugLogger.log("BOOT", "Kalender-Sync nach Boot geplant (Auto-Takt: $enabled)")
            } catch (e: Exception) {
                debugLogger.log("BOOT", "Kalender-Scheduler failed: ${e.message}")
            }
        }
    }

    /**
     * M20: kurzer Boot-WakeLock — begrenzt auf 60 s, released sich durch
     * den Timeout selbst. Nur CPU-Wachhalten (PARTIAL_WAKE_LOCK), kein
     * Screen-On; WAKE_LOCK ist eine normale Manifest-Berechtigung.
     */
    private fun acquireBootWakelock(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                ?: return
            @Suppress("DEPRECATION")
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "aevum:boot")
                .acquire(60_000L)
        } catch (e: Exception) {
            debugLogger.log("BOOT", "Boot-WakeLock nicht verfügbar: ${e.message}")
        }
    }

    /**
     * M18.129: Hilt-Zugang für den Boot-Pfad.
     *
     * Der BootReceiver ist @AndroidEntryPoint, aber dieses Coroutine-
     * Lambda braucht die Abhängigkeiten außerhalb des Injektions-Scopes —
     * der EntryPoint ist der saubere Weg (Muster von DriveStartWorker,
     * M18.62-FIX).
     */
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface CalendarBootDeps {
        fun calendarSyncScheduler(): com.d_drostes_apps.aevum.automation.calendar.CalendarSyncScheduler
        fun automationSettingsDao(): com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
    }
}

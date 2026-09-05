package com.d_drostes_apps.aevum.automation.geofence

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Minimal foreground service for geofencing on Android 15+ (SDK 35).
 *
 * Android 15 requires a foreground service with type "location"
 * when registering geofences that may fire while the app is in background.
 *
 * M18.104 (Akku-Redesign): Der Service beendet sich jetzt selbst, wenn er
 * nicht gebraucht wird — Geofencing-Gate AUS oder keine aktiven Geofences.
 * Vorher lief er pauschal ab App-Install 24/7 (ein Location-FGS ohne
 * Geofences hält nur den Prozess wach und erscheint als "Location-App"
 * in der Akku-Bilanz). Ein Idle-Re-Check alle 12h fängt nachträgliche
 * Änderungen (Geofence gelöscht, Gate ausgeschaltet) ab.
 */
class GeofenceForegroundService : Service() {

    /** Service-Scope für Idle-Checks — wird in onDestroy abgebaut. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleRecheckJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // M19: Konsolidierte Hintergrund-Benachrichtigung — alle Hintergrund-
        // Services nutzen denselben Channel + dieselbe ID → nur eine Notification
        // im Benachrichtigungsfeld statt drei.
        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.ensureChannel(this)
        val notification = com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // M18.45: SecurityException-Schutz für SDK 35. Ein FGS mit Typ "location"
                // darf im Hintergrund nur starten, wenn die Berechtigungen (Fine/Coarse + Background)
                // wirklich erteilt sind. Wenn nicht, stürzt die App ab.
                try {
                    startForeground(
                        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } catch (e: SecurityException) {
                    // Fallback: Wenn Location-FGS verweigert wird (z.B. im Hintergrund ohne Background-Permission),
                    // versuchen wir es als "normalen" Service ohne speziellen Typ (0).
                    // WICHTIG: 0 übergeben, damit das System nicht den manifest-default (location) nimmt.
                    startForeground(com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID, notification, 0)
                }
            } else {
                startForeground(com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Wenn alles fehlschlägt (z.B. Background-Start-Restriction ohne Exemption),
            // beenden wir uns selbst, um den Crash des Prozesses zu verhindern.
            stopSelf()
            return START_NOT_STICKY
        }

        // M18.104: Idle-Check sofort + alle 12h neu (siehe Klassen-Doc).
        checkIdleGate()
        scheduleIdleRecheck()

        return START_STICKY
    }

    /**
     * M18.104: Ist der Service noch nötig? Geofencing-Gate AN UND
     * mindestens ein aktiver (nicht gelöschter) Geofence. Fällt eines
     * weg, stopSelf — die GMS-Geofence-Registrierung wird beim nächsten
     * App-Start/Registrar-Refresh nachgezogen (GeofenceRegistrar
     * deregistriert ohnehin bei Gate-AUS).
     */
    private fun checkIdleGate() {
        scope.launch {
            try {
                val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    applicationContext,
                    GateDeps::class.java
                )
                val geofences = deps.placeGeofenceRepository().getAllEnabled().first()
                val active = geofences.filter { it.deletedAt == null }
                val settings = deps.settingsRepository().get().first()
                val needed = active.isNotEmpty() && settings?.geofencingEnabled != false
                if (!needed) {
                    android.util.Log.d(
                        "GeofenceFGS",
                        "Idle: kein aktiver Geofence oder Gate AUS (${active.size} Geofences, gate=${settings?.geofencingEnabled}) — Service beendet sich"
                    )
                    stopSelf()
                }
            } catch (e: Exception) {
                // Konservativ: Bei DB-Fehlern läuft der Service weiter —
                // ein FGS zu viel ist besser als Geofence-Trigger tot.
                android.util.Log.w("GeofenceFGS", "Idle-Check fehlgeschlagen: ${e.message} — Service läuft weiter (konservativ)")
            }
        }
    }

    /** M18.104: Idle-Re-Check alle 12h (Endlosschleife bis onDestroy). */
    private fun scheduleIdleRecheck() {
        if (idleRecheckJob?.isActive == true) return
        idleRecheckJob = scope.launch {
            while (true) {
                delay(12L * 60 * 60 * 1000)
                checkIdleGate()
            }
        }
    }

    override fun onDestroy() {
        idleRecheckJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface GateDeps {
        fun placeGeofenceRepository(): com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
        fun settingsRepository(): com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GeofenceForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // M18.66-FIX: ForegroundServiceStartNotAllowedException
                // (Android 12+) wenn die App im Hintergrund startet, oder
                // SecurityException. Der FGS-Start darf NIE crashen — die
                // Geofence-Registrierung (client.addGeofences) funktioniert
                // auch ohne FGS, nur weniger zuverlässig im Hintergrund.
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                    // Auch der Fallback schlägt fehl — Geofences laufen
                    // dann nur im Vordergrund. Nicht blockierend.
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GeofenceForegroundService::class.java))
        }
    }
}
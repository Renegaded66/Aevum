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
        // M18.107-CRASHFIX (Startup-Crash auf Android 14+/frischen Installationen):
        // Der alte SecurityException-Fallback rief startForeground(..., 0) auf.
        // Auf API 34+ (targetSdk 34+) ist Typ 0 VERBOTEN — Android wirft
        // InvalidForegroundServiceTypeException ("Starting FGS with type none
        // ... has been prohibited", offizielle Doku + mehrere Produktionsfälle).
        // Dieser Aufruf lag im outer try/catch(Exception) → stopSelf() OHNE je
        // erfüllten FGS-Vertrag → RemoteServiceException ("did not then call
        // Service.startForeground()") → Prozess-Kill beim App-Start (identischer
        // Mechanismus wie M18.105/DriveDetectionService, dort bewiesen per
        // API-30-Emulator-Repro). Frische Installation ohne Location-Permission:
        // startForeground(location) → SecurityException → Typ-0-Bombe → Crash.
        // Fix (zwei Schichten):
        //   1) start()-Companion: Permission-Gate (M18.105-Muster) — ohne
        //      Location-Permission wird der Service GAR NICHT gestartet
        //      (Geofencing funktioniert laut M18.66-Kommentar auch ohne FGS).
        //   2) Hier: Notification-Building in den try-Block, Fallback auf den
        //      2-Arg-Aufruf (nimmt den Manifest-Typ — kontraktgültig auf allen
        //      API-Leveln), NIE mehr Typ 0.
        // M18.108: ensureChannel/buildNotification ebenfalls IN den try-Block
        // gezogen (M18.24-Lektion aus LiveActivityService: Notification-Building
        // kann auf OEM-Geraeten werfen; ein uncaught Crash hier beim App-Start
        // killt den Prozess, bevor die UI erscheint).
        try {
            com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.ensureChannel(this)
            val notification = com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // M18.45/M18.107: SecurityException-Schutz. Ein FGS mit Typ "location"
                // darf nur starten, wenn die Location-Berechtigungen tatsächlich
                // erteilt sind (Vordergrund) bzw. zusätzlich Background-Location
                // beim Hintergrund-Start. Fehlt sie, wirft der location-Typ
                // SecurityException — UND der 2-Arg-Aufruf (Manifest-Typ location)
                // würde dieselbe werfen. Einziger vertragsgültiger Notausgang:
                // FOREGROUND_SERVICE_TYPE_SHORT_SERVICE (API 34+, keine
                // Runtime-Voraussetzungen, im Manifest mitdeklariert). Der Vertrag
                // ("startForeground binnen 5s") bleibt damit ERFÜLLT — der Service
                // lebt max. ~3min degradiert weiter statt den Prozess zu killen.
                try {
                    startForeground(
                        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } catch (e: SecurityException) {
                    try {
                        startForeground(
                            com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                        )
                    } catch (e2: Exception) {
                        android.util.Log.e("GeofenceFGS", "startForeground (shortService-Fallback) fehlgeschlagen — stopSelf", e2)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            } else {
                // API < 34: 2-Arg-Aufruf = Manifest-Typ. Pre-34 gibt es keine
                // Typ-Enforcement-Checks (InvalidForegroundServiceTypeException
                // existiert erst ab API 34) — hier ist der 2-Arg-Aufruf immer
                // vertragsgültig. Der alte Typ-0-Aufruf war unnötig und auf
                // 34+ verboten.
                startForeground(com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Wenn alles fehlschlägt (z.B. Background-Start-Restriction ohne Exemption),
            // beenden wir uns selbst, um den Crash des Prozesses zu verhindern.
            // (Letzter Rettungsanker — das Start()-Gate verhindert den häufigsten
            // Fall "keine Permission" bereits VOR dem Start.)
            android.util.Log.e("GeofenceFGS", "startForeground endgültig fehlgeschlagen — stopSelf", e)
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
        /** M18.107: Permission-Gate (M18.105-Muster von DriveDetectionService):
         *  Ohne Location-Permission wird der Service GAR NICHT gestartet.
         *  Grund 1: Auf Android 14+ wirft startForeground(location) ohne
         *  Permission SecurityException — der shortService-Fallback hält
         *  zwar den FGS-Vertrag ein (kein Crash mehr), aber der Service
         *  wäre trotzdem sinnlos (Geofence-Trigger brauchen Location).
         *  Grund 2: Kein pointless 3-min-shortService-Service im System.
         *  Das GMS-Geofencing selbst funktioniert ohne FGS (M18.66-Kommentar)
         *  — es läuft dann nur im Vordergrund zuverlässig; sobald der User
         *  die Permission erteilt, startet der nächste App-Start/Registrar-
         *  Refresh den Service normal. */
        fun start(context: Context) {
            val locationGranted =
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!locationGranted) {
                android.util.Log.w("GeofenceFGS", "Keine Location-Permission — GeofenceForegroundService nicht gestartet")
                return
            }
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
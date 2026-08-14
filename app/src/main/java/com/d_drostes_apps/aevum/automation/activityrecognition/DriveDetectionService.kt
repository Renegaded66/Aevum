package com.d_drostes_apps.aevum.automation.activityrecognition

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.d_drostes_apps.aevum.MainActivity
import com.d_drostes_apps.aevum.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * M18.66: AUTOFART-ERKENNUNG über KONTINUIERLICHEN GPS-Stream.
 *
 * DER Kern-Fix nach der Recherche (Google Maps / Life360 / Android-Doku):
 * Zuverlässige Fahrterkennung braucht einen DAUERHAFTEN Location-Stream
 * (requestLocationUpdates + LocationCallback), NICHT einen einmaligen
 * getCurrentLocation()-Fix alle 2 Minuten.
 *
 * WARUM der alte DriveProbeWorker scheiterte (bewiesen):
 * - Es gab KEINEN einzigen requestLocationUpdates-Aufruf in der ganzen App.
 * - Ein einmaliger getCurrentLocation()-Fix mit PRIORITY_BALANCED liefert
 *   fast NIE hasSpeed() — die Geschwindigkeit kommt nur aus einem aktiven
 *   Stream. Also war speedMps fast immer null, und DriveDetectionEngine
 *   (verlangt speed >= 8.0 m/s) konnte NIE eine Fahrt bestätigen.
 * - Im Hintergrund wird getCurrentLocation() zusätzlich gedrosselt.
 *
 * DIESER Service hält einen kontinuierlichen LocationRequest-Stream
 * (PRIORITY_HIGH_ACCURACY, ~5s Intervall) als ForegroundService vom Typ
 * "location". Er läuft die ganze Zeit, solange die Autofahrt-Erkennung in
 * den Trigger-Settings aktiv ist.
 *
 * Erkenngungslogik (an Google Maps / Life360 angelehnt):
 * - Jeder Location-Fix wird in die [ActivityRecognitionBridge] gepuffert
 *   (addDriveProbe mit echten speed/accuracy/distance).
 * - DriveDetectionEngine klassifiziert die Serie (mehrere Probes >= 8 m/s).
 * - Driving -> DriveStartWorker startet die Session "Autofahren" sofort.
 * - Bewegung >= MIN_PROBE_MOVEMENT_M zwischen zwei Probes refresht den
 *   Heartbeat (die Fahrt lebt) -> Watchdog wird verlängert.
 * - Der DriveWatchdogWorker stoppt nach 5 Minuten ohne Fahrt-Signal.
 */
@AndroidEntryPoint
class DriveDetectionService : Service() {

    @Inject lateinit var bridge: ActivityRecognitionBridge

    private lateinit var fusedClient: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTsMs: Long = 0L
    /** M18.66-FIX13: Timestamp des Service-Starts. Die ersten 90 Sekunden
     *  werden ignoriert (GPS-Kaltstart-Phase). In dieser Zeit liefert
     *  loc.speed oft Müllwerte (10-30 m/s trotz Stillstand) bei scheinbar
     *  akzeptabler Genauigkeit (< 30m) — der GPS-Empfänger sucht noch
     *  Satelliten und springt. Das ist exakt das "5 Minuten nach Update"-
     *  Muster: Service startet nach App-Update neu → Kaltstart → falsche
     *  Speed-Werte → False-Positive. 90s deckt den typischen Kaltstart
     *  ab (Assisted GPS: 20-60s, Cold GPS: 60-120s). */
    private var serviceStartMs: Long = 0L

    companion object {
        private const val TAG = "DriveDetectionSvc"
        private const val CHANNEL_ID = "aevum_drive_detection"
        private const val NOTIFICATION_ID = 6303

        /** Stream-Intervall: 5s. Schnell genug für Fahrt-Erkennung,
         *  akkufreundlicher als kontinuierlich (Google Maps nutzt ähnlich). */
        private const val LOCATION_INTERVAL_MS = 5_000L
        /** Mindest-Bewegung zwischen zwei Probes (5s Abstand), die als
         *  "Fahrt lebt" zählt (~7 km/h — deckt Stadtverkehr/Stau ab). */
        private const val MIN_PROBE_MOVEMENT_M = 10.0

        fun start(context: Context) {
            val intent = Intent(context, DriveDetectionService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Start fehlgeschlagen: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveDetectionService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceStartMs = System.currentTimeMillis()
        if (!hasLocationPermission()) {
            Log.w(TAG, "Keine Standort-Berechtigung — Autofahrt-Erkennung pausiert")
            stopSelf()
            return START_NOT_STICKY
        }
        // M18.66: Gate — Autofahrt-Erkennung in den Trigger-Settings aus?
        // Dann keinen GPS-Stream halten (Akku), sauber beenden. Der Service
        // wird vom Gate beim Aktivieren wieder gestartet.
        if (!bridge.isDrivingEnabled()) {
            Log.d(TAG, "Autofahrt-Erkennung deaktiviert — Service beendet sich")
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground starten (location-Typ). Fehler -> sauber stoppen.
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location-FGS verweigert (Hintergrund ohne Background-Permission)", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Foreground-Start fehlgeschlagen", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Falls der Service neu gestartet wurde, erst den alten Stream abmelden.
        callback?.let { old ->
            try { fusedClient.removeLocationUpdates(old) } catch (_: Exception) {}
        }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
            .setMaxUpdateDelayMillis(LOCATION_INTERVAL_MS * 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handleFix(it) }
            }
        }
        callback = cb
        try {
            fusedClient.requestLocationUpdates(req, cb, mainLooper)
            Log.d(TAG, "Kontinuierlicher Location-Stream aktiv (5s)")
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates fehlgeschlagen", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun handleFix(loc: Location) {
        val now = System.currentTimeMillis()

        // M18.66-FIX13: GPS-KALTSTART-WARMUP.
        // Die ersten 90 Sekunden nach Service-Start werden ignoriert.
        // In dieser Phase liefert FusedLocationProvider oft falsche
        // Speed-Werte (10-30 m/s trotz Stillstand) — der Empfänger
        // sucht Satelliten, position springt, speed wird aus der
        // Sprungdistanz geschätzt. Das ist exakt das "5 Minuten nach
        // Update"-Muster: App-Update → Service-Neustart → Kaltstart.
        // Probes werden in dieser Zeit NICHT gepuffert → Engine kann
        // sie nicht fälschlich auswerten.
        if (serviceStartMs == 0L || now - serviceStartMs < 90_000L) {
            Log.d(TAG, "GPS-Warmup (Kaltstart) — Probe ignoriert (${(now - serviceStartMs) / 1000}s)")
            return
        }

        val speed = if (loc.hasSpeed()) loc.speed else null
        val accuracy = loc.accuracy
        val distance = if (lastLat != null && lastLon != null) {
            haversineMeters(lastLat!!, lastLon!!, loc.latitude, loc.longitude)
        } else null

        val probe = DriveDetectionEngine.DriveProbe(
            timestampMs = now,
            speedMps = speed,
            accuracyMeters = accuracy,
            distanceFromLastM = distance,
            latitude = loc.latitude,
            longitude = loc.longitude
        )

        // M18.66-FIX6: Heartbeat wird NUR bei bestätigter Fahrt refresht.
        // Vorher: addDriveProbe(refreshHeartbeat=isMoving) refreshte den
        // Heartbeat bei speed >= 3.0 m/s — aber GPS-Noise kann das
        // fälschlich liefern → Watchdog läuft nie ab → Session endlos.
        // Jetzt: Probes werden OHNE Heartbeat gepuffert. Die Klassifikation
        // entscheidet: classify==Driving → Heartbeat refresht + Watchdog.
        // classify!=Driving → kein Heartbeat → Watchdog läuft nach 5 Min ab.
        val isReliableFix = accuracy <= 30f
        bridge.addDriveProbe(probe, refreshHeartbeat = false)

        // M18.66-FIX5: distance-basierten Heartbeat-Refresh ENTFERNT.
        // Indoor-GPS-Drift erzeugt 10-20m Springer trotz still sitzendem
        // User — das refreshte den Heartbeat fälschlich → Watchdog
        // lief nie ab. Nur Speed >= 3.0 m/s darf den Heartbeat refreshen.

        lastLat = loc.latitude
        lastLon = loc.longitude
        lastTsMs = now

        // Serie klassifizieren.
        when (val result = DriveDetectionEngine.classify(bridge.currentDriveProbes(), now)) {
            is DriveDetectionEngine.Classification.Driving -> {
                Log.d(TAG, "Fahrt erkannt (GPS-Stream, conf=${result.confidence}) -> Start")
                // M18.66-FIX6: Heartbeat NUR bei bestätigter Fahrt refresht.
                // Das verhindert, dass GPS-Noise (speed >= 3 m/s) den Watchdog
                // endlos am Leben hält — nur die Klassifikation als Driving
                // (3+ konsekutive Probes >= 8 m/s über 1 Min) bestätigt die Fahrt.
                bridge.refreshDriveHeartbeat(now)
                // M18.66-FIX15: GPS-Bestätigung markieren, BEVOR die Probes
                // gedrained werden. Der DriveStartWorker prüft sonst leere
                // Probes (classify=InsufficientData) und würde den Start
                // trotz bestätigter Fahrt ablehnen.
                bridge.markDriveConfirmed()
                bridge.drainDriveProbes()  // alte Probes leeren, nur frische zählen
                DriveStartWorker.schedule(this)
                DriveWatchdogWorker.schedule(this)
            }
            else -> {
                // Noch nicht genug / keine Fahrt — weiter sammeln.
                // KEIN Heartbeat-Refresh → Watchdog läuft nach 5 Min ab.
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Autofahrt-Erkennung",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Erkennt Autofahrten über GPS im Hintergrund"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Autofahrt-Erkennung aktiv")
            .setContentText("Aevum erkennt Fahrten über GPS im Hintergrund")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        callback?.let { cb ->
            try { fusedClient.removeLocationUpdates(cb) } catch (_: Exception) {}
        }
        callback = null
        Log.d(TAG, "Location-Stream gestoppt")
        super.onDestroy()
    }

    /** Haversine-Distanz in Metern (gleiche Formel wie DriveWorkers). */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}

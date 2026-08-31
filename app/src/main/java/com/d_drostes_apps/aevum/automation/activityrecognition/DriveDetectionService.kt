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
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    @Inject lateinit var liveActivityManager: com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
    // M18.84: Benannte Orte für das classify-Geofence-Veto + laufende
    // Walking-Phase. Feld-Injection (kein Konstruktor — Service wird vom
    // System instanziiert), einmaliger Snapshot-Ladevorgang pro Service-
    // Lebensdauer (Geofences ändern sich selten; ein Restart des Service
    // lädt neu — gleiche Frische-Philosophie wie der Settings-Cache).
    @Inject lateinit var geofenceRepository: PlaceGeofenceRepository
    // M18.86: Track-Punkte-Persistenz (Fahrtstrecke für die Orts-Timeline).
    @Inject lateinit var trackPointRepository: com.d_drostes_apps.aevum.data.repository.LocationTrackPointRepository

    /** M18.84: Service-eigener Coroutine-Scope für den Geofence-Snapshot. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** M18.84: Zeitpunkt, ab dem die Walking-Phase im aktuellen benannten
     *  Ort läuft (0 = keine). Ersatz für das alte feld-lokale Phasen-Setup:
     *  Die Phase wird nun auch gezählt, wenn der User sich bereits in einem
     *  Ort befindet — aber NIE während driveActive, und beim Verlassen des
     *  Orts-Kreises beginnt sie neu. */
    private var walkingPhaseStartMs: Long = 0L

    private lateinit var fusedClient: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTsMs: Long = 0L
    /** M18.84: Wurde der Geofence-Kontext (Veto-Kreise) bereits geladen? */
    private var geofenceContextLoaded = false
    /** M18.66-FIX13: Timestamp des Service-Starts. Die ersten 90 Sekunden
     *  werden ignoriert (GPS-Kaltstart-Phase). In dieser Zeit liefert
     *  loc.speed oft Müllwerte (10-30 m/s trotz Stillstand) bei scheinbar
     *  akzeptabler Genauigkeit (< 30m) — der GPS-Empfänger sucht noch
     *  Satelliten und springt. Das ist exakt das "5 Minuten nach Update"-
     *  Muster: Service startet nach App-Update neu → Kaltstart → falsche
     *  Speed-Werte → False-Positive. 90s deckt den typischen Kaltstart
     *  ab (Assisted GPS: 20-60s, Cold GPS: 60-120s).
     *  M18.71: 90s -> 60s. Die Erkennung soll sensibler/schneller
     *  ansprechen; Assisted GPS liefert nach 20-60s brauchbare Fixes.
     *  Die False-Positive-Abwehr übernimmt weiterhin das Netto-
     *  Displacement-Gate (≥ 150 m) in der Engine. */
    private var serviceStartMs: Long = 0L

    // ──────────────────────────────────────────────────────────────
    // M18.72: WALKING-PHASE (GPS-Pfad für die Wanderungs-Erkennung).
    //
    // Wenn Googles Activity-Recognition-Transitions keine WALKING-
    // ENTERs liefert (App im Hintergrund, Sensor-Spring), erkennt der
    // GPS-Stream die Wanderung über Netto-Displacement: Der User muss
    // sich ab [walkingPhaseStartMs] mindestens
    // [WALKING_MIN_GPS_DISTANCE_M] (300 m) geradlinig vom Phasenstart
    // entfernen — frühestens nach 5 Minuten (Engine-Schwelle). Steht
    // die Bewegung länger als [WALKING_PHASE_RESET_MS] still, wird die
    // Phase verworfen (kein akkumuliertes Gedächtnis über Pausen).
    // Netto-Displacement statt kumulierter Distanz: Indoor-GPS-Drift
    // springt 10-20 m pro Fix und würde die Summe fälschlich aufblähen
    // (gleiche Lektion wie DriveDetectionEngine M18.66-FIX13).
    //
    // M18.84: Phasen-Reset erweitert — die Phase beginnt auch NEU, wenn
    // der User einen benannten Orts-Kreis verlässt oder betritt (Orts-
    // wechsel ist "Unterwegs", keine zusammenhängende Wanderung) und
    // wird verworfen, solange eine Fahrt aktiv ist. Siehe updateWalkingPhase.
    // ──────────────────────────────────────────────────────────────
    private var walkPhaseStartLat: Double? = null
    private var walkPhaseStartLon: Double? = null
    private var walkLastLat: Double? = null
    private var walkLastLon: Double? = null

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
        /** M18.71: GPS-Kaltstart-Warmup verkürzt (90s -> 60s). */
        private const val GPS_WARMUP_MS = 60_000L
        /** M18.72: Mindest-Netto-Displacement für eine Wanderung (~300 m
         *  geradlinig). Eine echte Wanderung legt in 5 Minuten 300-500 m
         *  zurück; Indoor-Drift (10-50 m) und Gehen im Raum scheitern klar. */
        private const val WALKING_MIN_GPS_DISTANCE_M = 300.0
        /** M18.72: Steht der Standort länger still, wird die Walking-Phase
         *  verworfen — der 5-Minuten-Zähler startet bei neuer Bewegung neu. */
        private const val WALKING_PHASE_RESET_MS = 2L * 60 * 1000

        // ── M18.86: Track-Recording-Konstanten (ADR-0030) ──
        /** Bewegungs-Schwelle für einen Track-Punkt: ≥ 30 m seit dem
         *  letzten Punkt. Bei Stadt-Tempo (30-50 km/h) = alle ~2-5 s ein
         *  Punkt? NEIN — kombiniert mit dem 5s-Stream ergibt 30 m ≈ alle
         *  2-4 Fixes einen Punkt bei 30 km/h; die sichtbare Kurvendichte
         *  reicht für "halwegs die Fahrtstrecke" ohne jede Kurve. */
        private const val TRACK_MIN_MOVEMENT_M = 30.0
        /** Heartbeat: Auch ohne Bewegung alle 5 Min ein Punkt (Ampel/Stau/
         *  Pause — hält die Zeitachse der Strecke zusammen). */
        private const val TRACK_HEARTBEAT_MS = 5L * 60 * 1000
        /** Genauigkeits-Gate für Track-Punkte (Indoor-Multipath raus). */
        private const val TRACK_MAX_ACCURACY_M = 50f
        /** Batch-Flush: spätestens 8 Punkte ... */
        private const val TRACK_FLUSH_BATCH = 8
        /** ... oder 60 s (auch bei langsamen Punkt-Raten wird zügig
         *  persistiert, damit ein Prozess-Tod maximal 1 Min Strecke frisst). */
        private const val TRACK_FLUSH_INTERVAL_MS = 60_000L

        // ── M18.89: Pre-Session-Track-Backfill-Konstanten ──
        /** Pseudo-State-Key der Pre-Session-Sammelphase (Fahrt erkannt,
         *  Session noch nicht live). */
        private const val PENDING_TRACK_STATE_KEY = "pending"
        /** Pending-Fixes älter als dieses Fenster sind wertlos (Erkennungs-
         *  phasen dauern real 2–4 min; 25 min deckt Worst Cases ab). */
        private const val PENDING_TRACK_MAX_AGE_MS = 25L * 60 * 1000
        /** Obergrenze der Pending-Fixes (5s-Stream × 25 min = 300 → 400). */
        private const val PENDING_TRACK_MAX_POINTS = 400

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
        // M18.79: Der Service wird bei JEDEM App-Start neu gestartet
        // (AevumApplication.onCreate). Ein hier gesetztes serviceStartMs
        // würde bei jedem App-Start einen 60s-GPS-Kaltstart-Warmup
        // erzwingen und den laufenden Location-Stream abreißen — der
        // User, der die App öffnet und direkt losfährt, bliebe blind.
        // Der Warmup gilt nur für den ERSTEN Start des Service-Prozesses
        // (echter GPS-Kaltstart); bei Wiederholungs-Starts läuft der
        // Stream weiter (Recycling unten).
        if (serviceStartMs == 0L) serviceStartMs = System.currentTimeMillis()
        if (!hasLocationPermission()) {
            Log.w(TAG, "Keine Standort-Berechtigung — Autofahrt-Erkennung pausiert")
            stopSelf()
            return START_NOT_STICKY
        }
        // M18.66: Gate — Autofahrt-Erkennung in den Trigger-Settings aus?
        // Dann keinen GPS-Stream halten (Akku), sauber beenden. Der Service
        // wird vom Gate beim Aktivieren wieder gestartet.
        // M18.72: Der Service trägt auch die Wanderungs-Erkennung (GPS-
        // Displacement) — er läuft, solange Autofahrt ODER Walking an ist.
        if (!bridge.isDrivingEnabled() && !bridge.isWalkingEnabled()) {
            Log.d(TAG, "Autofahrt- und Walking-Erkennung deaktiviert — Service beendet sich")
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground starten (location-Typ). Fehler -> sauber stoppen.
        // M19: Konsolidierte Hintergrund-Benachrichtigung statt eigener.
        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.ensureChannel(this)
        val notification = com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this)
        try {
            startForeground(
                com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                notification,
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

        // M18.79: Stream-Recycling. Ein bereits aktiver Callback wird
        // NICHT abgerissen und neu angemeldet — das erzeugt sonst bei
        // jedem App-Start eine Fix-Lücke (GPS-Neuakquise). Nur wenn
        // kein Stream läuft (erster Start oder Service wurde vom System
        // gekillt und neu gestartet), wird einer angelegt.
        if (callback == null) {
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
        } else {
            Log.d(TAG, "Location-Stream läuft bereits — kein Neustart (App-Start-Recycling)")
        }
        return START_STICKY
    }

    private fun handleFix(loc: Location) {
        val now = System.currentTimeMillis()

        // M18.76-BLACKOUT-FIX („Fahrterkennung funktioniert nicht mehr“):
        // driveActive-Selbstheilung. M18.75 setzte driveActive nur beim
        // manuellen Dashboard-Stop zurück. Die Session kann aber über
        // MEHRERE andere Pfade enden, ohne dass das Flag gecleart wird —
        // dann klassifiziert der Service NIE WIEDER (Blackout bis
        // App-Neustart):
        //   • „■ Stoppen“-Button in der Live-Notification
        //     (LiveActivityService.ACTION_STOP, M18.75 nicht abgedeckt!)
        //   • manuelles Speichern einer neuen Activity (SaveManualActivityUseCase)
        //   • Session-Wechsel (DashboardViewModel.switchActivity)
        //   • forceFinishForAuto (Geofence/Ping/Walking übernehmen die Session)
        // Der Abgleich mit der realen Live-Session heilt ALLE diese Pfade
        // inkl. zukünftiger — ohne neue Call-Sites zu vergessen. PAUSED
        // zählt als live (isLive), damit Pause+Weiter die Erkennung nicht
        // killt; nur wenn wirklich keine Auto-Session mehr läuft, wird
        // das Flag zurückgesetzt.
        if (bridge.isDriveActive()) {
            val live = liveActivityManager.liveSession.value
            val autoSessionStillLive = live != null && live.isLive &&
                live.sourceType == "ACTIVITY_RECOGNITION_AUTO"
            if (!autoSessionStillLive) {
                // M18.79: healIfOrphaned respektiert das Start-in-flight-
                // Fenster nach markDriveConfirmed — ein Race zwischen
                // Bestätigung und asynchronem DriveStartWorker-Lauf killt
                // die Erkennung nicht mehr (siehe Bridge-Kommentar).
                if (bridge.healIfOrphaned(now)) {
                    Log.d(TAG, "M18.76-Selbstheilung: driveActive ohne laufende Auto-Session -> zurückgesetzt (Blackout verhindert)")
                }
            }
        }

        // M18.66-FIX13: GPS-KALTSTART-WARMUP.
        // Die ersten 60 Sekunden nach Service-Start werden ignoriert.
        // In dieser Phase liefert FusedLocationProvider oft falsche
        // Speed-Werte (10-30 m/s trotz Stillstand) — der Empfänger
        // sucht Satelliten, position springt, speed wird aus der
        // Sprungdistanz geschätzt. Das ist exakt das "5 Minuten nach
        // Update"-Muster: App-Update → Service-Neustart → Kaltstart.
        // Probes werden in dieser Zeit NICHT gepuffert → Engine kann
        // sie nicht fälschlich auswerten.
        if (serviceStartMs == 0L || now - serviceStartMs < GPS_WARMUP_MS) {
            Log.d(TAG, "GPS-Warmup (Kaltstart) — Probe ignoriert (${(now - serviceStartMs) / 1000}s)")
            return
        }

        val speed = if (loc.hasSpeed()) loc.speed else null
        val accuracy = loc.accuracy
        val distance = if (lastLat != null && lastLon != null) {
            haversineMeters(lastLat!!, lastLon!!, loc.latitude, loc.longitude)
        } else null

        // M18.84: GEOFENCE-KONTEXT (lazy, einmal pro Service-Lebensdauer):
        // Benannte Orte als GeoCircle in die Bridge — Basis für das
        // classify-Veto (alle Probes in einem Kreis = keine Fahrt) und für
        // die Walking-Phasen-Ortslogik. Laden bewusst NICHT blockierend im
        // onStartCommand (Service-Start-Latenz), sondern beim ersten Fix
        // async — bis dahin klassifiziert die Engine ohne Veto (alter
        // Zustand, kein False-Negative-Risiko durch fehlenden Kontext).
        if (!geofenceContextLoaded) {
            geofenceContextLoaded = true
            serviceScope.launch {
                try {
                    val circles = geofenceRepository.getAll().first()
                        .filter { it.deletedAt == null && it.enabled }
                        .map {
                            DriveDetectionEngine.GeoCircle(
                                id = it.id,
                                name = it.name,
                                latitude = it.latitude,
                                longitude = it.longitude,
                                radiusMeters = it.radiusMeters.toDouble()
                            )
                        }
                    bridge.setGeofenceContext(circles)
                    Log.d(TAG, "M18.84: ${circles.size} Geofence-Kreise geladen (Veto-Kontext)")
                } catch (e: Exception) {
                    // Kontext-Laden gescheitert → Veto bleibt aus (konservativ,
                    // kein Crash des Location-Streams). Nächster Service-
                    // Restart versucht erneut.
                    Log.w(TAG, "M18.84: Geofence-Kontext laden fehlgeschlagen: ${e.message}")
                }
            }
        }

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
        //
        // M18.67-FIX3: NACH bestätigter Fahrt wird der Heartbeat JEDEN
        // Poll refresht, solange speed > 1 m/s ist — egal ob classify
        // gerade Driving sagt. Die classify()-Re-Evaluation nach
        // drainDriveProbes() braucht 2 Min Spread (MIN_SPREAD_MS) → in
        // dieser Zeit würde der Heartbeat nicht refreshed → Watchdog
        // stoppt die Fahrt nach 5 Min (User: "hört oft nach 5 Minuten auf").
        // Jetzt: Einmal Driving → kontinuierlich am Leben, bis speed
        // wirklich < 1 m/s ist (Ampel/Stop-and-Go zählt nicht als Stop).
        val isReliableFix = accuracy <= 50f
        bridge.addDriveProbe(probe, refreshHeartbeat = false)

        if (bridge.isDriveActive() && speed != null && speed >= 2.0f) {
            // M18.67-FIX4: Schwelle von 1.0 → 3.0 m/s (10,8 km/h).
            // Vorher: Gehen (1,0-1,5 m/s) refreshte den Heartbeat →
            // 3 h zu Fuß = 4 h Autofahrt (User-Bug). 3 m/s schließt
            // Gehen aus, erfasst aber Stadtverkehr (5-15 m/s).
            // Ampel-Phasen (speed=0 für <5 Min) deckt der Watchdog.
            // M18.71: 3.0 -> 2.0 m/s (7,2 km/h). Stop&Go-Stadtverkehr
            // (Kriech-Tempo 5-10 km/h) fällt sonst unter die Schwelle
            // und der Watchdog stoppt die Fahrt nach 5 Min, obwohl sie
            // weiterläuft. 2 m/s bleibt über Geh-Tempo (1,0-1,5 m/s).
            bridge.refreshDriveHeartbeat(now)
            DriveWatchdogWorker.schedule(this)
        }

        // M18.66-FIX5: distance-basierten Heartbeat-Refresh ENTFERNT.
        // Indoor-GPS-Drift erzeugt 10-20m Springer trotz still sitzendem
        // User — das refreshte den Heartbeat fälschlich → Watchdog
        // lief nie ab. Nur Speed >= 3.0 m/s darf den Heartbeat refreshen.

        lastLat = loc.latitude
        lastLon = loc.longitude
        lastTsMs = now

        // M18.86: Track-Recording — verdichtet den Fix in die Strecken-
        // aufzeichnung, wenn eine trackbare Session (Auto/Walking) läuft.
        maybeRecordTrackPoint(loc, now, speed)

        // M18.84: Läuft die Fahrt noch (Herzschlag wurde oben refresht),
        // ist ein aktiver Restart-Cooldown überholt — er stammt aus einem
        // verlorenen Stop-Flag (Prozess-Tod) und würde eine REAL laufende
        // zweite Fahrt nach kurzer Pause blocken. Laufende Fahrt = Fahrt.
        if (bridge.isDriveActive() && bridge.isWithinDriveRestartCooldown(now)) {
            bridge.markDriveStopped(0L)
            Log.d(TAG, "M18.84: Cooldown bei laufender Fahrt zurückgesetzt")
        }

        // Serie klassifizieren — NUR wenn noch keine Fahrt bestätigt ist.
        // Nach bestätigter Fahrt wird die Fahrt über speed > 1 m/s am
        // Leben gehalten (siehe oben), nicht über Re-Klassifikation.
        // M18.84: (a) classify mit GEOFENCE-VETO (Indoor-Multipath im
        // Gym erfüllt sonst alle Speed-Gates), (b) Inside-Geofence-Cap:
        // Auch wenn das Veto nicht greift (ein Rand-Fix fiel aus dem
        // Kreis), startet KEINE Fahrt, deren komplett zurückdatierter
        // Start innerhalb eines Orts-Kreises liegt — der User war dort
        // nachweislich anwesend (Gym-Geofence-Session läuft), kein Auto.
        if (!bridge.isDriveActive()) {
            val circles = bridge.currentGeofenceContext()
            when (val result = DriveDetectionEngine.classify(bridge.currentDriveProbes(), now, circles)) {
                is DriveDetectionEngine.Classification.Driving -> {
                    // M18.84 INSIDE-GEOFENCE-CAP: Der Cluster-Start, den der
                    // DriveStartWorker rückdatiert, ist der älteste Probe.
                    // Liegt DER in einem benannten Orts-Kreis, war der User
                    // dort nachweislich anwesend (seine Geofence-Session
                    // läuft) — der "Fahrt-Anfang" ist Indoor-Drift, kein
                    // Auto. Blockiert selbst dann, wenn das Veto oben nicht
                    // griff (z. B. weil ein Rand-Fix knapp aus dem Kreis
                    // fiel, die Serie aber inzwischen km-weit driftete).
                    val windowProbes = bridge.currentDriveProbes()
                        .filter { now - it.timestampMs <= DriveDetectionEngine.MAX_PROBE_AGE_MS }
                    val oldestWithCoords = windowProbes
                        .filter { it.latitude != null && it.longitude != null }
                        .minByOrNull { it.timestampMs }
                    val startInsideGeofence = oldestWithCoords != null && circles.any {
                        DriveDetectionEngine.isInsideCircle(
                            oldestWithCoords!!.latitude!!, oldestWithCoords!!.longitude!!, it
                        )
                    }
                    if (startInsideGeofence) {
                        Log.d(
                            TAG,
                            "M18.84-Cap: Fahrt-Start läge in einem benannten Ort (ältester Probe im Kreis) — Start blockiert"
                        )
                        // Probes aus diesem Fenster verwerfen (sie gehören
                        // zum Ort, nicht zu einer Fahrt) und weiter sammeln.
                        bridge.drainDriveProbes()
                    } else {
                        Log.d(TAG, "Fahrt erkannt (GPS-Stream, conf=${result.confidence}) -> Start")
                        // M18.66-FIX15: GPS-Bestätigung markieren, BEVOR die Probes
                        // gedrained werden. Der DriveStartWorker prüft sonst leere
                        // Probes (classify=InsufficientData) und würde den Start
                        // trotz bestätigter Fahrt ablehnen.
                        bridge.markDriveConfirmed()
                        bridge.drainDriveProbes()  // alte Probes leeren, nur frische zählen
                        DriveStartWorker.schedule(this)
                        DriveWatchdogWorker.schedule(this)
                    }
                }
                else -> {
                    // Noch nicht genug / keine Fahrt — weiter sammeln.
                    // KEIN Heartbeat-Refresh → Watchdog läuft nach 5 Min ab.
                }
            }
        }

        // M18.72: WANDERUNGS-ERKENNUNG (GPS-Pfad).
        // Unabhängig von der Autofahrt-Erkennung: Der User kann gehen,
        // während keine Fahrt aktiv ist. Nur wenn gerade keine andere
        // Auto-Aufzeichnung läuft (nicht-überlappend, User-Spec).
        if (!bridge.isWalkingActive() && !bridge.isDriveActive()) {
            updateWalkingPhase(loc, now)
        }
    }

    /**
     * M18.72: Walking-Phase über Netto-Displacement verfolgen.
     *
     * Start: erster Fix mit ausreichender Genauigkeit (≤ 50 m). Bei jedem
     * Fix wird die geradlinige Distanz zum Phasenstart gemessen. Sind
     * ≥ 300 m erreicht UND die Engine-Schwelle (5 Minuten) erfüllt, wird
     * die Wanderung gestartet (WalkingStartWorker prüft zusätzlich, dass
     * nichts anderes aufzeichnet). Steht die Bewegung länger als
     * 2 Minuten still, wird die Phase verworfen — der 5-Minuten-Zähler
     * startet bei neuer Bewegung neu.
     *
     * M18.84 ORTS-BOUNDARIES: Die Phase beginnt NEU, wenn der User einen
     * benannten Orts-Kreis betritt oder verlässt (Ankunft ist "Unterwegs-
     * Ende", kein Wanderungs-Beginn; ein Ortswechsel über Auto ist keine
     * Wanderung). Der alte Code startete die Phase beim Parken/Aussteigen
     * sofort neu und zählte dann die FAHRT-Zeit als Walking-Dauer mit —
     * inklusive Netto-Displacement (User-Fall: "Spazieren 19:05–19:17"
     * beim 100-m-Gang zur Wohnung, gemessen ab Gym-Parkplatz).
     */
    private fun updateWalkingPhase(loc: Location, now: Long) {
        // Nur brauchbare Fixes zählen (gleiche Genauigkeits-Regel wie
        // DriveDetectionEngine.MAX_ACCURACY_M).
        if (loc.accuracy > 50f) return

        // M18.84: Orts-Kontext — in welchem benannten Kreis steht der Fix?
        val circles = bridge.currentGeofenceContext()
        val insideCircle = circles.firstOrNull {
            DriveDetectionEngine.isInsideCircle(loc.latitude, loc.longitude, it)
        }

        if (walkingPhaseStartMs == 0L) {
            // M18.84: Phase NICHT starten, während der Fix in einem
            // benannten Ort liegt — der User ist "da angekommen", nicht
            // "auf Wanderung". Die Phase beginnt erst mit dem ersten
            // Fix AUSSERHALB der Orte (echtes Unterwegs).
            if (insideCircle != null) {
                return
            }
            walkingPhaseStartMs = now
            walkPhaseStartLat = loc.latitude
            walkPhaseStartLon = loc.longitude
            walkLastLat = loc.latitude
            walkLastLon = loc.longitude
            return
        }

        // M18.84 ORTSWECHSEL-RESET: Betritt der User während einer
        // laufenden Phase einen benannten Ort, endet das "Unterwegs" hier
        // (Ankunft) — die Phase wird verworfen, ein neuer Besuch startet
        // sauber beim nächsten Verlassen. Ohne diesen Reset lief die
        // Phase über die Ankunft hinaus weiter und akkumulierte die
        // nächste Fahrt als "Wanderung".
        if (insideCircle != null) {
            Log.d(TAG, "M18.84: Walking-Phase bei Orts-Eintritt ('${insideCircle.name}') verworfen")
            resetWalkingPhase()
            return
        }

        val movedSinceLast = haversineMeters(
            walkLastLat ?: loc.latitude, walkLastLon ?: loc.longitude,
            loc.latitude, loc.longitude
        )

        // Lange Stillstand: Phase verwerfen (Bewegung war nur ein
        // Raumwechsel / kurzer Weg).
        if (now - walkingPhaseStartMs > WalkingDetectionEngine.WALKING_THRESHOLD_MS &&
            movedSinceLast < 1.0
        ) {
            Log.d(TAG, "Walking-Phase verworfen (Stillstand > 5min)")
            resetWalkingPhase()
            return
        }
        walkLastLat = loc.latitude
        walkLastLon = loc.longitude

        // Netto-Displacement vom Phasenstart.
        val startLat = walkPhaseStartLat ?: return
        val startLon = walkPhaseStartLon ?: return
        val net = haversineMeters(startLat, startLon, loc.latitude, loc.longitude)
        val duration = now - walkingPhaseStartMs

        // Erst nach 5 Minuten prüfen (Engine-Schwelle) — aber die Phase
        // läuft währenddessen weiter (Netto-Displacement wächst).
        if (duration < WalkingDetectionEngine.WALKING_THRESHOLD_MS) return

        if (net < WALKING_MIN_GPS_DISTANCE_M) {
            // Noch nicht weit genug vom Start weg — z. B. Gehen im Park
            // um den Block. Kein Start, aber Phase läuft weiter.
            return
        }

        Log.d(TAG, "Wanderung erkannt (GPS-Displacement ${net.toInt()}m in ${duration / 1000}s) -> Start")
        // Signal in die Bridge: Der WalkingStartWorker prüft die
        // 5-Minuten-Schwelle und startet mit Vorlaufzeit.
        // M18.84: Vorlauf-Clamp passiert im WalkingStartWorker (Engine:
        // recordingStartTime gegen letztes Auto-Ende) — hier läuft die
        // Phase nach dem Reset ohnehin nur außerhalb von Orten.
        bridge.markWalkingSignal(walkingPhaseStartMs)
        // Walking-Phase zurücksetzen, damit ein späterer erneuter Start
        // (nach dem Stopp) frisch beginnt.
        resetWalkingPhase()
        WalkingStartWorker.schedule(this)
    }

    /** M18.84: Walking-Phase-Felder zentral zurücksetzen (alle Reset-Pfade
     *  — Stillstand, Orts-Eintritt, erkannte Wanderung — teilen ihn). */
    private fun resetWalkingPhase() {
        walkingPhaseStartMs = 0L
        walkPhaseStartLat = null
        walkPhaseStartLon = null
        walkLastLat = null
        walkLastLon = null
    }

    // ──────────────────────────────────────────────────────────────
    // M18.86: TRACK-RECORDING (Fahrtstrecke für die Orts-Timeline).
    //
    // Während einer laufenden Auto-Session (ACTIVITY_RECOGNITION_AUTO)
    // oder Wanderung (WALKING_AUTO) schreibt der Service verdichtete
    // GPS-Punkte in location_track_point:
    //   • BEWEGUNGS-Punkt: ≥ 30 m seit dem letzten Punkt (~alle 25 s
    //     bei Stadt-Tempo — "nicht jede Kurve, aber alle paar Minuten",
    //     User-Wortlaut)
    //   • HEARTBEAT-Punkt: alle 5 Min auch ohne Bewegung (Ampel/Stau —
    //     sonst klammert die Karte stehende Phasen weg und springt)
    //   • Genauigkeits-Gate ≤ 50 m wie überall (Indoor-Multipath hat
    //     hier nichts verloren — ein Track ist Strecken-EVIDENZ)
    // Der 5-Sekunden-Stream existiert ohnehin (Fahrterkennung) — kein
    // zusätzlicher Sensor-Burn, nur ~1 Insert je 25 s über den Service-
    // Scope (gebatcht: insertAll einer Liste, nie pro Fix).
    // ──────────────────────────────────────────────────────────────
    private var trackLastLat: Double? = null
    private var trackLastLon: Double? = null
    private var trackLastPointAtMs: Long = 0L
    /** M18.86: Session-Id des letzten Track-Punkts — Wechsel = neuer
     *  Anker-Punkt (jede Strecke beginnt mit EINEM Punkt am Start, auch
     *  ohne 30-m-Bewegung; sonst klammerte die Karte den Aussteige-/Start-
     *  Moment weg und die Strecke begähe mitten in der Bewegung).
     *  M18.89: Sonderwert [PENDING_TRACK_STATE_KEY], solange die Fixe nur
     *  "vorläufig" (Fahrt erkannt, Session noch nicht live) gesammelt
     *  werden. */
    private var trackLastSessionId: String? = null
    private val trackBuffer = java.util.Collections.synchronizedList(mutableListOf<com.d_drostes_apps.aevum.data.model.LocationTrackPoint>())

    // ════════════════════════════════════════════════════════════════
    // M18.89: PRE-SESSION-TRACK-BACKFILL ("halbe Route"-Fix).
    //
    // Vorher: maybeRecordTrackPoint schrieb NUR, wenn die trackbare
    // Session schon live war. Live wird sie aber erst NACH der Erkennung
    // (classify: 90s-Spread + Worker + Gates ≈ 2–4 min) — und die Session
    // startet dann RÜCKDATIERT auf den Cluster-Start. Die gesamte Früh-
    // phase (Anfahren, Verlassen des Geofences) fehlte deshalb in JEDEM
    // Track: Die Route begann sichtbar erst mittendrin ("Route beginnt
    // erst ab der Mitte zwischen den beiden Geofences").
    //
    // Fix: Solange eine Fahrt ERKANNT ist (bridge.isDriveActive) bzw. eine
    // Walking-Phase läuft, aber noch keine trackbare Session existiert,
    // landen die Fixes im pending-Buffer. Sobald die Session live ist,
    // werden die gesammelten Fixe (rückdatiert, sessionId = live.id) in
    // den normalen Track-Puffer überführt. Startet KEINE Session (False
    // Positive, Cooldown-Verwerfen), wird der Puffer verworfen — es
    // bleibt bei "Evidenz statt Müll".
    // ════════════════════════════════════════════════════════════════
    private data class PendingTrackFix(
        val recordedAt: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val speedMps: Float?
    )
    private val pendingTrackBuffer = java.util.Collections.synchronizedList(
        mutableListOf<PendingTrackFix>()
    )

    /** M18.86: Verdichtet den Fix in den Track-Puffer, wenn eine
     *  trackbare Session läuft — oder (M18.89) eine Fahrt bereits ERKANNT
     *  ist und die Session in Kürze startet (Backfill der Frühphase).
     *  Flush passiert batched im selben Lauf. */
    private fun maybeRecordTrackPoint(loc: Location, now: Long, speedMps: Float?) {
        if (loc.accuracy > TRACK_MAX_ACCURACY_M) return

        // Welche Session läuft (und ist damit track-würdig)? Nur Auto-
        // Fahrten und Wanderungen haben Strecken — Geofence-Besuche sind
        // Punkte, manuelle Sessions haben keine Location-Evidenz.
        val live = liveActivityManager.liveSession.value
        val trackable = live != null && live.isLive && (
            live.sourceType == "ACTIVITY_RECOGNITION_AUTO" ||
                live.sourceType == "WALKING_AUTO"
            )

        // M18.89: BACKFILL-FALL — Fahrt erkannt (driveActive) oder Walking-
        // Phase aktiv, aber Session noch nicht live. Fixe für die spätere
        // Session vormerken.
        val pending = !trackable &&
            (bridge.isDriveActive() || walkingPhaseStartMs != 0L)

        if (!trackable && !pending) {
            // Kein trackbarer Zustand — altes Pending wäre Fehl-Evidenz
            // (Fahrt wurde doch nicht gestartet / Cooldown-Pfad hat
            // verworfen): verwerfen, damit die NEUE Fahrt nicht die alten
            // Punkte erbt.
            if (pendingTrackBuffer.isNotEmpty()) {
                pendingTrackBuffer.clear()
                Log.d(TAG, "M18.89: Pending-Track verworfen (kein trackbarer Zustand)")
            }
            return
        }

        // Session/State-Wechsel: Track-Anker neu setzen (erster Punkt immer).
        val stateKey = if (trackable) live!!.id else PENDING_TRACK_STATE_KEY
        val sessionChanged = trackLastSessionId != stateKey
        if (sessionChanged) {
            // Übergang pending → live Session: gesammelte Frühphase mit der
            // REALen sessionId in den normalen Puffer überführen.
            if (trackable && trackLastSessionId == PENDING_TRACK_STATE_KEY) {
                val migrated = synchronized(pendingTrackBuffer) {
                    val copy = pendingTrackBuffer.toList()
                    pendingTrackBuffer.clear()
                    copy
                }
                val migratedPoints = migrated.map {
                    com.d_drostes_apps.aevum.data.model.LocationTrackPoint(
                        id = java.util.UUID.randomUUID().toString(),
                        sessionId = live!!.id,
                        recordedAt = it.recordedAt,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        accuracyMeters = it.accuracyMeters,
                        speedMps = it.speedMps
                    )
                }
                if (migratedPoints.isNotEmpty()) {
                    trackBuffer.addAll(migratedPoints)
                    Log.d(TAG, "M18.89: ${migratedPoints.size} Pre-Session-Track-Punkte in Session überführt (Backfill der Frühphase)")
                }
            } else if (trackLastSessionId == PENDING_TRACK_STATE_KEY) {
                // Session-Wechsel OHNE gültige neue live-Session (kann nicht
                // passieren — stateKey pending nur bei pending) — defensiv.
                pendingTrackBuffer.clear()
            }
            trackLastLat = null
            trackLastLon = null
            trackLastPointAtMs = 0L
            trackLastSessionId = stateKey
        }

        val movedM = if (trackLastLat != null && trackLastLon != null) {
            haversineMeters(trackLastLat!!, trackLastLon!!, loc.latitude, loc.longitude)
        } else Double.MAX_VALUE // erster Punkt der Session immer

        val timeForHeartbeat = now - trackLastPointAtMs >= TRACK_HEARTBEAT_MS
        if (movedM < TRACK_MIN_MOVEMENT_M && !timeForHeartbeat) return

        if (pending) {
            // Sammelphase: nur im Speicher, KEIN DB-Write (Room-FK auf
            // activity_session würde ohne echte Session crashen).
            synchronized(pendingTrackBuffer) {
                pendingTrackBuffer += PendingTrackFix(
                    recordedAt = now,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracyMeters = loc.accuracy,
                    speedMps = speedMps
                )
                // Größen-/Alters-Grenze: sehr lange Erkennungsphasen oder
                // verwaiste Sammelzustände müssen nicht endlos wachsen.
                while (pendingTrackBuffer.size > PENDING_TRACK_MAX_POINTS) {
                    pendingTrackBuffer.removeAt(0)
                }
                val cutoff = now - PENDING_TRACK_MAX_AGE_MS
                pendingTrackBuffer.removeAll { it.recordedAt < cutoff }
            }
        } else {
            trackBuffer += com.d_drostes_apps.aevum.data.model.LocationTrackPoint(
                id = java.util.UUID.randomUUID().toString(),
                sessionId = live!!.id,
                recordedAt = now,
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracyMeters = loc.accuracy,
                speedMps = speedMps
            )

            // Batch-Flush: alle 8 Punkte ODER alle 60 s — was zuerst kommt.
            // Bewusst gechunkt statt pro Fix (DB-Write-I/O auf IO-Dispatcher).
            val timeSinceFlush = now - trackLastFlushMs
            if (trackBuffer.size >= TRACK_FLUSH_BATCH || timeSinceFlush >= TRACK_FLUSH_INTERVAL_MS) {
                flushTrackBuffer()
            }
        }
        trackLastLat = loc.latitude
        trackLastLon = loc.longitude
        trackLastPointAtMs = now
    }

    private var trackLastFlushMs: Long = 0L

    /** M18.86: Puffer persistieren (IO) und lokal leeren. */
    private fun flushTrackBuffer() {
        if (trackBuffer.isEmpty()) return
        val batch = synchronized(trackBuffer) {
            val copy = trackBuffer.toList()
            trackBuffer.clear()
            copy
        }
        trackLastFlushMs = System.currentTimeMillis()
        serviceScope.launch {
            try {
                trackPointRepository.insertAll(batch)
                Log.d(TAG, "M18.86: ${batch.size} Track-Punkte persistiert (Session ${batch.firstOrNull()?.sessionId?.take(8)})")
            } catch (e: Exception) {
                // Persistenz-Fehler darf den Location-Stream nie crashen;
                // Punkte sind Ergänzung, kein kritischer Pfad. Bewusst
                // KEIN Retrying (Duplikate durch REPLACE-Id-Unfall-Risiko
                // minimieren — verlorene 8 Punkte sind verkraftbar).
                Log.w(TAG, "M18.86: Track-Flush fehlgeschlagen: ${e.message}")
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_drive_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_drive_channel_desc)
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
            .setContentTitle(getString(R.string.service_drive_title))
            .setContentText(getString(R.string.service_drive_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        // M18.86: Restlichen Track-Puffer flushen, bevor der Scope stirbt
        // (lieber sofort als auf den nächsten Service-Start warten).
        flushTrackBuffer()
        callback?.let { cb ->
            try { fusedClient.removeLocationUpdates(cb) } catch (_: Exception) {}
        }
        callback = null
        // M18.84: Service-Scope sauber abbauen (Geofence-Snapshot-Laden
        // darf nicht über den zerstörten Service hinauslaufen).
        serviceScope.cancel()
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

package com.d_drostes_apps.aevum.automation.activityrecognition

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.104: EREIGNISGETRIEBENE FAHRT-/WANDERUNGS-ERKENNUNG (Akku-Redesign).
 *
 * ═════════════════════════════════════════════════════════════════════
 * WAS SICH GEÄNDERT HAT UND WARUM (Root Cause des 20%-in-5h-Akku-
 * Verbrauchs bei 5 Minuten Bildschirmzeit):
 *
 * M18.66 hielt diesen Service als DAUER-Location-Stream am Leben
 * (PRIORITY_HIGH_ACCURACY, alle 15s, 24/7 solange Auto/Walking-Erkennung
 * aktiv war). Der GPS-Chip schlief NIE — auch nicht, wenn der User 5 h
 * still im Büro saß. Ein FGS zählt in der Akku-Statistik als
 * "Vordergrund", weshalb Android die 20% der APP zuschrieb, obwohl die
 * Bildschirmzeit nur 5 Minuten betrug.
 *
 * M18.93v10/v11 drosselte nur Intervalle (5s→15s Stream, 2min→5min
 * Probes) — die Grundarchitektur "GPS immer an" blieb. Dieser Rewrite
 * ersetzt sie durch das Muster, das die Android-Doku ("Optimize
 * location use for battery life": "Request updates when the targeted
 * activity is detected, and remove updates when the user stops
 * performing that activity"; "avoid using PRIORITY_HIGH_ACCURACY for
 * sustained background work") und Life360/DriveQuant/HyperTrack
 * verwenden:
 *
 *   DAUER-SIGNALE (OS-managed, ~0 Akku):
 *     • Activity-Recognition-Transitions (Sensor-Hub, GMS)
 *     • GMS-Geofences (ENTER/EXIT via PendingIntent)
 *   BURST-SIGNALE (kurz, zweckgebunden, teuer):
 *     • CONFIRM-Burst (6 Min HIGH 15s): bestätigt einen Fahrzeug-
 *       Verdacht (AR IN_VEHICLE-ENTER, Geofence-EXIT) über die
 *       DriveDetectionEngine, BEVOR eine Session startet. DriveQuant:
 *       "GPS is deliberately not activated while the driver is not
 *       moving or before a trip is confirmed."
 *     • WALKING_CHECK-Burst (8 Min BALANCED 60s): misst das Netto-
 *       Displacement (≥ 300 m) für die Wanderungs-Erkennung.
 *     • TRACK: Während einer bestätigten Auto-/Wanderungs-Session
 *       läuft der Stream so lange die Session lebt. Ein 2-Min-Tick
 *       prüft, ob die Session noch läuft — beendet sie sich (Watchdog,
 *       Google-EXIT, manueller Stop, PAUSE-Split), geht der Stream AUS.
 *       Der Tick deckt ALLE Stop-Pfade ab, ohne dass jeder Worker den
 *       Service stoppen müsste (gleiche Lektion wie die M18.76-Blackout-
 *       Selbstheilung: nie Call-Sites jagen — Zustand abgleichen).
 *
 * Die ERKENNUNGS-SEMANTIK ist unverändert übernommen (jedes Gate aus
 * M18.66–M18.103 bleibt): GPS-Kaltstart-Warmup, Genauigkeits-Gate,
 * Netto-Displacement-Gate, Geofence-Veto + Inside-Geofence-Cap,
 * Restart-Cooldown, driveActive-Selbstheilung, Walking-Orts-
 * Boundaries, Track-Recording mit Pre-Session-Backfill.
 *
 * FGS-Start-Beschränkungen (Android 12+): Alle Start-Pfade sind
 * offizielle Exemptions — "your app receives an event that's related
 * to geofencing or activity recognition transition" (Android-Doku,
 * "Restrictions on starting a foreground service from the background").
 * ═════════════════════════════════════════════════════════════════════
 */
@AndroidEntryPoint
class DriveDetectionService : Service() {

    @Inject lateinit var bridge: ActivityRecognitionBridge
    @Inject lateinit var liveActivityManager: com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
    // M18.84: Benannte Orte für das classify-Geofence-Veto + laufende
    // Walking-Phase. Feld-Injection, einmaliger Snapshot pro Service-
    // Lebensdauer (Geofences ändern sich selten; ein Restart des
    // Service lädt neu).
    @Inject lateinit var geofenceRepository: PlaceGeofenceRepository
    // M18.86: Track-Punkte-Persistenz (Fahrtstrecke für die Orts-Timeline).
    @Inject lateinit var trackPointRepository: com.d_drostes_apps.aevum.data.repository.LocationTrackPointRepository

    /** Service-eigener Coroutine-Scope (Timer, Geofence-Snapshot, Flush). */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** M18.84: Beginn der laufenden Walking-Phase (0 = keine). */
    private var walkingPhaseStartMs: Long = 0L

    private lateinit var fusedClient: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTsMs: Long = 0L
    /** M18.84: Wurde der Geofence-Kontext (Veto-Kreise) bereits geladen? */
    private var geofenceContextLoaded = false
    /** M18.66-FIX13: Beginn des ERSTEN Streams dieser Service-Lebensdauer —
     *  die ersten 60s werden ignoriert (GPS-Kaltstart: speed oft Müllwerte
     *  bei scheinbar akzeptabler Genauigkeit). EINMAL pro Prozess — Folge-
     *  Bursts laufen mit warmem Empfänger; nur der allererste Fix nach
     *  echtem Kaltstart (Prozess frisch, GPS-Chip im Schlaf) braucht den
     *  Warmup. Wird erst bei erfolgreicher Stream-Anmeldung gesetzt. */
    private var streamStartMs: Long = 0L

    // ── M18.104: Burst-Zustandsmaschine ──────────────────────────────
    /** Aktueller Stream-Modus (OFF = kein GPS-Stream, Service endet). */
    private var mode: StreamMode = StreamMode.OFF
    /** Beginn des aktuellen Modus (Fenster-Timeout-Basis). */
    private var modeStartMs: Long = 0L
    /** Fenster-/Tick-Timer (Coroutine im lebenden FGS-Prozess — wird
     *  nicht eingefroren, delay() läuft zuverlässig; gleiches Muster
     *  wie der 1s-Loop im LiveActivityService). */
    private var modeTimerJob: Job? = null
    /** Ende des letzten ergebnislosen CONFIRM-Bursts (Burst-Cooldown). */
    private var lastResultlessConfirmEndMs: Long = 0L
    /** Ende des letzten ergebnislosen WALKING-Bursts. */
    private var lastResultlessWalkingEndMs: Long = 0L
    /** Grace-Deadline: Bestätigung frisch gesetzt, Session-Start des
     *  DriveStartWorker noch in-flight → Stream hält bis dahin. */
    private var confirmGraceUntilMs: Long = 0L
    /** M18.104: Verlängerungs-Zähler der aktuellen CONFIRM-Episode
     *  (Bewegungs-Gate + wiederholte Trigger — cap gegen Flapping). */
    private var confirmExtensions: Int = 0
    /** M18.104: Verlängerungs-Zähler der aktuellen WALKING-Episode. */
    private var walkingExtensions: Int = 0
    /** M18.104: Letztes Netto-Displacement der aktiven Walking-Phase (m). */
    private var lastWalkingNetDisplacementM: Double = 0.0
    /** Aktuell registrierte Stream-Parameter (Recycling-Check). */
    private var activePriority: Int = -1
    private var activeIntervalMs: Long = -1L

    private enum class StreamMode {
        OFF, CONFIRM, WALKING, TRACK_DRIVE, TRACK_WALK
    }

    // ──────────────────────────────────────────────────────────────
    // M18.72: WALKING-PHASE-Felder (GPS-Pfad der Wanderungs-Erkennung).
    // Vollständig übernommen aus M18.84 (Orts-Boundaries, Reset-Regeln).
    // ──────────────────────────────────────────────────────────────
    private var walkPhaseStartLat: Double? = null
    private var walkPhaseStartLon: Double? = null
    private var walkLastLat: Double? = null
    private var walkLastLon: Double? = null

    companion object {
        private const val TAG = "DriveDetectionSvc"

        /** M18.104: Aktionen des ereignisgetriebenen Starts. */
        const val ACTION_CONFIRM = "com.d_drostes_apps.aevum.DETECTION_CONFIRM"
        const val ACTION_WALKING_CHECK = "com.d_drostes_apps.aevum.DETECTION_WALKING_CHECK"
        const val ACTION_TRACK_RESTORE = "com.d_drostes_apps.aevum.DETECTION_TRACK_RESTORE"

        /** CONFIRM-Stream: HIGH_ACCURACY 15s — wie der alte Dauer-Stream
         *  (identische Erkennungs-Dichte: MIN_SPREAD 30s = 3 Fixes,
         *  2er-Kette, Netto 150m — die Engine merkt keinen Unterschied),
         *  aber nur für die Dauer des Burst-Fensters statt 24/7. */
        private const val CONFIRM_INTERVAL_MS = 15_000L
        /** WALKING-Stream: BALANCED 60s — WLAN/Cell-Fixes statt GPS-Chip-
         *  Dauerbetrieb. Displacement ≥ 300m braucht keine 15s-Dichte:
         *  8 Fixes à 60s erfassen 300m bei Geh-Tempo (1,4 m/s) locker. */
        private const val WALKING_INTERVAL_MS = 60_000L
        /** TRACK-Stream für Fahrten: 15s HIGH (Strecken-Aufzeichnung,
         *  TRACK_MIN_MOVEMENT_M = 60m ≈ alle 30s bei 30 km/h). */
        private const val TRACK_DRIVE_INTERVAL_MS = 15_000L
        /** TRACK-Stream für Wanderungen: 60s BALANCED — Gehen (1,4 m/s ×
         *  60s = 84m) überschreitet die 60m-Punkte-Schwelle weiterhin. */
        private const val TRACK_WALK_INTERVAL_MS = 60_000L
        /** Mindest-Bewegung zwischen zwei Fixes, die als "Wanderung
         *  lebt" zählt (~7 km/h — Herzschlag-Refresh bei TRACK_WALK). */
        private const val MIN_PROBE_MOVEMENT_M = 10.0
        /** M18.71: GPS-Kaltstart-Warmup (90s → 60s). */
        private const val GPS_WARMUP_MS = 60_000L
        /** M18.72: Mindest-Netto-Displacement für eine Wanderung. */
        private const val WALKING_MIN_GPS_DISTANCE_M = 300.0

        // ── M18.86: Track-Recording-Konstanten (ADR-0030, unverändert) ──
        private const val TRACK_MIN_MOVEMENT_M = 60.0
        private const val TRACK_HEARTBEAT_MS = 5L * 60 * 1000
        private const val TRACK_MAX_ACCURACY_M = 50f
        private const val TRACK_FLUSH_BATCH = 8
        private const val TRACK_FLUSH_INTERVAL_MS = 60_000L

        // ── M18.89: Pre-Session-Backfill-Konstanten (unverändert) ──
        private const val PENDING_TRACK_STATE_KEY = "pending"
        private const val PENDING_TRACK_MAX_AGE_MS = 25L * 60 * 1000
        private const val PENDING_TRACK_MAX_POINTS = 400

        /** M18.104: Restore-Warmup. Nach Prozess-Tod (Sticky-Restart,
         *  ACTION_TRACK_RESTORE) kann die Room-Query der liveSession
         *  noch laufen → .value wäre trotz laufender Session null
         *  (gleiche Lektion wie LiveActivityService M18.41: "null ist
         *  erst nach 3s wirklich null"). Der Restore wartet max. 3s. */
        private const val RESTORE_WARMUP_MS = 3_000L
        private const val RESTORE_POLL_MS = 600L

        /** M18.104: Start mit Aktion (von Receivern/Workern/Application).
         *  startForegroundService ist erlaubt: Alle Aufrufer sind AR-/
         *  Geofence-Event-Pfade (offizielle Exemptions, Android 12+) oder
         *  Vordergrund-/FGS-Kontexte (App-Start, laufende Worker). */
        fun start(context: Context, action: String) {
            val intent = Intent(context, DriveDetectionService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // FGS-Start darf NIE crashen (M18.66-FIX-Muster). Schlägt
                // er fehl, lebt die Erkennung über die nächsten AR-/
                // Geofence-Events weiter (jedes startet einen neuen Burst).
                Log.w(TAG, "Start ($action) fehlgeschlagen: ${e.message}")
            }
        }

        /** Beendet den Service komplett (Settings-Gate OFF). */
        fun stop(context: Context) {
            context.stopService(Intent(context, DriveDetectionService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (!hasLocationPermission()) {
            Log.w(TAG, "Keine Standort-Berechtigung — Erkennung pausiert")
            stopSelf()
            return START_NOT_STICKY
        }

        // M18.104: FGS IMMER zuerst starten (idempotent) — Android
        // verlangt nach JEDEM startForegroundService() ein startForeground()
        // innerhalb 5s, auch wenn der Service schon läuft (sonst
        // ForegroundServiceDidNotStartInTimeException auf manchen
        // OEMs). Lehnt der Start ab einen Modus, beendet sich der
        // Service unten sofort wieder (Notification-Blitz < 1s, selten).
        try {
            startForeground(
                com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location-FGS verweigert (Background-Location fehlt?)", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Foreground-Start fehlgeschlagen", e)
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_CONFIRM -> {
                if (bridge.isDrivingEnabled()) {
                    enterConfirm()
                } else if (bridge.isWalkingEnabled() && !bridge.isDriveActive()) {
                    // M18.104: Geofence-EXIT mit deaktivierter Fahr-Erkennung
                    // ist trotzdem ein Bewegungs-Verdacht → Walking-Check.
                    enterWalkingCheck()
                } else {
                    stopIfIdle("CONFIRM: Fahr- und Walking-Erkennung deaktiviert")
                }
            }
            ACTION_WALKING_CHECK -> {
                if (bridge.isWalkingEnabled() && !bridge.isDriveActive()) {
                    enterWalkingCheck()
                } else {
                    stopIfIdle("WALKING_CHECK: Walking aus oder Fahrt aktiv")
                }
            }
            ACTION_TRACK_RESTORE, null -> {
                // Restore (Worker nach Session-Start) oder Sticky-Restart
                // nach Prozess-Tod: Track-Stream wieder aufnehmen, WENN
                // eine trackbare Session läuft. Der 2-Min-Tick beendet
                // ihn automatisch, sobald die Session endet.
                restoreTrackAsync()
            }
            else -> stopIfIdle("Unbekannte Aktion: $action")
        }
        return START_STICKY
    }

    /** Service nur beenden, wenn gerade kein anderer Modus läuft (ein
     *  abgewiesener Request darf einen aktiven Burst nicht töten). */
    private fun stopIfIdle(reason: String) {
        if (mode == StreamMode.OFF) {
            Log.d(TAG, "$reason — Service beendet (idle)")
            stopSelf()
        } else {
            Log.d(TAG, "$reason — aktiver Modus $mode läuft weiter")
        }
    }

    /** M18.104: Restore mit Warmup — nach Prozess-Tod liefert
     *  liveSession.value evtl. noch null, obwohl die Session lebt
     *  (Room-Query in-flight, M18.41-Lektion). Bis 3s warten. */
    private fun restoreTrackAsync() {
        serviceScope.launch {
            val deadline = System.currentTimeMillis() + RESTORE_WARMUP_MS
            var session = liveActivityManager.liveSession.value
            while (session == null && System.currentTimeMillis() < deadline) {
                delay(RESTORE_POLL_MS)
                session = liveActivityManager.liveSession.value
            }
            val trackable = session != null && session.isRunning && (
                session.sourceType == "ACTIVITY_RECOGNITION_AUTO" ||
                    session.sourceType == "WALKING_AUTO"
                )
            if (trackable) {
                Log.d(TAG, "Restore: trackbare Session '${session!!.title}' — TRACK aufnehmen")
                enterTrack(session.sourceType == "ACTIVITY_RECOGNITION_AUTO")
            } else {
                Log.d(TAG, "Restore: keine trackbare Session — idle")
                if (mode == StreamMode.OFF) stopSelf()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // M18.104: MODUS-ÜBERGÄNGE
    // ════════════════════════════════════════════════════════════════

    private fun enterConfirm() {
        val now = System.currentTimeMillis()
        when (mode) {
            StreamMode.CONFIRM -> {
                // Läuft bereits — Fenster auffrischen (REPLACE-Semantik
                // wie die Worker: ein wiederholtes AR-ENTER ist ein
                // FRISCHER Verdacht).
                modeStartMs = now
                armModeTimer()
                return
            }
            StreamMode.TRACK_DRIVE, StreamMode.TRACK_WALK ->
                // Session läuft schon — Track hat Vorrang. CONFIRM wäre
                // redundant (Fixes fließen bereits in dichtester Form).
                return
            StreamMode.WALKING -> {
                // Upgrade: BALANCED 60s → HIGH 15s. Die Walking-Phase
                // profitiert weiter (mehr Fixes = besseres Displacement),
                // der Fahrzeug-Verdacht bekommt die nötige Dichte.
                Log.d(TAG, "Modus-Upgrade: WALKING → CONFIRM (Fahrzeug-Verdacht)")
            }
            StreamMode.OFF -> {
                if (!DetectionBurstPolicy.confirmBurstAllowed(now, lastResultlessConfirmEndMs)) {
                    Log.d(TAG, "CONFIRM-Burst im Cooldown — verworfen")
                    stopSelf()
                    return
                }
                Log.d(TAG, "CONFIRM-Burst gestartet (6 Min HIGH_ACCURACY)")
                // Neue Episode: Verlängerungs-Zähler zurücksetzen (Cap
                // gilt pro Episode, nicht pro Prozess-Lebensdauer).
                confirmExtensions = 0
            }
        }
        mode = StreamMode.CONFIRM
        modeStartMs = now
        confirmGraceUntilMs = 0L
        requestStreamForCurrentMode()
        armModeTimer()
    }

    private fun enterWalkingCheck() {
        val now = System.currentTimeMillis()
        when (mode) {
            StreamMode.WALKING -> {
                modeStartMs = now
                armModeTimer()
                return
            }
            StreamMode.CONFIRM, StreamMode.TRACK_DRIVE, StreamMode.TRACK_WALK ->
                // Fixes fließen bereits in gleicher oder besserer Dichte —
                // updateWalkingPhase läuft in handleFix in JEDEM Modus.
                return
            StreamMode.OFF -> {
                if (!DetectionBurstPolicy.walkingBurstAllowed(now, lastResultlessWalkingEndMs)) {
                    Log.d(TAG, "WALKING-Burst im Cooldown — verworfen")
                    stopSelf()
                    return
                }
                Log.d(TAG, "WALKING_CHECK-Burst gestartet (8 Min BALANCED)")
                // Neue Episode: Zähler + Displacement-Referenz zurücksetzen.
                walkingExtensions = 0
                lastWalkingNetDisplacementM = 0.0
            }
        }
        mode = StreamMode.WALKING
        modeStartMs = now
        requestStreamForCurrentMode()
        armModeTimer()
    }

    private fun enterTrack(isDrive: Boolean) {
        val target = if (isDrive) StreamMode.TRACK_DRIVE else StreamMode.TRACK_WALK
        if (mode == target) {
            armModeTimer() // Tick auffrischen (Session lebt nachweislich)
            return
        }
        Log.d(TAG, "TRACK-Modus: ${if (isDrive) "Fahrt" else "Wanderung"} — Stream folgt der Session")
        mode = target
        modeStartMs = System.currentTimeMillis()
        confirmGraceUntilMs = 0L
        requestStreamForCurrentMode()
        armModeTimer()
    }

    /** (Re-)Registriert den Location-Stream mit den Parametern des
     *  aktuellen Modus. Modus-Parameter unverändert → NICHT abreißen
     *  (M18.79 Recycling-Lektion: jede Neu-Anmeldung erzeugt eine
     *  Fix-Lücke durch GPS-Neuakquise). */
    @SuppressLint("MissingPermission")
    private fun requestStreamForCurrentMode() {
        val (priority, intervalMs) = when (mode) {
            StreamMode.CONFIRM -> Priority.PRIORITY_HIGH_ACCURACY to CONFIRM_INTERVAL_MS
            StreamMode.TRACK_DRIVE -> Priority.PRIORITY_HIGH_ACCURACY to TRACK_DRIVE_INTERVAL_MS
            StreamMode.WALKING -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to WALKING_INTERVAL_MS
            StreamMode.TRACK_WALK -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to TRACK_WALK_INTERVAL_MS
            StreamMode.OFF -> return
        }
        if (callback != null && activePriority == priority && activeIntervalMs == intervalMs) {
            return
        }
        callback?.let { old ->
            try { fusedClient.removeLocationUpdates(old) } catch (_: Exception) {}
        }
        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs * 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handleFix(it) }
            }
        }
        callback = cb
        activePriority = priority
        activeIntervalMs = intervalMs
        try {
            fusedClient.requestLocationUpdates(req, cb, mainLooper)
            // M18.66-FIX13: Warmup-Referenz = ERSTE erfolgreiche Anmeldung
            // dieser Prozess-Lebensdauer (echter GPS-Kaltstart).
            if (streamStartMs == 0L) streamStartMs = System.currentTimeMillis()
            Log.d(TAG, "Stream aktiv: $mode ($priority, ${intervalMs / 1000}s)")
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates fehlgeschlagen", e)
            mode = StreamMode.OFF
            stopSelf()
        }
    }

    /** Modus-Fenster/Tick-Timer (Coroutinen-delay im lebenden FGS). */
    private fun armModeTimer() {
        modeTimerJob?.cancel()
        val windowMs = when (mode) {
            StreamMode.CONFIRM -> DetectionBurstPolicy.CONFIRM_WINDOW_MS
            StreamMode.WALKING -> DetectionBurstPolicy.WALKING_CHECK_WINDOW_MS
            StreamMode.TRACK_DRIVE, StreamMode.TRACK_WALK -> DetectionBurstPolicy.TRACK_TICK_MS
            StreamMode.OFF -> return
        }
        modeTimerJob = serviceScope.launch {
            delay(windowMs)
            onModeWindowElapsed()
        }
    }

    /**
     * M18.104: Bewegungs-Gate für die CONFIRM-Verlängerung. Prüft die
     * aktuelle Probe-Serie auf echte Fortbewegung:
     *   a) Netto-Displacement ≥ 150 m (Engine-Gate-Wert — Indoor-Drift
     *      springt 10-50 m, echte Fortbewegung verlässt den Bereich),
     *   b) ODER Durchschnitts-Geschwindigkeit ≥ 2 m/s (7,2 km/h —
     *      Stau/Kriech-Tempo; weit über Geh-Tempo 1,5 m/s).
     * Stillstand erfüllt KEINES von beiden → keine Verlängerung.
     */
    private fun movementGateFromProbes(
        probes: List<DriveDetectionEngine.DriveProbe>
    ): Boolean {
        val withCoords = probes
            .filter { it.latitude != null && it.longitude != null }
        if (withCoords.size < 2) return false
        val first = withCoords.first()
        val last = withCoords.last()
        val net = haversineMeters(
            first.latitude!!, first.longitude!!,
            last.latitude!!, last.longitude!!
        )
        if (net >= DriveDetectionEngine.MIN_NET_DISPLACEMENT_M) return true
        val speeds = probes.mapNotNull { it.speedMps }.filter { it <= DriveDetectionEngine.OUTLIER_SPEED_MPS }
        if (speeds.isNotEmpty() &&
            speeds.average() >= DetectionBurstPolicy.EXTENSION_MIN_AVG_SPEED_MPS
        ) return true
        return false
    }

    /** Fenster/Tick abgelaufen — zentrale Zustandsübergänge. */
    private fun onModeWindowElapsed() {
        val now = System.currentTimeMillis()
        when (mode) {
            StreamMode.CONFIRM -> {
                // Grace: Bestätigung frisch gesetzt, Session-Start des
                // DriveStartWorker noch in-flight → kurz halten (gleiches
                // Fenster wie DRIVE_CONFIRM_IN_FLIGHT_MS, M18.79).
                if (bridge.driveConfirmedAgeMs(now) < DetectionBurstPolicy.GRACE_EXTENSION_MS) {
                    confirmGraceUntilMs = now + DetectionBurstPolicy.GRACE_EXTENSION_MS
                }
                if (now < confirmGraceUntilMs) {
                    Log.d(TAG, "CONFIRM-Grace: Bestätigung frisch — warte auf Session-Start")
                    modeTimerJob = serviceScope.launch {
                        delay(DetectionBurstPolicy.GRACE_EXTENSION_MS)
                        onModeWindowElapsed()
                    }
                    return
                }
                // Zwischenzeitlich eine Session gestartet? → TRACK.
                val session = liveActivityManager.liveSession.value
                if (session != null && session.isRunning && (
                    session.sourceType == "ACTIVITY_RECOGNITION_AUTO" ||
                        session.sourceType == "WALKING_AUTO"
                    )
                ) {
                    enterTrack(session.sourceType == "ACTIVITY_RECOGNITION_AUTO")
                    return
                }
                // M18.104 BEWEGUNGS-ERNEUERUNGSGATE: Der Burst lief
                // ergebnislos ab — ABER die Probe-Serie zeigt echte
                // Bewegung (Netto ≥ 150 m ODER avg ≥ 2 m/s): Stau/Kriech-
                // Tempo/Anfahren, das die 8-m/s-Gates der Engine noch
                // nicht erfüllt. Fenster verlängern statt Stream aus —
                // sonst würde eine Stockverkehr-Fahrt genau dann erblindet,
                // wenn die Engine die 2er-Kette gleich schafft. Stillstand
                // (Indoor-Drift) verlängert NIE: Drift < 150 m, Speed ~ 0.
                if (confirmExtensions < DetectionBurstPolicy.MAX_CONFIRM_EXTENSIONS &&
                    movementGateFromProbes(bridge.currentDriveProbes())
                ) {
                    confirmExtensions++
                    Log.d(TAG, "CONFIRM verlängert (Bewegung erkannt, Erneuerung #${confirmExtensions}/${DetectionBurstPolicy.MAX_CONFIRM_EXTENSIONS})")
                    armModeTimer()
                    return
                }
                // Ergebnislos: Cooldown setzen. Läuft noch eine Walking-
                // Phase (Displacement am Wachsen), bekommt sie ein
                // Frisch-Fenster im WALKING-Modus.
                lastResultlessConfirmEndMs = now
                if (bridge.isWalkingEnabled() && walkingPhaseStartMs != 0L) {
                    Log.d(TAG, "CONFIRM ergebnislos, Walking-Phase aktiv → WALKING_CHECK-Fortsetzung")
                    mode = StreamMode.WALKING
                    modeStartMs = now
                    requestStreamForCurrentMode()
                    armModeTimer()
                    return
                }
                Log.d(TAG, "CONFIRM-Burst ergebnislos beendet — Stream AUS")
                shutdownStream()
            }
            StreamMode.WALKING -> {
                val session = liveActivityManager.liveSession.value
                if (session != null && session.isRunning &&
                    session.sourceType == "WALKING_AUTO"
                ) {
                    enterTrack(false)
                    return
                }
                // Bewegungs-Verlängerung analog CONFIRM: Eine Phase, die
                // am Wachsen ist (Netto-Displacement > letzte Messung),
                // darf das Fenster einmal erneuern — der Spaziergang,
                // der nach 7 Min langsam die 300 m erreicht, fällt sonst
                // durchs Raster. Stillstand verlängert nie.
                if (walkingExtensions < DetectionBurstPolicy.MAX_WALKING_EXTENSIONS &&
                    walkingPhaseStartMs != 0L &&
                    lastWalkingNetDisplacementM > 0.0
                ) {
                    walkingExtensions++
                    Log.d(TAG, "WALKING verlängert (Phase aktiv, Erneuerung #${walkingExtensions}/${DetectionBurstPolicy.MAX_WALKING_EXTENSIONS})")
                    armModeTimer()
                    return
                }
                lastResultlessWalkingEndMs = now
                Log.d(TAG, "WALKING-Burst beendet (ergebnislos) — Stream AUS")
                shutdownStream()
            }
            StreamMode.TRACK_DRIVE, StreamMode.TRACK_WALK -> {
                // 2-Min-Tick: Lebt die trackbare Session noch? Das deckt
                // ALLE Stop-Pfade ab (Watchdog, Google-EXIT, manueller
                // Stop, PAUSE-Split) — robust gegen zukünftige Call-Sites.
                val session = liveActivityManager.liveSession.value
                val trackable = session != null && session.isRunning && (
                    session.sourceType == "ACTIVITY_RECOGNITION_AUTO" ||
                        session.sourceType == "WALKING_AUTO"
                    )
                if (!trackable) {
                    Log.d(TAG, "Session beendet — Track-Stream AUS")
                    shutdownStream()
                    return
                }
                // Session-Typ kann gewechselt haben (Fahrt → Spazieren).
                enterTrack(session!!.sourceType == "ACTIVITY_RECOGNITION_AUTO")
            }
            StreamMode.OFF -> Unit
        }
    }

    /** Stream sauber herunterfahren + Service beenden. */
    private fun shutdownStream() {
        mode = StreamMode.OFF
        activePriority = -1
        activeIntervalMs = -1
        callback?.let { cb ->
            try { fusedClient.removeLocationUpdates(cb) } catch (_: Exception) {}
        }
        callback = null
        // Restlichen Track-Puffer flushen (M18.86 onDestroy-Pflicht).
        flushTrackBuffer()
        stopSelf()
    }

    // ════════════════════════════════════════════════════════════════
    // FIX-VERARBEITUNG — vollständige Übernahme der M18.66–M18.103-Logik
    // ════════════════════════════════════════════════════════════════

    private fun handleFix(loc: Location) {
        val now = System.currentTimeMillis()

        // M18.76-BLACKOUT-FIX: driveActive-Selbstheilung — Abgleich mit
        // der realen Live-Session heilt ALLE Session-Ende-Pfade.
        if (bridge.isDriveActive()) {
            val live = liveActivityManager.liveSession.value
            val autoSessionStillLive = live != null && live.isLive &&
                live.sourceType == "ACTIVITY_RECOGNITION_AUTO"
            if (!autoSessionStillLive) {
                if (bridge.healIfOrphaned(now)) {
                    Log.d(TAG, "M18.76-Selbstheilung: driveActive ohne laufende Auto-Session -> zurückgesetzt")
                }
            }
        }

        // M18.66-FIX13: GPS-KALTSTART-WARMUP — erste 60s nach der ERSTEN
        // Stream-Anmeldung dieser Prozess-Lebensdauer ignorieren.
        if (streamStartMs == 0L || now - streamStartMs < GPS_WARMUP_MS) {
            Log.d(TAG, "GPS-Warmup (Kaltstart) — Probe ignoriert (${(now - streamStartMs) / 1000}s)")
            return
        }

        val speed = if (loc.hasSpeed()) loc.speed else null
        val accuracy = loc.accuracy
        val distance = if (lastLat != null && lastLon != null) {
            haversineMeters(lastLat!!, lastLon!!, loc.latitude, loc.longitude)
        } else null

        // M18.84: GEOFENCE-KONTEXT (lazy, einmal pro Service-Lebensdauer).
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

        // M18.66-FIX6/M18.67-FIX3: Heartbeat NUR bei bestätigter, leben-
        // der Fahrt (speed >= 2 m/s — M18.71). Vor Bestätigung KEIN
        // Refresh (Watchdog läuft sonst nie ab).
        bridge.addDriveProbe(probe, refreshHeartbeat = false)

        if (bridge.isDriveActive() && speed != null && speed >= 2.0f) {
            bridge.refreshDriveHeartbeat(now)
            DriveWatchdogWorker.schedule(this)
        }

        lastLat = loc.latitude
        lastLon = loc.longitude
        lastTsMs = now

        // M18.86: Track-Recording (verdichtet den Fix, wenn eine trackbare
        // Session läuft ODER eine erkannte Fahrt/Walking-Phase vormerkt).
        maybeRecordTrackPoint(loc, now, speed)

        // M18.104: Laufende Wanderungs-Session → Walking-Herzschlag über
        // GPS-Bewegung refreshen (≥ 10m zwischen Fixes). Die Track-Fixes
        // liegen ohnehin an — ein Herzschlag kostet nichts und hält den
        // WalkingWatchdog am Leben, solange echte Bewegung herrscht.
        if (mode == StreamMode.TRACK_WALK && distance != null && distance >= MIN_PROBE_MOVEMENT_M) {
            bridge.markWalkingSignal(now)
        }

        // M18.84: Cooldown bei laufender Fahrt zurücksetzen (verlorenes
        // Stop-Flag aus Prozess-Tod darf eine REAL laufende Fahrt nicht
        // blocken).
        if (bridge.isDriveActive() && bridge.isWithinDriveRestartCooldown(now)) {
            bridge.markDriveStopped(0L)
            Log.d(TAG, "M18.84: Cooldown bei laufender Fahrt zurückgesetzt")
        }

        // Serie klassifizieren — NUR solange keine Fahrt bestätigt ist
        // (M18.66-FIX5: danach lebt die Fahrt über den Heartbeat).
        // M18.104-Gate: Fahr-Erkennung aus → keine Drive-Klassifikation
        // (WALKING-Bursts dürfen keine Fahrten starten).
        if (!bridge.isDriveActive() && bridge.isDrivingEnabled()) {
            val circles = bridge.currentGeofenceContext()
            when (val result = DriveDetectionEngine.classify(bridge.currentDriveProbes(), now, circles)) {
                is DriveDetectionEngine.Classification.Driving -> {
                    // M18.84 INSIDE-GEOFENCE-CAP: Cluster-Start in einem
                    // benannten Orts-Kreis = Indoor-Drift, kein Auto.
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
                        Log.d(TAG, "M18.84-Cap: Fahrt-Start läge in einem benannten Ort — Start blockiert")
                        bridge.drainDriveProbes()
                    } else {
                        Log.d(TAG, "Fahrt erkannt (GPS-Burst, conf=${result.confidence}) -> Start")
                        // M18.66-FIX15: Bestätigung markieren, BEVOR die
                        // Probes gedrained werden (DriveStartWorker-Gate).
                        bridge.markDriveConfirmed()
                        bridge.drainDriveProbes()
                        DriveStartWorker.schedule(this)
                        DriveWatchdogWorker.schedule(this)
                    }
                }
                else -> {
                    // Noch nicht genug / keine Fahrt — weiter sammeln
                    // (innerhalb des Burst-Fensters).
                }
            }
        }

        // M18.72: WANDERUNGS-ERKENNUNG (GPS-Pfad) — unabhängig von der
        // Autofahrt, nur wenn weder Walking-Session noch Fahrt aktiv.
        // Läuft in JEDEM Modus (CONFIRM-Fixes sind wegen der höheren
        // Dichte sogar besser fürs Displacement).
        if (!bridge.isWalkingActive() && !bridge.isDriveActive() && bridge.isWalkingEnabled()) {
            updateWalkingPhase(loc, now)
        }
    }

    /**
     * M18.72/M18.84: Walking-Phase über Netto-Displacement verfolgen —
     * vollständig übernommen (Orts-Boundaries, Stillstand-Reset,
     * 5-Min-Schwelle, 300m-Displacement).
     */
    private fun updateWalkingPhase(loc: Location, now: Long) {
        if (loc.accuracy > 50f) return

        val circles = bridge.currentGeofenceContext()
        val insideCircle = circles.firstOrNull {
            DriveDetectionEngine.isInsideCircle(loc.latitude, loc.longitude, it)
        }

        if (walkingPhaseStartMs == 0L) {
            // M18.84: Phase NICHT starten, während der Fix in einem
            // benannten Ort liegt (Ankunft ≠ Wanderungs-Beginn).
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

        if (insideCircle != null) {
            Log.d(TAG, "M18.84: Walking-Phase bei Orts-Eintritt ('${insideCircle.name}') verworfen")
            resetWalkingPhase()
            return
        }

        val movedSinceLast = haversineMeters(
            walkLastLat ?: loc.latitude, walkLastLon ?: loc.longitude,
            loc.latitude, loc.longitude
        )

        if (now - walkingPhaseStartMs > WalkingDetectionEngine.WALKING_THRESHOLD_MS &&
            movedSinceLast < 1.0
        ) {
            Log.d(TAG, "Walking-Phase verworfen (Stillstand > 5min)")
            resetWalkingPhase()
            return
        }
        walkLastLat = loc.latitude
        walkLastLon = loc.longitude

        val startLat = walkPhaseStartLat ?: return
        val startLon = walkPhaseStartLon ?: return
        val net = haversineMeters(startLat, startLon, loc.latitude, loc.longitude)
        val duration = now - walkingPhaseStartMs

        if (duration < WalkingDetectionEngine.WALKING_THRESHOLD_MS) return

        if (net < WALKING_MIN_GPS_DISTANCE_M) {
            // Noch nicht weit genug — aktuelle Netto-Distanz als
            // Verlängerungs-Referenz merken (Bewegungs-Gate im
            // WALKING-Fenster: wachsende Phase verlängert einmal).
            lastWalkingNetDisplacementM = net
            return
        }

        Log.d(TAG, "Wanderung erkannt (GPS-Displacement ${net.toInt()}m in ${duration / 1000}s) -> Start")
        bridge.markWalkingSignal(walkingPhaseStartMs)
        resetWalkingPhase()
        WalkingStartWorker.schedule(this)
    }

    private fun resetWalkingPhase() {
        walkingPhaseStartMs = 0L
        walkPhaseStartLat = null
        walkPhaseStartLon = null
        walkLastLat = null
        walkLastLon = null
        lastWalkingNetDisplacementM = 0.0
    }

    // ──────────────────────────────────────────────────────────────
    // M18.86/M18.89: TRACK-RECORDING + PRE-SESSION-BACKFILL —
    // vollständig übernommen.
    // ──────────────────────────────────────────────────────────────
    private var trackLastLat: Double? = null
    private var trackLastLon: Double? = null
    private var trackLastPointAtMs: Long = 0L
    private var trackLastSessionId: String? = null
    private val trackBuffer = java.util.Collections.synchronizedList(
        mutableListOf<com.d_drostes_apps.aevum.data.model.LocationTrackPoint>()
    )

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

    private fun maybeRecordTrackPoint(loc: Location, now: Long, speedMps: Float?) {
        if (loc.accuracy > TRACK_MAX_ACCURACY_M) return

        val live = liveActivityManager.liveSession.value
        val trackable = live != null && live.isRunning && (
            live.sourceType == "ACTIVITY_RECOGNITION_AUTO" ||
                live.sourceType == "WALKING_AUTO"
            )

        // M18.94-FIX: PAUSED-Sessions sind NICHT trackbar und KEIN
        // Backfill-Zustand (bewusste Unterbrechung — Pause = Split).
        val pending = !trackable && live?.isPaused != true &&
            (bridge.isDriveActive() || walkingPhaseStartMs != 0L)

        if (!trackable && !pending) {
            if (pendingTrackBuffer.isNotEmpty()) {
                pendingTrackBuffer.clear()
                Log.d(TAG, "M18.89: Pending-Track verworfen (kein trackbarer Zustand)")
            }
            return
        }

        val stateKey = if (trackable) live!!.id else PENDING_TRACK_STATE_KEY
        val sessionChanged = trackLastSessionId != stateKey
        if (sessionChanged) {
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
                    Log.d(TAG, "M18.89: ${migratedPoints.size} Pre-Session-Track-Punkte in Session überführt (Backfill)")
                }
            } else if (trackLastSessionId == PENDING_TRACK_STATE_KEY) {
                pendingTrackBuffer.clear()
            }
            trackLastLat = null
            trackLastLon = null
            trackLastPointAtMs = 0L
            trackLastSessionId = stateKey
        }

        val movedM = if (trackLastLat != null && trackLastLon != null) {
            haversineMeters(trackLastLat!!, trackLastLon!!, loc.latitude, loc.longitude)
        } else Double.MAX_VALUE

        val timeForHeartbeat = now - trackLastPointAtMs >= TRACK_HEARTBEAT_MS
        if (movedM < TRACK_MIN_MOVEMENT_M && !timeForHeartbeat) return

        if (pending) {
            synchronized(pendingTrackBuffer) {
                pendingTrackBuffer += PendingTrackFix(
                    recordedAt = now,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracyMeters = loc.accuracy,
                    speedMps = speedMps
                )
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
                Log.w(TAG, "M18.86: Track-Flush fehlgeschlagen: ${e.message}")
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        modeTimerJob?.cancel()
        flushTrackBuffer()
        callback?.let { cb ->
            try { fusedClient.removeLocationUpdates(cb) } catch (_: Exception) {}
        }
        callback = null
        serviceScope.cancel()
        Log.d(TAG, "Burst-Service gestoppt, GPS-Stream aus")
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
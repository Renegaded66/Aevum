package com.d_drostes_apps.aevum.automation.apptracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.d_drostes_apps.aevum.MainActivity
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.repository.AppTrackingEntryRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.67: APP-AUFZEICHNUNG — automatische Activity-Aufzeichnung pro App.
 * M18.151: Übergabe-Regeln zwischen „Digital" und App-Aufzeichnung
 * (User-Report: „… stoppt leider auch nicht die Aufzeichnung von Digital
 * und die neue Aufzeichnung von der zugewiesenen [App wird] gestartet,
 * sondern fälschlicherweise wird einfach weiterhin nur Digital
 * aufgezeichnet.").
 *
 * User-Spezifikation:
 *  - Apps, die in der App-Aufzeichnung (rechte Spalte) stehen, werden
 *    automatisch als Activity aufgezeichnet, wenn sie in den Vordergrund
 *    kommen (z.B. Instagram → "Doomscrolling").
 *  - App schließen → die gestartete Session wird gestoppt („direkt nach
 *    Beenden der App soll die aktuelle stoppen").
 *  - App öffnen, während die EIGENE App-Tracking-Session läuft (Wechsel
 *    von getrackter App A zu getrackter App B) → A stoppen, B starten
 *    (konsistent mit "Neue Trigger stoppen laufende") — „und zwar direkt".
 *  - M18.151: App öffnen, während „Digital" (SCREEN_AUTO) läuft → Digital
 *    wird SOFORT gestoppt, die zugewiesene Activity beginnt (Übergabe;
 *    Regeln zentral in [AppTrackingTakeoverPolicy]).
 *  - M18.151: Endet die App-Aufzeichnung, wird der Digital-Start über
 *    [ScreenRecordingWorker.schedule] neu angesetzt — ohne neuen
 *    Screen-ON-Zyklus startet „Digital" sonst nie wieder („Falls dann
 *    2 Minuten das Display an ist ohne eine ausgewählte App, dann soll
 *    Digital wieder starten.").
 *  - M18.151: Screen OFF beendet auch die App-Aufzeichnung SOFORT (vorher
 *    lief sie eingefroren bis zum nächsten Entsperren weiter → falscher
 *    Riesen-Block über die Nacht).
 *  - RANGFOLGE (zwingend, User): „… das bezieht sich nur auf Smartphone-
 *    Aufzeichnungen — Geofence-, sportliche, manuell gestartete und
 *    Autofahrt-Aufzeichnungen haben immer Vorrang." Der Service stoppt/
 *    übernimmt daher NUR Sessions der Smartphone-Familie (eigene
 *    APP_TRACKING-Session + SCREEN_AUTO); alles andere wird nie angefasst.
 *
 * Technik: ForegroundService (specialUse) mit UsageStats-Event-Polling
 * alle 15s. Kein GPS, kein ActivityRecognition — nur die Event-API, die
 * auch Digital Balance nutzt. Der Service läuft nur, wenn mindestens
 * eine App getrackt ist (Gate in start()/onStartCommand).
 *
 * M19.2 (User-Report: „… wird einfach weiterhin nur Digital aufgezeichnet —
 * die zugewiesene Activity startet nicht mehr"): Der Service hatte nur
 * zwei Start-Pfade (App-Prozess-Start in AevumApplication + Settings-
 * Änderungen im AppTrackingViewModel). Nach Reboot / App-Update /
 * OEM-Kill blieb er deshalb dauerhaft tot, während „Digital" über den
 * Manifest-Receiver (ScreenEventReceiver + ScreenRecordingWorker) von
 * selbst weiterlief. Ergänzt werden rein ADDITIVE Revival-Pfade (die
 * M18.151-Übergabe-Semantik bleibt unverändert):
 *  - Boot/MY_PACKAGE_REPLACED → BootReceiver startet neu (M19.2),
 *  - 15-Minuten-Watchdog → AppTrackingWatchdogWorker (M19.2),
 *  - Screen-On-Nudge → ScreenEventReceiver (M19.2).
 * Dafür pflegt der Service prozess-lokale Liveness-Marker im Companion
 * (alive/lastPollAtMs, siehe unten) — die Gates der Revival-Pfade, damit
 * ein lebender Service nie doppelt angestoßen wird.
 */
@AndroidEntryPoint
class AppTrackingService : Service() {

    @Inject lateinit var liveActivityManager: LiveActivityManager
    @Inject lateinit var trackingRepository: AppTrackingEntryRepository
    @Inject lateinit var automationSettingsDao: com.d_drostes_apps.aevum.data.db.AutomationSettingsDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: kotlinx.coroutines.Job? = null
    // M18.121 (Crash-Loop t_fe3e99da) / M18.122 (t_55c14376): Sticky-
    // Guard — bricht die System-Wiederbelebung (START_STICKY-Rebirth
    // nach Crash/Kill), die den "crasht alle paar Sekunden"-Loop
    // amplifiziert. M18.122 (D2): Zustand PERSISTIERT (SharedPrefs),
    // damit der Guard nach Prozess-Kill (neue Instanz) den Rebirth
    // noch erkennt. Siehe StickyGuards.kt.
    // M18.123-CRASHFIX: NIE im Property-Init konstruieren — Android
    // attacht den Context erst NACH dem Konstruktor (newInstance), ein
    // getSharedPreferences(this) hier crasht mit NPE und killt den
    // Prozess beim App-Start. Init in onCreate (s.u.).
    private lateinit var stickyGuard: com.d_drostes_apps.aevum.automation.StickyGuardService
    private var processStartedAtRealtime = 0L

    /** Aktuell im Vordergrund befindliche getrackte App (packageName). */
    private var currentTrackedApp: String? = null

    /** Session-ID, die dieser Service gestartet hat (nur die darf er stoppen). */
    private var trackedSessionId: String? = null

    // M18.67-FIX2: Foreground-Status wird im Service getrackt, nicht
    // jedes Mal neu aus einem kurzen Event-Fenster abgeleitet. Die alte
    // Methode fragte Events aus den letzten 15s ab — wenn der User aber
    // 5 Min still auf Instagram schaute, gab es nach 15s keine Events mehr
    // → Methode lieferte null → Service stoppte die Session nach ~15s.
    // Jetzt: Events dienen nur für Übergänge (FOREGROUND/BACKGROUND).
    // Keine Events = App noch im Vordergrund = Status bleibt.
    private var lastKnownForeground: String? = null
    private var isFirstPoll = true

    companion object {
        private const val TAG = "AppTrackingSvc"
        private const val CHANNEL_ID = "aevum_app_tracking"
        private const val NOTIFICATION_ID = 6401
        // M18.93v11 (User: "Akkuverbrauch weiter drosseln"): 5s-Polling
        // war überdimensioniert — App-Wechsel werden auch mit 15s
        // zuverlässig erkannt (der User bleibt i.d.R. Minuten in einer
        // App), aber mit 3x weniger Wakes/Stunde.
        private const val POLL_INTERVAL_MS = 15_000L
        // M18.104 (Akku-Redesign): Screen aus → nur alle 10 Min prüfen, ob
        // der Screen wieder an ist (der Loop muss laufen, um den Zustand
        // im Auge zu behalten, aber pollForegroundApp() wird übersprun-
        // gen — UsageStats-I/O fällt weg). 10 Min reichen locker: Der
        // SCREEN_ON-Broadcast weckt den Sofort-Poll sofort.
        private const val POLL_INTERVAL_SCREEN_OFF_MS = 10L * 60 * 1000
        // M18.122 (D3): Marker-Action für app-interne Starts — der
        // Sticky-Guard bricht NUR Intents OHNE Action (System-Rebirth).
        // JEDER interne Start setzt sie, siehe start().
        const val ACTION_INTERNAL_START = "com.d_drostes_apps.aevum.APP_TRACKING_INTERNAL_START"

        // M18.151: Letzter Vordergrund-Schnappschuss des Polls —
        // PROZESS-LOKAL. Der ScreenRecordingWorker (gleicher Prozess) darf
        // „Digital" nicht starten, wenn eine getrackte App im Vordergrund
        // ist; dieses Cache liefert die Antwort exakt (der Service ist die
        // Autorität für den Vordergrund), ohne im Worker neue UsageStats-
        // Semantik zu duplizieren. Frische-Guard: ältere Werte sind nichts
        // wert (der Service pollt bei Screen an alle 15s).
        @Volatile private var pollSnapshotPackage: String? = null
        @Volatile private var pollSnapshotAtMs: Long = 0L
        @Volatile private var pollSnapshotValid: Boolean = false

        /**
         * M18.151: Zuletzt gepollter Vordergrund (null = Homescreen o. ä.),
         * nur wenn höchstens [maxAgeMs] alt. null = kein frischer Wert.
         */
        fun recentPollSnapshot(maxAgeMs: Long): String? {
            if (!pollSnapshotValid) return null
            return if (System.currentTimeMillis() - pollSnapshotAtMs <= maxAgeMs) {
                pollSnapshotPackage
            } else null
        }

        // M19.2 (User-Report: „… wird einfach weiterhin nur Digital
        // aufgezeichnet — die zugewiesene Activity startet nicht mehr"):
        // Prozess-lokale Liveness-Marker. Der Watchdog
        // ([AppTrackingWatchdogWorker]) und der Screen-On-Nudge
        // ([ScreenEventReceiver]) laufen im selben Prozess wie dieser
        // Service — über diese Flags sehen sie OHNE Systemaufruf, ob der
        // Service noch lebt bzw. wirklich pollt, und beleben ihn sonst
        // sofort wieder, statt blind doppelt zu starten. Absichtlich NICHT
        // persistiert: Nach einem Prozess-Kill ist „false“ der korrekte
        // Anfangszustand — genau der Zustand, auf den die Wiederbelebung
        // reagieren soll. WARUM zwei Marker: isAlive() allein sagt nichts
        // über einen tatsächlich arbeitenden Poll-Loop (onCreate lebt auch
        // beim Self-Stopp ohne getrackte Apps); lastPollAtMs() macht das
        // sichtbar und hilft bei der Log-Diagnose des Reports.
        @Volatile private var alive = false
        @Volatile private var lastPollAtMs = 0L

        /** M19.2: Lebt der Service gerade in diesem Prozess? */
        fun isAlive(): Boolean = alive

        /** M19.2: Zeitpunkt des letzten Foreground-Polls (0 = nie gepollt). */
        fun lastPollAtMs(): Long = lastPollAtMs

        fun start(context: Context) {
            // M18.122 (D3): Interne Starts tragen explizit
            // ACTION_INTERNAL_START — null-Action bleibt damit dem
            // System-Rebirth vorbehalten (START_STICKY nach Prozess-Kill).
            val intent = Intent(context, AppTrackingService::class.java)
                .setAction(ACTION_INTERNAL_START)
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
            context.stopService(Intent(context, AppTrackingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // M19.2: Liveness-Marker setzen — die Gates des Watchdogs
        // ([AppTrackingWatchdogWorker]) und des Screen-On-Nudges
        // ([ScreenEventReceiver]) dürfen einen laufenden Service nicht
        // doppelt anstoßen; ab hier gilt der Service als „lebendig“.
        alive = true
        processStartedAtRealtime = android.os.SystemClock.elapsedRealtime()
        // M18.123-CRASHFIX: Guard erst hier konstruieren — ab onCreate
        // ist der Context garantiert attacht (Property-Init crasht mit
        // NPE, siehe StickyGuards.kt-Doku).
        stickyGuard = com.d_drostes_apps.aevum.automation.StickyGuardService(
            com.d_drostes_apps.aevum.automation.SharedPrefsStickyGuardPersistence(
                this, "app_tracking"
            )
        )
        createNotificationChannel()
        // M18.104: Screen-Receiver sofort registrieren — der FGS lebt
        // danach evtl. tagelang; der Receiver kostet im Schlaf nichts.
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // M18.121 (t_fe3e99da) / M18.122 (t_55c14376, D1/D2/D3):
        // STICKY-GUARD. Der Break steht NACH startForegroundCompat() —
        // der FGS-Vertrag (startForeground binnen 5s auf JEDEM Pfad)
        // muss zuerst erfüllt sein, ein stopSelf davor wäre
        // RemoteServiceException → Prozess-Kill (der alte Pfad riss
        // genau das). Der Break passiert NUR bei action == null (echter
        // System-Rebirth; alle internen Starts tragen seit M18.122
        // ACTION_INTERNAL_START) und verlässt sich auf den
        // PERSISTIERTEN letzten-Start-Zeitstempel (D2).
        val stickyRebirthBreak = intent?.action == null && stickyGuard.shouldBreakStickyRebirth(
            processStartedAtRealtime,
            android.os.SystemClock.elapsedRealtime()
        )

        // WICHTIG: startForeground() MUSS sofort (synchron) aufgerufen
        // werden — Android wirft sonst nach 5s eine
        // ForegroundServiceDidNotStartInTimeException. Der Gate-Check
        // (getrackte Apps?) läuft danach in der Coroutine; wenn keine
        // App getrackt ist, beendet sich der Service selbst.
        startForegroundCompat()

        // M18.121/M18.122: STICKY-GUARD-BREAK — NACH erfülltem
        // FGS-Vertrag. Nur ein echter System-Rebirth (action == null)
        // innerhalb des Persistenz-Fensters nach einem echten Start
        // wird gebrochen — der nächste echte Anlass (App-Start,
        // Settings-Änderung) startet den Service regulär.
        if (stickyRebirthBreak) {
            Log.w(TAG, "M18.122: Sticky-Rebirth ohne Action gebrochen (Kill-Restart-Loop-Schutz, FGS-Vertrag erfüllt) — Service beendet")
            stopSelf()
            return START_NOT_STICKY
        }
        // M18.122: Echter Start (Action inkl. ACTION_INTERNAL_START) —
        // Wall-Clock-Zeitstempel persistieren (D2).
        stickyGuard.markLegitStart()

        scope.launch {
            val enabled = try {
                trackingRepository.getEnabledOnce().isNotEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Gate-Check fehlgeschlagen", e)
                false
            }
            if (!enabled) {
                Log.d(TAG, "Keine getrackten Apps — Service beendet sich")
                stopSelf()
                return@launch
            }
            startPolling()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        // M19-v2: Konsolidierte Hintergrund-Benachrichtigung statt eigener.
        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.ensureChannel(this)
        val notification = com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // M18.107: SPECIAL_USE existiert erst ab API 34 — der alte
                // `>= Q`-Check rief die Konstante bereits auf Android 10-13
                // auf. Mit Manifest-Typ specialUse ist auf 29-33 der
                // 2-Arg-Aufruf (Manifest-Typ) vertragsgültig; ab 34+ muss
                // der Typ explizit übergeben werden.
                startForeground(
                    com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground fehlgeschlagen", e)
            stopSelf()
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            // M18.104 (Akku-Redesign): Screen-gesteuertes Polling. Vorher
            // lief der 15s-Loop rund um die Uhr (5.760 UsageStats-Abfragen/
            // Tag), obwohl nachts niemand Apps wechselt. Jetzt:
            //   • Screen AN  → normaler 15s-Poll (App-Wechsel-Erkennung
            //     wie gehabt).
            //   • Screen AUS → KEIN Poll. Der letzte Vordergrund-Status
            //     bleibt eingefroren: Die Session der zuletzt genutzten
            //     getrackten App läuft weiter (korrekt — der User hat die
            //     App nicht verlassen, er hat nur das Display ausgemacht;
            //     Android friert die App ein, sie bleibt "im Vordergrund").
            //     Beim SCREEN_ON-Event läuft der erste Poll sofort wieder
            //     und verarbeitet die angefallenen Events (processForeground-
            //     Events fragt seit "letztem Poll" ab — Lücken-los, weil
            //     das UsageStats-System die Events gepuffert hält).
            //   • Der Screen-Receiver ist ein System-Broadcast — registriert
            //     in onCreate des laufenden FGS, null Kosten im Schlaf.
            while (isActive) {
                val screenOn = screenOn
                if (screenOn) {
                    try {
                        pollForegroundApp()
                    } catch (e: Exception) {
                        Log.e(TAG, "Poll-Fehler", e)
                    }
                }
                delay(if (screenOn) POLL_INTERVAL_MS else POLL_INTERVAL_SCREEN_OFF_MS)
            }
        }
    }

    /** M18.104: Screen-Zustand (von ACTION_SCREEN_ON/OFF-Receiver im
     *  FGS-Kontext gepflegt). Default true — bis zum ersten Broadcast
     // ist der conservative Status "an" (ein verpasstes Poll-Intervall
     // ist unkritisch, eine eingefrorene Erkennung schon). */
    @Volatile private var screenOn: Boolean = true
    private var screenReceiver: android.content.BroadcastReceiver? = null

    /** M18.104: Screen-Receiver registrieren (im FGS-Kontext — läuft
     *  solange der Service lebt, null Kosten wenn der Screen aus ist). */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        // Sofort-Poll: Nach dem Aufwachen verpasste
                        // App-Wechsel sofort nachziehen (kein Warten auf
                        // den nächsten Takt).
                        if (pollingJob?.isActive == true) {
                            scope.launch {
                                try { pollForegroundApp() } catch (_: Exception) {}
                            }
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        // M18.151 (User-Spec: „Und zwar direkt. Also direkt
                        // nach Beenden der App soll die aktuelle stoppen."):
                        // Die App-Aufzeichnung endet SOFORT mit dem
                        // Bildschirm. VORHER lief sie eingefroren bis zum
                        // nächsten Entsperren weiter — lag das Handy über
                        // Nacht mit offener Instagram-Session, entstand ein
                        // falscher Riesen-Block bis zum Morgen (die Pause-
                        // Events des System wurden wegen des 17s-Event-
                        // Fensters nie gesehen). Konsistent mit M18.138
                        // („Bildschirm-Aufzeichnung stoppt sofort bei OFF").
                        if (pollingJob?.isActive == true) {
                            scope.launch {
                                try {
                                    stopOwnSessionIfRunning()
                                    currentTrackedApp = null
                                } catch (e: Exception) {
                                    Log.w(TAG, "Screen-OFF-Stop fehlgeschlagen: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Screen-Receiver-Registrierung fehlgeschlagen: ${e.message} — Polling läuft dauerhaft weiter")
        }
    }

    /**
     * M18.67-FIX2: Verarbeitet UsageStats-Events seit dem letzten Poll.
     * Aktualisiert [lastKnownForeground] nur bei FOREGROUND/BACKGROUND-
     * Events. Keine Events = App noch im Vordergrund = Status bleibt.
     *
     * Beim allerersten Poll wird ein größeres Fenster (24h) abgefragt,
     * um den aktuellen Foreground-Status zu initialisieren (falls die
     * App schon vor Service-Start geöffnet war).
     */
    private fun processForegroundEvents() {
        val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return
        val now = System.currentTimeMillis()
        val lookback = if (isFirstPoll) now - 24 * 60 * 60 * 1000L else now - POLL_INTERVAL_MS - 2_000L
        val events = usageStats.queryEvents(lookback, now) ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // M18.151: Faltung als PURE Logik ausgelagert ([ForegroundTracker],
            // unit-getestet) — identische Semantik wie zuvor, jetzt aber auch
            // vom [ForegroundAppProbe] (ScreenRecordingWorker) genutzt, damit
            // es nur EINE Definition von „Vordergrund" gibt.
            lastKnownForeground = ForegroundTracker.next(
                current = lastKnownForeground,
                packageName = event.packageName ?: "",
                isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED,
                isBackgroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                    event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            )
        }
        isFirstPoll = false
    }

    private suspend fun pollForegroundApp() {
        // M19.2: Zuerst den Liveness-Zeitstempel pflegen — Watchdog und
        // Screen-On-Nudge erkennen daran (letzte Poll-Zeit), dass der
        // Service tatsächlich noch arbeitet.
        lastPollAtMs = System.currentTimeMillis()
        processForegroundEvents()
        // M18.151: Schnappschuss für den ScreenRecordingWorker (gleicher
        // Prozess) — VOR den frühen Returns, damit JEDER Poll den Stand
        // aktualisiert (auch „Homescreen").
        rememberForegroundSnapshot()
        val foreground = lastKnownForeground ?: run {
            // Keine App im Vordergrund (Homescreen/Sperrbildschirm) →
            // eigene Session stoppen, falls eine läuft.
            stopOwnSessionIfRunning()
            currentTrackedApp = null
            return
        }

        // Getrackte Apps frisch laden (reagiert sofort auf UI-Änderungen)
        val tracked = try {
            trackingRepository.getEnabledOnce().associateBy { it.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Tracked-Liste laden fehlgeschlagen", e)
            return
        }

        val entry = tracked[foreground]
        if (entry == null) {
            // Aktuelle App ist NICHT getrackt → eigene Session stoppen
            // (falls die vorherige getrackte App verlassen wurde).
            stopOwnSessionIfRunning()
            currentTrackedApp = null
            return
        }

        // App ist getrackt.
        if (currentTrackedApp == foreground) {
            // Gleiche App wie beim letzten Poll — nichts tun.
            return
        }

        // Neue getrackte App im Vordergrund — M18.151: Übergabe-Regeln
        // zentral in [AppTrackingTakeoverPolicy] (unit-getestet):
        //   nichts live → starten · eigene Session → Wechsel (A→B) ·
        //   Digital (SCREEN_AUTO) → ÜBERNEHMEN · alles Fremde → Vorrang.
        val live = liveActivityManager.liveSession.value
        val action = AppTrackingTakeoverPolicy.onTrackedForeground(
            liveSourceType = live?.sourceType,
            // M18.151: „eigene" Familie = die von diesem Feature gestartete
            // Session ODER eine VERWAISTE App-Tracking-Session (nach einem
            // Service-Kill ist trackedSessionId leer, die Session in der DB
            // lebt aber weiter — sie gehört trotzdem zur Smartphone-Familie
            // und darf gewechselt/übernommen werden; fremde Quellen bleiben
            // unberührt). Nur dieses Feature erzeugt sourceType=APP_TRACKING.
            isOwnSession = live != null && (live.id == trackedSessionId ||
                live.sourceType == AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING)
        )

        when (action) {
            AppTrackingTakeoverPolicy.Action.KEEP_FOREIGN -> {
                // Geofence/Fahrt/Sport/manuell/Kalender läuft → NICHTS
                // anfassen (User-Rangfolge). Auch kein Start.
                Log.d(TAG, "Fremde Session läuft (source=${live?.sourceType}) — kein Auto-Start für $foreground (Rangfolge)")
                currentTrackedApp = foreground
                return
            }
            AppTrackingTakeoverPolicy.Action.TAKE_OVER_DIGITAL -> {
                val digital = live ?: return
                // Race-Schutz: NUR stoppen, wenn EXAKT diese Session noch
                // live ist — hat in der Zwischenzeit z. B. die Fahrterkennung
                // übernommen, wird sie nie angefasst.
                if (!liveActivityManager.stopIfLive(digital.id)) {
                    Log.d(TAG, "Digital-Übergabe abgebrochen — Live-Session hat inzwischen gewechselt")
                    currentTrackedApp = foreground
                    return
                }
                Log.d(TAG, "Digital (${digital.id}) SOFORT gestoppt — App-Aufzeichnung $foreground übernimmt")
            }
            AppTrackingTakeoverPolicy.Action.STOP_AND_START -> {
                // Eigene Session stoppen (Wechsel getrackte App A → B)
                stopOwnSessionIfRunning()
            }
            AppTrackingTakeoverPolicy.Action.START_APP -> Unit // nichts zuvor zu tun
        }

        // Auto-Start der zugeordneten Activity
        val typeId = entry.activityTypeId
        try {
            val session = liveActivityManager.start(
                activityTypeId = typeId,
                title = null,
                sourceType = AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING
            )
            trackedSessionId = session.id
            currentTrackedApp = foreground
            // M18.151: Live-Notification sicherstellen — vorher startete NUR
            // der Digital-Pfad den LiveActivityService; eine App-Session aus
            // dem Leerlauf heraus lief ohne Live-Banner (M18.21-Restore griff
            // nur beim App-Prozess-Start). Beim Digital-Übernahme-Fall läuft
            // der Service ohnehin — der Aufruf ist dann ein No-Op.
            com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
            Log.d(TAG, "Auto-Start: $foreground → Session ${session.id} (Typ $typeId)")
        } catch (e: Exception) {
            Log.e(TAG, "Auto-Start fehlgeschlagen für $foreground", e)
            trackedSessionId = null
            currentTrackedApp = null
        }
    }

    /** M18.151: Letzten Vordergrund-Stand für externe Konsumenten ablegen. */
    private fun rememberForegroundSnapshot() {
        pollSnapshotPackage = lastKnownForeground
        pollSnapshotAtMs = System.currentTimeMillis()
        pollSnapshotValid = true
    }

    /** Stoppt die eigene Session, falls sie noch live ist. */
    private suspend fun stopOwnSessionIfRunning() {
        // M18.151: Ziel bestimmen — die eigene Session (getrackte ID) ODER
        // eine verwaiste App-Tracking-Session (Service-Neustart:
        // trackedSessionId ist leer, die Session lebt aber weiter; sie
        // gehört zur Smartphone-Familie und muss z. B. beim Verlassen der
        // App trotzdem stoppen). Fremde Quellen werden NIE Ziel (Rangfolge).
        val live = liveActivityManager.liveSession.value
        val targetId: String? = when {
            live == null || !live.isLive -> null
            live.id == trackedSessionId -> live.id
            live.sourceType == AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING -> live.id
            else -> null
        }
        // stopIfLive statt unbedingtem stop(): Falls zwischenzeitlich etwas
        // Höherpriores übernommen hat (z. B. Fahrt-Start), wird NICHTS
        // angefasst.
        val stopped = targetId?.let { liveActivityManager.stopIfLive(it) } ?: false
        if (stopped) {
            try {
                // M18.134 (Kanban t_0bf5541e): Ein App-Tracking-Ende kann
                // einen Kalender-Termin verdrängt haben (jeder Auto-Start
                // trimmt die laufende CALENDAR_AUTO-Session) — Termin-
                // Wiedereinstieg sofort prüfen („läuft immer, wenn nichts
                // anderes läuft"), nicht erst beim 15-Minuten-Takt.
                com.d_drostes_apps.aevum.automation.calendar.CalendarAutoRunScheduler
                    .restartNow(applicationContext)
                Log.d(TAG, "Auto-Stop: Session $targetId")
                // M18.151 (User-Spec: „Falls dann 2 Minuten das Display an
                // ist ohne eine ausgewählte App, dann soll Digital wieder
                // starten."): Digital-Neuansatz einplanen. Ohne diesen
                // Aufruf hing der Digital-Start NUR am nächsten
                // Screen-ON-Zyklus — wer nach dem App-Ende am selben
                // Bildschirm-Einschaltvorgang weiternutzte, bekam nie
                // wieder Digital. Der Worker prüft beim Feuern selbst
                // (nichts live? Screen an? keine getrackte App im
                // Vordergrund?) — REPLACE macht den Aufruf idempotent.
                scheduleDigitalRecheck()
            } catch (e: Exception) {
                Log.e(TAG, "Auto-Stop-Nachbereitung fehlgeschlagen", e)
            }
        }
        trackedSessionId = null
    }

    /** M18.151: „Digital" nach dem App-Ende mit der konfigurierten
     *  Vorlaufzeit neu ansetzen (gleicher UniqueWork-Name wie der
     *  ScreenEventReceiver — ein einziger ausstehender Start). */
    private suspend fun scheduleDigitalRecheck() {
        try {
            val settings = automationSettingsDao.getSettingsSync()
            val minutes = settings?.screenRecordingMinutes ?: 5
            if (minutes == com.d_drostes_apps.aevum.automation.screen.ScreenRecordingEngine.DEACTIVATED) return
            com.d_drostes_apps.aevum.automation.screen.ScreenRecordingWorker.schedule(
                applicationContext,
                minutes.coerceAtLeast(0).toLong()
            )
            Log.d(TAG, "Digital-Neuansatz geplant (+${minutes}min nach App-Ende)")
        } catch (e: Exception) {
            Log.w(TAG, "Digital-Neuansatz konnte nicht geplant werden: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_apptracking_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_apptracking_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_apptracking_title))
            .setContentText(getString(R.string.service_apptracking_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        // M19.2: Liveness-Marker zurücksetzen — sonst glauben Watchdog und
        // Screen-On-Nudge fälschlich, der Service lebe noch, und eine
        // bewusst beendete App-Aufzeichnung (letzte getrackte App entfernt)
        // würde sofort wiederbelebt, statt zu bleiben.
        alive = false
        pollingJob?.cancel()
        // M18.104: Screen-Receiver sauber deregistrieren.
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { /* nie registriert */ }
        }
        screenReceiver = null
        scope.cancel()
        super.onDestroy()
    }
}

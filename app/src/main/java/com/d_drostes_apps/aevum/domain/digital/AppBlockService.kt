package com.d_drostes_apps.aevum.domain.digital

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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.AppLimit
import com.d_drostes_apps.aevum.data.repository.AppLimitRepository
import com.d_drostes_apps.aevum.data.repository.BalanceProfileRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.61: Digital Balance — Sperr-Service.
 *
 * Läuft als Foreground-Service und beobachtet per UsageEvents, welche App
 * gerade im Vordergrund ist. Ist für diese App ein aktives Limit erreicht
 * (und keine Ausnahme aktiv), erscheint ein Vollbild-Overlay:
 *   "App gesperrt — Limit erreicht"
 *   [Noch 5 Minuten] [Schließen]
 *
 * Das Overlay ist ein TYPE_APPLICATION_OVERLAY-Window (kein RemoteViews,
 * kein Accessibility-Service nötig). Der User kann das Limit mit
 * "Noch 5 Minuten" um 5 Minuten verlängern (einmal pro Sperre).
 */
@AndroidEntryPoint
class AppBlockService : Service() {

    @Inject lateinit var appLimitRepository: AppLimitRepository
    @Inject lateinit var aggregator: AppUsageAggregator
    // M18.61f: Profile — aktives Profil sperrt ALLE zugeordneten Apps
    @Inject lateinit var balanceProfileRepository: BalanceProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var currentBlockedPkg: String? = null
    private var extensionGrantedFor: String? = null
    private var ignoredTodayPkg: String? = null
    // M18.131: Die alte In-Memory-Menge ist ersetzt durch
    // [warningGuard] — dieser Zustand muss Service-Neustarts überleben
    // (siehe LimitWarningGuard-Doku). lastForegroundPkg bleibt: er wird
    // für die Profil-/Overlay-Logik gebraucht.
    private var lastForegroundPkg: String? = null
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

    /**
     * M18.131: Wächter der „Gleich gesperrt"-Vorwarnung.
     *
     * M18.123-Lektion: NIE im Property-Init konstruieren — Android attacht
     * den Context erst NACH dem Konstruktor (newInstance), ein Zugriff auf
     * SharedPreferences hier crasht mit NPE und killt den Prozess beim
     * App-Start. Deshalb in onCreate (s. u.).
     *
     * Absichtlich ohne den konkreten Methodennamen im Text: der
     * StickyGuardInitRegressionTest verbietet Context-Zugriffe in dieser
     * Zone und prüft auch Block-Kommentare — ein Code-Beispiel hier würde
     * ihn fälschlich auslösen.
     */
    private lateinit var warningGuard: com.d_drostes_apps.aevum.domain.digital.LimitWarningGuard

    // M18.61g-FIX 2: Rückkanal von der BlockActivity (Buttons) zum Service.
    private val blockActionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
            when (intent.action) {
                ACTION_EXTEND -> {
                    extensionGrantedFor = pkg
                    currentBlockedPkg = null
                }
                ACTION_IGNORE_TODAY -> {
                    ignoredTodayPkg = pkg
                    currentBlockedPkg = null
                }
                ACTION_CLOSE -> {
                    currentBlockedPkg = null
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // M19: Konsolidierte Hintergrund-Benachrichtigung statt eigener.
        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.ensureChannel(this)
        // M18.131-BUGFIX (Root Cause „es kam nie eine Warnung"): M19 hat
        // startForeground auf BackgroundNotificationHelper umgestellt und
        // dabei den Aufruf von createChannel() entfernt — die Methode blieb
        // als toter Code stehen. Folge: der Channel "digital_balance_block"
        // wurde NIE angelegt, und seit Android 8 verwirft das System
        // Notifications in einen nicht existierenden Channel STILLSCHWEIGEND.
        // Die 80-%-Warnung (M18.61) hat den Nutzer deshalb nie erreicht.
        // Jetzt VOR jeder Nutzung des Channels angelegt — die Limit-Warnung
        // hängt am selben Channel und wäre sonst genauso unsichtbar.
        createChannel()
        // M18.107-CRASHFIX: startForeground war UNGESCHÜTZT — jede Exception
        // hier (kaputte Notification auf OEM-ROMs, Restriction-Exceptions)
        // crashte den ganzen Prozess, obwohl der Service optional ist
        // (Sperr-Funktion). Zusätzlich: expliziter SPECIAL_USE-Typ erst ab
        // API 34 (Konstante existiert erst dort); auf 29-33 vertragsgültiger
        // 2-Arg-Aufruf (Manifest-Typ specialUse).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                    com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                    com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this)
                )
            }
        } catch (e: Exception) {
            Log.e("AppBlockSvc", "startForeground fehlgeschlagen — stopSelf", e)
            stopSelf()
        }
        // M18.61g: BlockActivity-Broadcasts empfangen
        // M18.121 (Crash-Loop t_fe3e99da): processStartedAtRealtime für den
        // Sticky-Guard (onStartCommand, s.u.).
        processStartedAtRealtime = android.os.SystemClock.elapsedRealtime()
        // M18.123-CRASHFIX: Guard erst hier konstruieren — ab onCreate
        // ist der Context garantiert attacht (Property-Init crasht mit
        // NPE, siehe StickyGuards.kt-Doku).
        stickyGuard = com.d_drostes_apps.aevum.automation.StickyGuardService(
            com.d_drostes_apps.aevum.automation.SharedPrefsStickyGuardPersistence(
                this, "app_block"
            )
        )
        // M18.131: Guard für die Limit-Vorwarnung — hier, weil ab onCreate
        // der Context garantiert attacht ist (siehe Feld-Doku). Eigene
        // Prefs-Datei: reine Laufzeit-Bequemlichkeit, soll bei „alle Daten
        // löschen"/Restore nicht mitgeschleppt werden.
        warningGuard = com.d_drostes_apps.aevum.domain.digital.LimitWarningGuard(
            com.d_drostes_apps.aevum.domain.digital.SharedPrefsLimitWarningStore(
                getSharedPreferences("aevum_limit_warnings", Context.MODE_PRIVATE)
            )
        )
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_EXTEND)
            addAction(ACTION_IGNORE_TODAY)
            addAction(ACTION_CLOSE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(blockActionReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(blockActionReceiver, filter)
        }
        // M18.104: Screen-Receiver (siehe startWatching-Kommentar).
        registerScreenStateReceiver()
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // M18.121/M18.122 (t_55c14376): STICKY-GUARD. Der FGS-Vertrag ist
        // hier bereits in onCreate erfüllt (startForeground, siehe dort) —
        // der Break unten bricht NUR echte System-Rebirths (action == null;
        // alle internen Starts tragen seit M18.122 ACTION_INTERNAL_START)
        // auf Basis des PERSISTIERTEN letzten-Start-Zeitstempels (D2).
        val stickyRebirthBreak = intent?.action == null && stickyGuard.shouldBreakStickyRebirth(
            processStartedAtRealtime,
            android.os.SystemClock.elapsedRealtime()
        )
        // Neu gestartet (z.B. nach Reboot) → Watchdog neu starten
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // M18.121/M18.122: STICKY-GUARD-BREAK — nach erfülltem
        // FGS-Vertrag (startForeground in onCreate). Der nächste echte
        // Anlass (App-Start, Limit-Änderung) startet den Service regulär.
        if (stickyRebirthBreak) {
            Log.w(TAG, "M18.122: Sticky-Rebirth ohne Action gebrochen (Kill-Restart-Loop-Schutz, FGS-Vertrag erfüllt) — Service beendet")
            stopSelf()
            return START_NOT_STICKY
        }
        // M18.122: Echter Start (Action inkl. ACTION_INTERNAL_START) —
        // Wall-Clock-Zeitstempel persistieren (D2).
        stickyGuard.markLegitStart()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(blockActionReceiver) } catch (_: Exception) { /* nie registriert */ }
        // M18.104: Screen-Receiver sauber deregistrieren.
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { /* nie registriert */ }
        }
        screenStateReceiver = null
        super.onDestroy()
    }

    private fun startWatching() {
        handler.post(object : Runnable {
            override fun run() {
                // M18.104 (Akku-Redesign): Screen aus → KEIN Foreground-Check.
                // Vorher liefen 5s queryUsageStats-Abfragen rund um die Uhr
                // (17.280/Tag) — nachts nutzlos, niemand wechselt Apps bei
                // ausgeschaltetem Display. Der Screen-Receiver unten setzt
                // das Flag und triggert einen Sofort-Check bei SCREEN_ON,
                // sodass die Sperr-Reaktion (max. 5s) unverändert bleibt,
                // sobald der User das Gerät wieder nutzt. SCREEN_OFF friert
                // den Zustand korrekt ein: Die zuletzt geöffnete App bleibt
                // "im Vordergrund", solange der Screen aus ist.
                if (screenOn) {
                    checkForegroundApp()
                }
                handler.postDelayed(
                    this,
                    if (screenOn) CHECK_INTERVAL_MS else CHECK_INTERVAL_SCREEN_OFF_MS
                )
            }
        })
    }

    /** M18.104: Screen-Zustand (ACTION_SCREEN_ON/OFF-Receiver im FGS-
     *  Kontext). Default true — konservativ, bis der erste Broadcast
     *  eintrifft. */
    @Volatile private var screenOn: Boolean = true
    private var screenStateReceiver: android.content.BroadcastReceiver? = null

    /** M18.104: Screen-Receiver — null Kosten im Schlaf, sofortige
     *  Sperr-Reaktion beim Aufwachen. */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        // Sofort-Check beim Aufwachen — der User könnte
                        // direkt in eine gesperrte App wechseln.
                        handler.post { checkForegroundApp() }
                    }
                    Intent.ACTION_SCREEN_OFF -> screenOn = false
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
            screenStateReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Screen-Receiver fehlgeschlagen: ${e.message} — Watchdog läuft dauerhaft")
        }
    }

    private fun checkForegroundApp() {
        val pkg = currentForegroundPackage() ?: return
        if (pkg == packageName) return // eigene App nie sperren
        if (pkg == currentBlockedPkg) return // Overlay schon aktiv
        if (pkg == extensionGrantedFor) return // Verlängerung aktiv
        if (pkg == ignoredTodayPkg) return // "Heute ignorieren" aktiv

        scope.launch {
            // M18.61f: Aktives Profil? Dann sind ALLE Profil-Apps gesperrt
            // (unabhängig von individuellen Limits) — z.B. Lern-Profil
            // sperrt Social Media komplett.
            val activeProfile = balanceProfileRepository.getActiveOnce()
            if (activeProfile != null) {
                val profileApps = balanceProfileRepository.getAppPackages(activeProfile.id)
                // M18.61g-FIX 3 (User: "Profil Fokus mit Instagram gesperrt
                // — trotzdem normal nutzbar"): Der Profil-Dialog speicherte
                // App-NAMEN ("Instagram") statt Paketnamen. Match deshalb
                // gegen Paketname UND App-Label — heilt bestehende Profile.
                val label = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                } catch (_: Exception) { pkg }
                if (pkg in profileApps || label in profileApps) {
                    lastForegroundPkg = pkg
                    handler.post { showOverlay(pkg, null, activeProfile.name) }
                    return@launch
                }
            }

            val limit = appLimitRepository.getByPackageOnce(pkg)
            if (limit == null || !limit.enabled) return@launch
            val used = aggregator.usageTodayFor(pkg)
            val now = System.currentTimeMillis()
            val blocked = AppLimitChecker.isBlocked(limit, used, now)
            if (blocked) {
                lastForegroundPkg = pkg
                handler.post { showOverlay(pkg, limit, null) }
            } else if (AppLimitChecker.isWarningDue(limit, used, now)) {
                // M18.131 (Auftrag: „5 Minuten vor Erreichen des Limits eine
                // Benachrichtigung, dass die App in 5 Minuten gesperrt wird").
                //
                // Ersetzt die 80-%-Warnung aus M18.61. Warum die Prozent-
                // Schwelle fachlich falsch war: bei 60 min Limit bedeutete
                // 80 % eine Warnung 12 Minuten vor der Sperre, bei 10 min
                // Limit nur 2 Minuten vorher. Die Aussage „in 5 Minuten
                // gesperrt" war damit nie zutreffend.
                //
                // Die Warnung ist zeitpunktbasiert (Restzeit <= 5 min) und
                // wird über [LimitWarningGuard] PERSISTENT genau einmal pro
                // App und Tag gesendet. Der alte In-Memory-HashSet ging bei
                // jedem Service-Neustart verloren → wiederholte Warnungen.
                if (warningGuard.markWarned(pkg, now)) {
                    val remaining = AppLimitChecker.remainingMs(limit, used) ?: 0L
                    showWarningNotification(pkg, limit, remaining)
                    Log.i(TAG, "Limit-Vorwarnung gesendet: $pkg (noch ${remaining / 60_000} min)")
                }
            }
        }
    }

    private fun currentForegroundPackage(): String? {
        return try {
            val mgr = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // M18.61g-FIX (User: "Apps werden nicht geblockt"): queryEvents
            // liefert EVENTS, keinen STATE. Bei einem 60s-Fenster fehlt das
            // MOVE_TO_FOREGROUND-Event, sobald der User länger als 60s in
            // derselben App sitzt → null → keine Sperre.
            //
            // Robustes Muster (Digital-Wellbeing-Ansatz, per Recherche
            // bestätigt): queryUsageStats liefert lastTimeUsed als STATE.
            // Die App mit dem höchsten lastTimeUsed IST die aktuelle —
            // aber nur, wenn lastTimeUsed nahe an "jetzt" liegt (sonst
            // ist der Screen aus / Home-Screen aktiv).
            val stats = mgr.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 24L * 60 * 60 * 1000,
                now
            ) ?: emptyList()
            val current = stats
                .filter { it.lastTimeUsed > 0L }
                .maxByOrNull { it.lastTimeUsed }
            if (current != null && now - current.lastTimeUsed < 30_000L) {
                return current.packageName
            }
            // Fallback: letztes MOVE_TO_FOREGROUND-Event der letzten 24h
            // (falls queryUsageStats nichts liefert).
            val events = mgr.queryEvents(now - 24L * 60 * 60 * 1000, now) ?: return null
            var lastPkg: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> lastPkg = event.packageName
                }
            }
            lastPkg
        } catch (_: Exception) { null }
    }

    private fun showOverlay(pkg: String, limit: AppLimit?, profileName: String?) {
        if (currentBlockedPkg != null) return
        currentBlockedPkg = pkg

        // M18.61g-FIX 2: BlockActivity statt TYPE_APPLICATION_OVERLAY.
        // Das Overlay brauchte SYSTEM_ALERT_WINDOW — die App hat diese
        // Berechtigung nie angefragt, wm.addView() warf still und die
        // Sperre erschien nie. Die Activity braucht keine Berechtigung
        // und pausiert die gesperrte App garantiert (Instagram läuft
        // nicht weiter).
        try {
            BlockActivity.start(
                this,
                pkg,
                limit?.limitMinutes ?: 0,
                profileName
            )
        } catch (e: Exception) {
            Log.e("AppBlockService", "BlockActivity-Start fehlgeschlagen", e)
            currentBlockedPkg = null
        }
    }

    private fun removeOverlay() {
        // M18.61g-FIX 2: Overlay entfernt — BlockActivity schließt sich
        // selbst per finish(). Nichts zu tun.
        currentBlockedPkg = null
    }

    /**
     * M18.61: Warn-Benachrichtigung, wenn das Limit fast erreicht ist.
     * M18.131: Zeitpunktbasiert (5 Minuten Restzeit) statt 80-%-Schwelle —
     * genau einmal pro App und Tag (siehe [warningGuard]).
     *
     * WORTWAHL: „bei weiterer Nutzung" ist bewusst dabei. Die Sperre tritt
     * ein, sobald die Nutzungszeit das Limit erreicht — nicht nach Ablauf
     * einer Uhr. Ohne diesen Zusatz würde die Meldung eine feste Frist
     * behaupten, die nur bei durchgehender Nutzung stimmt.
     *
     * Die Notification läuft über den Channel [CHANNEL_ID], der in
     * [createChannel] mit IMPORTANCE_HIGH angelegt wird: die Ankündigung
     * einer unmittelbar bevorstehenden Sperre ist zeitkritisch und soll
     * als Heads-up erscheinen (Digital-Wellbeing-Muster). Da der Channel
     * vorher nie existierte, kann die Wichtigkeit jetzt einmalig korrekt
     * gesetzt werden — nachträglich ließe Android das nicht zu.
     */
    private fun showWarningNotification(pkg: String, limit: AppLimit, remainingMs: Long) {
        // Ohne POST_NOTIFICATIONS (Android 13+) wird nichts gesendet — der
        // Aufruf würde still verpuffen. Früh raus statt Exception-Fang.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS fehlt — Limit-Vorwarnung nicht zustellbar")
            return
        }
        try {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            // Aufrunden: „noch 4,3 min" als „noch 4 Minuten" wäre irreführend
            // kurz vor einer Sperre — der Nutzer plant damit seine restliche
            // Zeit. Mindestens 1, damit die Meldung nie „0 Minuten" sagt.
            val remainingMin = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            val intent = Intent(this, com.d_drostes_apps.aevum.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this, pkg.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_block_warning_title, label))
                .setContentText(getString(R.string.notif_block_warning_text, remainingMin, limit.limitMinutes))
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                        getString(R.string.notif_block_warning_text, remainingMin, limit.limitMinutes)
                    )
                )
                .setSmallIcon(com.d_drostes_apps.aevum.R.drawable.ic_notification)
                .setContentIntent(pi)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(WARNING_NOTIFICATION_ID + pkg.hashCode() % 1000, notification)
        } catch (e: Exception) {
            // Notification-Permission fehlt o.ä. — die Sperr-Funktion darf
            // davon nicht abhängen (M18.107-Muster: optional bleibt optional).
            Log.w(TAG, "Limit-Vorwarnung fehlgeschlagen für $pkg: ${e.message}")
        }
    }

    /**
     * M18.131: Channel für die Limit-Warnungen und die Sperr-Anzeige.
     *
     * Wird in [onCreate] aufgerufen. Der Aufruf war seit M19 verschwunden
     * (die Methode blieb als toter Code stehen), sodass der Channel nie
     * existierte und Android alle Notifications darauf stillschweigend
     * verwarf — die 80-%-Warnung hat den Nutzer nie erreicht.
     *
     * IMPORTANCE_HIGH: Die Ankündigung einer bevorstehenden App-Sperre ist
     * zeitkritisch und darf nicht lautlos verpuffen. Höchstens eine solche
     * Meldung pro App und Tag ([LimitWarningGuard]).
     */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_block_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_block_channel_desc)
                // Keine dauerhafte Vibration: die Meldung ist ein Hinweis,
                // kein Alarm. Sichtbar (Heads-up) bleibt sie trotzdem.
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, com.d_drostes_apps.aevum.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notif_block_active_title))
            .setContentText(getString(R.string.notif_block_active_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** M18.131: Log-Tag dieses Services (Warn-Pfad + Diagnose). */
        private const val TAG = "AppBlockSvc"
        const val ACTION_STOP = "com.d_drostes_apps.aevum.digitalbalance.STOP"
        // M18.61g-FIX 2: BlockActivity-Button-Aktionen (Broadcast-Rückkanal)
        const val ACTION_EXTEND = "com.d_drostes_apps.aevum.digitalbalance.EXTEND"
        const val ACTION_IGNORE_TODAY = "com.d_drostes_apps.aevum.digitalbalance.IGNORE_TODAY"
        const val ACTION_CLOSE = "com.d_drostes_apps.aevum.digitalbalance.CLOSE"
        const val EXTRA_PKG = "blocked_pkg"
        // M18.122 (D3): Marker-Action für app-interne Starts — der
        // Sticky-Guard bricht NUR Intents OHNE Action (System-Rebirth).
        // JEDER interne Start setzt sie, siehe start().
        const val ACTION_INTERNAL_START = "com.d_drostes_apps.aevum.APP_BLOCK_INTERNAL_START"
        private const val CHANNEL_ID = "digital_balance_block"
        private const val NOTIFICATION_ID = 9002
        private const val WARNING_NOTIFICATION_ID = 9100
        // M18.93v11 (User: "Akkuverbrauch weiter drosseln"): 2s-Polling
        // mit queryUsageStats war der aggressivste Fresser bei aktiven
        // Limits (43.200 Checks/Tag). 5s reicht für die Sperr-Funktion
        // völlig (Overlay erscheint max. 3s später — für den User
        // unsichtbar), spart 60% der UsageStats-Zugriffe.
        private const val CHECK_INTERVAL_MS = 5_000L
        // M18.104 (Akku-Redesign): Screen aus → Watchdog-Takt verlangsamen
        // (siehe startWatching-Kommentar — der Screen-ON-Broadcast weckt
        // sofort, die 10-Min-Schleife ist nur der Fallback-Keepalive).
        private const val CHECK_INTERVAL_SCREEN_OFF_MS = 10L * 60 * 1000

        fun start(context: Context) {
            // M18.122 (D3): Interne Starts tragen explizit
            // ACTION_INTERNAL_START — null-Action bleibt damit dem
            // System-Rebirth vorbehalten (START_STICKY nach Prozess-Kill).
            val intent = Intent(context, AppBlockService::class.java)
                .setAction(ACTION_INTERNAL_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // M18.107: FGS-Start darf NIE crashen (M18.66-Muster) —
                // ForegroundServiceStartNotAllowedException (Hintergrund-Start,
                // Android 12+) und OEM-Restriktionen. Der Service holt nach
                // beim nächsten App-Start / Limit-Change.
                Log.w(TAG, "Start fehlgeschlagen: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppBlockService::class.java))
        }
    }
}

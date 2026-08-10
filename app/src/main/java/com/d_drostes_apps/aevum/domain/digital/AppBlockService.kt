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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private val warnedPkgs = HashSet<String>()
    private var lastForegroundPkg: String? = null

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
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // M18.61g-FIX 2: BlockActivity-Broadcasts empfangen
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
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Neu gestartet (z.B. nach Reboot) → Watchdog neu starten
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(blockActionReceiver) } catch (_: Exception) { /* nie registriert */ }
        super.onDestroy()
    }

    private fun startWatching() {
        handler.post(object : Runnable {
            override fun run() {
                checkForegroundApp()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        })
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
                if (pkg in profileApps) {
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
            } else {
                // M18.61: Warnschwelle 80% (Google-Muster) — einmalige
                // Benachrichtigung, wenn das Limit fast erreicht ist.
                val progress = AppLimitChecker.progress(limit, used)
                if (progress >= 0.8f && warnedPkgs.add(pkg)) {
                    val remaining = AppLimitChecker.remainingMs(limit, used) ?: 0L
                    showWarningNotification(pkg, limit, remaining)
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
            android.util.Log.e("AppBlockService", "BlockActivity-Start fehlgeschlagen", e)
            currentBlockedPkg = null
        }
    }

    private fun removeOverlay() {
        // M18.61g-FIX 2: Overlay entfernt — BlockActivity schließt sich
        // selbst per finish(). Nichts zu tun.
        currentBlockedPkg = null
    }

    /**
     * M18.61: Warn-Benachrichtigung bei 80% des Limits (Google-Muster).
     * Einmalig pro App und Tag.
     */
    private fun showWarningNotification(pkg: String, limit: AppLimit, remainingMs: Long) {
        try {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            val remainingMin = (remainingMs / 60_000).coerceAtLeast(1)
            val intent = Intent(this, com.d_drostes_apps.aevum.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this, pkg.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder
                .setContentTitle("Fast am Limit: $label")
                .setContentText("Noch $remainingMin Minuten — Limit ist ${limit.limitMinutes} min")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(WARNING_NOTIFICATION_ID + pkg.hashCode() % 1000, notification)
        } catch (_: Exception) { /* Notification-Permission fehlt o.ä. */ }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Digital Balance Sperre",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Überwacht App-Limits" }
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
            .setContentTitle("Digital Balance aktiv")
            .setContentText("App-Limits werden überwacht")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.d_drostes_apps.aevum.digitalbalance.STOP"
        // M18.61g-FIX 2: BlockActivity-Button-Aktionen (Broadcast-Rückkanal)
        const val ACTION_EXTEND = "com.d_drostes_apps.aevum.digitalbalance.EXTEND"
        const val ACTION_IGNORE_TODAY = "com.d_drostes_apps.aevum.digitalbalance.IGNORE_TODAY"
        const val ACTION_CLOSE = "com.d_drostes_apps.aevum.digitalbalance.CLOSE"
        const val EXTRA_PKG = "blocked_pkg"
        private const val CHANNEL_ID = "digital_balance_block"
        private const val NOTIFICATION_ID = 9002
        private const val WARNING_NOTIFICATION_ID = 9100
        private const val CHECK_INTERVAL_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, AppBlockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppBlockService::class.java))
        }
    }
}

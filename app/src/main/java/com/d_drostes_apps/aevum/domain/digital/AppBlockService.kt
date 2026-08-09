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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var currentBlockedPkg: String? = null
    private var extensionGrantedFor: String? = null
    private var lastForegroundPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
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
        removeOverlay()
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

        scope.launch {
            val limit = appLimitRepository.getByPackageOnce(pkg)
            if (limit == null || !limit.enabled) return@launch
            val used = aggregator.usageTodayFor(pkg)
            val blocked = AppLimitChecker.isBlocked(limit, used, System.currentTimeMillis())
            if (blocked) {
                lastForegroundPkg = pkg
                handler.post { showOverlay(pkg, limit) }
            }
        }
    }

    private fun currentForegroundPackage(): String? {
        return try {
            val mgr = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = mgr.queryEvents(now - 60_000, now) ?: return null
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

    private fun showOverlay(pkg: String, limit: AppLimit) {
        if (overlayView != null) return
        currentBlockedPkg = pkg

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.digital_balance_block_overlay, null)

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        view.findViewById<TextView>(R.id.block_title).text = "$label gesperrt"
        view.findViewById<TextView>(R.id.block_subtitle).text =
            "Tägliches Limit von ${limit.limitMinutes} Minuten erreicht."

        view.findViewById<Button>(R.id.block_extend).setOnClickListener {
            extensionGrantedFor = pkg
            removeOverlay()
        }
        view.findViewById<Button>(R.id.block_close).setOnClickListener {
            removeOverlay()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        try {
            wm.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
            // Kein Overlay-Permission → Overlay überspringen (App bleibt nutzbar)
            currentBlockedPkg = null
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) { /* schon entfernt */ }
        }
        overlayView = null
        currentBlockedPkg = null
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
        private const val CHANNEL_ID = "digital_balance_block"
        private const val NOTIFICATION_ID = 9002
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

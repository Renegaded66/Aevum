package de.devondroste.aevum.domain.liveactivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import de.devondroste.aevum.MainActivity
import de.devondroste.aevum.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Foreground service for the live activity notification.
 * Shows a persistent notification with activity info, running timer, and action buttons.
 */
@AndroidEntryPoint
class LiveActivityService : Service() {

    @Inject lateinit var liveActivityManager: LiveActivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    companion object {
        const val CHANNEL_ID = "live_activity"
        const val NOTIFICATION_ID = 9001
        const val ACTION_PAUSE = "de.devondroste.aevum.LIVE_PAUSE"
        const val ACTION_RESUME = "de.devondroste.aevum.LIVE_RESUME"
        const val ACTION_STOP = "de.devondroste.aevum.LIVE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, LiveActivityService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveActivityService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { liveActivityManager.pause() }
            ACTION_RESUME -> scope.launch { liveActivityManager.resume() }
            ACTION_STOP -> scope.launch {
                liveActivityManager.stop()
                stopSelf()
            }
        }

        // Start periodic notification updates
        if (updateJob == null || updateJob?.isActive != true) {
            updateJob = scope.launch {
                while (true) {
                    updateNotification()
                    delay(1000)
                }
            }
        }

        // Ensure we're foreground
        startForeground(NOTIFICATION_ID, buildNotification())

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        updateJob?.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Laufende Aktivität",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Zeigt die laufende Aktivität und Steuerungsaktionen"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val session = liveActivityManager.liveSession.value
        if (session == null || !session.isLive) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val session = liveActivityManager.liveSession.value
        if (session == null || !session.isLive) {
            return buildEmptyNotification()
        }
        val isPaused = session.isPaused
        val now = System.currentTimeMillis()
        val totalMs = (now - session.startAt).coerceAtLeast(0)
        val activeMs = (totalMs - session.effectivePausedMs(now)).coerceAtLeast(0)

        val title = session.title
        val timeStr = formatDuration(activeMs)

        // M12.1 / M12.2: Human-readable subtext for auto-started sessions.
        // Wir nutzen [AUTO_SOURCES] statt hardcoded "GEOFENCE_AUTO", damit
        // Health-Sleep und Activity-Recognition die gleiche Notification erhalten,
        // falls sie je als Live-Session laufen (z. B. wenn Geofence-Regel sie startet).
        val isAuto = session.sourceType in de.devondroste.aevum.ui.screens.timeline.AUTO_SOURCES
        val subText = when {
            isPaused && isAuto -> "Pausiert · automatisch gestartet"
            isPaused -> "Pausiert"
            isAuto -> "automatisch gestartet"
            else -> "Aktiv"
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, LiveActivityService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePending = PendingIntent.getService(
            this, 1, pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseResumeLabel = if (isPaused) "Fortsetzen" else "Pause"

        val stopIntent = Intent(this, LiveActivityService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(timeStr)
            .setSubText(subText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(0, pauseResumeLabel, pauseResumePending)
            .addAction(0, "Stoppen", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // BigTextStyle mit Gesamt/Aktiv bei Pause
        if (isPaused) {
            val totalStr = formatHumanDuration(totalMs)
            val activeStr = formatHumanDuration(activeMs)
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$timeStr aktiv · $totalStr gesamt")
            )
        }
        return builder.build()
    }

    private fun buildEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Aevum")
            .setContentText("Aktivität läuft")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.GERMANY, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.GERMANY, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatHumanDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min"
            else -> "$seconds s"
        }
    }
}

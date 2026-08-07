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
    // M18.41: Zeitpunkt des Service-Starts. Wird fuer die
    // Initialisierungsphase genutzt: Beim Start kann die Room-Query
    // noch laufen (liveSession.value == null, obwohl eine Session
    // existiert). Nach 3s ist die Phase vorbei — dann ist null
    // wirklich "keine Session" und der Service stoppt.
    private var serviceStartTime = 0L

    companion object {
        // M18.4: NEUE Channel-ID — NotificationChannels sind nach dem ersten
        // Erstellen UNVERÄNDERBAR (Android-Pitfall). Der alte Channel
        // "live_activity" (IMPORTANCE_LOW) existiert auf Bestands-Installationen
        // und ignoriert jede Code-Änderung. Nur ein neuer Channel erzwingt
        // das Heads-up (IMPORTANCE_HIGH) auch nach App-Update.
        const val CHANNEL_ID = "live_activity_high"
        const val NOTIFICATION_ID = 9001
        const val ACTION_PAUSE = "de.devondroste.aevum.LIVE_PAUSE"
        const val ACTION_RESUME = "de.devondroste.aevum.LIVE_RESUME"
        const val ACTION_STOP = "de.devondroste.aevum.LIVE_STOP"
        // M18.19: Wechseln — öffnet das Popup (SwitchActivity).
        const val ACTION_SWITCH = "de.devondroste.aevum.LIVE_SWITCH"

        fun start(context: Context) {
            val intent = Intent(context, LiveActivityService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // M18.24: ForegroundServiceStartNotAllowedException (Android 12+)
                // oder andere Start-Fehler — die App darf NIE crashen, nur
                // weil die Notification nicht erscheinen kann. Fallback:
                // normaler Service-Start (funktioniert auf Android < 8 und
                // wenn die App im Vordergrund ist).
                try {
                    context.startService(intent)
                } catch (_: Exception) { }
            }
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
        serviceStartTime = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { liveActivityManager.pause() }
            ACTION_RESUME -> scope.launch { liveActivityManager.resume() }
            ACTION_STOP -> scope.launch {
                liveActivityManager.stop()
                stopSelf()
            }
            // M18.19: Wechsel-Popup öffnen (transparente Activity).
            ACTION_SWITCH -> openSwitchActivity()
        }

        // Start periodic notification updates
        if (updateJob == null || updateJob?.isActive != true) {
            updateJob = scope.launch {
                while (true) {
                    try {
                        updateNotification()
                    } catch (e: Exception) {
                        // M18.24: Ein fehlerhaftes Notification-Update darf
                        // den Loop nicht killen — sonst friert der Timer ein.
                    }
                    delay(1000)
                }
            }
        }

        // Ensure we're foreground
        // M18.24: startForeground kann crashen (ForegroundServiceDidNotStartInTimeException,
        // kaputte Notification auf OEM-Geraeten). Die App darf NIE abstuerzen,
        // nur weil die Notification nicht gebaut werden kann.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // M18.19: Wechsel-Popup (SwitchActivity) als Dialog über der App öffnen.
    private fun openSwitchActivity() {
        val intent = Intent(this, de.devondroste.aevum.ui.screens.dashboard.SwitchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        updateJob?.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        // M18.4: IMPORTANCE_HIGH statt LOW — die Notification soll als
        // Heads-up über allem aufpoppen (User-Anforderung: "deutlich
        // sichtbarer Banner"). CATEGORY_STOPWATCH ist der Standard für
        // laufende Timer und bekommt auf Android 13+ automatisch
        // Priorität im Notification-Manager.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Laufende Aktivität",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Zeigt die laufende Aktivität und Steuerungsaktionen"
            setShowBadge(true)
            enableVibration(true)
            // M18.4: Kein Laut, aber Vibration + Heads-up — der User soll
            // es sehen, nicht hören (Zeit-Tracking ist diskret).
            setSound(null, null)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val session = liveActivityManager.liveSession.value
        // M18.41-FIX (Root Cause "Notification bleibt ewig"): Vorher wurde
        // bei session == null NIE gestoppt (M18.24-Entscheidung) — der
        // Service zeigte stattdessen buildEmptyNotification() "Aktivität
        // läuft" FÜR IMMER, auch wenn gar keine Session existierte.
        // Jetzt: 3s Initialisierungsphase (Room-Query kann beim Start noch
        // laufen), danach ist null wirklich "keine Session" -> stoppen.
        if (session == null) {
            if (System.currentTimeMillis() - serviceStartTime > 3_000) {
                stopSelf()
            }
            return
        }
        if (!session.isLive) {
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

        // M18.19: Wechseln — öffnet das Popup (SwitchActivity).
        val switchIntent = Intent(this, LiveActivityService::class.java).apply {
            action = ACTION_SWITCH
        }
        val switchPending = PendingIntent.getService(
            this, 3, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // M18.19: Aktivitätsfarbe aus dem ActivityType laden.
        val type = liveActivityManager.cachedActivityType(session.activityTypeId)
        val accentColor = (type?.color?.takeIf { it != 0L } ?: 0xFF4CAF50).toInt()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(timeStr)
            .setSubText(subText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(0, pauseResumeLabel, pauseResumePending)
            .addAction(0, "Wechseln", switchPending)
            .addAction(0, "Stoppen", stopPending)
            // M18.4: HIGH + STOPWATCH — Heads-up Banner über allem.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(accentColor)
            .setColorized(true)

        // M18.25: KEIN Custom-RemoteViews-Layout mehr!
        // Das M18.23-Layout (live_notification.xml) enthielt
        // android:fontFamily="monospace" und android:shadow* — beides
        // KEINE unterstuetzten RemoteViews-Methoden. Das System wirft
        // beim Inflaten eine RemoteViews$ActionException:
        //   - M18.23: Notification wurde verworfen -> "verschwunden"
        //   - M18.24: Exception kam beim notify() ueber Binder zurueck
        //     -> App-Crash ~1s nach dem App-Oeffnen
        // Die Standard-Notification mit Actions + Farbe ist auf JEDEM
        // Geraet zuverlaessig und IMMER sichtbar.

        // M18.4: BigTextStyle mit Gesamt/Aktiv bei Pause + Timer-Update.
        // Bei RUNNING zeigt der ContentText den Live-Timer (aktualisiert
        // jede Sekunde via updateNotification).
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
        // M18.41-FIX (Root Cause "Vibration alle paar Sekunden"): Die
        // Empty-Notification wurde JEDE Sekunde neu gepostet (updateLoop),
        // ohne setOnlyAlertOnce/setSilent -> jeder notify() war ein neuer
        // Alert -> Channel hat enableVibration(true) + IMPORTANCE_HIGH ->
        // Vibration + Heads-up alle paar Sekunden. Jetzt: silent + nur
        // einmal alerten. (Diese Notification wird nur noch in der 3s-
        // Initialisierungsphase gezeigt, danach stoppt der Service.)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Aevum")
            .setContentText("Aktivität läuft")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
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

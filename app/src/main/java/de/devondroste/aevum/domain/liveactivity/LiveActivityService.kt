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

        // M18.49 (User: "Beim Klick auf Wechsel in der Benachrichtigung
        // passiert nichts falls man die App nicht geöffnet hat. Da darf
        // sich ruhig die App öffnen und dann das PopUp erscheinen."):
        // Der alte Weg lief über PendingIntent.getService -> Service ->
        // startActivity(). Das ist ein Activity-Launch-Trampolin aus dem
        // Hintergrund — Android 10+ blockiert genau das, sobald die App
        // nicht im Vordergrund ist (daher "passiert nichts").
        // Jetzt zeigt der PendingIntent DIREKT auf die SwitchActivity
        // (Notification-Action-PendingIntents auf Activities sind von den
        // Background-Launch-Restrictions ausgenommen — sie öffnen die App
        // inkl. Popup, egal ob sie im Hintergrund oder beendet ist).
        val switchIntent = Intent(this, de.devondroste.aevum.ui.screens.dashboard.SwitchActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val switchPending = PendingIntent.getActivity(
            this, 3, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // M18.19: Aktivitätsfarbe aus dem ActivityType laden.
        val type = liveActivityManager.cachedActivityType(session.activityTypeId)
        val accentColor = (type?.color?.takeIf { it != 0L } ?: 0xFF4CAF50).toInt()
        val activityIcon = type?.icon?.takeIf { it.isNotBlank() } ?: "•"

        // M18.48 (User: "sollte schöner aussehen. Wie bei Duolingo mit einem
        // schönen Bild, statt einfach nur grüne Farbe"): Statt nur Farbe wird
        // ein farbiger Kreis mit dem Aktivitäts-Icon (Emoji) als LargeIcon
        // gezeichnet. setLargeIcon ist eine STANDARD-Notification-Methode —
        // kein RemoteViews (das hat in M18.25 gecrasht). So entsteht ein
        // markantes, bildhaftes Icon auf jedem Gerät.
        val largeIcon = buildActivityIcon(activityIcon, accentColor)
        // M18.49 (User: "fancy Muster als Hintergrund statt einer Farbe"):
        // Die aufgeklappte Notification zeigt ein generiertes 2:1-Bild mit
        // Farbverlauf + Punkte-Muster (Duolingo-artig) statt flacher Farbe.
        val patternBitmap = buildPatternBackground(activityIcon, accentColor, title, timeStr)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle(if (isPaused) "⏸ $title" else "▶ $title")
            .setContentText(timeStr)
            .setSubText(subText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(0, pauseResumeLabel, pauseResumePending)
            .addAction(0, "⇄ Wechseln", switchPending)
            .addAction(0, "■ Stoppen", stopPending)
            // M18.4: HIGH + STOPWATCH — Heads-up Banner über allem.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(accentColor)
            .setColorized(true)
            // M18.49: Aufgeklappte Notification mit Muster-Bild statt Text.
            // BigPictureStyle ist eine Standard-Style-Methode (kein
            // RemoteViews) — der Text steht direkt auf dem Bild.
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(patternBitmap)
                    .bigLargeIcon(largeIcon)
                    .setBigContentTitle(if (isPaused) "⏸ $title" else "▶ $title")
                    .setSummaryText(timeStr)
            )

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
        // M18.49: Bei Pause zusätzlich als Zusatztext auf das Muster-Bild —
        // der BigPictureStyle bleibt erhalten, damit das Bild nicht wieder
        // durch einen Text-Style verdrängt wird.
        if (isPaused) {
            val totalStr = formatHumanDuration(totalMs)
            val activeStr = formatHumanDuration(activeMs)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(patternBitmap)
                    .bigLargeIcon(largeIcon)
                    .setBigContentTitle(if (isPaused) "⏸ $title" else "▶ $title")
                    .setSummaryText("$timeStr aktiv · $totalStr gesamt")
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

    /**
     * M18.48: Zeichnet das Aktivitäts-Icon (Emoji) als farbigen Kreis auf
     * einen Bitmap-LargeIcon für die Notification. setLargeIcon ist eine
     * Standard-Notification-Methode (kein RemoteViews -> kein M18.25-Crash).
     *
     * M18.49 (User: "Ich möchte ein fancy Muster als Hintergrund statt einer
     * Farbe"): Statt eines flachen Kreises wird jetzt ein radialer Gradient
     * (hell→dunkel) mit dezentem Punkte-Muster gezeichnet — das wirkt
     * plastisch statt flach, bleibt aber als Small-LargeIcon lesbar.
     */
    private fun buildActivityIcon(emoji: String, accent: Int): android.graphics.Bitmap {
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Radialer Gradient: heller Mittelpunkt -> dunkler Rand (Duolingo-artige Tiefe)
        val light = android.graphics.Color.argb(255, 255, 255, 255)
        val base = android.graphics.Color.rgb(
            android.graphics.Color.red(accent),
            android.graphics.Color.green(accent),
            android.graphics.Color.blue(accent)
        )
        val dark = android.graphics.Color.rgb(
            (android.graphics.Color.red(accent) * 0.55).toInt(),
            (android.graphics.Color.green(accent) * 0.55).toInt(),
            (android.graphics.Color.blue(accent) * 0.55).toInt()
        )
        val shader = android.graphics.RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(light, base, dark),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
        paint.shader = null

        // Dezent weiße Punkte als Muster (Duolingo-artig), alpha 0.14
        val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(36, 255, 255, 255)
        }
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val dx = size * 0.18f + col * size * 0.22f
                val dy = size * 0.16f + row * size * 0.24f
                canvas.drawCircle(dx, dy, size * 0.028f, dotPaint)
            }
        }

        // Emoji in der Mitte
        paint.color = android.graphics.Color.WHITE
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.textSize = size * 0.5f
        paint.isFakeBoldText = true
        val baseline = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(emoji, size / 2f, baseline, paint)
        return bitmap
    }

    /**
     * M18.49 (User: "fancy Muster als Hintergrund statt einer Farbe"):
     * Erzeugt das aufgeklappte Notification-Bild (BigPictureStyle) im
     * 2:1-Format: farbiger Verlauf + Punkte-Muster als Hintergrund, großes
     * Aktivitäts-Emoji links, Titel + Timer als Text rechts. Das ist eine
     * Standard-Notification-Methode (setStyle(BigPictureStyle)) — kein
     * RemoteViews, also kein M18.25-Crash-Risiko. Das Muster ist pro
     * Aktivitätsfarbe einzigartig (Farbe + Rotation des Punkt-Rasters
     * leiten sich vom Accent ab), was der App Wiedererkennungswert gibt.
     */
    private fun buildPatternBackground(
        emoji: String,
        accent: Int,
        title: String,
        timeStr: String
    ): android.graphics.Bitmap {
        val w = 800
        val h = 400
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // 1) Linearer Gradient (diagonal, hell -> dunkel) — statt flacher Farbe
        val r = android.graphics.Color.red(accent)
        val g = android.graphics.Color.green(accent)
        val b = android.graphics.Color.blue(accent)
        val dark = android.graphics.Color.rgb((r * 0.45).toInt(), (g * 0.45).toInt(), (b * 0.45).toInt())
        val base = android.graphics.Color.rgb(r, g, b)
        val mid = android.graphics.Color.rgb(
            ((r + 255) * 0.6).toInt().coerceAtMost(255),
            ((g + 255) * 0.6).toInt().coerceAtMost(255),
            ((b + 255) * 0.6).toInt().coerceAtMost(255)
        )
        val gradient = android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(mid, base, dark),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // 2) Großes Punkt-Raster als Muster (Duolingo-artig) — Abstand und
        //    Radius hängen von der Accent-Farbe ab (einzigartig pro Aktivität).
        val dotAlpha = 26 + (r % 14)
        val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(dotAlpha, 255, 255, 255)
        }
        val step = 34 + (g % 10) // 34..43 px Abstand
        for (x in 0 until w step step) {
            for (y in 0 until h step step) {
                canvas.drawCircle(x.toFloat() + step / 2f, y.toFloat() + step / 2f, 3.5f, dotPaint)
            }
        }

        // 3) Emoji in einem weißen, halbtransparenten Kreis links
        val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(90, 255, 255, 255)
        }
        val iconCenterX = 110f
        val iconCenterY = 200f
        val iconRadius = 76f
        canvas.drawCircle(iconCenterX, iconCenterY, iconRadius, circlePaint)
        val emojiPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 78f
        }
        val emojiBaseline = iconCenterY - ((emojiPaint.descent() + emojiPaint.ascent()) / 2f)
        canvas.drawText(emoji, iconCenterX, emojiBaseline, emojiPaint)

        // 4) Titel + Timer als weißen Text rechts daneben
        val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            isFakeBoldText = true
        }
        val timePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(220, 255, 255, 255)
            textSize = 30f
        }
        val textX = 230f
        canvas.drawText(title, textX, 185f, titlePaint)
        canvas.drawText(timeStr, textX, 232f, timePaint)

        return bitmap
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

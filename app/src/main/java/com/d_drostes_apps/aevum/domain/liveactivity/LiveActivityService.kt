package com.d_drostes_apps.aevum.domain.liveactivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.d_drostes_apps.aevum.MainActivity
import com.d_drostes_apps.aevum.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.d_drostes_apps.aevum.util.AppLocale
import javax.inject.Inject

/**
 * Foreground service for the live activity notification.
 * Shows a persistent notification with activity info, running timer, and action buttons.
 */
@AndroidEntryPoint
class LiveActivityService : Service() {

    @Inject lateinit var liveActivityManager: LiveActivityManager

    /** M18.76: EntryPoint für die ActivityRecognitionBridge — der
     *  „■ Stoppen“-Button der Notification ist ein Stop-Pfad, den M18.75
     *  nicht abdeckte (driveActive blieb true → Fahrterkennung tot bis
     *  App-Neustart). */
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface BridgeEntryPoint {
        fun activityRecognitionBridge(): com.d_drostes_apps.aevum.automation.activityrecognition.ActivityRecognitionBridge
    }

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
        const val ACTION_PAUSE = "com.d_drostes_apps.aevum.LIVE_PAUSE"
        const val ACTION_RESUME = "com.d_drostes_apps.aevum.LIVE_RESUME"
        const val ACTION_STOP = "com.d_drostes_apps.aevum.LIVE_STOP"
        // M18.19: Wechseln — öffnet das Popup (SwitchActivity).
        const val ACTION_SWITCH = "com.d_drostes_apps.aevum.LIVE_SWITCH"

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
                // M18.76-FIX („Fahrterkennung funktioniert nicht mehr“):
                // Der „■ Stoppen“-Button in der Live-Notification ist ein
                // eigener Stop-Pfad neben dem Dashboard — M18.75 fixte nur
                // stopLiveActivity() im ViewModel. Stoppt der User eine
                // AUTO-Session (Fahrterkennung) hier, blieb driveActive in
                // der Bridge true → der DriveDetectionService klassifizierte
                // NIE WIEDER (Blackout bis App-Neustart). Jetzt: Flag
                // zurücksetzen + Drive-Watchdog canceln (nur bei
                // ACTIVITY_RECOGNITION_AUTO-Sessions, wie im Dashboard).
                val sessionBefore = liveActivityManager.liveSession.value
                liveActivityManager.stop()
                if (sessionBefore != null && sessionBefore.sourceType == "ACTIVITY_RECOGNITION_AUTO") {
                    try {
                        dagger.hilt.android.EntryPointAccessors.fromApplication(
                            applicationContext,
                            BridgeEntryPoint::class.java
                        ).activityRecognitionBridge().clearDriveActive()
                        com.d_drostes_apps.aevum.automation.activityrecognition.DriveWatchdogWorker.cancel(applicationContext)
                    } catch (e: Exception) {
                        android.util.Log.e("LiveActivitySvc", "driveActive-Reset nach Notification-Stop fehlgeschlagen", e)
                    }
                }
                stopSelf()
            }
            // M18.19: Wechsel-Popup öffnen (transparente Activity).
            ACTION_SWITCH -> openSwitchActivity()
        }

        // M19-v2: Wenn eine Live-Aufzeichnung läuft, hat die Live-Notification
        // Priorität — die Hintergrund-Benachrichtigung wird gecancelt, damit
        // zu jedem Zeitpunkt maximal eine Aevum-Notification sichtbar ist.
        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.cancelIfLiveRecording(this)

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
        val intent = Intent(this, com.d_drostes_apps.aevum.ui.screens.dashboard.SwitchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        updateJob?.cancel()
        // M19-v2: Wenn die Live-Aufzeichnung endet, die Hintergrund-
        // Benachrichtigung wiederherstellen — die Hintergrund-Services
        // laufen weiter und brauchen ihre Notification zurück.
        try {
            val nm = getSystemService(NotificationManager::class.java)
            com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.ensureChannel(this)
            nm.notify(
                com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this)
            )
        } catch (_: Exception) { }
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
            getString(R.string.notif_live_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_live_channel_desc)
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
        // M18.62-FIX: Bei PAUSED ist die Aufzeichnung beendet (endAt =
        // Pause-Zeitpunkt) — die Zeit friert ein. Bei RUNNING läuft sie.
        val totalMs = if (isPaused) {
            (session.endAt ?: now) - session.startAt
        } else {
            now - session.startAt
        }.coerceAtLeast(0)
        val activeMs = (totalMs - session.totalPausedMs).coerceAtLeast(0)

        val title = session.title
        val timeStr = formatDuration(activeMs)

        // M12.1 / M12.2: Human-readable subtext for auto-started sessions.
        // Wir nutzen [AUTO_SOURCES] statt hardcoded "GEOFENCE_AUTO", damit
        // Health-Sleep und Activity-Recognition die gleiche Notification erhalten,
        // falls sie je als Live-Session laufen (z. B. wenn Geofence-Regel sie startet).
        val isAuto = session.sourceType in com.d_drostes_apps.aevum.ui.screens.timeline.AUTO_SOURCES
        val subText = when {
            isPaused && isAuto -> getString(R.string.notif_live_subtext_paused_auto)
            isPaused -> getString(R.string.notif_live_subtext_paused)
            isAuto -> getString(R.string.notif_live_subtext_auto)
            else -> getString(R.string.notif_live_subtext_active)
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
        val pauseResumeLabel = if (isPaused) getString(R.string.notif_live_resume) else getString(R.string.notif_live_pause)

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
        val switchIntent = Intent(this, com.d_drostes_apps.aevum.ui.screens.dashboard.SwitchActivity::class.java).apply {
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
            .addAction(0, getString(R.string.notif_live_switch), switchPending)
            .addAction(0, getString(R.string.notif_live_stop), stopPending)
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
                    .setSummaryText(getString(R.string.notif_live_summary_paused, timeStr, totalStr))
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
            .setContentText(getString(R.string.notif_live_empty_text))
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

        // 1) „Activity Signal“: tiefes Night-Surface mit Akzent-Aura.
        //    Die dunkle Basis sichert Kontrast im System-Dark- und Light-Mode;
        //    nur die Aura übernimmt die Farbe der jeweiligen Aktivität.
        val r = android.graphics.Color.red(accent)
        val g = android.graphics.Color.green(accent)
        val b = android.graphics.Color.blue(accent)
        val baseNight = android.graphics.Color.rgb(12, 15, 30)
        val ink = android.graphics.Color.rgb(7, 9, 19)
        val aura = android.graphics.Color.rgb(
            ((r + 20) * 0.72f).toInt().coerceAtMost(255),
            ((g + 18) * 0.72f).toInt().coerceAtMost(255),
            ((b + 35) * 0.78f).toInt().coerceAtMost(255)
        )
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(aura, baseNight, ink),
            floatArrayOf(0f, 0.48f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // 2) Orbit-Linien: die wiedererkennbare Aevum-Signatur statt einer
        //    flachen Farbe. Sie suggerieren Zeitfluss, bleiben aber dezent.
        val orbitPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            color = android.graphics.Color.argb(44, 255, 255, 255)
        }
        val orbitCenterX = w * 0.86f
        val orbitCenterY = h * 0.28f
        repeat(4) { index ->
            canvas.drawCircle(
                orbitCenterX,
                orbitCenterY,
                92f + index * 60f,
                orbitPaint
            )
        }

        // 3) Punkt-Raster als zweite, materielle Ebene. Die Phasenverschiebung
        //    stammt aus der Aktivitätsfarbe und macht jedes Signal subtil anders.
        val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(34 + (b % 16), 255, 255, 255)
        }
        val step = 42 + (r % 12)
        val offsetX = g % step
        for (x in -step until w + step step step) {
            for (y in 0 until h step step) {
                canvas.drawCircle((x + offsetX).toFloat(), y + step / 2f, 2.6f, dotPaint)
            }
        }

        // 4) Emoji in einem weißen, halbtransparenten Kreis links
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

        // 5) Status, Titel und Live-Timer. Titel werden aktiv gekürzt, damit
        //    der Timer nie vom Artwork oder von langen Namen verdrängt wird.
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(184, 255, 255, 255)
            textSize = 18f
            letterSpacing = 0.12f
        }
        val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 42f
            isFakeBoldText = true
        }
        val timePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(206, 252, 244)
            textSize = 46f
            typeface = android.graphics.Typeface.MONOSPACE
            isFakeBoldText = true
        }
        val textX = 230f
        val maxTitleWidth = w - textX - 48f
        val compactTitle = if (titlePaint.measureText(title) > maxTitleWidth) {
            buildString {
                title.forEach { character ->
                    if (titlePaint.measureText(this.toString() + character + "…") <= maxTitleWidth) {
                        append(character)
                    }
                }
                append("…")
            }
        } else {
            title
        }
        canvas.drawText(getString(R.string.notif_live_signal_label), textX, 132f, labelPaint)
        canvas.drawText(compactTitle, textX, 188f, titlePaint)
        canvas.drawText(timeStr, textX, 250f, timePaint)

        return bitmap
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(AppLocale.current, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(AppLocale.current, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatHumanDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> getString(R.string.notif_live_duration_h_m, hours, minutes)
            minutes > 0 -> getString(R.string.notif_live_duration_min, minutes)
            else -> getString(R.string.notif_live_duration_s, seconds)
        }
    }
}

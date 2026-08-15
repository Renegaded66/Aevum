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
 *
 * User-Spezifikation:
 *  - Apps, die in der App-Aufzeichnung (rechte Spalte) stehen, werden
 *    automatisch als Activity aufgezeichnet, wenn sie in den Vordergrund
 *    kommen (z.B. Instagram → "Doomscrolling").
 *  - App schließen → die gestartete Session wird gestoppt.
 *  - App öffnen, während eine ANDERE Aufzeichnung läuft → nichts passiert.
 *  - App öffnen, während die EIGENE App-Tracking-Session läuft (Wechsel
 *    von getrackter App A zu getrackter App B) → A stoppen, B starten
 *    (konsistent mit "Neue Trigger stoppen laufende").
 *
 * Technik: ForegroundService (specialUse) mit UsageStats-Event-Polling
 * alle 5s. Kein GPS, kein ActivityRecognition — nur die Event-API, die
 * auch Digital Balance nutzt. Der Service läuft nur, wenn mindestens
 * eine App getrackt ist (Gate in start()/onStartCommand).
 *
 * WICHTIG (Fremd-Sessions-Schutz): Der Service stoppt NUR Sessions, die
 * er selbst gestartet hat (sourceType == "APP_TRACKING" + eigene ID).
 * Eine laufende Geofence-/Drive-/manuelle Session wird NIE angefasst.
 */
@AndroidEntryPoint
class AppTrackingService : Service() {

    @Inject lateinit var liveActivityManager: LiveActivityManager
    @Inject lateinit var trackingRepository: AppTrackingEntryRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: kotlinx.coroutines.Job? = null

    /** Aktuell im Vordergrund befindliche getrackte App (packageName). */
    private var currentTrackedApp: String? = null

    /** Session-ID, die dieser Service gestartet hat (nur die darf er stoppen). */
    private var trackedSessionId: String? = null

    companion object {
        private const val TAG = "AppTrackingSvc"
        private const val CHANNEL_ID = "aevum_app_tracking"
        private const val NOTIFICATION_ID = 6401
        private const val POLL_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, AppTrackingService::class.java)
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // WICHTIG: startForeground() MUSS sofort (synchron) aufgerufen
        // werden — Android wirft sonst nach 5s eine
        // ForegroundServiceDidNotStartInTimeException. Der Gate-Check
        // (getrackte Apps?) läuft danach in der Coroutine; wenn keine
        // App getrackt ist, beendet sich der Service selbst.
        startForegroundCompat()

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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground fehlgeschlagen", e)
            stopSelf()
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    pollForegroundApp()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll-Fehler", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Ermittelt die aktuell im Vordergrund befindliche App über die
     * UsageStats-Event-API (gleiche Quelle wie Digital Balance).
     * Liefert null, wenn der letzte Event ein MOVE_TO_BACKGROUND war
     * (Homescreen / andere App ohne Tracking-Relevanz).
     */
    private fun currentForegroundApp(): String? {
        val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - 15_000L, now) ?: return null
        var foreground: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> foreground = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (event.packageName == foreground) foreground = null
                }
            }
        }
        return foreground
    }

    private suspend fun pollForegroundApp() {
        val foreground = currentForegroundApp() ?: run {
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

        // Neue getrackte App im Vordergrund.
        val live = liveActivityManager.liveSession.value
        val ownSessionRunning = live != null && live.id == trackedSessionId

        if (live != null && !ownSessionRunning) {
            // Eine FREMDE Aufzeichnung läuft (Geofence/Drive/manuell) →
            // User-Regel: "Wenn ich schon was anderes aufzeichne und dann
            // Instagram öffne soll nichts passieren."
            Log.d(TAG, "Fremde Session läuft (${live.id}) — kein Auto-Start für $foreground")
            currentTrackedApp = foreground
            return
        }

        // Eigene Session stoppen (Wechsel getrackte App A → B)
        if (ownSessionRunning) {
            stopOwnSessionIfRunning()
        }

        // Auto-Start der zugeordneten Activity
        val typeId = entry.activityTypeId
        try {
            val session = liveActivityManager.start(
                activityTypeId = typeId,
                title = null,
                sourceType = "APP_TRACKING"
            )
            trackedSessionId = session.id
            currentTrackedApp = foreground
            Log.d(TAG, "Auto-Start: $foreground → Session ${session.id} (Typ $typeId)")
        } catch (e: Exception) {
            Log.e(TAG, "Auto-Start fehlgeschlagen für $foreground", e)
            trackedSessionId = null
            currentTrackedApp = null
        }
    }

    /** Stoppt die eigene Session, falls sie noch live ist. */
    private suspend fun stopOwnSessionIfRunning() {
        val sessionId = trackedSessionId ?: return
        val live = liveActivityManager.liveSession.value
        if (live != null && live.id == sessionId) {
            try {
                liveActivityManager.stop()
                Log.d(TAG, "Auto-Stop: Session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-Stop fehlgeschlagen", e)
            }
        }
        trackedSessionId = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App-Aufzeichnung",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Erkennt getrackte Apps und zeichnet sie automatisch als Activity auf"
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
            .setContentTitle("App-Aufzeichnung aktiv")
            .setContentText("Getrackte Apps werden automatisch als Activity aufgezeichnet")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

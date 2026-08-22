package com.d_drostes_apps.aevum.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.d_drostes_apps.aevum.MainActivity

/**
 * M19: Konsolidierte Hintergrund-Benachrichtigung.
 *
 * Vorher hatten GeofenceForegroundService, DriveDetectionService,
 * AppBlockService und AppTrackingService jeweils EIGENE Notification-
 * Channels mit verschiedenen Texten ("Ortserkennung aktiv",
 * "Autofahrt-Erkennung aktiv", "Digital Balance aktiv",
 * "App-Aufzeichnung aktiv"). Der Nutzer sah bis zu 4 Benachrichtigungen,
 * die alle sagten, dass Aevum im Hintergrund läuft — unnötig.
 *
 * Jetzt nutzen alle 4 Services denselben Channel ("aevum_background")
 * und dieselbe Notification-ID (6200). Android zeigt dadurch nur EINE
 * Benachrichtigung an.
 *
 * M19-v2 (User-Feedback): Die Hintergrund-Benachrichtigung soll NUR
 * angezeigt werden, wenn KEINE Aufzeichnung läuft. Wenn eine Live-
 * Activity aktiv ist, übernimmt die LiveActivityService-Notification
 * (mit Timer + Actions) — dann ist die Hintergrund-Notification
 * überflüssig und wird vom LiveActivityService beim Start cancelled.
 *
 * Die LiveActivityService-Notification (aktive Session mit Timer) hat
 * eine eigene ID (9001) und bleibt separat — sie hat Priorität.
 */
object BackgroundNotificationHelper {

    const val CHANNEL_ID = "aevum_background"
    const val NOTIFICATION_ID = 6200
    private const val GROUP_KEY = "aevum_background_group"

    /** Muss einmal beim App-Start aufgerufen werden (z.B. in Application.onCreate). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hintergrund-Erkennung",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aevum trackt Aktivitäten im Hintergrund — Orte, Fahrten, App-Nutzung"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Baut die konsolidierte Hintergrund-Notification.
     * Alle Services nutzen dieselbe ID → Android zeigt nur eine.
     */
    fun buildNotification(context: Context): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Aevum läuft im Hintergrund")
            .setContentText("Erkennt Orte, Fahrten und App-Nutzung")
            .setSmallIcon(com.d_drostes_apps.aevum.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .build()
    }

    /**
     * M19-v2: Cancelled die Hintergrund-Benachrichtigung, wenn eine
     * Live-Aufzeichnung läuft. Wird von LiveActivityService.onStartCommand
     * aufgerufen — die Live-Notification (ID 9001) hat Priorität.
     */
    fun cancelIfLiveRecording(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
    }
}
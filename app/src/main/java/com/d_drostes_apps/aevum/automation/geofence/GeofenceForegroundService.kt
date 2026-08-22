package com.d_drostes_apps.aevum.automation.geofence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.d_drostes_apps.aevum.MainActivity

/**
 * Minimal foreground service for geofencing on Android 15+ (SDK 35).
 *
 * Android 15 requires a foreground service with type "location"
 * when registering geofences that may fire while the app is in background.
 *
 * This service shows a quiet, non-intrusive notification.
 */
class GeofenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // M19: Konsolidierte Hintergrund-Benachrichtigung — alle Hintergrund-
        // Services nutzen denselben Channel + dieselbe ID → nur eine Notification
        // im Benachrichtigungsfeld statt drei.
        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.ensureChannel(this)
        val notification = com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.buildNotification(this)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // M18.45: SecurityException-Schutz für SDK 35. Ein FGS mit Typ "location"
                // darf im Hintergrund nur starten, wenn die Berechtigungen (Fine/Coarse + Background)
                // wirklich erteilt sind. Wenn nicht, stürzt die App ab.
                try {
                    startForeground(
                        com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } catch (e: SecurityException) {
                    // Fallback: Wenn Location-FGS verweigert wird (z.B. im Hintergrund ohne Background-Permission),
                    // versuchen wir es als "normalen" Service ohne speziellen Typ (0).
                    // WICHTIG: 0 übergeben, damit das System nicht den manifest-default (location) nimmt.
                    startForeground(com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID, notification, 0)
                }
            } else {
                startForeground(com.d_drostes_apps.aevum.util.BackgroundNotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Wenn alles fehlschlägt (z.B. Background-Start-Restriction ohne Exemption),
            // beenden wir uns selbst, um den Crash des Prozesses zu verhindern.
            stopSelf()
        }

        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GeofenceForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // M18.66-FIX: ForegroundServiceStartNotAllowedException
                // (Android 12+) wenn die App im Hintergrund startet, oder
                // SecurityException. Der FGS-Start darf NIE crashen — die
                // Geofence-Registrierung (client.addGeofences) funktioniert
                // auch ohne FGS, nur weniger zuverlässig im Hintergrund.
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                    // Auch der Fallback schlägt fehl — Geofences laufen
                    // dann nur im Vordergrund. Nicht blockierend.
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GeofenceForegroundService::class.java))
        }
    }
}

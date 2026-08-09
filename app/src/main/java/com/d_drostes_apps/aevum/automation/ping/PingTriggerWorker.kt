package com.d_drostes_apps.aevum.automation.ping

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.repository.PingTriggerRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * M18.61g: Ping-Trigger-Worker.
 *
 * Prüft periodisch die Erreichbarkeit aller aktiven Ping-Trigger (z.B.
 * FireTV im Heimnetz). Sobald die IP antwortet, wird die konfigurierte
 * Activity automatisch gestartet; sobald sie nicht mehr antwortet, wird
 * die zugehörige Session beendet.
 *
 * Erreichbarkeits-Check: ICMP (InetAddress.isReachable) braucht auf vielen
 * Geräten Root-Rechte und schlägt fehl. Deshalb: Socket-Verbindung auf
 * typische FireTV-Ports (8008 = FireTV-Webserver, 443, 80) mit kurzem
 * Timeout — zuverlässiger als ICMP.
 */
class PingTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pingTriggerRepository: PingTriggerRepository,
    private val liveActivityManager: LiveActivityManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val triggers = pingTriggerRepository.getAllEnabled()
            if (triggers.isEmpty()) return Result.success()

            for (trigger in triggers) {
                val reachable = isReachable(trigger.ipAddress)
                val live = liveActivityManager.liveSession.value

                if (reachable) {
                    // Erreichbar → Session starten (falls nicht schon eine
                    // passende läuft)
                    val sameSession = live != null && live.isLive &&
                        live.sourceTriggerId == trigger.id
                    if (!sameSession) {
                        if (live != null && live.isLive) {
                            // Andere Live-Session beenden, bevor die neue startet
                            liveActivityManager.forceFinishForAuto()
                        }
                        val session = liveActivityManager.start(
                            activityTypeId = trigger.activityTypeId,
                            title = trigger.name,
                            sourceType = "PING_AUTO",
                            sourceTriggerId = trigger.id
                        )
                        com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
                        android.util.Log.d("PingTriggerWorker", "Ping OK (${trigger.ipAddress}) → Session gestartet: ${session.title}")
                    }
                } else {
                    // Nicht erreichbar → Session beenden, wenn sie von
                    // DIESEM Trigger gestartet wurde
                    if (live != null && live.isLive && live.sourceTriggerId == trigger.id) {
                        liveActivityManager.stop()
                        com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.stop(applicationContext)
                        android.util.Log.d("PingTriggerWorker", "Ping verloren (${trigger.ipAddress}) → Session beendet")
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("PingTriggerWorker", "Ping-Check fehlgeschlagen", e)
            Result.retry()
        }
    }

    /**
     * Robuster Erreichbarkeits-Check: Socket auf typische FireTV-Ports.
     * ICMP (isReachable) ist auf vielen Geräten ohne Root unzuverlässig.
     */
    private suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        val ports = intArrayOf(8008, 443, 80, 8080)
        for (port in ports) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 1500)
                socket.close()
                return@withContext true
            } catch (_: Exception) {
                // Port nicht offen → nächsten versuchen
            }
        }
        // Fallback: ICMP-Ping (funktioniert auf manchen Geräten)
        try {
            InetAddress.getByName(ip).isReachable(1500)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val WORK_NAME = "aevum.ping_trigger"
        const val INTERVAL_MINUTES = 2L
    }
}

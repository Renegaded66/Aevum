package com.d_drostes_apps.aevum.automation.ping

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.repository.PingTriggerRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
 *
 * M18.62-FIX (Root Cause "Ping-Trigger zeichnet nichts auf"):
 * - Der Worker nutzte @AssistedInject, aber die App hat KEINE
 *   HiltWorkerFactory (Configuration.Provider). WorkManager kann einen
 *   @AssistedInject-Worker nicht instanziieren → der Job schlug IMMER
 *   fehl. Fix: etabliertes Projektmuster wie SleepImportWorker —
 *   Dependencies via EntryPointAccessors aus dem SingletonComponent
 *   holen (kein HiltWorkerFactory nötig).
 * - Der Scheduler plante PeriodicWorkRequest mit 2 Minuten — unter dem
 *   WorkManager-Minimum von 15 Minuten → IllegalArgumentException beim
 *   App-Start (vom try/catch in AevumApplication geschluckt) → der Job
 *   wurde NIE enqueued. Fix: OneTimeWorkRequest mit Selbst-Erneuerung —
 *   der Worker plant sich am Ende jedes Laufs selbst neu (2-Minuten-Takt).
 */
class PingTriggerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun pingTriggerRepository(): PingTriggerRepository
        fun liveActivityManager(): LiveActivityManager
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        val pingTriggerRepository = deps.pingTriggerRepository()
        val liveActivityManager = deps.liveActivityManager()

        val result = try {
            val triggers = pingTriggerRepository.getAllEnabled()
            if (triggers.isNotEmpty()) {
                for (trigger in triggers) {
                    val reachable = isReachable(trigger.ipAddress)
                    val live = liveActivityManager.liveSession.value

                    if (reachable) {
                        // Erreichbar → Session starten (falls nicht schon eine
                        // passende läuft)
                        val sameSession = live != null && live.isLive &&
                            live.sourceTriggerId == trigger.id
                        if (!sameSession) {
                            // M18.93v10-FIX: forceFinishForAuto() vor
                            // start() entfernt — start() löst die alte
                            // Session selbst auf.
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
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // Cancel (z.B. durch cancel()) darf NICHT neu planen
        } catch (e: Exception) {
            android.util.Log.e("PingTriggerWorker", "Ping-Check fehlgeschlagen", e)
            // M18.62-FIX: KEIN Result.retry() — der Selbst-Erneuerungs-Takt
            // (2 min) ist zuverlässiger als WorkManager-Backoff. Fehler werden
            // geloggt, der nächste Check kommt trotzdem.
            Result.success()
        }
        // M18.62-FIX: Selbst-Erneuerung — der nächste Check wird in jedem
        // Fall geplant (Erfolg, Fehler, leere Trigger-Liste), aber NICHT
        // bei Cancellation: ein cancel() des Users muss den Takt wirklich
        // stoppen. Deshalb KEIN finally — scheduleNext() steht hinter dem
        // try/catch, der CancellationException-Rethrow verhindert die
        // Wiederplanung.
        scheduleNext()
        return result
    }

    /**
     * M18.62-FIX: Plant den nächsten Check als OneTimeWorkRequest mit
     * INTERVAL_MINUTES Verzögerung. REPLACE: falls ein alter Job aus einer
     * früheren Version existiert (oder der Scheduler beim App-Start einen
     * neuen geplant hat), gewinnt der frisch geplante.
     */
    private fun scheduleNext() {
        try {
            val next = OneTimeWorkRequestBuilder<PingTriggerWorker>()
                .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, next)
        } catch (e: Exception) {
            android.util.Log.e("PingTriggerWorker", "Selbst-Neuplanung fehlgeschlagen", e)
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
        const val INTERVAL_MINUTES = 5L
    }
}

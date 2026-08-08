package com.d_drostes_apps.aevum.automation.midnight

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M17.3: Schedules the daily [MidnightAllowanceWorker].
 *
 * Runs once a day at ~00:05 local time. We use the standard
 * PeriodicWorkRequest with a 24h period; the first run is delayed so
 * that it lands shortly after midnight in the user's timezone.
 */
@Singleton
class MidnightAllowanceScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        val initialDelay = computeInitialDelay()
        val request = PeriodicWorkRequestBuilder<MidnightAllowanceWorker>(
            1, TimeUnit.DAYS
        ).setInitialDelay(initialDelay, TimeUnit.MILLISECONDS).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MidnightAllowanceWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Berechnet die Verzögerung bis zum nächsten 00:05 in der
     * User-Timezone. Beispiel: jetzt = 14:23 → Delay = 9h 42min bis
     * 00:05 morgen früh.
     */
    private fun computeInitialDelay(): Long {
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val target = now.toLocalDate().atTime(LocalTime.of(0, 5)).atZone(zone)
        val effective = if (now.toLocalTime().isBefore(LocalTime.of(0, 5))) {
            // Heute vor 00:05 → heute um 00:05
            target
        } else {
            // Nach 00:05 → morgen um 00:05
            target.plusDays(1)
        }
        return java.time.Duration.between(now, effective).toMillis()
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(MidnightAllowanceWorker.WORK_NAME)
    }
}

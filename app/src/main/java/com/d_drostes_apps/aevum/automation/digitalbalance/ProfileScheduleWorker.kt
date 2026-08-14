package com.d_drostes_apps.aevum.automation.digitalbalance

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.repository.BalanceProfileRepository
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * M18.66-FIX14: ProfileScheduleWorker — automatische Aktivierung/Deaktivierung
 * von Digital-Balance-Profilen nach Zeitplan.
 *
 * Läuft alle 15 Minuten und prüft für jedes Profil mit scheduleEnabled=true:
 * - Ist der aktuelle Wochentag in scheduleDays?
 * - Liegt die aktuelle Zeit zwischen scheduleStartMinute und scheduleEndMinute?
 * Wenn ja → Profil aktivieren. Wenn nein → Profil deaktivieren (falls es das
 * aktuell aktive ist).
 *
 * Es wird nur EIN Profil gleichzeitig aktiviert — das erste, das matching.
 * Wenn kein Profil matcht, wird das aktive Profil deaktiviert (nur wenn es
 * einen Zeitplan hat — manuell aktivierte Profile bleiben unangetastet).
 */
class ProfileScheduleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun balanceProfileRepository(): BalanceProfileRepository
    }

    companion object {
        private const val TAG = "ProfileSchedule"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProfileScheduleWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "profile_schedule_check",
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val deps = EntryPointAccessors.fromApplication(
                applicationContext,
                Deps::class.java
            )
            val repo = deps.balanceProfileRepository()

            val now = LocalDateTime.now(ZoneId.systemDefault())
            val dow = now.dayOfWeek.value // 1=Monday, 7=Sunday
            val minuteOfDay = now.hour * 60 + now.minute
            val bitForToday = 1 shl (dow - 1) // Bit 0=Mo, 6=So

            val scheduled = repo.getScheduledProfiles()
            val active = repo.getActiveOnce()

            // Finde das Profil, das gerade aktiv sein sollte
            val shouldActivate = scheduled.firstOrNull { p ->
                (p.scheduleDays and bitForToday) != 0 &&
                minuteOfDay in p.scheduleStartMinute until p.scheduleEndMinute
            }

            if (shouldActivate != null) {
                if (active?.id != shouldActivate.id) {
                    Log.d(TAG, "Aktiviere Profil '${shouldActivate.name}' (Zeitplan: dow=$dow, $minuteOfDay min)")
                    repo.setActive(shouldActivate.id)
                }
            } else {
                // Kein Profil matcht — deaktiviere das aktive, aber NUR
                // wenn es einen Zeitplan hat (manuell aktivierte Profile
                // bleiben unangetastet).
                if (active != null && active.scheduleEnabled) {
                    Log.d(TAG, "Deaktiviere Profil '${active.name}' (Zeitplan-Fenster vorbei)")
                    repo.deactivate()
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ProfileScheduleWorker fehlgeschlagen", e)
            Result.retry()
        }
    }
}
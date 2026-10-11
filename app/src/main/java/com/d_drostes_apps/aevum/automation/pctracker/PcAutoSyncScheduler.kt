package com.d_drostes_apps.aevum.automation.pctracker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plant den stillen PC-Sync (App 1.0.26): alle [INTERVAL_MINUTES] Minuten,
 * nur wenn ein Netzwerk verbunden ist. Der Worker selbst prüft zusätzlich
 * Schalter + Kopplung; hier wird nur die Planung verwaltet.
 *
 * [schedule] ist idempotent (UPDATE-Policy) und wird beim App-Start, nach dem
 * Koppeln/Entkoppeln und beim Umschalten aufgerufen — so verliert weder ein
 * Update noch ein Neustart die Planung, und Ausschalten entfernt sie sofort.
 */
@Singleton
class PcAutoSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PcTrackerPrefs
) {

    companion object {
        /** WorkManager-Minimum ist 15 Minuten; 30 sind fürs Heimnetz reichlich. */
        const val INTERVAL_MINUTES = 30L
    }

    /** Plant (oder aktualisiert) den Hintergrund-Sync — ohne Kopplung/Schalter aus. */
    fun schedule() {
        if (prefs.load() == null || !prefs.isAutoSyncEnabled()) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<PcAutoSyncWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PcAutoSyncWorker.WORK_NAME,
            // UPDATE: erneutes Anstoßen ersetzt die Planung, ohne Doppel-Jobs.
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(PcAutoSyncWorker.WORK_NAME)
    }
}

package com.d_drostes_apps.aevum.automation.pctracker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plant den stillen PC-Sync. App 1.0.27 (M18.147):
 *
 *  - **Regelmäßig** alle [INTERVAL_MINUTES] Minuten — nur im **WLAN**
 *    (UNMETERED): Der PC steht im Heimnetz; über Mobilfunk würde der Lauf
 *    nur Strom und Daten kosten. (Vorher 30 Min. mit „irgendein Netz" —
 *    User-Wunsch: zuverlässiger, aber weiter sparsam.)
 *  - **Zusätzlich** [triggerOpportunistic]: ein einmaliger, gedrosselter
 *    Kick, wann immer die App ohnehin aufwacht (App-Öffnen, Hintergrund-
 *    Prozessstart). So holt sie Verpasstes nach, statt bis zum nächsten
 *    Takt zu warten, den das System (Doze) jederzeit aufschieben kann.
 *
 * Alles bleibt vollkommen still — Meldungen zeigt nur der manuelle Sync.
 * Der Worker prüft zusätzlich Schalter + Kopplung; hier wird nur geplant.
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
        /** WorkManager-Minimum ist 15 Minuten; fürs Heimnetz reichlich. */
        const val INTERVAL_MINUTES = 15L
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
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PcAutoSyncWorker.WORK_NAME,
            // UPDATE: erneutes Anstoßen ersetzt die Planung, ohne Doppel-Jobs,
            // und ohne den nächsten Lauf neu zu verschieben.
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(PcAutoSyncWorker.WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PcAutoSyncWorker.WORK_NAME_KICK)
    }

    /**
     * Einmaliger stiller Sync-Kick beim App-Öffnen bzw. wenn die App im
     * Hintergrund ohnehin erwacht. Gedrosselt über [PcSyncGate]: höchstens ein
     * Versuch pro Mindestabstand, nichts bei frischem Sync, ohne Kopplung oder
     * bei ausgeschaltetem Schalter. Läuft über WorkManager (UNMETERED) —
     * Aufrufer blockieren nie, und ohne WLAN passiert einfach nichts.
     */
    fun triggerOpportunistic() {
        val connection = prefs.load() ?: return
        val now = System.currentTimeMillis()
        if (!PcSyncGate.shouldAttemptNow(
                nowMs = now,
                lastAttemptMs = prefs.lastAttemptAt(),
                lastSyncMs = connection.lastSyncAt,
                coupled = true,
                autoSyncEnabled = prefs.isAutoSyncEnabled()
            )
        ) {
            return
        }
        prefs.setLastAttemptAt(now)
        val request = OneTimeWorkRequestBuilder<PcAutoSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PcAutoSyncWorker.WORK_NAME_KICK,
            // KEEP: ein bereits wartender/laufender Kick wird nicht doppelt gestapelt.
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

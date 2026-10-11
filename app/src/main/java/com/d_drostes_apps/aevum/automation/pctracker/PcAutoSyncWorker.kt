package com.d_drostes_apps.aevum.automation.pctracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs
import com.d_drostes_apps.aevum.data.repository.PcTrackerRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Stiller Hintergrund-Sync mit dem gekoppelten Windows-PC (App 1.0.26).
 *
 * Holt Aufzeichnungen ab und spiegelt Aktivitäten zum PC, solange beide im
 * selben WLAN sind. Wichtige Eigenschaft: NICHTS wird gemeldet — kein Toast,
 * keine Notification, kein Fehlertext. Ist der PC gerade nicht erreichbar,
 * passiert schlicht nichts beim nächsten regulären Lauf wieder. Fehler zeigt
 * ausschließlich der manuelle Sync in den PC-Tracker-Einstellungen.
 *
 * Nutzt Hilt-EntryPointAccessors (gleiches Muster wie SleepImportWorker) —
 * kein eigener WorkManager-Configuration.Provider nötig.
 */
class PcAutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun pcTrackerRepository(): PcTrackerRepository
        fun pcTrackerPrefs(): PcTrackerPrefs
    }

    companion object {
        const val WORK_NAME = "aevum_pc_auto_sync"
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        // Schalter aus oder keine Kopplung → nichts tun (der Scheduler räumt
        // eine bestehende Planung ohnehin ab; das hier ist nur die Absicherung).
        if (!deps.pcTrackerPrefs().isAutoSyncEnabled()) {
            return Result.success()
        }
        return try {
            // Ergebnis (Success/NotReachable/…) wird bewusst ignoriert — der
            // nächste Lauf probiert es einfach wieder. Keine Meldungen.
            deps.pcTrackerRepository().syncNow()
            Result.success()
        } catch (_: Exception) {
            // Auch unerwartete Fehler bleiben still; WorkManager-Retry würde
            // nur Backoff-Läufe stapeln — der periodische Lauf kommt sowieso.
            Result.success()
        }
    }
}

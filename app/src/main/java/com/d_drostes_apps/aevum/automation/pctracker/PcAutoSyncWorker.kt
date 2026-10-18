package com.d_drostes_apps.aevum.automation.pctracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.pc.PcLiveState
import com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs
import com.d_drostes_apps.aevum.data.repository.PcTrackerRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Stiller Hintergrund-Sync mit dem gekoppelten Windows-PC (App 1.0.26,
 * M18.147: auch als gedrosselter App-Öffnen-Kick).
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

        /** PC 1.6.0: für den Verifikations-Ping nach dem Sync. */
        fun liveActivityManager(): LiveActivityManager

        /** M22: Fallback, falls der App-Aufzeichnungs-Dienst gerade nicht lebt. */
        fun pcRemoteCommandRunner(): PcRemoteCommandRunner
    }

    companion object {
        const val WORK_NAME = "aevum_pc_auto_sync"
        /** Einmaliger Kick (App-Öffnen/Hintergrund) — siehe [PcAutoSyncScheduler]. */
        const val WORK_NAME_KICK = "aevum_pc_auto_sync_kick"
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val prefs = deps.pcTrackerPrefs()
        // Schalter aus oder keine Kopplung → nichts tun (der Scheduler räumt
        // eine bestehende Planung ohnehin ab; das hier ist nur die Absicherung).
        if (!prefs.isAutoSyncEnabled() || prefs.load() == null) {
            return Result.success()
        }
        // M18.147: Versuch stempeln — die gedrosselten App-Öffnen-Kicks
        // (PcSyncGate) rechnen mit diesem Wert, egal welcher Lauf ihn setzt
        // (Erfolg wie Fehlschlag; gedrosselt wird der VERSUCH, nicht nur der Erfolg).
        prefs.setLastAttemptAt(System.currentTimeMillis())
        return try {
            // Ergebnis nur auswerten, um bei Erfolg den Verifikations-Ping zu
            // schicken; Fehlschläge bleiben bewusst still (der nächste Lauf
            // probiert es einfach wieder — keine Meldungen).
            when (deps.pcTrackerRepository().syncNow()) {
                is PcTrackerRepository.SyncOutcome.Success -> {
                    // PC 1.6.0: Verifikations-Ping — dem PC frisch bestätigen,
                    // was am Handy läuft (oder ausdrücklich: nichts läuft).
                    // Ohne diese Bestätigung startet der PC (ab 1.6.0) gar nichts.
                    deps.pcTrackerRepository().relayLive(
                        PcLiveState.snapshot(deps.liveActivityManager())
                    )
                    // M22: Steuerbefehl nachholen (Fallback zum 6-s-Takt des
                    // App-Aufzeichnungs-Dienstes; greift, wenn der Dienst
                    // gerade nicht läuft). Still, wie alles hier.
                    runCatching { deps.pcRemoteCommandRunner().pollOnce() }
                }
                else -> Unit
            }
            Result.success()
        } catch (_: Exception) {
            // Auch unerwartete Fehler bleiben still; WorkManager-Retry würde
            // nur Backoff-Läufe stapeln — der periodische Lauf kommt sowieso.
            Result.success()
        }
    }
}

package com.d_drostes_apps.aevum.automation.calendar

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.data.repository.CalendarRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * M18.129: Synchronisiert den Kalender in den lokalen Cache.
 *
 * Der EINZIGE Ort, der den ContentResolver anfasst. Alles andere
 * (Auto-Start, Timeline-Vorschau) liest den Cache — das ist die
 * M18.104-Lektion: teure IO in seltenen Bursts, nicht im Minutentakt.
 *
 * Gates (in dieser Reihenfolge, jedes bricht sauber ab):
 *  1. Feature aktiviert (`calendarSyncEnabled`)?
 *  2. READ_CALENDAR erteilt? → sonst [Result.success] (KEIN Retry —
 *     eine fehlende Berechtigung ist kein transienter Fehler; Retry
 *     würde nur den Akku leeren. Der nächste reguläre Takt bzw. der
 *     manuelle Sync greift, sobald der Nutzer sie erteilt).
 *  3. Lesen → Cache schreiben → Zeitstempel setzen.
 *
 * Der Zeitstempel wird AUCH bei 0 gefundenen Terminen gesetzt: „erfolgreich
 * synchronisiert, nichts im Fenster" ist ein legitimes Ergebnis und der
 * Nutzer soll das im Textfeld sehen (statt eines uralten Datums, das wie
 * ein Fehler wirkt).
 */
class CalendarSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun automationSettingsDao(): AutomationSettingsDao
        fun calendarRepository(): CalendarRepository
        fun calendarReader(): CalendarReader
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val settings = try {
            deps.automationSettingsDao().getSettingsSync()
        } catch (e: Exception) {
            Log.e(TAG, "Settings nicht lesbar", e)
            null
        }

        // Gate 1: Feature-Schalter.
        if (settings?.calendarSyncEnabled != true) {
            Log.d(TAG, "Kalender-Sync deaktiviert — Abbruch")
            return Result.success()
        }

        // Gate 2: Berechtigung.
        val reader = deps.calendarReader()
        if (!reader.hasPermission()) {
            Log.d(TAG, "READ_CALENDAR fehlt — Sync übersprungen (kein Retry)")
            return Result.success()
        }

        // Lesen + Cache aktualisieren.
        return when (val result = reader.readEvents()) {
            is CalendarReader.CalendarReadResult.Success -> {
                try {
                    val (from, _) = reader.window()
                    val repo = deps.calendarRepository()
                    repo.replaceWindow(result.events, pruneBefore = from)
                    repo.markSynced(System.currentTimeMillis())
                    Log.i(TAG, "Kalender-Sync ok: ${result.events.size} Termine im Cache")
                    Result.success()
                } catch (e: Exception) {
                    Log.e(TAG, "Cache-Schreiben fehlgeschlagen", e)
                    Result.retry()
                }
            }

            is CalendarReader.CalendarReadResult.PermissionMissing -> {
                Log.d(TAG, "Berechtigung während des Syncs entzogen — sauberer Abbruch")
                Result.success()
            }

            is CalendarReader.CalendarReadResult.Failed -> {
                Log.w(TAG, "Kalender-Lesen fehlgeschlagen: ${result.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "CalendarSyncWorker"
        const val WORK_NAME = "aevum_calendar_sync"
        const val WORK_NAME_MANUAL = "aevum_calendar_sync_manual"
        const val KEY_MANUAL = "manual"
    }
}

package com.d_drostes_apps.aevum.automation.calendar

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.129: Plant den Kalender-Sync.
 *
 * AKUU-SCHONEND, drei Entscheidungen:
 *
 *  1. PERIODISCH nur alle [DEFAULT_INTERVAL_HOURS] (Default 6 h). Ein
 *     Kalender ändert sich nicht minütlich; 4 Syncs pro Tag sind für
 *     die Vorschau und den Auto-Start vollkommen ausreichend.
 *
 *  2. `BATTERY_NOT_LOW` als Constraint — auf dem letzten Prozent wird
 *     nicht synchronisiert. Der Kalender ist nie dringend.
 *
 *  3. KEIN exakter Alarm und KEINE SCHEDULE_EXACT_ALARM-Berechtigung.
 *     Ab Android 14 ist dieses Recht nicht mehr automatisch erteilt;
 *     es zu verlangen wäre eine zusätzliche, für Kalender-Syncs
 *     unnötige Hürde. Die Periodik ist inexakt — das ist hier egal.
 *
 * Der Nutzer kann den Takt im UI einstellen (Sync-Takt-Auswahl);
 * [schedule] liest ihn aus den Settings, damit ein Änderungswunsch
 * sofort wirkt (UPDATE-Policy ersetzt die bestehende Planung).
 */
@Singleton
class CalendarSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDao: AutomationSettingsDao
) {

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6
        /** Erlaubte Takt-Werte für die UI-Auswahl (Stunden). */
        val ALLOWED_INTERVALS = listOf(1, 3, 6, 12, 24)
    }

    /** Plant (oder aktualisiert) den periodischen Sync. */
    suspend fun schedule() {
        val hours = readIntervalHours().coerceIn(1, 24)
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
            hours.toLong(), TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    // Kein Sync auf dem letzten Prozent — der Kalender
                    // ist nie dringend (M18.104-Akku-Philosophie).
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CalendarSyncWorker.WORK_NAME,
            // UPDATE: ein geänderter Takt (oder ein erneuter App-Start)
            // ersetzt die Planung, ohne einen Doppel-Job zu erzeugen.
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Manueller Sync (Button in den Einstellungen) — sofort, einmalig.
     *
     * Eigener Unique-Work-Name mit KEEP (M18.63-Muster): wiederholtes
     * Tippen erzeugt keinen Job-Stapel, und der manuelle Sync kann nicht
     * parallel zum periodischen laufen.
     *
     * Bewusst OHNE Battery-Constraint: der Nutzer hat gerade explizit
     * getippt — seine Absicht schlägt die Akku-Heuristik. Nur die
     * Netzwerk-/Akkulage der Periodik bleibt konservativ.
     */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putBoolean(CalendarSyncWorker.KEY_MANUAL, true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CalendarSyncWorker.WORK_NAME_MANUAL,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(CalendarSyncWorker.WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(CalendarSyncWorker.WORK_NAME_MANUAL)
    }

    /** Takt aus den Settings — Fallback auf den Default bei Lesefehler. */
    private suspend fun readIntervalHours(): Int = try {
        settingsDao.getSettingsSync()?.calendarSyncIntervalHours ?: DEFAULT_INTERVAL_HOURS
    } catch (e: Exception) {
        DEFAULT_INTERVAL_HOURS
    }
}

package com.d_drostes_apps.aevum.automation.calendar

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * M18.129: Plant den Auto-Start/Stop-Lauf.
 *
 * WARUM SELBST-ERNEUERND UND NICHT PERIODISCH:
 * WorkManager verlangt für PeriodicWorkRequest mindestens 15 Minuten.
 * Alles darunter wirft eine IllegalArgumentException — genau dieser
 * Fehler hat den Ping-Trigger monatelang stillgelegt (M18.62-FIX:
 * „der Job wurde NIE enqueued", weil der Fehler in einem try/catch
 * verschwand). Ein OneTimeWorkRequest mit setInitialDelay hat dieses
 * Limit nicht und erlaubt den gezielten Weckruf exakt auf die nächste
 * Termingrenze (z. B. 10:15 statt „irgendwann zwischen 10:15 und 10:30").
 *
 * `REPLACE`: bei einem frischen Anlass (App-Start, Regel-Änderung,
 * Toggle) wird der geplante Lauf verworfen und neu berechnet — sonst
 * würde eine alte Planung die neue überschreiben bzw. doppelt feuern.
 */
object CalendarAutoRunScheduler {

    /**
     * Plant den nächsten Lauf.
     *
     * @param delayMs Verzögerung bis zum Lauf (aus der Engine:
     *        nächste Termingrenze, gedeckelt auf 15 Minuten).
     */
    fun scheduleNext(context: Context, delayMs: Long = CalendarAutoRunWorker.DEFAULT_MAX_DELAY_MS) {
        val request = OneTimeWorkRequestBuilder<CalendarAutoRunWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CalendarAutoRunWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Startet den Takt neu — mit einem sofortigen ersten Lauf.
     *
     * Wird aufgerufen, wenn der Nutzer die Auto-Aufzeichnung gerade
     * aktiviert hat: dann soll der nächste fällige Termin nicht erst in
     * 15 Minuten geprüft werden, sondern jetzt (M18.61e-Lektion: eine
     * Toggle-Aktivierung muss den System-Mechanismus sofort anstoßen).
     */
    fun restartNow(context: Context) {
        scheduleNext(context, delayMs = 1_000L)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CalendarAutoRunWorker.WORK_NAME)
    }
}

package com.d_drostes_apps.aevum.automation.midnight

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.DailyAllowance
import com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M17.3: Midnight Allowance Worker.
 *
 * Läuft täglich um 00:05 (vom App-Start-Scheduler eingereiht). Iteriert
 * alle aktiven [DailyAllowance]s und schreibt für den Vortag einen
 * [AllowanceAccumulationDay] Eintrag pro Allowance.
 *
 * Idempotent: ein zweiter Lauf am gleichen Tag überschreibt den Eintrag
 * (PK = date + timezoneId + allowanceId) — kein Doppel-Zählen.
 *
 * Bewusst KEIN neuer Eintrag in activity_session — die Tagespauschalen
 * sind reine Statistik-Werte, sie sollen NICHT in der Timeline
 * erscheinen. Der InsightsAnalytics-Loader addiert sie on-the-fly zu
 * den aggregierten Minuten.
 */
class MidnightAllowanceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun dailyAllowanceRepository(): DailyAllowanceRepository
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val deps = EntryPointAccessors.fromApplication(ctx, Deps::class.java)
        val repo = deps.dailyAllowanceRepository()

        val zoneId = ZoneId.systemDefault()
        // Der Worker läuft um 00:05 — also ist "heute" der neue Tag und
        // "gestern" der Tag, für den die Pauschalen verbucht werden.
        val yesterday = LocalDate.now(zoneId).minusDays(1)
        val dateStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val allowances = repo.getEnabled()
        if (allowances.isEmpty()) {
            Log.d(TAG, "Keine aktiven DailyAllowances → skip")
            return Result.success()
        }

        var insertedCount = 0
        // M18.60-FIX: Overrides des Tages schützen — hat der User die
        // Pauschale fuer diesen Tag angepasst (allowance_day_override),
        // darf der Worker den Tageswert NICHT mit dem Standardwert
        // ueberschreiben. Der Override gewinnt.
        val overrides = repo.getOverridesForDate(dateStr).associateBy { it.allowanceId }
        allowances.forEach { allowance ->
            try {
                val effectiveMinutes = overrides[allowance.id]?.minutes ?: allowance.minutesPerDay
                val acc = AllowanceAccumulationDay(
                    date = dateStr,
                    timezoneId = zoneId.id,
                    allowanceId = allowance.id,
                    activityTypeId = allowance.activityTypeId,
                    minutes = effectiveMinutes
                )
                repo.insertAccumulation(acc)
                insertedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Fehler bei Allowance ${allowance.id} ($dateStr)", e)
            }
        }
        Log.d(TAG, "$insertedCount Accumulation-Einträge für $dateStr geschrieben")
        return Result.success()
    }

    companion object {
        const val TAG = "MidnightAllowance"
        const val WORK_NAME = "aevum.midnight.allowance"
    }
}

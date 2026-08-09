package com.d_drostes_apps.aevum.automation.sleep

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.db.AppUsageSampleDao

/**
 * M14: WorkManager-Wrapper für die 3-Signal-Schlaf-Fusion.
 *
 * Wird getriggert durch:
 *  - [ActivityTransitionReceiver] bei jeder STILL-Transition (One-Time, nicht-periodic)
 *  - manuelle Auslösung über "Jetzt analysieren" in den Automation-Settings
 *  - optionaler App-Start-Trigger in [com.d_drostes_apps.aevum.AevumApplication]
 *
 * Bewusst KEIN Periodic Worker: das passiert nur, wenn ein Signal ankommt.
 * Wir wollen nicht jede Stunde eine teure Analyse laufen lassen, wenn gar
 * keine neuen Events da sind.
 */
class SleepFusionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun sleepFusionEngine(): SleepFusionEngine
        fun sleepHeuristicEngine(): SleepHeuristicEngine
        fun appUsageSampleDao(): AppUsageSampleDao
        fun screenEventRepository(): ScreenEventRepository
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SettingsDeps {
        fun automationSettingsDao(): com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        return try {
            // M16: Repository-Init defensiv aufrufen — Singleton-Init ist im M13-Code
            // explizit nötig, weil `appContext` als Var gesetzt wird.
            deps.screenEventRepository().init(applicationContext)

            // M18.58: sleepSource steuert, welche Schlaf-Erkennung läuft.
            //   "screen"          → Screen-Heuristik IMMER + 3-Signal-Fusion
            //                       (Fusion verbessert die Screen-Erkennung
            //                       mit AR-Signal, wenn Permission da ist)
            //   "health_connect"  → Health-Connect-Import (eigener Scheduler)
            //   "garmin"          → Garmin-Import (eigener Worker)
            //   "none"            → KEINE automatische Schlaf-Erkennung
            // Vorher: sleepFusionEnabled-Toggle (default false) — jetzt ist
            // die Fusion Teil der Quelle "screen" (User-Wunsch: genau EIN
            // Trigger für Schlaf, keine vielen Toggles).
            val settingsDeps = EntryPointAccessors.fromApplication(applicationContext, SettingsDeps::class.java)
            val settings = try {
                settingsDeps.automationSettingsDao().getSettingsSync()
            } catch (e: Exception) {
                null // Wenn Settings nicht lesbar, läuft der Worker (Best-Effort)
            }
            val sleepSource = settings?.sleepSource ?: "screen"
            if (sleepSource == "none") {
                android.util.Log.d(TAG, "sleepSource = none — keine Schlaf-Erkennung aktiv")
                return Result.success()
            }
            if (sleepSource != "screen") {
                android.util.Log.d(TAG, "sleepSource = $sleepSource — Screen-Heuristik ist No-Op (Quelle: $sleepSource)")
                return Result.success()
            }

            // M18.11: BEIDE Engines triggern — die Screen-Heuristik (immer)
            // UND die 3-Signal-Fusion (nur wenn aktiviert).
            //
            // Vorher triggerte der Morgen-Scheduler nur die Fusion. Die
            // Heuristik lief nur bei App-Start/Screen-ON. Wenn der User
            // morgens die App nicht öffnete, wurde der Schlaf nie erkannt.
            // Jetzt deckt der periodische Morgen-Lauf beide Pfade ab.
            deps.sleepHeuristicEngine().analyzeLatest()

            deps.sleepFusionEngine().analyzeLatest()
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "SleepFusionWorker failed", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "aevum.sleep_fusion"
        private const val TAG = "SleepFusionWorker"
    }
}

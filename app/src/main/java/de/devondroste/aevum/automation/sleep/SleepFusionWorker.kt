package de.devondroste.aevum.automation.sleep

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.data.db.AppUsageSampleDao

/**
 * M14: WorkManager-Wrapper für die 3-Signal-Schlaf-Fusion.
 *
 * Wird getriggert durch:
 *  - [ActivityTransitionReceiver] bei jeder STILL-Transition (One-Time, nicht-periodic)
 *  - manuelle Auslösung über "Jetzt analysieren" in den Automation-Settings
 *  - optionaler App-Start-Trigger in [de.devondroste.aevum.AevumApplication]
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
        fun appUsageSampleDao(): AppUsageSampleDao
        fun screenEventRepository(): ScreenEventRepository
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SettingsDeps {
        fun automationSettingsDao(): de.devondroste.aevum.data.db.AutomationSettingsDao
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

            // M16: sleepFusionEnabled prüfen. Die einfache Screen-Heuristik
            // (SleepHeuristicEngine) läuft immer — sie braucht keine Settings.
            // Die 3-Signal-Fusion ist optional und muss vom User aktiviert
            // werden. Wenn der Toggle aus ist, ist der Worker ein No-Op.
            // Default ist false (siehe AutomationSettings), also läuft die
            // Fusion nur, wenn der User sie bewusst einschaltet.
            val settingsDeps = EntryPointAccessors.fromApplication(applicationContext, SettingsDeps::class.java)
            val settings = try {
                settingsDeps.automationSettingsDao().getSettingsSync()
            } catch (e: Exception) {
                null // Wenn Settings nicht lesbar, läuft der Worker (Best-Effort)
            }
            if (settings?.sleepFusionEnabled == false) {
                android.util.Log.d(TAG, "sleepFusionEnabled = false — Worker ist No-Op")
                return Result.success()
            }

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

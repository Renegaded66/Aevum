package com.d_drostes_apps.aevum.automation.screen

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import kotlinx.coroutines.flow.first
import android.util.Log

/**
 * M18.70: Bildschirm-Aufzeichnung — startet die „Digital"-Session.
 *
 * Wird vom [ScreenEventReceiver] nach Screen-ON mit Delay = x Minuten
 * enqueued (x = konfigurierte Vorlaufzeit). Beim Feuern prüft der Worker
 * die Bedingungen erneut (Screen noch an? nichts anderes zeichnet auf?)
 * und startet die Session mit rückwirkender Startzeit (now − x min).
 *
 * Screen-OFF cancelt den Worker (UniqueWork) — die Aufzeichnung startet
 * dann nie. Läuft die Session bereits, stoppt der Receiver sie direkt.
 */
class ScreenRecordingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun liveActivityManager(): LiveActivityManager
        fun automationSettingsDao(): AutomationSettingsDao
        fun activityTypeRepository(): ActivityTypeRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        return try {
            val settings = deps.automationSettingsDao().getSettingsSync()
            val minutes = settings?.screenRecordingMinutes ?: 5
            if (minutes == ScreenRecordingEngine.DEACTIVATED) {
                Log.d(TAG, "Screen-Aufzeichnung deaktiviert — Abbruch")
                return Result.success()
            }

            // Bedingung: gerade nichts anderes zeichnet auf.
            val live = deps.liveActivityManager().liveSession.value
            if (live != null && live.isLive) {
                Log.i(TAG, "Screen-Aufzeichnung SKIPPED: andere Session aktiv (${live.title}, source=${live.sourceType}) — das ist nur sichtbar, wenn der User eine Parallel-Automatisierung laufen hat")
                return Result.success()
            }

            // Bedingung: Screen muss noch an sein (Delay könnte abgelaufen sein,
            // während der Screen schon wieder aus ist).
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE)
                as android.os.PowerManager
            if (!powerManager.isInteractive) {
                Log.i(TAG, "Screen-Aufzeichnung SKIPPED: Screen ist aus (Worker feuerte nach OFF — harmlos)")
                return Result.success()
            }

            // Digital-Typ existiert als Seed; Fallback auf "other" (M18.51-Muster).
            val type = deps.activityTypeRepository().getById("digital").first()
            val typeId = if (type != null) "digital" else "other"

            val now = System.currentTimeMillis()
            val startedAt = ScreenRecordingEngine.recordingStartTime(now, minutes)
            val session = deps.liveActivityManager().start(
                activityTypeId = typeId,
                title = "Digital",
                sourceType = "SCREEN_AUTO",
                startedAt = startedAt
            )
            LiveActivityService.start(applicationContext)
            Log.d(TAG, "Screen-Aufzeichnung gestartet: ${session.id} (start=$startedAt, vorlauf=${minutes}min)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Screen-Aufzeichnung fehlgeschlagen", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScreenRecordingWorker"
        const val WORK_NAME = "screen_recording_auto"
    }
}

package com.d_drostes_apps.aevum.automation.screen

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.data.repository.AppTrackingEntryRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import kotlinx.coroutines.flow.first
import android.util.Log

/**
 * M18.70: Bildschirm-Aufzeichnung — startet die „Digital"-Session.
 *
 * Wird vom [ScreenEventReceiver] nach Screen-ON mit Delay = x Minuten
 * enqueued (x = konfigurierte Vorlaufzeit) und seit M18.151 zusätzlich vom
 * AppTrackingService, wenn eine App-Aufzeichnung endet („Falls dann
 * 2 Minuten das Display an ist ohne eine ausgewählte App, dann soll Digital
 * wieder starten."). Beim Feuern prüft der Worker die Bedingungen erneut
 * (Screen noch an? nichts anderes zeichnet auf? M18.151: keine getrackte
 * App im Vordergrund?) und startet die Session mit rückwirkender Startzeit
 * (now − x min).
 *
 * M18.151 (Übergabe-Regeln, User-Report: „… dann wird einfach weiterhin die
 * ganze Zeit Digital aufgezeichnet. Eigentlich sollte dann nach den
 * 2 Minuten Bildschirmzeit die Activity von zugewiesenem Instagram starten,
 * statt Digital, falls ich noch auf der App drauf bin."): Ist beim Feuern
 * eine getrackte App im Vordergrund, wird NICHT gestartet — diese Zeit
 * gehört der zugewiesenen Activity. Läuft die App-Session bereits, greift
 * schon der „nichts anderes live"-Check; der neue Check schließt das
 * Restfenster (App gerade erst geöffnet / AppTrackingService gerade nicht
 * am Pollen) und stupst den Service an.
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
        fun appTrackingEntryRepository(): AppTrackingEntryRepository
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

            // M18.151: Läuft gerade eine GETRACKTE App im Vordergrund? Dann
            // gehört diese Zeit der App-Aufzeichnung, NICHT dem Standard-
            // Digital. Zwei Quellen (siehe ForegroundAppProbe/AppTrackingService):
            //  1. Event-Faltung der letzten 2 Minuten (ForegroundAppProbe) —
            //     sieht einen gerade geöffneten ODER gerade verlassenen
            //     Vordergrund sofort.
            //  2. Poll-Schnappschuss des AppTrackingService (frisch ≤ 90s) —
            //     deckt „App seit Stunden ruhig im Vordergrund" ab, wo es
            //     keine Übergangs-Events gibt.
            // Treffer → NICHT starten und den AppTrackingService anstupsen
            // (er übernimmt die Aufzeichnung; läuft er gerade nicht, startet
            // ihn der Nudge — das Gate prüft getrackte Apps selbst).
            val foreground = com.d_drostes_apps.aevum.automation.apptracking.ForegroundAppProbe
                .currentForeground(applicationContext)
                ?: com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService
                    .recentPollSnapshot(90_000L)
            if (foreground != null) {
                val trackedPackages = try {
                    deps.appTrackingEntryRepository().getEnabledOnce().map { it.packageName }
                } catch (e: Exception) {
                    Log.w(TAG, "Tracked-Liste für Vordergrund-Check nicht ladbar: ${e.message}")
                    emptyList()
                }
                if (foreground in trackedPackages) {
                    Log.i(TAG, "Screen-Aufzeichnung SKIPPED: getrackte App '$foreground' im Vordergrund — App-Aufzeichnung übernimmt (M18.151)")
                    com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService
                        .start(applicationContext)
                    return Result.success()
                }
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

        /**
         * M18.151: Zentraler Einplan-Punkt für den Digital-Start (einziger
         * UniqueWork-Name [WORK_NAME] — REPLACE macht Doppel-Aufrufe
         * idempotent). Genutzt vom ScreenEventReceiver (Screen-ON) und vom
         * AppTrackingService (nach App-Ende).
         */
        fun schedule(context: Context, delayMinutes: Long) {
            try {
                val request = androidx.work.OneTimeWorkRequestBuilder<ScreenRecordingWorker>()
                    .setInitialDelay(
                        delayMinutes.coerceAtLeast(0L),
                        java.util.concurrent.TimeUnit.MINUTES
                    )
                    .build()
                androidx.work.WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        WORK_NAME,
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        request
                    )
            } catch (e: Exception) {
                Log.w(TAG, "schedule fehlgeschlagen: ${e.message}")
            }
        }
    }
}

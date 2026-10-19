package com.d_drostes_apps.aevum.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.d_drostes_apps.aevum.automation.activityrecognition.ActivityRecognitionBridge
import com.d_drostes_apps.aevum.automation.activityrecognition.DriveWatchdogWorker
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * M23: AppWidget-Provider für das 2x1-Aufzeichnungs-Widget.
 *
 * Der Stopp-Knopf läuft über einen expliziten Broadcast (Widget-Klicks
 * dürfen keine Service-Starts aus dem Hintergrund auslösen). Der Provider
 * beendet die Session direkt und räumt die Auto-Erkennungs-Flags auf —
 * gleiche Semantik wie der „■ Stoppen"-Knopf der Live-Notification
 * ([LiveActivityService.performStopFromOutside]).
 */
class AevumWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StopDeps {
        fun liveActivityManager(): LiveActivityManager
        fun activityRecognitionBridge(): ActivityRecognitionBridge
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        AevumWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AevumWidgetUpdater.updateAll(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_STOP) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val deps = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        StopDeps::class.java
                    )
                    val manager = deps.liveActivityManager()
                    val session = manager.liveSession.value
                    manager.stop()
                    // Auto-Quellen zurücksetzen — sonst bleibt z. B. die
                    // Fahrterkennung bis zum App-Neustart stumm.
                    when (session?.sourceType) {
                        "ACTIVITY_RECOGNITION_AUTO" -> {
                            try {
                                deps.activityRecognitionBridge().clearDriveActive()
                                DriveWatchdogWorker.cancel(context)
                            } catch (_: Exception) {
                            }
                        }
                        "WALKING_AUTO" -> {
                            try {
                                deps.activityRecognitionBridge().clearWalkingActive()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    LiveActivityService.stop(context)
                } catch (_: Exception) {
                    // Widget darf nie crashen — Zustand wird unten neu gebaut.
                } finally {
                    AevumWidgetUpdater.updateAll(context)
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_WIDGET_STOP = "com.d_drostes_apps.aevum.WIDGET_STOP"
    }
}

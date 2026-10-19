package com.d_drostes_apps.aevum.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.d_drostes_apps.aevum.MainActivity
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale

/**
 * M23: Baut und aktualisiert das 2x1-Homescreen-Widget.
 *
 * Bewusst ein eigener Updater statt Logik im Provider: Der
 * [LiveActivityService] tickt ohnehin jede Sekunde (bzw. alle 10 s bei
 * ausgeschaltetem Bildschirm) und ruft [updateAll] aus demselben Takt auf.
 * So läuft der Timer im Widget ohne zusätzlichen AlarmManager/WorkManager.
 *
 * Alle Zustände werden aus EINER Quelle gelesen ([LiveActivityManager.liveSession]):
 *  - keine Session → „BEREIT" + grüner Start-Knopf,
 *  - RUNNING → „LÄUFT" + Aktivitätsname + Timer + roter Stopp-Knopf,
 *  - PAUSED → „PAUSIERT" (Timer eingefroren) + roter Stopp-Knopf.
 *
 * Das Maskottchen wird nur gezeichnet, wenn es in den Einstellungen aktiv
 * ist; die Bitmap wird pro Konfiguration gecacht (kein Canvas-Rendering im
 * Sekundentakt).
 */
object AevumWidgetUpdater {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDeps {
        fun liveActivityManager(): LiveActivityManager
        fun mascotRepository(): MascotRepository
    }

    private data class MascotCacheKey(
        val colorId: String,
        val antenna: String,
        val cheeks: Boolean,
        val sizePx: Int
    )

    private var mascotCacheKey: MascotCacheKey? = null
    private var mascotCacheBitmap: Bitmap? = null

    /** Aktualisiert alle platzierten Widgets. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context, AevumWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        update(context, manager, ids)
    }

    /** Aktualisiert genau die vom System gelieferten IDs. */
    fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetDeps::class.java
        )
        val session = deps.liveActivityManager().liveSession.value
        val mascotConfig = deps.mascotRepository().currentSync()
        val views = buildViews(context, session, mascotConfig)
        manager.updateAppWidget(ids, views)
    }

    internal fun buildViews(
        context: Context,
        session: com.d_drostes_apps.aevum.data.model.ActivitySession?,
        mascotConfig: MascotRepository.MascotConfig
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.aevum_widget)
        val isLive = session?.isLive == true
        val isPaused = session?.isPaused == true

        val statusText: String
        val titleText: String
        val timerText: String
        val timerVisible: Boolean
        val statusColor: Int
        val titleColor: Int
        val backgroundRes: Int
        val actionText: String
        val actionBgRes: Int
        val actionContentDescription: String

        if (session != null && isLive) {
            statusText = if (isPaused) {
                context.getString(R.string.widget_status_paused)
            } else {
                context.getString(R.string.widget_status_running)
            }
            titleText = session.title
            timerText = formatTimer(session.activeDurationMs())
            timerVisible = true
            statusColor = if (isPaused) 0xFFFFD166.toInt() else 0xFFB9F6CA.toInt()
            titleColor = 0xFFFFFFFF.toInt()
            backgroundRes = R.drawable.widget_bg_recording
            actionText = "■"
            actionBgRes = R.drawable.widget_action_stop
            actionContentDescription = context.getString(R.string.widget_cd_stop)
        } else {
            statusText = context.getString(R.string.widget_status_idle)
            titleText = context.getString(R.string.widget_idle_title)
            timerText = ""
            timerVisible = false
            statusColor = 0xFF58A700.toInt()
            titleColor = 0xFF1B2333.toInt()
            backgroundRes = R.drawable.widget_bg_idle
            actionText = "▶"
            actionBgRes = R.drawable.widget_action_start
            actionContentDescription = context.getString(R.string.widget_cd_start)
        }

        views.setInt(R.id.widget_root, "setBackgroundResource", backgroundRes)
        views.setTextViewText(R.id.widget_status, statusText)
        views.setTextColor(R.id.widget_status, statusColor)
        views.setTextViewText(R.id.widget_title, titleText)
        views.setTextColor(R.id.widget_title, titleColor)
        views.setTextViewText(R.id.widget_timer, timerText)
        views.setTextColor(
            R.id.widget_timer,
            if (isPaused) 0xFFFFD166.toInt() else 0xFFB9F6CA.toInt()
        )
        views.setViewVisibility(
            R.id.widget_timer,
            if (timerVisible) View.VISIBLE else View.GONE
        )
        views.setTextViewText(R.id.widget_action, actionText)
        views.setTextColor(R.id.widget_action, 0xFFFFFFFF.toInt())
        views.setInt(R.id.widget_action, "setBackgroundResource", actionBgRes)
        views.setContentDescription(R.id.widget_action, actionContentDescription)
        views.setContentDescription(R.id.widget_title, titleText)

        // Maskottchen — nur wenn aktiviert. Größe in dp → px, gecacht.
        if (mascotConfig.enabled) {
            val density = context.resources.displayMetrics.density
            val sizePx = (30f * density).toInt().coerceAtLeast(1)
            val key = MascotCacheKey(
                colorId = mascotConfig.colorId,
                antenna = mascotConfig.antennaStyle,
                cheeks = mascotConfig.cheeks,
                sizePx = sizePx
            )
            if (mascotCacheKey != key || mascotCacheBitmap == null) {
                mascotCacheBitmap = WidgetMascotRenderer.render(mascotConfig, sizePx)
                mascotCacheKey = key
            }
            views.setViewVisibility(R.id.widget_mascot, View.VISIBLE)
            views.setImageViewBitmap(R.id.widget_mascot, mascotCacheBitmap)
        } else {
            views.setViewVisibility(R.id.widget_mascot, View.GONE)
        }

        // Klick auf den Körper öffnet die App.
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        // Der Knopf startet (Picker) oder stoppt (Broadcast).
        val actionIntent = if (session != null && isLive) {
            stopPendingIntent(context)
        } else {
            startPickerPendingIntent(context)
        }
        views.setOnClickPendingIntent(R.id.widget_action, actionIntent)
        return views
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startPickerPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetStartActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_START,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AevumWidgetProvider::class.java).apply {
            action = AevumWidgetProvider.ACTION_WIDGET_STOP
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Timer-Format: unter 1 h „MM:SS", darüber „H:MM:SS". Bewusst mit
     * Sekunden — das Widget soll leben (Devon: „wie lange").
     */
    fun formatTimer(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private const val REQUEST_OPEN = 2301
    private const val REQUEST_START = 2302
    private const val REQUEST_STOP = 2303
}

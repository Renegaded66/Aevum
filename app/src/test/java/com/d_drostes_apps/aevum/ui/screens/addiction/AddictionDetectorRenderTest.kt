package com.d_drostes_apps.aevum.ui.screens.addiction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.apps.InstalledApp
import com.d_drostes_apps.aevum.data.model.AddictionWatchApp
import com.d_drostes_apps.aevum.domain.addiction.AddictionAnalytics
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * M27: Rendert die ECHTE Sucht-Detektor-Seite (zustandsgetrieben, ohne
 * ViewModel) in ein PNG. Kein Emulator nötig — dadurch werden Überläufe,
 * leere Karten und die Hierarchie sichtbar, die ein Widget-Baum nicht zeigt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AddictionDetectorRenderTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val zone = ZoneId.systemDefault()
    private val ig = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"

    private fun shot(name: String, state: AddictionDetectorUiState, height: Int = 2400) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity).apply {
            setContent {
                AevumTheme(darkTheme = true) {
                    AddictionDetectorContent(
                        state = state,
                        onRangeChange = {},
                        onRefresh = {},
                        onLoadInstalledApps = {},
                        onAddApps = {},
                        onRemoveApp = {}
                    )
                }
            }
        }
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        val width = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        view.draw(canvas)
        val outDir = File("build/screenshots").apply { mkdirs() }
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun watched() = listOf(
        AddictionWatchApp(ig, "Instagram", 1_700_000_000_000, 0),
        AddictionWatchApp(tiktok, "TikTok", 1_700_000_000_000, 1)
    )

    private fun session(pkg: String, date: LocalDate, hour: Int, minute: Int, minutes: Int): AddictionAnalytics.Session {
        val start = date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
        return AddictionAnalytics.Session(pkg, start, start + minutes * 60_000L)
    }

    private fun filledAnalysis(): AddictionAnalytics.Result {
        val today = LocalDate.now(zone)
        val sessions = buildList {
            for (i in 1..7) {
                add(session(ig, today.minusDays(i.toLong()), 19, 0, 70 + i * 12))
                add(session(tiktok, today.minusDays(i.toLong()), 20, 30, 25 + i * 5))
            }
            add(session(ig, today, 9, 12, 12))
            add(session(ig, today, 12, 35, 5))
            add(session(ig, today, 18, 5, 25))
            add(session(tiktok, today, 8, 10, 10))
        }
        return AddictionAnalytics.analyze(
            watchedApps = watched().map { AddictionAnalytics.WatchedApp(it.packageName, it.appLabel) },
            sessions = sessions,
            anchorDate = today,
            zoneId = zone,
            range = AddictionAnalytics.Range.DAYS_7
        )
    }

    @Test
    fun `gefuellte-Seite-rendert`() {
        shot(
            "addiction-detector-filled",
            AddictionDetectorUiState(
                hasPermission = true,
                watchedApps = watched(),
                analysis = filledAnalysis(),
                availableApps = listOf(
                    InstalledApp(ig, "Instagram", null),
                    InstalledApp(tiktok, "TikTok", null)
                ),
                loading = false
            ),
            height = 4200
        )
    }

    @Test
    fun `leere-Seite-rendert`() {
        val today = LocalDate.now(zone)
        shot(
            "addiction-detector-empty",
            AddictionDetectorUiState(
                hasPermission = true,
                watchedApps = emptyList(),
                analysis = AddictionAnalytics.analyze(
                    watchedApps = emptyList(),
                    sessions = emptyList(),
                    anchorDate = today,
                    zoneId = zone,
                    range = AddictionAnalytics.Range.DAYS_7
                ),
                availableApps = listOf(
                    InstalledApp(ig, "Instagram", null),
                    InstalledApp(tiktok, "TikTok", null)
                ),
                loading = false
            )
        )
    }
}

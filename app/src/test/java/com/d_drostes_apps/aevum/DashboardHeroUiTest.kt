package com.d_drostes_apps.aevum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.ui.screens.dashboard.DASHBOARD_HERO_GAUGE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.DASHBOARD_HERO_STATUS_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.DASHBOARD_HERO_TIMELINE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.DailyQualityPoint
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardFlowSegment
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroBackground
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroContent
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroFallbackHeight
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardSheet
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardUiState
import com.d_drostes_apps.aevum.ui.screens.dashboard.QualityTrendCard
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M19.4: UI-Evidenz für den Dashboard-Hero 2.0 (Robolectric rendert die
 * ECHTE Compose-Hierarchie, kein Mock).
 *
 * Geprüft wird die Skizzen-Struktur als Verhalten:
 *  1. Die drei Zeit-Blöcke (Erfasst/Schlaf/Bildschirm) sind da, die
 *     Werte sind nach der Zähler-Animation final (5h 10m …).
 *  2. Das Gauge trägt die Güte-Zahl (75) als Text und ist klickbar
 *     (öffnet später den Güte-Dialog).
 *  3. Die Tagesleiste ist klickbar (öffnet die Timeline).
 *  4. Der Aufnahme-Status stimmt je Zustand: live „Recording" + Timer,
 *     pausiert „Paused", idle „Ready to record" + Tagesfortschritt.
 *  5. Der Zonen-Chip zeigt den Geofence-Namen bzw. „Away".
 *
 * Die Uhr läuft manuell (autoAdvance = false, feste 2 s): Die
 * Endlos-Animationen (Schimmer, Puls) machen sonst jede Assertion
 * zeitabhängig — mit fixem Zeitpunkt sind die Screenshots UND die
 * Wert-Assertions deterministisch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardHeroUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val ctx
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun sampleState(
        quality: Int = 75,
        headline: String = "Vor allem Arbeit (3h 20m)"
    ) = DashboardUiState(
        headline = headline,
        totalTrackedMs = 5L * 3_600_000 + 10 * 60_000,
        lastSleepDurationMs = 7L * 3_600_000,
        digitalScreenTimeMs = 2L * 3_600_000,
        digitalScreenTimeFormatted = "2h",
        qualityScore = quality,
        dayProgress = 0.63f,
        flowSegments = listOf(
            DashboardFlowSegment(
                id = "1", title = "Deep Work", categoryName = "Arbeit",
                categoryId = "work", startMinute = 8 * 60, endMinute = 11 * 60,
                timeRange = "08:00–11:00", duration = "3h", isCurrent = false
            ),
            DashboardFlowSegment(
                id = "2", title = "Sport", categoryName = "Sport",
                categoryId = "sport", startMinute = 18 * 60, endMinute = 19 * 60,
                timeRange = "18:00–19:00", duration = "1h", isCurrent = true
            )
        ),
        currentMinute = 16 * 60
    )

    private fun render(
        state: DashboardUiState = sampleState(),
        isLive: Boolean = false,
        isPaused: Boolean = false,
        liveTitle: String = "",
        liveActiveMs: Long = 0L,
        zone: CurrentZoneProvider.ZoneInfo? = null,
        onQuality: () -> Unit = {},
        onTimeline: () -> Unit = {}
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.testTag(HERO_SHOT_ROOT)) {
                        DashboardHeroContent(
                            state = state,
                            isLive = isLive,
                            isPaused = isPaused,
                            liveTitle = liveTitle,
                            liveActiveMs = liveActiveMs,
                            currentZone = zone,
                            onQualityClick = onQuality,
                            onOpenTimeline = onTimeline,
                            onPreviousDay = {},
                            onNextDay = {},
                            onResetToToday = {},
                            onDateClick = {}
                        )
                    }
                }
            }
        }
        // Feste 2 s: Kaskade + Zähler sicher fertig, Endlos-Effekte an
        // einem deterministischen Punkt.
        composeRule.mainClock.advanceTimeBy(2_000)
    }

    @Test
    fun tilesShowAllThreeBlocksWithFinalValues() {
        render()
        composeRule.onNodeWithText(ctx.getString(R.string.common_captured)).assertExists()
        composeRule.onNodeWithText(ctx.getString(R.string.common_sleep)).assertExists()
        composeRule.onNodeWithText(ctx.getString(R.string.dashboard_screen)).assertExists()
        // Zähler-Animation ist nach 2 s fertig → finaler Wert.
        composeRule.onNodeWithText("5h 10m").assertExists()
        composeRule.onNodeWithText("7h").assertExists()
    }

    @Test
    fun gaugeShowsScoreAndIsClickable() {
        var clicked = false
        render(onQuality = { clicked = true })
        composeRule.onNodeWithTag(DASHBOARD_HERO_GAUGE_TEST_TAG).assertTextContains("75")
        composeRule.onNodeWithTag(DASHBOARD_HERO_GAUGE_TEST_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(200)
        assertThat(clicked).isTrue()
    }

    @Test
    fun timelineStripIsClickable() {
        var opened = false
        render(onTimeline = { opened = true })
        composeRule.onNodeWithTag(DASHBOARD_HERO_TIMELINE_TEST_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(200)
        assertThat(opened).isTrue()
    }

    @Test
    fun liveStateShowsRecordingBarWithRunningTimer() {
        render(
            isLive = true,
            isPaused = false,
            liveTitle = "Deep Work",
            liveActiveMs = 2L * 3_600_000 + 25 * 60_000 + 7_000
        )
        composeRule.onNodeWithTag(DASHBOARD_HERO_STATUS_TEST_TAG).assertExists()
        composeRule.onNodeWithText(ctx.getString(R.string.dashboard_recording)).assertExists()
        composeRule.onNodeWithText("02:25:07").assertExists()
        composeRule.onNodeWithText("Deep Work").assertExists()
    }

    @Test
    fun pausedStateShowsPausedBar() {
        render(isLive = true, isPaused = true, liveTitle = "Deep Work", liveActiveMs = 90_000)
        composeRule.onNodeWithText(ctx.getString(R.string.dashboard_paused)).assertExists()
        composeRule.onNodeWithText("00:01:30").assertExists()
    }

    @Test
    fun idleStateShowsReadyBarWithDayProgress() {
        render()
        composeRule.onNodeWithText(ctx.getString(R.string.dashboard_recording_ready)).assertExists()
        composeRule.onNodeWithText(ctx.getString(R.string.dashboard_day_progress, 63)).assertExists()
    }

    @Test
    fun zoneChipShowsGeofenceName() {
        val zone = CurrentZoneProvider.ZoneInfo(
            geofence = PlaceGeofence(
                id = "g1", name = "Zuhause",
                latitude = 52.0, longitude = 13.0, radiusMeters = 120f
            ),
            distanceMeters = 25.0,
            updatedAt = 0L
        )
        render(zone = zone)
        composeRule.onNodeWithText("Zuhause").assertExists()
    }

    @Test
    fun zoneChipShowsAbsentWithoutZone() {
        render(zone = null)
        composeRule.onNodeWithText(ctx.getString(R.string.component_zone_absent)).assertExists()
    }

    /**
     * M19.4: Erzeugt die Sichtprüfungs-Screenshots der ECHTEN Komponenten
     * für die Ablage im Build-Output. Die Tests schlagen fehl, wenn nichts
     * Brauchbares geschrieben wurde — die Screenshots sind Evidenz, kein
     * Nebenprodukt:
     *  - dunkel (Hauptmodus) und hell (Gegenprobe Kontrast),
     *  - zusätzlich „Sheet über Hero": Hero + echtes Dashboard-Sheet mit
     *    Güte-Verlauf und Garmin-Kacheln, damit die Karten-über-Hero-
     *    Optik (Referenzbild) statisch beurteilbar ist.
     */
    @Test
    fun heroScreenshotDark() {
        captureDashboardShot(dark = true, fileName = "dashboard-hero-dark.png", withSheet = false)
    }

    @Test
    fun heroScreenshotLight() {
        captureDashboardShot(dark = false, fileName = "dashboard-hero-light.png", withSheet = false)
    }

    @Test
    fun heroWithSheetScreenshotDark() {
        captureDashboardShot(dark = true, fileName = "dashboard-hero-sheet-dark.png", withSheet = true)
    }

    private fun captureDashboardShot(dark: Boolean, fileName: String, withSheet: Boolean) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (withSheet) {
                            // Deko-Fläche wie im echten Screen: gepinnt
                            // hinter dem Inhalt, Überstand unter dem Hero.
                            DashboardHeroBackground(
                                height = DashboardHeroFallbackHeight,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(HERO_SHOT_ROOT)
                        ) {
                            DashboardHeroContent(
                                state = sampleState(),
                                isLive = true,
                                isPaused = false,
                                liveTitle = "Deep Work",
                                liveActiveMs = 1L * 3_600_000 + 12 * 60_000 + 34_000,
                                currentZone = sampleZone(),
                                onQualityClick = {},
                                onOpenTimeline = {},
                                onPreviousDay = {},
                                onNextDay = {},
                                onResetToToday = {},
                                onDateClick = {}
                            )
                            if (withSheet) {
                                Spacer(Modifier.height(AevumSpacing.md))
                                DashboardSheet {
                                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                                        QualityTrendCard(trend = sampleTrend())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        // captureToImage scheidet aus: es rendert über PixelCopy, das es
        // unter Robolectric nicht gibt. Stattdessen den echten
        // View-Baum auf einen Software-Canvas zeichnen — im
        // NATIVE-Grafikmodus sind das echte Pixel (Roborazzi-Prinzip).
        val view = composeRule.activity.window.decorView
        assertThat(view.width).isGreaterThan(0)
        assertThat(view.height).isGreaterThan(0)
        val full = Bitmap.createBitmap(
            view.width,
            view.height,
            Bitmap.Config.ARGB_8888
        )
        composeRule.runOnUiThread {
            view.draw(android.graphics.Canvas(full))
        }
        // Auf den tatsächlich gefüllten Bereich zuschneiden (Hero bzw.
        // Hero + Sheet) — die Test-Fensterhöhe ist höher.
        val bounds = composeRule.onNodeWithTag(HERO_SHOT_ROOT).fetchSemanticsNode().boundsInRoot
        val cropHeight = (bounds.bottom.toInt() + 24).coerceIn(1, full.height)
        val bitmap = Bitmap.createBitmap(full, 0, 0, full.width, cropHeight)
        val dir = File("build/reports/dashboard-hero").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        println("HERO-SCREENSHOT: ${file.absolutePath} (${file.length()} bytes, ${bitmap.width}x${bitmap.height})")
        // Ein leerer/uniformer Render komprimiert auf wenige KB — echte
        // Pixel mit Text/Gradient liegen deutlich darüber.
        assertThat(file.length()).isGreaterThan(10_000L)
    }

    private fun sampleZone() = CurrentZoneProvider.ZoneInfo(
        geofence = PlaceGeofence(
            id = "g1", name = "Zuhause",
            latitude = 52.0, longitude = 13.0, radiusMeters = 120f
        ),
        distanceMeters = 25.0,
        updatedAt = 0L
    )

    private fun sampleTrend(): List<DailyQualityPoint> = listOf(
        DailyQualityPoint("Sa", 58, 6L * 3_600_000),
        DailyQualityPoint("So", 44, 4L * 3_600_000),
        DailyQualityPoint("Mo", 70, 7L * 3_600_000),
        DailyQualityPoint("Di", 62, 5L * 3_600_000),
        DailyQualityPoint("Mi", 78, 8L * 3_600_000),
        DailyQualityPoint("Do", 66, 6L * 3_600_000),
        DailyQualityPoint("Fr", 75, 7L * 3_600_000)
    )

    private companion object {
        const val HERO_SHOT_ROOT = "dashboard-hero-shot-root"
    }
}

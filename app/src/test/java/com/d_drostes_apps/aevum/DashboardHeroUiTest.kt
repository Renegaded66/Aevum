package com.d_drostes_apps.aevum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.ui.screens.dashboard.DASHBOARD_HERO_GAUGE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.DASHBOARD_HERO_MASCOT_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.DASHBOARD_HERO_RECORDING_SLOT_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.DASHBOARD_HERO_TIMELINE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.dashboard.DailyQualityPoint
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardFlowSegment
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroBackground
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroContent
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroFallbackHeight
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroMetrics
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroPeek
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardSheet
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardUiState
import com.d_drostes_apps.aevum.ui.screens.dashboard.LiveActivityBanner
import com.d_drostes_apps.aevum.ui.screens.dashboard.QualityTrendCard
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M19.4: UI-Evidenz für den Dashboard-Hero 2.0 (Robolectric rendert die
 * ECHTE Compose-Hierarchie, kein Mock).
 * M19.5: Angepasst an Pinning/Maskottchen/Banner-im-Hero —
 *  - Aufnahme-Slot unter dem Gauge: live → Blubber-Banner („Recording" +
 *    Titel), pausiert → „Paused", idle → „Ready to record" + Fortschritt.
 *  - Maskottchen-Modus: Wesen statt großer Güte-Zahl erscheint NUR bei
 *    aktivierter Konfiguration; Default ist aus.
 *  - Nachtschlaf: stateDescription des Wesens wechselt zwischen „schläft"
 *    und „ist wach" — live übersteuert den Schlaf (wechselnd).
 *
 * Die Uhr läuft manuell (autoAdvance = false, feste 2 s): Die
 * Endlos-Animationen (Schimmer, Puls, Blinzeln) machen sonst jede
 * Assertion zeitabhängig — mit fixem Zeitpunkt sind die Screenshots UND
 * die Wert-Assertions deterministisch.
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
        currentMinute: Int = 16 * 60
    ) = DashboardUiState(
        headline = "Vor allem Arbeit (3h 20m)",
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
        displayedDate = LocalDate.now(),
        currentMinute = currentMinute
    )

    /** Laufende Session: activeMs(nowMs = 0) = 02:25:07. */
    private fun runningSample() = LiveActivityState.Running(
        sessionId = "s1",
        title = "Deep Work",
        categoryId = null,
        startAt = -(2L * 3_600_000 + 25 * 60_000 + 7_000),
        totalPausedMs = 0L,
        note = null
    )

    /** Pausierte Session: Timer steht bei 00:01:30. */
    private fun pausedSample() = LiveActivityState.Paused(
        sessionId = "s1",
        title = "Deep Work",
        categoryId = null,
        startAt = -90_000L,
        totalPausedMs = 0L,
        pauseStartedAt = 0L,
        note = null
    )

    private fun mascotEnabled() = MascotRepository.MascotConfig(
        enabled = true,
        colorId = "ice",
        earStyle = MascotRepository.EARS_ROUND,
        cheeks = true
    )

    private fun render(
        state: DashboardUiState = sampleState(),
        liveState: LiveActivityState = LiveActivityState.Idle,
        nowMs: Long = 0L,
        mascot: MascotRepository.MascotConfig = MascotRepository.MascotConfig(),
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
                            liveState = liveState,
                            nowMs = nowMs,
                            currentZone = zone,
                            mascot = mascot,
                            onQualityClick = onQuality,
                            onOpenTimeline = onTimeline,
                            onPauseLive = {},
                            onResumeLive = {},
                            onStopLive = {},
                            onSwitchLive = { _, _ -> },
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
    fun mascotIsAbsentByDefault() {
        // Default aus ⇒ exakt altes Verhalten: keine Kreatur im Gauge.
        render()
        composeRule.onNodeWithTag(DASHBOARD_HERO_MASCOT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun mascotAppearsInGaugeWhenEnabled() {
        render(mascot = mascotEnabled())
        composeRule.onNodeWithTag(DASHBOARD_HERO_MASCOT_TEST_TAG).assertExists()
        // Die kleine Zahl-Zeile als Datenanker bleibt ablesbar.
        composeRule.onNodeWithTag(DASHBOARD_HERO_GAUGE_TEST_TAG).assertTextContains("75")
    }

    @Test
    fun mascotSleepsAtNightWhileIdle() {
        // 23:00, nichts läuft → das Wesen schläft (22–6-Regel).
        render(state = sampleState(currentMinute = 23 * 60), mascot = mascotEnabled())
        composeRule
            .onNodeWithTag(DASHBOARD_HERO_MASCOT_TEST_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    ctx.getString(R.string.mascot_state_sleeping)
                )
            )
    }

    @Test
    fun recordingOverridesNightSleepLive() {
        // 23:00, ABER es läuft eine Aufzeichnung → wach („wechselnd").
        render(
            state = sampleState(currentMinute = 23 * 60),
            liveState = runningSample(),
            mascot = mascotEnabled()
        )
        composeRule
            .onNodeWithTag(DASHBOARD_HERO_MASCOT_TEST_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    ctx.getString(R.string.mascot_state_awake)
                )
            )
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
    fun liveStateShowsBannerInRecordingSlot() {
        // M19.5: Der Blubber-Banner wohnt jetzt IM HERO unter dem Gauge.
        render(liveState = runningSample())
        composeRule.onNodeWithTag(DASHBOARD_HERO_RECORDING_SLOT_TEST_TAG).assertExists()
        composeRule.onNodeWithText(ctx.getString(R.string.dashboard_recording)).assertExists()
        composeRule.onNodeWithText("Deep Work").assertExists()
    }

    @Test
    fun pausedStateShowsPausedBanner() {
        render(liveState = pausedSample())
        composeRule.onNodeWithText(ctx.getString(R.string.dashboard_paused)).assertExists()
        composeRule.onNodeWithText("Deep Work").assertExists()
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
     * M19.4/M19.5: Erzeugt die Sichtprüfungs-Screenshots der ECHTEN
     * Komponenten für die Ablage im Build-Output. Die Tests schlagen fehl,
     * wenn nichts Brauchbares geschrieben wurde — die Screenshots sind
     * Evidenz, kein Nebenprodukt:
     *  - dunkel (Hauptmodus) und hell (Gegenprobe Kontrast),
     *  - „Sheet über Hero" (Karten-über-Hero-Optik, statisch beurteilbar),
     *  - Maskottchen wach (mit Live-Banner) und schlafend (nachts, idle),
     *  - der Banner einzeln (Emoji-Zentrierung prüfbar).
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

    @Test
    fun heroMascotScreenshotDark() {
        captureDashboardShot(
            dark = true,
            fileName = "dashboard-hero-mascot-dark.png",
            withSheet = false,
            mascot = mascotEnabled()
        )
    }

    @Test
    fun heroMascotSleepingScreenshotDark() {
        captureDashboardShot(
            dark = true,
            fileName = "dashboard-hero-mascot-sleeping-dark.png",
            withSheet = false,
            mascot = mascotEnabled(),
            live = false,
            currentMinute = 23 * 60
        )
    }

    @Test
    fun bannerScreenshotShowsCenteredGlyphs() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AevumSpacing.md)
                            .testTag(BANNER_SHOT_ROOT)
                    ) {
                        LiveActivityBanner(
                            state = runningSample(),
                            nowMs = 0L,
                            onPause = {},
                            onResume = {},
                            onStop = {},
                            onSwitch = { _, _ -> },
                            activityTypes = emptyList()
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        captureTaggedAreaToFile(BANNER_SHOT_ROOT, "banner-buttons.png", minBytes = 5_000L)
    }

    /**
     * M19.5-DYNAMIK als ECHTER Integrationstest: In einer realen
     * LazyColumn (Hero-Item + Sheet-Item — exakt die Verdrahtung aus
     * DashboardScreen: onSizeChanged misst die Höhe, graphicsLayer
     * kompensiert den Scroll über DashboardHeroMetrics) bleibt der Hero
     * beim Scrollen GEPINNT (Bildschirmposition unverändert), während das
     * Sheet-Item darüber nach oben wandert.
     */
    @Test
    fun heroStaysPinnedWhileSheetSlidesOver() {
        lateinit var listState: LazyListState
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    listState = rememberLazyListState()
                    val heroHeightState = remember { mutableStateOf(0) }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item(key = "hero") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { heroHeightState.value = it.height }
                                    .graphicsLayer {
                                        translationY = DashboardHeroMetrics.heroStickyTranslation(
                                            offsetPx = listState.firstVisibleItemScrollOffset,
                                            heroHeightPx = heroHeightState.value,
                                            belowHero = listState.firstVisibleItemIndex > 0
                                        ).toFloat()
                                    }
                                    .testTag(PIN_HERO)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(700.dp)
                                        .background(Color(0xFF123456))
                                )
                            }
                        }
                        item(key = "sheet") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1500.dp)
                                    .background(Color(0xFF654321))
                                    .testTag(PIN_SHEET)
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val heroTopBefore = composeRule
            .onNodeWithTag(PIN_HERO)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertThat(heroTopBefore).isWithin(4f).of(0f)
        // Um 400dp scrollen (dieselbe Mechanik wie in der App).
        val scrollPx = with(composeRule.density) { 400.dp.toPx() }
        composeRule.runOnIdle {
            runBlocking { listState.scrollBy(scrollPx) }
        }
        composeRule.waitForIdle()
        val heroTop = composeRule
            .onNodeWithTag(PIN_HERO)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val sheetTop = composeRule
            .onNodeWithTag(PIN_SHEET)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        // Der Hero steht EXAKT still (Kompensation = Scroll-Offset) …
        assertThat(heroTop).isWithin(4f).of(0f)
        // … während das Sheet-Item sichtbar nach oben gerutscht ist.
        val heroHeightPx = with(composeRule.density) { 700.dp.toPx() }
        assertThat(sheetTop).isLessThan(heroHeightPx - scrollPx + 100f)
    }

    private fun captureDashboardShot(
        dark: Boolean,
        fileName: String,
        withSheet: Boolean,
        mascot: MascotRepository.MascotConfig = MascotRepository.MascotConfig(),
        live: Boolean = true,
        currentMinute: Int = 16 * 60
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AevumTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // M19.5: Die Deko-Fläche misst sich am ECHTEN Hero
                        // (+ Überstand), damit der Farbbereich wie in der
                        // App neben den Sheet-Ecken/-Rändern hervorschimmert
                        // — vorher stand hier die zu klein gewordene
                        // Fallback-Höhe und der Überstand war unsichtbar.
                        val shotHeroHeightPx = remember { mutableStateOf(0) }
                        val shotDensity = LocalDensity.current
                        if (withSheet) {
                            DashboardHeroBackground(
                                height = if (shotHeroHeightPx.value > 0) {
                                    with(shotDensity) {
                                        shotHeroHeightPx.value.toDp() + DashboardHeroPeek
                                    }
                                } else {
                                    DashboardHeroFallbackHeight
                                },
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { shotHeroHeightPx.value = it.size.height }
                                .testTag(HERO_SHOT_ROOT)
                        ) {
                            DashboardHeroContent(
                                state = sampleState(currentMinute = currentMinute),
                                liveState = if (live) runningSample() else LiveActivityState.Idle,
                                nowMs = 0L,
                                currentZone = sampleZone(),
                                mascot = mascot,
                                onQualityClick = {},
                                onOpenTimeline = {},
                                onPauseLive = {},
                                onResumeLive = {},
                                onStopLive = {},
                                onSwitchLive = { _, _ -> },
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
        captureTaggedAreaToFile(HERO_SHOT_ROOT, fileName, minBytes = 10_000L)
    }

    /**
     * captureToImage scheidet aus: es rendert über PixelCopy, das es unter
     * Robolectric nicht gibt. Stattdessen den echten View-Baum auf einen
     * Software-Canvas zeichnen — im NATIVE-Grafikmodus sind das echte
     * Pixel (Roborazzi-Prinzip); auf die getaggte Fläche zuschneiden.
     */
    private fun captureTaggedAreaToFile(rootTag: String, fileName: String, minBytes: Long) {
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
        val bounds = composeRule.onNodeWithTag(rootTag).fetchSemanticsNode().boundsInRoot
        val cropBottom = (bounds.bottom.toInt() + 24).coerceIn(1, full.height)
        val bitmap = Bitmap.createBitmap(full, 0, 0, full.width, cropBottom)
        val dir = File("build/reports/dashboard-hero").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        println("HERO-SCREENSHOT: ${file.absolutePath} (${file.length()} bytes, ${bitmap.width}x${bitmap.height})")
        // Ein leerer/uniformer Render komprimiert auf wenige KB — echte
        // Pixel mit Text/Gradient liegen deutlich darüber.
        assertThat(file.length()).isGreaterThan(minBytes)
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
        const val BANNER_SHOT_ROOT = "dashboard-banner-shot-root"
        const val PIN_HERO = "dashboard-pin-hero"
        const val PIN_SHEET = "dashboard-pin-sheet"
    }
}

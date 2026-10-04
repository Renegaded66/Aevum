package com.d_drostes_apps.aevum

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.ui.screens.activitystats.ACTIVITY_STATS_BACK_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.activitystats.ACTIVITY_STATS_CHART_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.activitystats.ACTIVITY_STATS_EMPTY_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsAnalytics
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsContent
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsRange
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsUiModel
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsUiState
import com.d_drostes_apps.aevum.ui.screens.activitystats.activityStatsRangeTestTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.146: Verdrahtung der Aktivitäts-Statistikseite — gerendert wird der
 * ECHTE zustandsfreie Rumpf ([ActivityStatsContent]) mit einem vorgegebenen
 * Zustand; geprüft wird die tatsächliche Compose-Hierarchie.
 *
 * WARUM DIESE EBENE: Die Analytik-Suite deckt die Zahlen, diese Suite den
 * Weg des Nutzers ab — Leerzustand statt leerem Chart, Umschalter wechselt
 * Granularität, Zurück-Knopf meldet, und (Kern des Auftrags) der Verlauf
 * erscheint NUR, wenn es Daten gibt. Das Muster ist dasselbe wie bei
 * [InsightsScreenWiringTest]: der Screen-Rumpf ohne Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityStatsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 10, 4)

    // ---------------------------------------------------------------
    // Leerzustand: keine Aufzeichnungen → Empty-State, KEIN Chart
    // ---------------------------------------------------------------

    @Test
    fun emptyActivityShowsEmptyStateAndNoChart() {
        val emptyModel = ActivityStatsAnalytics.build(
            sessions = emptyList(),
            activityTypeId = "meal",
            range = ActivityStatsRange.Days30,
            today = today,
            zoneId = zone
        )
        composeRule.setContent {
            ActivityStatsContent(
                state = ActivityStatsUiState(
                    activityTypeId = "meal",
                    activityName = "Essen",
                    activityIcon = "🍽",
                    activityColor = 0xFFF59E0BL,
                    model = emptyModel
                )
            )
        }

        composeRule.onNodeWithTag(ACTIVITY_STATS_EMPTY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ACTIVITY_STATS_CHART_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Essen").assertIsDisplayed()
    }

    // ---------------------------------------------------------------
    // Mit Daten: Chart erscheint + Umschalter schaltet um
    // ---------------------------------------------------------------

    @Test
    fun activityWithDataShowsTheChart() {
        val model = modelWithOneSession()
        composeRule.setContent {
            ActivityStatsContent(
                state = ActivityStatsUiState(
                    activityTypeId = "meal",
                    activityName = "Essen",
                    activityIcon = "🍽",
                    model = model
                )
            )
        }

        composeRule.onNodeWithTag(ACTIVITY_STATS_CHART_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ACTIVITY_STATS_EMPTY_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun rangeToggleReportsSelection() {
        var selected: ActivityStatsRange? = null
        composeRule.setContent {
            ActivityStatsContent(
                state = ActivityStatsUiState(
                    activityTypeId = "meal",
                    activityName = "Essen",
                    model = modelWithOneSession()
                ),
                onSelectRange = { selected = it }
            )
        }

        composeRule.onNodeWithTag(activityStatsRangeTestTag(ActivityStatsRange.Days30)).performClick()
        assertThat(selected).isEqualTo(ActivityStatsRange.Days30)

        composeRule.onNodeWithTag(activityStatsRangeTestTag(ActivityStatsRange.All)).performClick()
        assertThat(selected).isEqualTo(ActivityStatsRange.All)
    }

    @Test
    fun backButtonReportsClick() {
        var backPressed = false
        composeRule.setContent {
            ActivityStatsContent(
                state = ActivityStatsUiState(
                    activityTypeId = "meal",
                    activityName = "Essen",
                    model = modelWithOneSession()
                ),
                onBack = { backPressed = true }
            )
        }

        composeRule.onNodeWithTag(ACTIVITY_STATS_BACK_TEST_TAG).performClick()
        assertThat(backPressed).isTrue()
    }

    /**
     * Alle drei Granularitäten rendern den Chart — ein leeres Bucket-Array
     * (bei vorhandenen Daten) würde hier auffallen. Die Modelle kommen aus
     * der ECHTEN Analytik, nicht aus handgebauten Listen.
     */
    @Test
    fun allThreeRangesRenderTheChart() {
        val sessions = listOf(
            session("a", daysAgo = 1, startHour = 8, durationMinutes = 60),
            session("b", daysAgo = 2, startHour = 8, durationMinutes = 30)
        )
        // Die drei Granularitaeten werden in EINEM setContent gerendert
        // (die Compose-Testregel erlaubt nur einen Aufruf pro Test) —
        // umgeschaltet wird ueber den echten State des Screens.
        var range by mutableStateOf(ActivityStatsRange.Days30)
        composeRule.setContent {
            val model = ActivityStatsAnalytics.build(sessions, "meal", range, today, zone)
            ActivityStatsContent(
                state = ActivityStatsUiState(
                    activityTypeId = "meal",
                    activityName = "Essen",
                    model = model
                ),
                onSelectRange = { range = it }
            )
        }

        ActivityStatsRange.entries.forEach { candidate ->
            composeRule.onNodeWithTag(activityStatsRangeTestTag(candidate)).performClick()
            composeRule.waitForIdle()
            assertThat(range).isEqualTo(candidate)
            composeRule.onNodeWithTag(ACTIVITY_STATS_CHART_TEST_TAG).assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------
    // Helfer
    // ---------------------------------------------------------------

    private fun modelWithOneSession(): ActivityStatsUiModel {
        val sessions = listOf(session("a", daysAgo = 1, startHour = 8, durationMinutes = 90))
        return ActivityStatsAnalytics.build(sessions, "meal", ActivityStatsRange.Days30, today, zone)
    }

    private fun session(
        id: String,
        daysAgo: Long,
        startHour: Int,
        durationMinutes: Int,
        typeId: String = "meal"
    ): ActivitySession {
        val date = today.minusDays(daysAgo)
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = id,
            title = typeId,
            activityTypeId = typeId,
            categoryId = "food",
            startAt = start,
            endAt = start + durationMinutes * 60_000L
        )
    }
}

package com.d_drostes_apps.aevum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.ui.screens.insights.BalanceMode
import com.d_drostes_apps.aevum.ui.screens.insights.BalanceOption
import com.d_drostes_apps.aevum.ui.screens.insights.BalanceSlice
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_BALANCE_MODE_CATEGORY_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_BALANCE_SELECT_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_CHART_VALUE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_DONUT_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_LIST_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_RADAR_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_RADAR_VALUE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_TREND_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.InsightCard
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsScreenContent
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsUiState
import com.d_drostes_apps.aevum.ui.screens.insights.PeriodChange
import com.d_drostes_apps.aevum.ui.screens.insights.TimeDistributionSlice
import com.d_drostes_apps.aevum.ui.screens.insights.TrendBucket
import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.ui.screens.insights.insightsBalanceOptionTestTag
import com.d_drostes_apps.aevum.ui.screens.insights.insightsDonutLegendTestTag
import com.d_drostes_apps.aevum.ui.screens.insights.insightsRadarChipTestTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M31: Verdrahtung der neuen Diagramme.
 *
 * Die Suite rendert den echten Screen-Rumpf mit einem vollständigen UiState
 * und prüft:
 *  1. die früheren Blöcke „Veränderungen" und Insight-Karten erscheinen NICHT
 *     mehr, obwohl der State sie noch trägt,
 *  2. alle drei Diagramme sind erreichbar,
 *  3. ein Klick auf die Donut-Legende zeigt den zugehörigen Wert in der Mitte,
 *  4. ein Klick auf einen Radar-Chip zeigt den zugehörigen Bereichswert.
 *
 * Punkte 3/4 sichern die eigentliche Interaktion — nicht nur, dass Canvas-
 * Flächen gezeichnet werden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsChartsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val colors = listOf(
        Color(0xFF6366F1), Color(0xFF22C55E), Color(0xFFF59E0B)
    )

    private fun state(): InsightsUiState = InsightsUiState(
        periodLabel = "Heute",
        summary = "Zusammenfassung",
        hasData = true,
        timeDistribution = listOf(
            TimeDistributionSlice("work", "Arbeit", colors[0], 90 * 60_000L, 75),
            TimeDistributionSlice("sport", "Sport", colors[1], 30 * 60_000L, 25)
        ),
        trendBuckets = listOf(
            TrendBucket("00", "00:00–02:00 Uhr", 10 * 60_000L),
            TrendBucket("02", "02:00–04:00 Uhr", 0L),
            TrendBucket("04", "04:00–06:00 Uhr", 30 * 60_000L)
        ),
        balanceMode = BalanceMode.Activity,
        balanceAxes = listOf(
            BalanceSlice("Arbeit", colors[0], 60 * 60_000L, 50),
            BalanceSlice("Sport", colors[1], 30 * 60_000L, 25),
            BalanceSlice("Lesen", colors[2], 30 * 60_000L, 25)
        ),
        balanceOptions = listOf(
            BalanceOption("work", "Arbeit", colors[0], "□", 60 * 60_000L, 40),
            BalanceOption("sport", "Sport", colors[1], "▲", 30 * 60_000L, 20),
            BalanceOption("reading", "Lesen", colors[2], "▤", 30 * 60_000L, 20),
            BalanceOption("learning", "Lernen", colors[0], "✎", 20 * 60_000L, 13),
            BalanceOption("household", "Haushalt", colors[1], "⌂", 10 * 60_000L, 7)
        ),
        balanceSelectedIds = listOf("work", "sport", "reading"),
        // Absichtlich gesetzt: der Screen darf diese Blöcke NICHT mehr zeigen.
        changes = listOf(
            PeriodChange("work", "Arbeit", colors[0], 90 * 60_000L, 60 * 60_000L, 30 * 60_000L, 50)
        ),
        insightCards = listOf(InsightCard("Größter Zeitblock", "Arbeit war heute dein größter Bereich.", "◷"))
    )

    @Test
    fun oldChangesAndInsightBlocksAreNoLongerRendered() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent { InsightsScreenContent(uiState = state()) }

        composeRule.onNodeWithText(context.getString(R.string.insights_changes_title)).assertDoesNotExist()
        composeRule.onNodeWithText("Größter Zeitblock").assertDoesNotExist()
        composeRule.onNodeWithText("Arbeit war heute dein größter Bereich.").assertDoesNotExist()
    }

    @Test
    fun allThreeChartsAreReachable() {
        composeRule.setContent { InsightsScreenContent(uiState = state()) }

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(INSIGHTS_DONUT_TEST_TAG))
        composeRule.onNodeWithTag(INSIGHTS_DONUT_TEST_TAG).assertExists()

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(INSIGHTS_TREND_TEST_TAG))
        composeRule.onNodeWithTag(INSIGHTS_TREND_TEST_TAG).assertExists()

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(INSIGHTS_RADAR_TEST_TAG))
        composeRule.onNodeWithTag(INSIGHTS_RADAR_TEST_TAG).assertExists()
    }

    @Test
    fun tappingDonutLegendShowsThatSlicesValueInTheCenter() {
        composeRule.setContent { InsightsScreenContent(uiState = state()) }

        // Standard: Gesamtsumme (2h 0m).
        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(INSIGHTS_CHART_VALUE_TEST_TAG))
        composeRule.onNodeWithTag(INSIGHTS_CHART_VALUE_TEST_TAG, useUnmergedTree = true)
            .assertTextEquals("2h 0m")

        // Sport-Segment (30m) über die Legende auswählen.
        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(insightsDonutLegendTestTag(1)))
        composeRule.onNodeWithTag(insightsDonutLegendTestTag(1)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(INSIGHTS_CHART_VALUE_TEST_TAG, useUnmergedTree = true)
            .assertTextEquals("30m")
    }

    @Test
    fun tappingRadarChipShowsAreaValue() {
        composeRule.setContent { InsightsScreenContent(uiState = state()) }

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(insightsRadarChipTestTag(0)))
        composeRule.onNodeWithTag(insightsRadarChipTestTag(0)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(INSIGHTS_RADAR_VALUE_TEST_TAG, useUnmergedTree = true)
            .assertTextEquals("1h 0m · 50 %")
    }

    /** M32: Auswahl-Sheet öffnen, zwei fehlende Achsen ergänzen, übernehmen. */
    @Test
    fun choosingBalanceAreasAppliesTheSelection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var saved: List<String> = emptyList()
        composeRule.setContent {
            InsightsScreenContent(
                uiState = state(),
                onSaveBalanceSelection = { saved = it }
            )
        }

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(INSIGHTS_BALANCE_SELECT_TEST_TAG))
        composeRule.onNodeWithTag(INSIGHTS_BALANCE_SELECT_TEST_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(insightsBalanceOptionTestTag(3)).performClick()
        composeRule.onNodeWithTag(insightsBalanceOptionTestTag(4)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.insights_balance_apply)).performClick()
        composeRule.waitForIdle()

        assertThat(saved)
            .containsExactly("work", "sport", "reading", "learning", "household")
            .inOrder()
    }

    /** M32: Der Modus-Chip meldet den Wechsel an das ViewModel. */
    @Test
    fun balanceModeChipCallsTheHandler() {
        var chosen: BalanceMode? = null
        composeRule.setContent {
            InsightsScreenContent(
                uiState = state(),
                onSelectBalanceMode = { chosen = it }
            )
        }

        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(INSIGHTS_BALANCE_MODE_CATEGORY_TEST_TAG))
        composeRule.onNodeWithTag(INSIGHTS_BALANCE_MODE_CATEGORY_TEST_TAG).performClick()

        assertThat(chosen).isEqualTo(BalanceMode.Category)
    }
}

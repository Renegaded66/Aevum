package com.d_drostes_apps.aevum

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.ui.screens.insights.BreakdownMode
import com.d_drostes_apps.aevum.ui.screens.insights.TOP_ACTIVITIES_TOGGLE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.TopActivitiesCard
import com.d_drostes_apps.aevum.ui.screens.insights.TopActivitySlice
import com.d_drostes_apps.aevum.ui.screens.insights.topActivitiesRowTestTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M18.137 (Kanban t_70a06809): UI-Evidenz fuer den Icon-Toggle der
 * Top-Aktivitäten.
 *
 * Geprüft wird die tatsächliche Compose-Hierarchie (Robolectric rendert die
 * echte App-Komponente, kein Mock):
 *
 *  1. Zugeklappt sind GENAU 5 Zeilen gerendert — Zeile 6 und 7 existieren nicht.
 *  2. Ein Klick auf den Toggle zeigt alle Zeilen (Ausklappen funktioniert).
 *  3. Ein zweiter Klick bringt es auf 5 zurück (Zuklappen funktioniert).
 *  4. Der Toggle ist ein reiner Icon-Button: er trägt KEINEN sichtbaren Text
 *     (weder "Ausklappen" noch "Alle Aktivitäten anzeigen" erscheinen als
 *     Textknoten), aber sehr wohl ein Screenreader-Label.
 *  5. Der Zustand wird als Toggleable-State gemeldet (Compose-Gegenstueck zu
 *     aria-expanded) und wechselt beim Klick On/Off.
 *  6. Bei <= 5 Einträgen gibt es gar keinen Toggle.
 *
 * Warum Robolectric statt eines Geräts: dieser Host hat kein /dev/kvm, ein
 * Emulator-Lauf ist also nicht möglich. Die Suite prüft darum die echte
 * Compose-Semantik-Baum-Struktur, die identisch zu dem ist, was TalkBack auf
 * einem Gerät liest.
 */
@RunWith(RobolectricTestRunner::class)
// M18.137: KEIN `manifest = Config.NONE` — die Compose-Test-Rule startet eine
// echte ComponentActivity, die nur im gemergten Manifest steht
// (ui-test-manifest, debugImplementation).
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopActivitiesToggleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val colors = listOf(
        Color(0xFF6366F1), Color(0xFF22C55E), Color(0xFF64748B),
        Color(0xFFEC4899), Color(0xFFF59E0B), Color(0xFF14B8A6),
        Color(0xFF8B5CF6)
    )

    private fun slices(count: Int): List<TopActivitySlice> =
        (1..count).map { i ->
            TopActivitySlice(
                id = "type$i",
                label = "Aktivität $i",
                color = colors[(i - 1) % colors.size],
                durationMs = (10 - i).coerceAtLeast(1) * 60_000L,
                percent = 10,
                icon = "•"
            )
        }

    @Test
    fun collapsedShowsExactlyFiveRows() {
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Activity,
                items = slices(7),
                expanded = false,
                onToggleExpanded = {}
            )
        }

        for (i in 0..4) {
            composeRule.onNodeWithTag(topActivitiesRowTestTag(i)).assertIsDisplayed()
        }
        // Zeile 6 und 7 (Index 5, 6) sind zugeklappt NICHT vorhanden.
        composeRule.onNodeWithTag(topActivitiesRowTestTag(5)).assertDoesNotExist()
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertDoesNotExist()
    }

    @Test
    fun expandingRevealsAllRows() {
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Activity,
                items = slices(7),
                expanded = true,
                onToggleExpanded = {}
            )
        }

        composeRule.waitForIdle()
        for (i in 0..6) {
            composeRule.onNodeWithTag(topActivitiesRowTestTag(i)).assertIsDisplayed()
        }
    }

    /** Klick-Kette über die echte Komponente: auf → zu → auf. */
    @Test
    fun toggleClickCollapsesAndExpandsTheList() {
        // WICHTIG: Compose-State (mutableStateOf), keine einfache `var` —
        // nur so wird die Komposition beim Klick wirklich invalidiert und
        // der Test prüft den echten Recomposition-Pfad (denselben nimmt der
        // ViewModel-Flow in der App).
        val expanded = mutableStateOf(false)
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Activity,
                items = slices(7),
                expanded = expanded.value,
                onToggleExpanded = { expanded.value = !expanded.value }
            )
        }

        // Start: zugeklappt.
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertDoesNotExist()

        // Aufklappen.
        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertIsDisplayed()

        // Wieder zuklappen.
        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertDoesNotExist()
    }

    /**
     * Der Kern der Nutzer-Anforderung "kein Text, nur ein Icon": der
     * Toggle-Knoten darf KEINEN Text tragen. Sonst wäre es wieder ein
     * Text-Button.
     */
    @Test
    fun toggleHasNoVisibleText() {
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Activity,
                items = slices(7),
                expanded = false,
                onToggleExpanded = {}
            )
        }

        val toggle = composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG)
        toggle.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        // Der sichtbare Kartentitel existiert weiterhin — der Toggle selbst
        // ist textfrei. Den Titel zur Laufzeit aus den Ressourcen holen:
        // Robolectric laeuft je nach Umgebung unter einer anderen Locale
        // (hier en-US → values-en), ein hart kodierter deutscher String
        // waere ein falscher Test.
        val title = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.insights_top_activities)
        composeRule.onNodeWithText(title).assertExists()
    }

    /**
     * aria-expanded-Aequivalent: der Toggle meldet seinen Zustand als
     * ToggleableState und wechselt beim Auf-/Zuklappen.
     */
    @Test
    fun toggleReportsExpandedState() {
        val expanded = mutableStateOf(false)
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Activity,
                items = slices(7),
                expanded = expanded.value,
                onToggleExpanded = { expanded.value = !expanded.value }
            )
        }

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    /** Der Toggle traegt ein Screenreader-Label (Action + Zustand). */
    @Test
    fun toggleCarriesScreenReaderLabels() {
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Activity,
                items = slices(7),
                expanded = false,
                onToggleExpanded = {}
            )
        }

        val node = composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG)
            .fetchSemanticsNode()
        val described = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertThat(described).isNotEmpty()
        val state: String? = node.config.getOrNull(SemanticsProperties.StateDescription)
        assertThat(state ?: "").isNotEmpty()
    }

    /** 5 oder weniger Einträge → nichts auszuklappen → kein Toggle. */
    @Test
    fun noToggleWhenNothingIsHidden() {
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Activity,
                items = slices(5),
                expanded = false,
                onToggleExpanded = {}
            )
        }

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).assertDoesNotExist()
        assertThat(
            composeRule.onAllNodesWithTag(topActivitiesRowTestTag(4)).fetchSemanticsNodes()
        ).hasSize(1)
    }

    /** Kategorie-Ansicht: kein Toggle, alle Zeilen sichtbar. */
    @Test
    fun categoryModeHasNoToggleAndShowsEveryRow() {
        composeRule.setContent {
            TopActivitiesCard(
                mode = BreakdownMode.Category,
                items = slices(8),
                expanded = false,
                onToggleExpanded = {}
            )
        }

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).assertDoesNotExist()
        for (i in 0..7) {
            composeRule.onNodeWithTag(topActivitiesRowTestTag(i)).assertIsDisplayed()
        }
    }
}

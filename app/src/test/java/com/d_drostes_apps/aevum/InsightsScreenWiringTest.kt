package com.d_drostes_apps.aevum

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_ERROR_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_LIST_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_LOADING_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_RETRY_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsScreenContent
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsUiState
import com.d_drostes_apps.aevum.ui.screens.insights.TOP_ACTIVITIES_TOGGLE_TEST_TAG
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
 * M18.137 (Kanban t_386782be): VERDRAHTUNG des Insights-Screens.
 *
 * WARUM DIESE SUITE DER KERN DER KARTE IST
 *
 * Die Vorgaenger-Karten prueften Datenschicht und Karte getrennt — beide
 * gruen, waehrend der Screen die Liste haette kuerzen koennen, ohne dass ein
 * Test rot wird. Genau diese Nahtstelle ist hier abgesichert: gerendert wird
 * der ECHTE Screen-Rumpf ([InsightsScreenContent]) mit einem vorgegebenen
 * UiState, und geprueft wird die tatsaechliche Compose-Hierarchie.
 *
 * Nachweis der Wirksamkeit (RED-Check, dokumentiert im Handoff): mit einem
 * zusaetzlichen `.take(5)` auf `uiState.allBreakdown` im Screen faellt
 * [expandedShowsAllActivitiesFromUiState] um — ohne diese Suite blieb die
 * Mutation unbemerkt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsScreenWiringTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val colors = listOf(
        Color(0xFF6366F1), Color(0xFF22C55E), Color(0xFF64748B),
        Color(0xFFEC4899), Color(0xFFF59E0B), Color(0xFF14B8A6),
        Color(0xFF8B5CF6)
    )

    /** Sieben Aktivitaeten, absteigend — mehr als die 5er-Grenze. */
    private fun slices(count: Int = 7): List<TopActivitySlice> =
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

    private fun contentState(
        expanded: Boolean = false,
        loading: Boolean = false,
        error: String? = null,
        data: List<TopActivitySlice>? = slices()
    ): InsightsUiState = InsightsUiState(
        periodLabel = "Heute",
        summary = "Zusammenfassung",
        allBreakdown = data ?: emptyList(),
        topBreakdown = (data ?: emptyList()).take(5),
        hasData = !(data ?: emptyList()).isEmpty(),
        isLoading = loading,
        errorMessage = error,
        topActivitiesExpanded = expanded
    )

    /**
     * KERN-ZUSICHERUNG: der Screen liest die vollstaendige Liste aus dem
     * UiState. Zugeklappt 5 Zeilen, nach dem Klick alle 7 — die Zeilen 6 und 7
     * existieren vorher nicht und danach schon.
     *
     * HINWEIS ZUM SCROLLEN: die Liste ist eine LazyColumn. In Robolectric ist
     * der Viewport klein, deshalb liegen die hinteren Zeilen nach dem
     * Aufklappen zwar in der Komposition, aber ausserhalb des sichtbaren
     * Bereichs. Geprueft wird darum in zwei Stufen: die Zeile EXISTIERT
     * (das ist die Aussage der Verdrahtung) und sie ist per gezieltem Scroll
     * auch ERREICHBAR (das ist die Aussage der Darstellung). Nur
     * `assertIsDisplayed` ohne Scroll waere ein Testfehler, kein Codefehler.
     */
    @Test
    fun expandedShowsAllActivitiesFromUiState() {
        val state = mutableStateOf(contentState(expanded = false))
        composeRule.setContent {
            InsightsScreenContent(
                uiState = state.value,
                onToggleExpanded = { state.value = state.value.copy(topActivitiesExpanded = !state.value.topActivitiesExpanded) }
            )
        }

        // Zugeklappt: genau 5 Zeilen EXISTIEREN, Zeile 6/7 nicht.
        // (Existenz statt Sichtbarkeit: der Robolectric-Viewport ist klein,
        // die hinteren Zeilen liegen unterhalb des Hero-Headers.)
        for (i in 0..4) {
            composeRule.onNodeWithTag(topActivitiesRowTestTag(i)).assertExists()
        }
        composeRule.onNodeWithTag(topActivitiesRowTestTag(5)).assertDoesNotExist()
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertDoesNotExist()

        // Aufklappen.
        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()

        // Jetzt existieren ALLE sieben Zeilen (Verdrahtung der vollstaendigen Liste).
        for (i in 0..6) {
            composeRule.onNodeWithTag(topActivitiesRowTestTag(i)).assertExists()
        }
        // Und die letzte ist per Scroll auch wirklich sichtbar.
        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(topActivitiesRowTestTag(6)))
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertIsDisplayed()

        // Wieder zuklappen: die Zeilen 6 und 7 verschwinden komplett.
        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG).performScrollToIndex(0)
        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(topActivitiesRowTestTag(5)).assertDoesNotExist()
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertDoesNotExist()
    }

    /** Kaltstart: Ladeplatzhalter sichtbar, keine "Noch keine Daten"-Karte. */
    @Test
    fun coldStartRendersLoadingPlaceholder() {
        composeRule.setContent {
            InsightsScreenContent(uiState = contentState(loading = true, data = null))
        }

        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(INSIGHTS_ERROR_TEST_TAG).assertDoesNotExist()
        val emptyTitle = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.insights_empty_title)
        composeRule.onNodeWithText(emptyTitle).assertDoesNotExist()
    }

    /**
     * Fehlerzustand: Fehlerkarte MIT Text sichtbar — und ausdruecklich NICHT
     * die "Noch keine Daten"-Karte (das war der Zustand vor dieser Karte).
     */
    @Test
    fun errorStateRendersErrorCardNotEmptyState() {
        val message = "Auswertung konnte nicht geladen werden."
        composeRule.setContent {
            InsightsScreenContent(uiState = contentState(error = message, data = null))
        }

        composeRule.onNodeWithTag(INSIGHTS_ERROR_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithTag(INSIGHTS_RETRY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertDoesNotExist()

        val emptyTitle = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.insights_empty_title)
        composeRule.onNodeWithText(emptyTitle).assertDoesNotExist()
    }

    /** Der Retry-Button im Screen ruft den Handler aus dem Screen-Vertrag. */
    @Test
    fun retryButtonCallsTheHandler() {
        var retries = 0
        composeRule.setContent {
            InsightsScreenContent(
                uiState = contentState(error = "kaputt", data = null),
                onRetry = { retries += 1 }
            )
        }

        composeRule.onNodeWithTag(INSIGHTS_RETRY_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertThat(retries).isEqualTo(1)
    }

    /**
     * Der Toggle-Handler geht wirklich bis zum Screen durch (und nicht an
     * einer lokalen Kopie vorbei).
     */
    @Test
    fun toggleHandlerIsReachedFromTheScreen() {
        var toggles = 0
        composeRule.setContent {
            InsightsScreenContent(
                uiState = contentState(expanded = false),
                onToggleExpanded = { toggles += 1 }
            )
        }

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertThat(toggles).isEqualTo(1)
    }

    /**
     * Leere Datenbank bei durchgelaufenem Aufbau: hier — und NUR hier —
     * gehoert der Empty-State hin.
     */
    @Test
    fun emptyDatabaseShowsEmptyState() {
        composeRule.setContent {
            InsightsScreenContent(uiState = contentState(data = null, loading = false))
        }

        val emptyTitle = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.insights_empty_title)
        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
        composeRule.onNodeWithTag(INSIGHTS_ERROR_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertDoesNotExist()
    }

    /**
     * Kein zweiter Ladevorgang beim Aufklappen: weder Spinner noch Fehlerkarte
     * erscheinen, waehrend die Liste waechst. Damit ist dokumentiert, dass
     * das Aufklappen hier kein Ladevorgang ist.
     */
    @Test
    fun expandingNeverShowsALoadingState() {
        val state = mutableStateOf(contentState(expanded = false))
        composeRule.setContent {
            InsightsScreenContent(
                uiState = state.value,
                onToggleExpanded = { state.value = state.value.copy(topActivitiesExpanded = true) }
            )
        }

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()

        // Die neue Zeile ist da (per Scroll erreichbar) …
        composeRule.onNodeWithTag(INSIGHTS_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(topActivitiesRowTestTag(6)))
        composeRule.onNodeWithTag(topActivitiesRowTestTag(6)).assertIsDisplayed()
        // … und es wurde kein Lade-/Fehlerzustand eingeblendet.
        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(INSIGHTS_ERROR_TEST_TAG).assertDoesNotExist()
    }
}

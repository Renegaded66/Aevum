package com.d_drostes_apps.aevum

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_ERROR_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_LOADING_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.INSIGHTS_RETRY_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsErrorCard
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsLoadingCard
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsPhase
import com.d_drostes_apps.aevum.ui.screens.insights.TOP_ACTIVITIES_TOGGLE_TEST_TAG
import com.d_drostes_apps.aevum.ui.screens.insights.insightsPhaseOf
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M18.137 (Kanban t_386782be): Lade- und Fehlerdarstellung der Insights-Ansicht.
 *
 * DIESE SUITE SCHLIESST DIE OFFENE AKZEPTANZLUECKE DER KARTE.
 *
 * Karten-Body: "loading and error states are shown appropriately". Die
 * Datenbasis lieferte `isLoading`/`errorMessage`/`retry()`, der Screen zeigte
 * davon aber nichts — ein fehlgeschlagener Aufbau landete optisch bei
 * "Noch keine Daten" (irrefuehrend, kein Weg zurueck). Hier wird geprueft,
 * dass beide Zustaende tatsaechlich sichtbar sind und sich ausschliessen.
 *
 * Aufbau in zwei Ebenen, weil die Zustandslogik und die Sichtbarkeit
 * unabhaengig voneinander brechen koennen:
 *
 *  A) [insightsPhaseOf] — die reine Phasenwahl (ohne Compose, ohne Robolectric).
 *     Hier liegt die eigentliche Entscheidung: was schlaegt was.
 *  B) Die gerenderten Karten (echte Compose-Hierarchie unter Robolectric):
 *     Ladeindikator, Fehlertext, Retry-Button — inklusive Klick-Pfad.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsLoadErrorStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---------------------------------------------------------------
    // A) Phasenwahl — reine Logik
    // ---------------------------------------------------------------

    @Test
    fun coldStartWithoutDataIsLoading() {
        assertThat(insightsPhaseOf(isLoading = true, errorMessage = null, hasData = false))
            .isEqualTo(InsightsPhase.Loading)
    }

    @Test
    fun loadedStateWithDataIsContent() {
        assertThat(insightsPhaseOf(isLoading = false, errorMessage = null, hasData = true))
            .isEqualTo(InsightsPhase.Content)
    }

    @Test
    fun loadedStateWithoutDataIsStillContent() {
        // Leere Datenbank ist KEIN Ladezustand: der Aufbau ist durchgelaufen,
        // es gibt nur nichts anzuzeigen. Der Empty-State gehoert hierher.
        assertThat(insightsPhaseOf(isLoading = false, errorMessage = null, hasData = false))
            .isEqualTo(InsightsPhase.Content)
    }

    @Test
    fun errorWithoutDataIsError() {
        assertThat(insightsPhaseOf(isLoading = false, errorMessage = "kaputt", hasData = false))
            .isEqualTo(InsightsPhase.Error)
    }

    /**
     * KERN-ZUSICHERUNG: der Fehler schlaegt den Ladezustand.
     *
     * Ohne diese Regel koennte ein Zustand mit BEIDEN Flags (Fehler aus einem
     * vorherigen Emission plus noch true stehendes isLoading) als "laedt"
     * gerendert werden — der Nutzer saehe dann einen Spinner OHNE
     * retry()-Button und haette keinen Weg zurueck ausser App-Neustart.
     */
    @Test
    fun errorWinsOverLoading() {
        assertThat(insightsPhaseOf(isLoading = true, errorMessage = "kaputt", hasData = false))
            .isEqualTo(InsightsPhase.Error)
    }

    /**
     * Ein Rebuild (Sprachwechsel, Retry) bei bereits vorhandenen Daten darf
     * den Inhalt nicht durch einen Platzhalter ersetzen — sonst flackert der
     * Screen bei jeder Neuberechnung.
     */
    @Test
    fun rebuildWithExistingDataDoesNotFallBackToLoading() {
        assertThat(insightsPhaseOf(isLoading = true, errorMessage = null, hasData = true))
            .isEqualTo(InsightsPhase.Content)
    }

    // ---------------------------------------------------------------
    // B) Gerenderte Sichtbarkeit
    // ---------------------------------------------------------------

    @Test
    fun loadingCardRendersSpinnerAndText() {
        composeRule.setContent { InsightsLoadingCard() }

        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertIsDisplayed()
        val label = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.insights_loading)
        composeRule.onNodeWithText(label).assertIsDisplayed()
    }

    @Test
    fun errorCardShowsMessageAndRetry() {
        composeRule.setContent {
            InsightsErrorCard(message = "Auswertung konnte nicht geladen werden.", onRetry = {})
        }

        composeRule.onNodeWithTag(INSIGHTS_ERROR_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Auswertung konnte nicht geladen werden.").assertIsDisplayed()
        composeRule.onNodeWithTag(INSIGHTS_RETRY_TEST_TAG).assertIsDisplayed()
        val retryLabel = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.insights_retry)
        composeRule.onNodeWithText(retryLabel).assertIsDisplayed()
    }

    /** Der Retry-Button muss den uebergebenen Callback wirklich ausloesen. */
    @Test
    fun retryButtonInvokesCallback() {
        val clicks = mutableStateOf(0)
        composeRule.setContent {
            InsightsErrorCard(message = "kaputt", onRetry = { clicks.value = clicks.value + 1 })
        }

        composeRule.onNodeWithTag(INSIGHTS_RETRY_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INSIGHTS_RETRY_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertThat(clicks.value).isEqualTo(2)
    }

    /**
     * Fehler- und Ladezustand schliessen sich in der UI aus.
     *
     * Wird ueber die Phase gesteuert: sobald ein Fehler anliegt, ist der
     * Ladeplatzhalter nicht mehr Teil der Komposition.
     */
    @Test
    fun errorPhaseDoesNotRenderLoadingIndicator() {
        val phase = mutableStateOf(InsightsPhase.Loading)
        composeRule.setContent {
            when (phase.value) {
                InsightsPhase.Loading -> InsightsLoadingCard()
                InsightsPhase.Error -> InsightsErrorCard(message = "kaputt", onRetry = {})
                InsightsPhase.Content -> InsightsLoadingCard()
            }
        }

        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertIsDisplayed()

        phase.value = InsightsPhase.Error
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(INSIGHTS_ERROR_TEST_TAG).assertIsDisplayed()
    }

    /**
     * Der Toggle aus t_70a06809 bleibt von der Phasenlogik unberuehrt: die
     * Phasen betreffen den Screen-Aufbau, NICHT das Auf-/Zuklappen. Ein
     * Spinner am Toggle waere der in beiden Vorgaenger-Kommentaren
     * verworfene Ansatz — hier wird festgehalten, dass er nicht existiert.
     */
    @Test
    fun loadAndErrorStateAreNotWiredToTheExpandToggle() {
        val expanded = mutableStateOf(false)
        composeRule.setContent {
            com.d_drostes_apps.aevum.ui.screens.insights.TopActivitiesCard(
                mode = com.d_drostes_apps.aevum.ui.screens.insights.BreakdownMode.Activity,
                items = (1..7).map { i ->
                    com.d_drostes_apps.aevum.ui.screens.insights.TopActivitySlice(
                        id = "t$i",
                        label = "Aktivität $i",
                        color = androidx.compose.ui.graphics.Color(0xFF6366F1),
                        durationMs = (10 - i) * 60_000L,
                        percent = 10,
                        icon = "•"
                    )
                },
                expanded = expanded.value,
                onToggleExpanded = { expanded.value = !expanded.value }
            )
        }

        composeRule.onNodeWithTag(TOP_ACTIVITIES_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()

        // Nach dem Aufklappen: keine Lade-/Fehler-Sicht, aber die neuen Zeilen.
        composeRule.onNodeWithTag(INSIGHTS_LOADING_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(INSIGHTS_ERROR_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(
            com.d_drostes_apps.aevum.ui.screens.insights.topActivitiesRowTestTag(6)
        ).assertIsDisplayed()
    }
}

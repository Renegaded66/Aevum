package com.d_drostes_apps.aevum.ui.screens.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import com.d_drostes_apps.aevum.util.LocaleHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M34 — Robolectric-Evidenz für die Wizard-Seiten (echte Compose-Hierarchie).
 *
 * Warum diese Tests: Der Wizard ist der erste Eindruck einer frischen
 * Installation. Zwei Dinge dürfen nicht kaputtgehen:
 *  1. Die Sprachwahl auf Seite 1 muss die RESTLICHEN Seiten verändern —
 *     genau das prüft [LocalOnboardingRes] (Ressourcen statt `stringResource`).
 *  2. Jede Seite muss ihre Auswahl wirklich melden (Chip/Schalter → Callback),
 *     sonst sieht der Nutzer Haken, die nichts bewirken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
class OnboardingPagesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** Setzt Inhalt mit den Ressourcen der gewählten Sprache — wie im Wizard. */
    private fun setLocalizedContent(language: String, content: @Composable () -> Unit) {
        val resources = LocaleHelper.resourcesFor(context, language)
        composeRule.setContent {
            AevumTheme(darkTheme = true) {
                CompositionLocalProvider(LocalOnboardingRes provides resources) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    private fun fixtureState(
        selectedCategories: Set<String> = setOf("work", "sleep"),
        selectedActivities: Set<String> = setOf("work", "joggen"),
        permissions: Set<String> = OnboardingViewModel.DEFAULT_PERMISSIONS
    ) = OnboardingViewModel.OnboardingUiState(
        page = 1,
        language = "en",
        categories = listOf(
            OnboardingViewModel.PickItem("work", "Work", "◼", 0xFF6366F1, true),
            OnboardingViewModel.PickItem("sleep", "Sleep", "◒", 0xFF334155, true),
            OnboardingViewModel.PickItem("sport", "Sport", "◆", 0xFF22C55E, true)
        ),
        selectedCategoryIds = selectedCategories,
        activities = listOf(
            OnboardingViewModel.PickItem("work", "Work", "💼", 0xFF5C6BC0, true),
            OnboardingViewModel.PickItem("joggen", "Jogging", "🏃", 0xFF26A69A, true),
            OnboardingViewModel.PickItem("reading", "Reading", "📖", 0xFF8D6E63, true)
        ),
        selectedActivityIds = selectedActivities,
        permissions = permissions,
        ready = true
    )

    // ── Seite 1: Sprache ──────────────────────────────────────────────────

    @Test
    fun `Sprachseite zeigt alle waehlbaren Sprachen`() {
        setLocalizedContent("en") {
            LanguagePage(selected = "en", onSelect = {})
        }
        listOf("English", "Deutsch", "Español", "Français", "Português", "Italiano", "Türkçe", "Polski", "Dansk", "Svenska")
            .forEach { label ->
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }
        composeRule.onNodeWithText("Norsk bokmål").assertIsDisplayed()
    }

    @Test
    fun `Sprachwahl meldet den Code`() {
        var chosen: String? = null
        setLocalizedContent("en") {
            LanguagePage(selected = "en", onSelect = { chosen = it })
        }
        composeRule.onNodeWithText("Français").performClick()
        assertThat(chosen).isEqualTo("fr")
    }

    @Test
    fun `die restlichen Seiten folgen der Sprachwahl ohne Recreate`() {
        // Mit spanischen Ressourcen muss die Seite spanisch sein — das ist
        // der Mechanismus, der Seite 1 glaubwürdig macht.
        setLocalizedContent("es") {
            PermissionsPage(state = fixtureState(), onToggle = {})
        }
        composeRule.onNodeWithText("¿Qué puede ver Aevum?").assertIsDisplayed()
        composeRule.onNodeWithText("Ubicación").assertIsDisplayed()
    }

    @Test
    fun `dieselbe Seite erscheint auf Deutsch`() {
        setLocalizedContent("de") {
            PermissionsPage(state = fixtureState(), onToggle = {})
        }
        composeRule.onNodeWithText("Was darf Aevum sehen?").assertIsDisplayed()
        composeRule.onNodeWithText("Standort").assertIsDisplayed()
    }

    // ── Seite 2: Kategorien + Aktivitäten ─────────────────────────────────

    @Test
    fun `abgewaehlte Kategorie ist sichtbar und laesst sich wieder waehlen`() {
        var toggled: String? = null
        setLocalizedContent("en") {
            FocusPage(
                state = fixtureState(selectedCategories = setOf("work", "sleep")),
                onToggleCategory = { toggled = it },
                onToggleActivity = {},
                onAddCategory = {},
                onAddActivity = {}
            )
        }
        composeRule.onNodeWithText("Sport").performClick()
        assertThat(toggled).isEqualTo("sport")
    }

    @Test
    fun `Aktivitaet abwaehlen meldet die ID`() {
        var toggled: String? = null
        setLocalizedContent("en") {
            FocusPage(
                state = fixtureState(),
                onToggleCategory = {},
                onToggleActivity = { toggled = it },
                onAddCategory = {},
                onAddActivity = {}
            )
        }
        composeRule.onNodeWithText("Jogging").performClick()
        assertThat(toggled).isEqualTo("joggen")
    }

    @Test
    fun `Zaehler zeigt die Auswahl`() {
        setLocalizedContent("en") {
            FocusPage(
                state = fixtureState(
                    selectedCategories = setOf("work"),
                    selectedActivities = setOf("work", "joggen", "reading")
                ),
                onToggleCategory = {},
                onToggleActivity = {},
                onAddCategory = {},
                onAddActivity = {}
            )
        }
        composeRule.onNodeWithText("1 of 3 selected").assertIsDisplayed()
        composeRule.onNodeWithText("3 of 3 selected").assertIsDisplayed()
    }

    // ── Seite 3: Maskottchen ──────────────────────────────────────────────

    @Test
    fun `Maskottchen laesst sich an- und abschalten`() {
        var enabled: Boolean? = null
        setLocalizedContent("en") {
            MascotPage(
                state = fixtureState().copy(mascotEnabled = false),
                onEnable = { enabled = it },
                onColor = {},
                onAntenna = {},
                onCheeks = {}
            )
        }
        composeRule.onNodeWithText("Yes, come along").performClick()
        assertThat(enabled).isTrue()

        // Bei "aus" bleibt die Bühne leer — kein Maskottchen-Bild.
        composeRule.onNodeWithText("The dashboard stays calm and empty.").assertIsDisplayed()
    }

    // ── Seite 4: Berechtigungen ───────────────────────────────────────────

    @Test
    fun `jede Berechtigung ist einzeln schaltbar`() {
        val toggled = mutableListOf<String>()
        setLocalizedContent("en") {
            PermissionsPage(
                state = fixtureState(permissions = emptySet()),
                onToggle = { toggled += it }
            )
        }
        listOf("Location", "Motion & driving", "Notifications", "Screen time", "Calendar", "Calls")
            .forEach { composeRule.onNodeWithText(it).performClick() }
        assertThat(toggled).containsExactly(
            "location", "activity", "notifications", "usage", "calendar", "calls"
        )
    }

    @Test
    fun `ohne Auswahl bleibt die Seite bedienbar`() {
        setLocalizedContent("en") {
            PermissionsPage(state = fixtureState(permissions = emptySet()), onToggle = {})
        }
        composeRule.onNodeWithText("Your choice").assertIsDisplayed()
        composeRule.onNodeWithText("You can also continue without a single permission — Aevum then stays manual.")
            .assertIsDisplayed()
    }

    // ── Seite 5: Abschluss ────────────────────────────────────────────────

    @Test
    fun `Abschlussseite nennt kostenlos, lokal und Kontrolle`() {
        setLocalizedContent("en") { TrustPage() }
        composeRule.onNodeWithText("Yours. Entirely.").assertIsDisplayed()
        composeRule.onNodeWithText("Free").assertIsDisplayed()
        composeRule.onNodeWithText("On your device").assertIsDisplayed()
        composeRule.onNodeWithText("In your hands").assertIsDisplayed()
    }

    // ── Maskottchen-Konstanten ────────────────────────────────────────────

    @Test
    fun `Standard-Maskottchen ist blau mit Kugel und Baeckchen`() {
        // Der Wizard startet mit derselben Konfiguration wie die Einstellungen.
        val state = fixtureState()
        assertThat(state.mascotColor).isEqualTo(MascotRepository.COLOR_DEFAULT)
        assertThat(state.mascotAntenna).isEqualTo(MascotRepository.ANTENNA_DEFAULT)
        assertThat(state.mascotEnabled).isFalse()
    }
}

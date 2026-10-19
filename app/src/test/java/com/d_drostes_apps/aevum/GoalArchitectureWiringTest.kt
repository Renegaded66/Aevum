package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * M20: Verdrahtungs-Pins der Ziel-Architektur.
 *
 * Die Logik-Tests (GoalProgressAnalyticsTest, SchemaV47Test,
 * GoalV47MigrationTest) beweisen Auswertung und Datenbank. Dieser Test
 * beweist die NAHTSTELLEN — genau die Fehlerklasse, bei der die alte
 * Goals-Basis (M18.138) scheiterte: die Screens existierten, hingen aber
 * an KEINER Navigation („verwaist"), und kein Test wurde rot.
 *
 * Geprüft werden deshalb die Quelltext-Verdrahtungen, die einen verwaisten
 * Screen sofort sichtbar machen: Settings-Eintrag → Route → NavHost →
 * Screen, dazu Migration-Registrierung und die Dopamin-Bausteine.
 * (Muster wie PmRemoval/„Wiring-Pins" in den bestehenden Suites.)
 */
class GoalArchitectureWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(".").resolve(relative),
            File("..").resolve("app/$relative"),
            File("../app").resolve(relative),
            File("app").resolve(relative)
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Quelldatei $relative nicht gefunden (CWD=${File(".").absolutePath})")
        return file.readText()
    }

    private fun main(relative: String) =
        source("src/main/java/com/d_drostes_apps/aevum/$relative")

    @Test
    fun `Settings hat den neuen Punkt Ziele`() {
        val settings = main("ui/screens/settings/SettingsScreen.kt")
        assertThat(settings).contains("onOpenGoals")
        assertThat(settings).contains("settings_goals_section")
        assertThat(settings).contains("R.string.settings_goals")
    }

    @Test
    fun `Navigation verdrahtet Liste - Editor - Detail`() {
        val destination = main("navigation/AppDestination.kt")
        assertThat(destination).contains("Goals(\"goals\"")
        assertThat(destination).contains("goal_editor?goalId=")
        assertThat(destination).contains("goal_detail/{goalId}")

        val host = main("navigation/AppNavHost.kt")
        assertThat(host).contains("AppDestination.Goals.route")
        assertThat(host).contains("ui.screens.goals.GoalsScreen(")
        assertThat(host).contains("ui.screens.goals.GoalEditorScreen(")
        assertThat(host).contains("ui.screens.goals.GoalDetailScreen(")
        assertThat(host).contains("onOpenGoals = { navController.navigate(AppDestination.Goals.route) }")
    }

    @Test
    fun `Datenbank - Migration-46-47-ist-registriert-und-version-ist-aktuell`() {
        val database = main("data/db/AppDatabase.kt")
        assertThat(database).contains("version = 48")
        assertThat(database).contains("MIGRATION_46_47")
        assertThat(database).contains("MIGRATION_47_48")
        assertThat(database).contains("goalCheckinDao()")

        val module = main("di/DatabaseModule.kt")
        assertThat(module).contains("AppDatabase.MIGRATION_46_47")
        assertThat(module).contains("AppDatabase.MIGRATION_47_48")
        assertThat(module).contains("provideGoalCheckinDao")
    }

    @Test
    fun `Dopamin-Bausteine-sind-im-Liste-Screen-verdrahtet`() {
        val screen = main("ui/screens/goals/GoalsScreen.kt")
        // Feier-Overlay + Schimmer bei erreichten Zielen.
        assertThat(screen).contains("GoalCelebrationOverlay")
        assertThat(screen).contains("Modifier.shimmering")

        val viewModel = main("ui/screens/goals/GoalsViewModel.kt")
        // Feier genau einmal je Ziel+Periode (Store-gated) + Abhaken/Rückgängig.
        assertThat(viewModel).contains("maybeEmitCelebration")
        assertThat(viewModel).contains("celebrationStore.shouldCelebrate")
        assertThat(viewModel).contains("fun checkIn(")
        assertThat(viewModel).contains("fun undoLastCheckin(")
    }

    @Test
    fun `Detail-Screen-hat-Statistiken - Verlauf - Heatmap - Check-in-Liste`() {
        val detail = main("ui/screens/goals/GoalDetailScreen.kt")
        assertThat(detail).contains("goal_detail_stats")
        assertThat(detail).contains("GoalHistoryBars")
        assertThat(detail).contains("GoalHeatmap")
        assertThat(detail).contains("CheckinList")

        val components = main("ui/screens/goals/GoalComponents.kt")
        assertThat(components).contains("fun GoalHistoryBars(")
        assertThat(components).contains("fun GoalHeatmap(")
        assertThat(components).contains("fun GoalCheckButton(")
        assertThat(components).contains("fun GoalCelebrationOverlay(")
        assertThat(components).contains("fun scheduleText(")
    }

    @Test
    fun `Editor - Vorlagen-und-Auto-Manuell-Umschaltung-sind-da`() {
        val editor = main("ui/screens/goals/GoalEditorScreen.kt")
        assertThat(editor).contains("goal_presets")
        assertThat(editor).contains("GoalWeekdayPills")
        assertThat(editor).contains("GoalAccentPicker")

        val viewModel = main("ui/screens/goals/GoalEditorViewModel.kt")
        assertThat(viewModel).contains("fun applyPreset(")
        assertThat(viewModel).contains("GoalPreset.DIGITAL_DAILY")
        assertThat(viewModel).contains("GoalPreset.LEARNING_WEEKDAYS")
        assertThat(viewModel).contains("GoalPreset.TASK_COUNT_WEEKDAYS")
        assertThat(viewModel).contains("fun setTrackingMode(")
    }
}

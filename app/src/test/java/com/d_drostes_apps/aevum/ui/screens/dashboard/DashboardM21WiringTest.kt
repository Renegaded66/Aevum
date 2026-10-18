package com.d_drostes_apps.aevum.ui.screens.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * M21: Verdrahtungs-Pins für den Dashboard-Umbau (Nutzerwunsch 17.10.2026).
 *
 * Geprüft wird der QUELLTEXT — die UI selbst ist ohne Gerät nicht
 * instrumentierbar, aber genau die Fehler der Vergangenheit (Block
 * versehentlich wieder eingebaut, Screen verwaist, Diagramm beim
 * Tages-Wechsel sichtbar) sind reine Verdrahtungsfehler.
 */
class DashboardM21WiringTest {

    private val projectRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        while (!File(dir, "app/src/main/java").exists()) {
            dir = dir.parentFile ?: error("Projektwurzel nicht gefunden")
        }
        dir
    }

    private fun read(relativePath: String): String =
        File(projectRoot, relativePath).readText()

    private val screen = "app/src/main/java/com/d_drostes_apps/aevum/ui/screens/dashboard/DashboardScreen.kt"
    private val viewModel = "app/src/main/java/com/d_drostes_apps/aevum/ui/screens/dashboard/DashboardViewModel.kt"
    private val goalsViewModel = "app/src/main/java/com/d_drostes_apps/aevum/ui/screens/dashboard/DashboardGoalsViewModel.kt"
    private val navHost = "app/src/main/java/com/d_drostes_apps/aevum/navigation/AppNavHost.kt"

    @Test
    fun `Ziele-Karte ist im Dashboard verdrahtet`() {
        val source = read(screen)
        assertThat(source).contains("DashboardGoalsCard(")
        assertThat(source).contains("DashboardGoalRow(")
        // Abhaken für manuelle Ziele direkt auf dem Dashboard
        assertThat(source).contains("onGoalCheckIn")
        assertThat(source).contains("onGoalUndoCheckIn")
        // Fancy: farbige Bar mit Schimmer
        assertThat(source).contains("GoalShimmerBar(")
        assertThat(source).contains(".shimmering(")
    }

    @Test
    fun `Ziele-Karte laeuft ueber eigenes ViewModel mit echter Auswertung`() {
        val source = read(goalsViewModel)
        assertThat(source).contains("@HiltViewModel")
        assertThat(source).contains("GoalProgressAnalytics.evaluateGoal(")
        assertThat(source).contains("goalRepository.addCheckin(")
        assertThat(source).contains("goalRepository.removeCheckin(")
        assertThat(source).contains("celebrationStore.markCelebrated(")
        assertThat(source).contains("MAX_CARD_GOALS")
    }

    @Test
    fun `Dashboard-Ziele navigieren zu Liste und Detail`() {
        val source = read(navHost)
        assertThat(source).contains("onOpenGoals = { navController.navigate(AppDestination.Goals.route) }")
        assertThat(source).contains("onOpenGoalDetail = { goalId -> navController.navigate(\"goal_detail/\$goalId\") }")
    }

    @Test
    fun `Grosse Bloecke und Offene Zeit sind aus den Insights entfernt`() {
        val source = read(viewModel)
        val insightsBlock = source.substringAfter("private fun buildInsights(")
            .substringBefore("private fun estimateBalanceScore(")
        assertThat(insightsBlock).doesNotContain("dashboard_insight_biggest")
        assertThat(insightsBlock).doesNotContain("dashboard_insight_open")
        // Der Erzähltext nennt die offene Zeit ebenfalls nicht mehr.
        assertThat(source).doesNotContain("dashboard_narrative_open_time")
    }

    @Test
    fun `Tagespauschalen stehen als klickbare Zeile in Wo deine Zeit hingeht`() {
        val vm = read(viewModel)
        assertThat(vm).contains("isAllowance = true")
        assertThat(vm).contains("allowanceId = allowance.id")

        val source = read(screen)
        assertThat(source).contains("dashboard_allowance_suffix")
        assertThat(source).contains("onAllowanceClick(")
        // Der eigene Pauschalen-Block wird NICHT mehr aufgerufen — die
        // Definition darf bleiben, der Aufruf im Sheet nicht.
        val calls = Regex("DashboardAllowancesRow\\(").findAll(source).count()
        assertThat(calls).isEqualTo(1) // nur die Definition
    }

    @Test
    fun `Guete-Verlauf erscheint nur am heutigen Tag`() {
        val source = read(screen)
        assertThat(source).contains("val isShowingToday = state.displayedDate == java.time.LocalDate.now()")
        val guarded = source.substringAfter("if (isShowingToday) {").substringBefore("}")
        assertThat(guarded).contains("QualityTrendCard(")
    }
}

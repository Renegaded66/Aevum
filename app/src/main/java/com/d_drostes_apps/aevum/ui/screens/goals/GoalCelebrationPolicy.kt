package com.d_drostes_apps.aevum.ui.screens.goals

/**
 * M27: Welche Ziel-Periode darf jetzt gefeiert werden?
 *
 * Vorher feierte die App die LAUFENDE Periode, sobald sie live erfüllt war.
 * Bei „Bildschirmzeit unter 3 h" ist das jeden Morgen der Fall — der Tag
 * kann abends aber noch kippen. Diese Regel verschiebt die Feier deshalb
 * auf die letzte ABGESCHLOSSENE Periode:
 *
 *  - automatisch gemessene Ziele: letzter voller Tag / letzte volle Woche /
 *    letzter voller Monat,
 *  - manuelle Ziele: mit dem Check-in sofort, weil der Check-in selbst die
 *    abschließende Handlung ist.
 *
 * Der zurückgegebene [Candidate.periodKey] ist gleichzeitig der
 * Dedupe-Schlüssel im [com.d_drostes_apps.aevum.data.goals.GoalCelebrationStore]
 * — dadurch feuert dieselbe Periode genau einmal, auch wenn mehrere Screens
 * dieselbe Auswertung anstoßen.
 */
object GoalCelebrationPolicy {

    data class Candidate(
        val goalId: String,
        val goalTitle: String,
        val periodKey: String,
        val periodLabel: String,
        val streak: Int,
        val accent: String?
    )

    fun candidate(goal: GoalWithProgress): Candidate? {
        if (!goal.celebrationEligible) return null
        return Candidate(
            goalId = goal.goal.id,
            goalTitle = goal.goal.title,
            periodKey = goal.celebrationPeriodKey,
            periodLabel = goal.celebrationPeriodLabel,
            streak = goal.celebrationStreak,
            accent = goal.goal.accent
        )
    }
}

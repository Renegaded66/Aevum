package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * M24-Regressionswächter für Devons Digital-Balance-Befund:
 * „Die Zahl oben bleibt die Zeit vom heutigen Tag und wechselt nicht beim
 * Klick auf das Balkendiagramm."
 *
 * Der Laufzeitbeweis für `detailForDay` liegt in
 * [com.d_drostes_apps.aevum.domain.digital.AppUsageAggregatorDetailTest].
 * Dieser Test hält zusätzlich die VERDRAHTUNG fest: Der ViewModel-Build
 * muss für einen gewählten Tag `detailForDay(effectiveDay)` aufrufen, und
 * die Hero-Karte muss `state.totalMs` lesen (nicht mehr ein Today-Feld).
 */
class DigitalBalanceDaySwitchWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(".").resolve(relative),
            File("..").resolve("app/$relative"),
            File("../app").resolve(relative),
            File("app").resolve(relative)
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("Quelldatei $relative nicht gefunden (CWD=${File(".").absolutePath})")
    }

    @Test
    fun `ViewModel holt Detailwerte fuer den gewaehlten Tag`() {
        val vm = source(
            "src/main/java/com/d_drostes_apps/aevum/ui/screens/digitalbalance/DigitalBalanceViewModel.kt"
        )
        assertThat(vm).contains("if (isToday) aggregator.todayDetail()")
        assertThat(vm).contains("else aggregator.detailForDay(effectiveDay)")
        assertThat(vm).doesNotContain("val detailDeferred = async { aggregator.todayDetail() }")
    }

    @Test
    fun `Hero liest den Tageswert statt eines Today-Feldes`() {
        val screen = source(
            "src/main/java/com/d_drostes_apps/aevum/ui/screens/digitalbalance/DigitalBalanceScreen.kt"
        )
        assertThat(screen).contains("state.totalMs")
        assertThat(screen).doesNotContain("state.todayTotalMs")
        // Der Kopf zeigt bei gewähltem Tag dessen Datum, nicht „Heute".
        assertThat(screen).contains("state.selectedDay")
    }
}

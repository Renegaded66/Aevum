package com.d_drostes_apps.aevum.domain.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.1: Feier-Gate.
 *
 * Das Feuerwerk hängt an „Fortschritt == 100 %" — eine Bedingung, die in
 * Compose bei JEDER Neukomposition erneut wahr ist (Scrollen, Rotation,
 * Rückkehr auf die Seite, Datenbank-Emission). Ohne dieses Gate würde der
 * Nutzer bei jeder Bewegung ein Feuerwerk sehen und die Belohnung wäre
 * wertlos. Diese Suite legt das Verhalten für jeden Übergang fest.
 */
class PmCelebrationGateTest {

    private val today = "2026-10-17"
    private val yesterday = "2026-10-16"

    @Test
    fun `nicht-voll-feiert-nicht`() {
        assertThat(
            PmCelebrationGate.shouldCelebrate(PmCelebrationGate.State(), today, total = 10, done = 9)
        ).isFalse()
    }

    @Test
    fun `voll-feiert-beim-ersten-Mal`() {
        assertThat(
            PmCelebrationGate.shouldCelebrate(PmCelebrationGate.State(), today, total = 10, done = 10)
        ).isTrue()
    }

    @Test
    fun `leerer-Tag-feiert-nicht`() {
        // 0 von 0 ist NICHT vollständig — sonst feiert die App beim Öffnen.
        assertThat(
            PmCelebrationGate.shouldCelebrate(PmCelebrationGate.State(), today, total = 0, done = 0)
        ).isFalse()
    }

    @Test
    fun `zweite-Rekomposition-feiert-nicht-erneut`() {
        val state = PmCelebrationGate.afterCelebration(PmCelebrationGate.State(), today, done = 10)
        assertThat(PmCelebrationGate.shouldCelebrate(state, today, total = 10, done = 10)).isFalse()
        // beliebig oft geprüft — bleibt still
        assertThat(PmCelebrationGate.shouldCelebrate(state, today, total = 10, done = 10)).isFalse()
    }

    @Test
    fun `abgehakt-zurueckgesetzt-wieder-abgehakt-feiert-nicht-erneut`() {
        val state = PmCelebrationGate.afterCelebration(PmCelebrationGate.State(), today, done = 3)
        // 3 → 2 → 3: der gefeierte Stand war 3, danach nichts Neues verdient.
        assertThat(PmCelebrationGate.shouldCelebrate(state, today, total = 3, done = 2)).isFalse()
        assertThat(PmCelebrationGate.shouldCelebrate(state, today, total = 3, done = 3)).isFalse()
    }

    @Test
    fun `nachgelegt-feiert-verdient-erneut`() {
        val state = PmCelebrationGate.afterCelebration(PmCelebrationGate.State(), today, done = 10)
        // Nutzer plant ein elftes Todo für heute ein und hakt es ab.
        assertThat(PmCelebrationGate.shouldCelebrate(state, today, total = 11, done = 11)).isTrue()
    }

    @Test
    fun `neuer-Tag-feiert-wieder`() {
        val state = PmCelebrationGate.afterCelebration(PmCelebrationGate.State(), yesterday, done = 5)
        assertThat(PmCelebrationGate.shouldCelebrate(state, today, total = 5, done = 5)).isTrue()
    }

    @Test
    fun `Zustand-traegt-Datum-und-Stand`() {
        val state = PmCelebrationGate.afterCelebration(PmCelebrationGate.State(), today, done = 7)
        assertThat(state.date).isEqualTo(today)
        assertThat(state.celebratedDone).isEqualTo(7)
    }
}

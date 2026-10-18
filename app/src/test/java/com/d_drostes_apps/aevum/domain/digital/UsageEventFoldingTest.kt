package com.d_drostes_apps.aevum.domain.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M21: Regression der Vordergrund-Faltung für Digital Balance.
 *
 * Nutzer-Report (17.10.2026): „Duolingo taucht in Digital Balance gar nicht
 * auf; Aevum sagt 5 Minuten, die System-App 10 Minuten — und Duolingo mit
 * 4 Minuten." Ursache war die Faltung auf PAKET-Ebene: das `ACTIVITY_PAUSED`
 * der Splash-Activity beendete die Phase der ganzen App. Diese Suite hält
 * die Korrektur fest.
 */
class UsageEventFoldingTest {

    private fun folder(clipStart: Long = 0L) = UsageEventFolding.Folder(clipStart)

    @Test
    fun `Splash-PAUSED beendet die App-Sitzung nicht - Duolingo-Fall`() {
        // 4 Minuten Duolingo: Splash startet, Main übernimmt, Splash pausiert.
        val f = folder()
        f.onForeground("com.duolingo", ".SplashActivity", 1_000)
        f.onForeground("com.duolingo", ".MainActivity", 1_500)
        f.onBackground("com.duolingo", ".SplashActivity", 1_600)
        f.onBackground("com.duolingo", ".MainActivity", 241_000)

        val phases = f.finish(300_000)

        assertThat(phases).hasSize(1)
        assertThat(phases[0].packageName).isEqualTo("com.duolingo")
        assertThat(phases[0].start).isEqualTo(1_000)
        assertThat(phases[0].end).isEqualTo(241_000)
        // Exakt die 4 Minuten, die auch die System-App zeigt.
        assertThat(phases[0].durationMs).isEqualTo(240_000)
    }

    @Test
    fun `Fehlender Klassenname beim PAUSED schliesst das aelteste Fenster`() {
        val f = folder()
        f.onForeground("com.duolingo", "SplashActivity", 1_000)
        f.onForeground("com.duolingo", "MainActivity", 1_500)
        f.onBackground("com.duolingo", null, 1_600) // OEM-Event ohne className
        f.onBackground("com.duolingo", "MainActivity", 241_000)

        val phases = f.finish(300_000)

        assertThat(phases).hasSize(1)
        assertThat(phases[0].durationMs).isEqualTo(240_000)
    }

    @Test
    fun `Zwei Apps werden getrennt gefuehrt`() {
        val f = folder()
        f.onForeground("com.a", ".A", 0)
        f.onBackground("com.a", ".A", 1_000)
        f.onForeground("com.b", ".B", 2_000)
        f.onBackground("com.b", ".B", 3_000)

        val phases = f.finish(5_000)

        assertThat(phases.map { it.packageName }).containsExactly("com.a", "com.b")
        assertThat(phases.first { it.packageName == "com.a" }.durationMs).isEqualTo(1_000)
        assertThat(phases.first { it.packageName == "com.b" }.durationMs).isEqualTo(1_000)
    }

    @Test
    fun `Ueberlappende Activities desselben Pakets zaehlen nicht doppelt`() {
        val f = folder()
        f.onForeground("com.a", ".Act1", 0)
        f.onForeground("com.a", ".Act2", 500)
        f.onBackground("com.a", ".Act1", 1_500)
        f.onBackground("com.a", ".Act2", 2_000)

        val phases = f.finish(10_000)

        // Union 0..2000 — nicht 1500 + 1500.
        assertThat(phases).hasSize(1)
        assertThat(phases[0].durationMs).isEqualTo(2_000)
    }

    @Test
    fun `Offene App laeuft bis zum Fensterende`() {
        val f = folder()
        f.onForeground("com.a", ".A", 1_000)

        val phases = f.finish(5_000)

        assertThat(phases).hasSize(1)
        assertThat(phases[0].start).isEqualTo(1_000)
        assertThat(phases[0].end).isEqualTo(5_000)
    }

    @Test
    fun `ClipStart begrenzt frueher gestartete Fenster`() {
        // OEM liefert ein Foreground-Event von VOR dem Query-Fenster.
        val f = folder(clipStart = 1_000)
        f.onForeground("com.a", ".A", 200)

        val phases = f.finish(3_000)

        assertThat(phases[0].start).isEqualTo(1_000)
        assertThat(phases[0].durationMs).isEqualTo(2_000)
    }

    @Test
    fun `Kurz- und Langform der Klasse werden erkannt`() {
        val f = folder()
        f.onForeground("com.duolingo", "com.duolingo.MainActivity", 0)
        f.onBackground("com.duolingo", ".MainActivity", 1_000)

        val phases = f.finish(5_000)

        assertThat(phases).hasSize(1)
        assertThat(phases[0].end).isEqualTo(1_000)
    }

    @Test
    fun `Doppeltes RESUMED derselben Activity oeffnet kein zweites Fenster`() {
        val f = folder()
        f.onForeground("com.a", ".A", 1_000)
        f.onForeground("com.a", ".A", 1_500) // z. B. Screen-Zyklus ohne PAUSED
        f.onBackground("com.a", ".A", 2_000)

        val phases = f.finish(10_000)

        assertThat(phases).hasSize(1)
        assertThat(phases[0].start).isEqualTo(1_000)
        assertThat(phases[0].durationMs).isEqualTo(1_000)
    }

    @Test
    fun `Foreground-Events werden gezaehlt`() {
        val f = folder()
        f.onForeground("com.a", ".A", 0)
        f.onForeground("com.b", ".B", 100)
        f.onBackground("com.a", ".A", 200)

        assertThat(f.foregroundEvents).isEqualTo(2)
    }
}

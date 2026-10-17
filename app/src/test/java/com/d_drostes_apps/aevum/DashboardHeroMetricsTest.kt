package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroMetrics
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.4: Reine JVM-Suite für die Hero-Arithmetik (kein Android nötig).
 * M19.5: Collapse ist dem Pinning gewichen — geprüft wird jetzt die
 * Sticky-Kompensation (Hero bleibt beim Scrollen stehen).
 *
 * Geprüft wird, was die Canvas-/Pinning-Geometrie wirklich steuert:
 *  - Gauge-Füllgrad und Marker-Winkel des 180°-Bogens,
 *  - Sticky-Translation der Liste (Pinning hängt daran),
 *  - Prozent-Formatierung des Tagesfortschritts.
 *
 * Grenzfälle inklusive (unter 0, über 100, ungemessene Höhe) — genau
 * dort bricht solche Arithmetik sonst still.
 */
class DashboardHeroMetricsTest {

    @Test
    fun gaugeSweepFractionClampsToUnitRange() {
        assertThat(DashboardHeroMetrics.gaugeSweepFraction(75)).isEqualTo(0.75f)
        assertThat(DashboardHeroMetrics.gaugeSweepFraction(0)).isEqualTo(0f)
        assertThat(DashboardHeroMetrics.gaugeSweepFraction(100)).isEqualTo(1f)
        // Unter-/Überlaufen wird geklemmt (Canvas darf nie ausbrechen).
        assertThat(DashboardHeroMetrics.gaugeSweepFraction(-10)).isEqualTo(0f)
        assertThat(DashboardHeroMetrics.gaugeSweepFraction(150)).isEqualTo(1f)
    }

    @Test
    fun gaugeNeedleAngleSpansLeftToRightHalfCircle() {
        // 180° = linkes Ende (Score 0), 270° = Scheitel (50), 360° = rechts (100).
        assertThat(DashboardHeroMetrics.gaugeNeedleAngleDegrees(0)).isEqualTo(180f)
        assertThat(DashboardHeroMetrics.gaugeNeedleAngleDegrees(50)).isEqualTo(270f)
        assertThat(DashboardHeroMetrics.gaugeNeedleAngleDegrees(100)).isEqualTo(360f)
        assertThat(DashboardHeroMetrics.gaugeNeedleAngleDegrees(75)).isEqualTo(315f)
    }

    @Test
    fun stickyTranslationTracksScrollExactly() {
        // M19.5-Pinning: Kompensation = exakt der Scroll-Offset → netto
        // steht der Hero still, während das Sheet darüber rutscht.
        assertThat(DashboardHeroMetrics.heroStickyTranslation(0, 2000, false)).isEqualTo(0)
        assertThat(DashboardHeroMetrics.heroStickyTranslation(500, 2000, false)).isEqualTo(500)
        assertThat(DashboardHeroMetrics.heroStickyTranslation(1999, 2000, false)).isEqualTo(1999)
        // Negativer Offset (Overscroll) darf die Verschiebung nicht ins
        // Negative ziehen (Hero darf nicht nach UNTEN wandern).
        assertThat(DashboardHeroMetrics.heroStickyTranslation(-50, 2000, false)).isEqualTo(0)
    }

    @Test
    fun stickyTranslationKeepsHeroStationaryAcrossScroll() {
        // Netto-Position = Layout(-offset) + Translation(+offset) = 0 für
        // jede Scroll-Position, solange Item 0 sichtbar ist.
        val height = 1800
        listOf(0, 1, 137, 900, 1799).forEach { offset ->
            val translation = DashboardHeroMetrics.heroStickyTranslation(offset, height, false)
            assertThat(translation - offset).isEqualTo(0)
        }
    }

    @Test
    fun stickyTranslationCompensatesFullHeightOnceHeroIsLeftBehind() {
        // Sobald Item 0 durchgescrollt ist, volle Kompensation (Hero ist
        // dann ohnedies vom Sheet überdeckt bzw. ge-cullt).
        assertThat(DashboardHeroMetrics.heroStickyTranslation(2001, 2000, true)).isEqualTo(2000)
        assertThat(DashboardHeroMetrics.heroStickyTranslation(0, 2000, true)).isEqualTo(2000)
        // Unmessbare Höhe (erster Frame) darf keinen Sprung erzeugen.
        assertThat(DashboardHeroMetrics.heroStickyTranslation(0, 0, true)).isEqualTo(0)
    }

    @Test
    fun dayProgressPercentFormatsAndClamps() {
        assertThat(DashboardHeroMetrics.dayProgressPercent(0.63f)).isEqualTo(63)
        assertThat(DashboardHeroMetrics.dayProgressPercent(0f)).isEqualTo(0)
        assertThat(DashboardHeroMetrics.dayProgressPercent(1f)).isEqualTo(100)
        // Randfälle: negative/überzählige Werte werden geklemmt.
        assertThat(DashboardHeroMetrics.dayProgressPercent(-0.5f)).isEqualTo(0)
        assertThat(DashboardHeroMetrics.dayProgressPercent(1.4f)).isEqualTo(100)
    }
}

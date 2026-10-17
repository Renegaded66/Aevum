package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardHeroMetrics
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.4: Reine JVM-Suite für die Hero-Arithmetik (kein Android nötig).
 *
 * Geprüft wird, was die Canvas-/Parallax-Geometrie wirklich steuert:
 *  - Gauge-Füllgrad und Marker-Winkel des 180°-Bogens,
 *  - Collapse-Fortschritt der Liste (Parallax hängt daran),
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
    fun collapseGrowsWithScrollAndCapsAtOne() {
        // Ausgangslage: Hero voll sichtbar.
        assertThat(DashboardHeroMetrics.collapseFor(0, 2000, false)).isEqualTo(0f)
        // Halb gescrollt: halber Collapse.
        assertThat(DashboardHeroMetrics.collapseFor(1000, 2000, false)).isEqualTo(0.5f)
        // Über die Höhe hinaus: geklemmt auf 1.
        assertThat(DashboardHeroMetrics.collapseFor(2500, 2000, false)).isEqualTo(1f)
        // Negativer Offset (Overscroll) darf nicht unter 0 rutschen.
        assertThat(DashboardHeroMetrics.collapseFor(-50, 2000, false)).isEqualTo(0f)
    }

    @Test
    fun collapseIsOneOnceHeroItemIsLeftBehind() {
        assertThat(DashboardHeroMetrics.collapseFor(0, 2000, true)).isEqualTo(1f)
        assertThat(DashboardHeroMetrics.collapseFor(10, 2000, true)).isEqualTo(1f)
    }

    @Test
    fun collapseStaysZeroWhileHeightIsUnmeasured() {
        // Vor der ersten Messung (Höhe 0) darf der Hero nicht wegspringen.
        assertThat(DashboardHeroMetrics.collapseFor(500, 0, false)).isEqualTo(0f)
        assertThat(DashboardHeroMetrics.collapseFor(0, 0, false)).isEqualTo(0f)
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

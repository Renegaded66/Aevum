package com.d_drostes_apps.aevum.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.2: Reine Arithmetik des Schimmers — bewusst ohne Android/Compose.
 *
 * WARUM: Die Sweep-Kurve und die Band-Geometrie sind der einzige Teil des
 * Effekts, den die JVM-Suite beweisen kann; die Optik selbst (Rotation,
 * Gradient) gehört den Previews. Liegt die Ruhephase nicht mehr über 1f
 * oder startet das Band nicht mehr komplett versteckt, failt hier sofort.
 */
class PmShimmerTest {

    @Test
    fun sweepStartsAtZero() {
        assertThat(PmShimmer.sweepProgress(0f)).isEqualTo(0f)
    }

    @Test
    fun sweepIsMonotonicDuringSweepPhase() {
        val sweepFraction = 0.72f
        var previous = 0f
        var t = 0.02f
        while (t < sweepFraction) {
            val current = PmShimmer.sweepProgress(t, sweepFraction)
            assertThat(current).isAtLeast(previous)
            assertThat(current).isLessThan(1f) // Sweep-Phase nur, solange das Band unterwegs ist
            previous = current
            t += 0.02f
        }
    }

    @Test
    fun sweepRestPhaseIsBeyondOne() {
        // Exakt an der Grenze und danach: Ruhephase, Band gilt als weg.
        assertThat(PmShimmer.sweepProgress(0.72f)).isGreaterThan(1f)
        assertThat(PmShimmer.sweepProgress(0.90f)).isGreaterThan(1f)
        assertThat(PmShimmer.sweepProgress(1f)).isGreaterThan(1f)
    }

    @Test
    fun bandStartsHiddenLeftAndEndsFullyOutRight() {
        assertThat(PmShimmer.bandStartX(300f, 100f, 0f)).isEqualTo(-100f)
        assertThat(PmShimmer.bandStartX(300f, 100f, 1f)).isEqualTo(300f)
    }

    @Test
    fun bandTraversalIsMonotonic() {
        var previous = PmShimmer.bandStartX(300f, 100f, 0f)
        var sweep = 0.1f
        while (sweep <= 1f) {
            val current = PmShimmer.bandStartX(300f, 100f, sweep)
            assertThat(current).isAtLeast(previous)
            previous = current
            sweep += 0.1f
        }
    }
}
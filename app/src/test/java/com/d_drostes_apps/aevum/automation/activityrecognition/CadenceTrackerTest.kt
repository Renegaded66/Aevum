package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * M18.118: CadenceTracker — Schrittfrequenz aus synthetischen
 * Beschleunigungs-Signalen (JVM, kein Sensor-Hardware nötig).
 *
 * Signale (Audit docs/activity-detection.md §4.2.2):
 *  - Joggen: 2,2–3,2 Hz, Amplitude ~1,5–3 m/s² → Cadence im Band.
 *  - Gehen: 1,5 Hz → Cadence messbar, aber UNTER dem Jogging-Band.
 *  - Auto: Vibration > 10 Hz, niederamplitudig (< 0,3 m/s²) → keine
 *    Schritte über der Amplitude-Schwelle → keine Cadence.
 */
class CadenceTrackerTest {

    /** Sinus-Signal mit Frequenz [hz] und Amplitude [ampMps2], 50 Hz. */
    private fun sine(
        hz: Double,
        ampMps2: Float,
        durationS: Double,
        sampleRateHz: Double = 50.0
    ): List<Pair<Long, Float>> {
        val n = (durationS * sampleRateHz).toInt()
        return (0 until n).map { i ->
            val t = i / sampleRateHz
            val mag = 9.81f + ampMps2 * sin(2 * PI * hz * t).toFloat()
            (t * 1000.0).toLong() to mag
        }
    }

    @Test
    fun `Joggen 2,4 Hz wird als Jogging-Cadence erkannt`() {
        val tracker = CadenceTracker()
        sine(hz = 2.4, ampMps2 = 2.0f, durationS = 60.0).forEach { (ts, mag) ->
            tracker.addSample(ts, mag)
        }
        val cadence = tracker.currentCadenceHz()
        assertThat(cadence).isNotNull()
        // Toleranz: Fenster-Zählung + High-Pass-Einschwingen.
        assertThat(cadence!!).isAtLeast(2.0f)
        assertThat(cadence).isAtMost(2.8f)
        assertThat(tracker.validFraction()).isEqualTo(1.0f)
        assertThat(
            DriveDetectionEngine.isJoggingCadence(cadence, tracker.validFraction())
        ).isTrue()
    }

    @Test
    fun `Gehen 1,5 Hz ist messbar, aber KEIN Joggen`() {
        val tracker = CadenceTracker()
        sine(hz = 1.5, ampMps2 = 1.5f, durationS = 60.0).forEach { (ts, mag) ->
            tracker.addSample(ts, mag)
        }
        val cadence = tracker.currentCadenceHz()
        assertThat(cadence).isNotNull()
        assertThat(cadence!!).isAtLeast(1.2f)
        assertThat(cadence).isAtMost(1.8f)
        assertThat(
            DriveDetectionEngine.isJoggingCadence(cadence, tracker.validFraction())
        ).isFalse()
    }

    @Test
    fun `Auto-Vibration 12 Hz niederamplitudig erzeugt KEINE Cadence`() {
        val tracker = CadenceTracker()
        // 12 Hz, aber nur 0,1 m/s² Amplitude — unter der Schritt-Schwelle.
        sine(hz = 12.0, ampMps2 = 0.1f, durationS = 60.0).forEach { (ts, mag) ->
            tracker.addSample(ts, mag)
        }
        assertThat(tracker.currentCadenceHz()).isNull()
        assertThat(tracker.validFraction()).isEqualTo(0f)
    }

    @Test
    fun `Messung unter einem Fenster liefert noch keine Cadence`() {
        val tracker = CadenceTracker()
        sine(hz = 2.4, ampMps2 = 2.0f, durationS = 5.0).forEach { (ts, mag) ->
            tracker.addSample(ts, mag)
        }
        assertThat(tracker.currentCadenceHz()).isNull()
    }

    @Test
    fun `Stillstand (nur Schwerkraft) erzeugt keine Cadence`() {
        val tracker = CadenceTracker()
        (0 until 3000).forEach { i ->
            tracker.addSample(i * 20L, 9.81f)
        }
        assertThat(tracker.currentCadenceHz()).isNull()
        assertThat(tracker.validFraction()).isEqualTo(0f)
    }
}

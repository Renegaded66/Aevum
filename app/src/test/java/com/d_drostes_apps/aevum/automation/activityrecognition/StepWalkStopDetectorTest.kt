package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.133: Step-Walk-Stop — Unit-Tests der puren Entscheidungslogik.
 *
 * User-Spezifikation: „Sobald ich aus dem Auto aussteige und gehe, bin ich
 * offensichtlich nicht mehr am Autofahren — die Aufzeichnung kann gestoppt
 * werden. Falls die Berechtigung erteilt ist, soll die Aufzeichnung
 * automatisch stoppen, sobald Schritte bzw. Gehen erkannt wird."
 *
 * Abgesichert werden die vier Regeln des Detektors:
 *  1. Geh-Kette (10 Schritte / 15 s) → STOPP
 *  2. Fahrzeug-Veto (frischer Herzschlag ODER ≥ 8 m/s) → kein Stop + Reset
 *  3. Artefakt-Grenze (defekter Step-Detector) → Verwerfen statt Stop
 *  4. Echo-Schutz (Doppel-Events) → kein zweiter Schritt
 */
class StepWalkStopDetectorTest {

    private val detector = StepWalkStopDetector()

    /** Ein Schritt pro 200 ms (= 5 Schritte/s Gehtempo ist zu schnell;
     *  real sind es 2 Schritte/s = 500 ms — hier bewusst schneller, damit
     *  die 10 Schritte im 15-s-Fenster bleiben). */
    private fun feedSteps(
        count: Int,
        startMs: Long,
        gapMs: Long = 500L,
        vehicleHeartbeatFresh: Boolean = false,
        probes: List<DriveDetectionEngine.DriveProbe> = emptyList()
    ): Boolean {
        var last = false
        for (i in 0 until count) {
            last = detector.onStep(startMs + i * gapMs, vehicleHeartbeatFresh, probes)
        }
        return last
    }

    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 10f,
        distanceFromLastM: Double? = null
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = distanceFromLastM,
        latitude = 50.0,
        longitude = 8.0
    )

    // ── 1) Geh-Kette ─────────────────────────────────────────────

    @Test
    fun `zehn Schritte im Fenster loesen den Stop aus`() {
        val t0 = 1_000_000L
        // Die ersten 9 Schritte sind noch kein Stop…
        for (i in 0 until StepWalkStopDetector.MIN_STEPS_IN_WINDOW - 1) {
            assertThat(detector.onStep(t0 + i * 500L)).isFalse()
        }
        // …der zehnte schon.
        val tenth = t0 + (StepWalkStopDetector.MIN_STEPS_IN_WINDOW - 1) * 500L
        assertThat(detector.onStep(tenth)).isTrue()
    }

    @Test
    fun `neun Schritte reichen nicht fuer den Stop`() {
        val t0 = 2_000_000L
        assertThat(feedSteps(9, t0)).isFalse()
    }

    @Test
    fun `alte Schritte ausserhalb des Fensters zaehlen nicht`() {
        val t0 = 3_000_000L
        // 9 Schritte, dann eine Luecke groesser als das Fenster, dann 9 weitere:
        // Die ersten verfallen, die Kette startet neu → kein Stop.
        feedSteps(9, t0)
        val gap = StepWalkStopDetector.STEP_WINDOW_MS + 5_000L
        assertThat(feedSteps(9, t0 + gap)).isFalse()
    }

    @Test
    fun `aussteigen mit wenigen Schritten genuegt im Fenster`() {
        // Realistisch: Aussteigen + ein paar Schritte zur Haustuer.
        val t0 = 4_000_000L
        assertThat(feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW, t0, gapMs = 700L)).isTrue()
    }

    // ── 2) Fahrzeug-Veto ─────────────────────────────────────────

    @Test
    fun `frischer Fahrzeug-Herzschlag blockiert den Stop`() {
        val t0 = 5_000_000L
        // Waehrend der Fahrt ist der Herzschlag frisch → selbst 20 Schritte
        // (Vibrations-Fehlzaehlung) loesen nichts aus.
        assertThat(feedSteps(20, t0, vehicleHeartbeatFresh = true)).isFalse()
    }

    @Test
    fun `Veto verwirft die Evidenz statt sie anzusparen`() {
        val t0 = 6_000_000L
        // 9 Schritte im Stillstand (kein Veto) …
        feedSteps(9, t0, vehicleHeartbeatFresh = false)
        // … dann ein Fahrzeug-Signal (Veto, Kette geleert) …
        detector.onStep(t0 + 5_000L, vehicleHeartbeatFresh = true)
        // … danach 9 neue Schritte → die alten 9 duerfen NICHT mitzaehlen.
        assertThat(feedSteps(9, t0 + 10_000L)).isFalse()
        // Der naechste Schritt komplettiert die Kette der 9.
        assertThat(detector.onStep(t0 + 10_000L + 9 * 500L)).isTrue()
    }

    @Test
    fun `GPS-Fahrzeug-Tempo widerlegt Gehen`() {
        val t0 = 7_000_000L
        // 40 km/h = 11,1 m/s → eindeutig Fahrzeug, kein Gehen.
        val probes = listOf(probe(t0 - 5_000L, speedMps = 11.1f))
        assertThat(feedSteps(15, t0, probes = probes)).isFalse()
    }

    @Test
    fun `langsames Rollen unter 8 m-s blockiert den Stop nicht`() {
        val t0 = 8_000_000L
        // 4 m/s (14 km/h) ist unter AUTO_SPEED_MPS und unter dem
        // Geh-Tempo-Veto → der Ausstieg muss erkannt werden. Wichtig:
        // Nach dem Parken rollt das Auto noch kurz, der User steigt aus.
        val probes = listOf(probe(t0 - 5_000L, speedMps = 4.0f))
        assertThat(feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW, t0, probes = probes)).isTrue()
    }

    @Test
    fun `alter GPS-Probe widerlegt Gehen nicht`() {
        val t0 = 9_000_000L
        // Ein Fix von vor 10 Minuten mit Fahrzeug-Tempo darf einen echten
        // Ausstieg nicht blockieren (VETO_PROBE_AGE_MS = 90 s).
        val old = t0 - 10 * 60_000L
        val probes = listOf(probe(old, speedMps = 12.0f))
        assertThat(feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW, t0, probes = probes)).isTrue()
    }

    @Test
    fun `abgeleitetes Fahrzeug-Tempo aus Distanz und Zeit widerlegt Gehen`() {
        val t0 = 10_000_000L
        // Fixes ohne Speed-Feld (M18.77-Fall): 600 m in 60 s = 10 m/s.
        val probes = listOf(
            probe(t0 - 65_000L, speedMps = null, accuracy = 10f),
            probe(t0 - 5_000L, speedMps = null, accuracy = 10f, distanceFromLastM = 600.0)
        )
        assertThat(feedSteps(15, t0, probes = probes)).isFalse()
    }

    @Test
    fun `ungenauer Probe zaehlt nicht als Fahrzeug-Evidenz`() {
        val t0 = 11_000_000L
        // Accuracy 80 m > MAX_ACCURACY_M (50) → kein Veto.
        val probes = listOf(probe(t0 - 5_000L, speedMps = 12.0f, accuracy = 80f))
        assertThat(feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW, t0, probes = probes)).isTrue()
    }

    // ── 3) Artefakt-Grenze ───────────────────────────────────────

    @Test
    fun `pathologische Schritt-Rate wird als Sensor-Artefakt verworfen`() {
        val t0 = 12_000_000L
        // 5-Hz-Zaehlung (Vibration): Der Detektor darf NIE stoppen — der
        // Sensor ist defekt (M18.126-Muster), nicht der User am Gehen.
        // Ohne die Ratengrenze haette die Kette nach 2 s gefeuert.
        var stopped = false
        for (i in 0 until 200) {
            stopped = detector.onStep(t0 + i * 200L) || stopped
        }
        assertThat(stopped).isFalse()
    }

    @Test
    fun `echtes Gehtempo passiert die Ratengrenze`() {
        val t0 = 12_500_000L
        // 2 Schritte/s (500 ms) = normales Gehen (120 Schritte/Min).
        // Der Span der 10er-Kette ist 4,5 s > 2,571 s → der Stop feuert.
        assertThat(feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW, t0, gapMs = 500L)).isTrue()
    }

    @Test
    fun `Sprint-Tempo passiert die Ratengrenze noch`() {
        val t0 = 12_800_000L
        // 3 Schritte/s (333 ms) = Sprint (180 Schritte/Min) — liegt unter
        // MAX_PLAUSIBLE_STEP_HZ (3,5), der Span ist 3,0 s > 2,571 s.
        assertThat(feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW, t0, gapMs = 333L)).isTrue()
    }

    @Test
    fun `nach dem Artefakt ist die Kette geleert - kein Spaet-Stop`() {
        val t0 = 13_000_000L
        // Artefakt-Fenster fuellen (Vibrations-Fehlzaehlung) …
        for (i in 0 until StepWalkStopDetector.MAX_STEPS_IN_WINDOW + 5) {
            detector.onStep(t0 + i * 200L)
        }
        // … danach normale Schritte. Der Abstand ist bewusst GROESSER als
        // das Beobachtungsfenster: Die Artefakt-Reste sind dann verfallen
        // und es braucht die volle, frische Kette.
        val after = t0 + (StepWalkStopDetector.MAX_STEPS_IN_WINDOW + 5) * 200L +
            StepWalkStopDetector.STEP_WINDOW_MS + 5_000L
        assertThat(feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW - 1, after)).isFalse()
        assertThat(detector.onStep(after + (StepWalkStopDetector.MIN_STEPS_IN_WINDOW - 1) * 500L)).isTrue()
    }

    // ── 4) Echo-Schutz ───────────────────────────────────────────

    @Test
    fun `Doppel-Event innerhalb der halben Sprint-Periode zaehlt nicht als zweiter Schritt`() {
        val t0 = 14_000_000L
        // Ein Schritt, dann ein Echo nach 50 ms (< MIN_STEP_GAP_MS = 150 ms).
        assertThat(detector.onStep(t0)).isFalse()
        assertThat(detector.onStep(t0 + 50L)).isFalse()
        // 9 weitere echte Schritte (je 500 ms) → nach dem Echo sind es
        // erst 9 gezaehlte Schritte → noch kein Stop.
        assertThat(feedSteps(8, t0 + 500L, gapMs = 500L)).isFalse()
        // Der naechste komplettiert die 10.
        assertThat(detector.onStep(t0 + 500L + 8 * 500L)).isTrue()
    }

    // ── Reset & Diagnose ─────────────────────────────────────────

    @Test
    fun `reset leert die Kette`() {
        val t0 = 15_000_000L
        feedSteps(9, t0)
        detector.reset()
        assertThat(detector.stepsInWindow(t0 + 10_000L)).isEqualTo(0)
        assertThat(feedSteps(9, t0 + 20_000L)).isFalse()
    }

    @Test
    fun `hasWalkingEvidence spiegelt den Ketten-Status`() {
        val t0 = 16_000_000L
        assertThat(detector.hasWalkingEvidence(t0)).isFalse()
        feedSteps(StepWalkStopDetector.MIN_STEPS_IN_WINDOW, t0)
        assertThat(detector.hasWalkingEvidence(t0 + 9 * 500L)).isTrue()
        // Nach dem Fenster-Ablauf ohne neue Schritte: keine Evidenz mehr.
        assertThat(detector.hasWalkingEvidence(t0 + 9 * 500L + StepWalkStopDetector.STEP_WINDOW_MS + 1_000L))
            .isFalse()
    }

    @Test
    fun `stepsInWindow zaehlt nur das aktuelle Fenster`() {
        val t0 = 17_000_000L
        feedSteps(5, t0)
        assertThat(detector.stepsInWindow(t0 + 4 * 500L)).isEqualTo(5)
        // Nach dem Fenster sind alle fuenf verfallen: Der LETZTE Schritt
        // liegt bei t0+2 s — er verlaesst das 15-s-Fenster erst nach
        // t0+17 s (Prune-Grenze ist ein striktes „> Fenster").
        assertThat(detector.stepsInWindow(t0 + StepWalkStopDetector.STEP_WINDOW_MS + 3_000L)).isEqualTo(0)
    }

    @Test
    fun `lastStepMs wird beim Verarbeiten gesetzt`() {
        val t0 = 18_000_000L
        assertThat(detector.lastStepMs).isEqualTo(0L)
        detector.onStep(t0)
        assertThat(detector.lastStepMs).isEqualTo(t0)
    }

    @Test
    fun `ohne Schritte feuert der Detektor nie`() {
        // Diagnose VOR dem ersten Schritt (der Aufruf selbst zaehlt einen Schritt).
        assertThat(detector.stepsInWindow(19_000_000L)).isEqualTo(0)
        assertThat(detector.hasWalkingEvidence(19_000_000L)).isFalse()
        assertThat(detector.onStep(19_000_000L)).isFalse()
    }
}

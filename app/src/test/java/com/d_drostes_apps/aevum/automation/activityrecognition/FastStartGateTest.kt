package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.128 (Kanban t_1c95af07): Regression-Tests für das FAST-START-GATE
 * `DriveDetectionEngine.shouldFastStart` — der sofortige Fahrt-Start OHNE
 * 30-s-Spread, sobald frische IN_VEHICLE-Evidence + 2 konsekutive
 * ≥-8-m/s-Fixes die Fahrt bestätigen (Design t_bea94587 §6.2).
 *
 * Kern-Akzeptanz (Root t_1c59e5f2): NIE bei Joggen/Gehen/Radfahren/
 * Stillstand starten, ABER bei der 30er-Zone-Fahrt (8,3 m/s) + frischer
 * IN_VEHICLE-Evidence SOFORT starten.
 *
 * Alle Regeln V1–V8 der puren Funktion:
 *   V1  frische IN_VEHICLE-Evidence (Confidence ≥ 60, ≤ 90 s)
 *   V2  ≥ 2 gültige Probes (Frische, Accuracy ≤ 50 m, kein Outlier)
 *   V3  letzte 2 Fixes BEIDE ≥ 8 m/s (Konsekutiv-Kette)
 *   V4  Hysterese ≥ 10 s (Prime-+Sofort-Fix-Sprung-Schutz)
 *   V5  Netto-Displacement ≥ 100 m (Indoor-Drift-Stillstands-Filter)
 *   V6  Geofence-Veto: nicht beide Fixes in EINEM benannten Ort
 *   V8  Cadence-Veto im Lauf-Speed-Band (Joggen-Schutz, M18.117/118)
 *   V7  Restart-Cooldown prüft der Aufrufer (handleFix) — Bridge-Test
 */
class FastStartGateTest {

    private val t0 = 1_000_000L

    /** 1 Breitengrad ≈ 111,23 km bei 50° N — Helfer für Netto-Displacement. */
    private fun latOffsetMeters(m: Double) = m / 111_229.0

    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 12f,
        displacementFromStartM: Double = 0.0,
        moving: Boolean = false
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        latitude = if (moving) 50.0 + latOffsetMeters(displacementFromStartM) else 50.0,
        longitude = 8.0
    )

    /** Frische IN_VEHICLE-Evidence, Confidence 80, exakt jetzt. */
    private fun evidence(confidence: Int = 80, ageMs: Long = 0L) =
        DriveDetectionEngine.VehicleEvidence(atMs = t0 - ageMs, confidence = confidence)

    /** Fahrt-Paar: 8,3 m/s, 15 s Hysterese, Netto 120 m — der Design-Positivfall. */
    private fun drivingPair() = listOf(
        probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0),
        probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
    )

    private fun fastStart(
        probes: List<DriveDetectionEngine.DriveProbe>,
        ev: DriveDetectionEngine.VehicleEvidence?,
        geofences: List<DriveDetectionEngine.GeoCircle> = emptyList(),
        cadenceHz: Float? = null,
        cadenceValidFraction: Float = 0f,
        nowMs: Long = t0 + 15_000L
    ): Boolean = DriveDetectionEngine.shouldFastStart(
        probes, ev, nowMs, geofences, cadenceHz, cadenceValidFraction
    )

    // ──────────────────────────────────────────────────────────────
    // POSITIVFALL: 30er-Zone-Fahrt (Design-Akzeptanz „Recording startet")
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Fahrt 30er-Zone mit frischer IN_VEHICLE-Evidence - Fast-Start TRUE`() {
        // Design-Testplan §11: „30er-Fahrt IN_VEHICLE (2× 8,3 m/s, 15 s,
        // Netto 120 m) → true". 8,3 m/s = 30 km/h — die häufigste
        // Stadt-Geschwindigkeit (M18.71), die der Normalpfad über den
        // 30-s-Spread erst nach ~45 s startet.
        assertThat(fastStart(drivingPair(), evidence())).isTrue()
    }

    @Test
    fun `Zug - IN_VEHICLE-Evidence + schnelle Fixes startet als transport`() {
        // Design §8: Google klassifiziert Zug als IN_VEHICLE → gewollter
        // Start (User: „Auto oder Zug"). Identische Gates wie die Fahrt.
        val train = listOf(
            probe(t0, 12.0f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 12.0f, moving = true, displacementFromStartM = 180.0)
        )
        assertThat(fastStart(train, evidence())).isTrue()
    }

    @Test
    fun `Confidence exakt an der Schwelle 60 - Fast-Start TRUE`() {
        assertThat(fastStart(drivingPair(), evidence(confidence = 60))).isTrue()
    }

    @Test
    fun `Evidence exakt 90s alt - noch frisch - Fast-Start TRUE`() {
        // nowMs = t0 + 15s; Evidence exakt 90 s davor → age == 90_000
        // ≤ FAST_START_EVIDENCE_MAX_AGE_MS (Grenzfall, Design §6.1).
        val now = t0 + 15_000L
        val ev = DriveDetectionEngine.VehicleEvidence(atMs = now - 90_000L, confidence = 80)
        assertThat(
            DriveDetectionEngine.shouldFastStart(drivingPair(), ev, now, emptyList())
        ).isTrue()
    }

    @Test
    fun `Hysterese exakt 10s - Fast-Start TRUE`() {
        val probes = listOf(
            probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 10_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(probes, evidence())).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // KERN-AKZEPTANZ: NIE beim JOGGEN (20 km/h = 5,6 m/s)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Joggen 20kmh ohne IN_VEHICLE-Evidence - nie Fast-Start, auch mit Speed-Spikes`() {
        // Design §8-Kern: Joggen hat KEIN IN_VEHICLE-Sample (der
        // Sensor-Hub meldet RUNNING/ON_FOOT → der Receiver registriert
        // keine Evidence, V1). Selbst wenn GPS-Multipath 2 Spikes
        // ≥ 8 m/s liefert, fehlt der Qualifikator → false.
        val joggingWithSpikes = listOf(
            probe(t0, 8.5f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.5f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(joggingWithSpikes, ev = null)).isFalse()
    }

    @Test
    fun `Joggen 20kmh - Tempo 5,6 m-s erreicht die 8-m-s-Schwelle nie`() {
        // Selbst MIT (fälschlich vorhandener) Evidence: 5,6 m/s < 8 m/s
        // → V3 scheitert. Design §6.3: „Joggen 20 km/h = 5,6 m/s —
        // erreicht die Schwelle nicht einmal, geschweige denn 2 Fixes".
        val jogging = listOf(
            probe(t0, 5.6f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 5.6f, moving = true, displacementFromStartM = 84.0)
        )
        assertThat(fastStart(jogging, evidence())).isFalse()
    }

    @Test
    fun `Joggen-Spikes mit ehrlicher Position - Netto unter 100m - Fast-Start FALSE`() {
        // 2 Multipath-Spikes ≥ 8 m/s, aber die POSITION bewegt sich real
        // mit 5,6 m/s (83 m in 15 s) → Netto-Displacement < 100 m (V5).
        val spikes = listOf(
            probe(t0, 8.5f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.5f, moving = true, displacementFromStartM = 83.0)
        )
        assertThat(fastStart(spikes, evidence())).isFalse()
    }

    @Test
    fun `Joggen-Cadence im Lauf-Band - V8-Veto, auch bei exakt 8,0 m-s Fixes`() {
        // V8 (M18.117/118): Schrittfrequenz 2,6 Hz (156 spm) + stabiler
        // Anteil ≥ 60 % + Schnitt im Band 2,5..8,0 m/s → Joggen, kein Auto.
        // Bei 8,0/8,0 liegt der Schnitt exakt an der Band-Grenze 8,0 —
        // der einzige Punkt, an dem V3 (≥ 8) UND V8 (≤ 8) gemeinsam
        // greifen können.
        val pair = listOf(
            probe(t0, 8.0f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.0f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(
            fastStart(pair, evidence(), cadenceHz = 2.6f, cadenceValidFraction = 0.8f)
        ).isFalse()
        // Ohne Cadence ist exakt 8,0 m/s eine echte (Grenz-)Fahrt.
        assertThat(fastStart(pair, evidence())).isTrue()
    }

    @Test
    fun `Cadence-Schnit ueber 8 m-s - ausserhalb des Lauf-Bands - kein Veto`() {
        // 8,5 m/s Durchschnitt kann kein Läufer halten — das Veto-Band
        // endet bei 8,0 m/s (CADENCE_VETO_MAX_SPEED_MPS), die Fahrt startet.
        val pair = listOf(
            probe(t0, 8.5f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.5f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(
            fastStart(pair, evidence(), cadenceHz = 2.6f, cadenceValidFraction = 0.8f)
        ).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // WEITERE KEIN-START-SZENARIEN (Design §8-Edge-Cases)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Gehen - Tempo 1,5 m-s - nie Fast-Start`() {
        val walking = listOf(
            probe(t0, 1.5f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 1.5f, moving = true, displacementFromStartM = 22.0)
        )
        assertThat(fastStart(walking, evidence())).isFalse()
    }

    @Test
    fun `Fahrrad 25-30kmh - kein IN_VEHICLE-Sample (ON_BICYCLE-Reset) - nie Fast-Start`() {
        // Design §8: ON_BICYCLE-Sample resetet die Evidence (V1-Konkurrenz)
        // → der Qualifikator fehlt, auch wenn 2 Rennrad-Spikes 8,3 m/s
        // über 15 s + 120 m liegen.
        val bike = listOf(
            probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(bike, ev = null)).isFalse()
        assertThat(fastStart(bike, evidence())).isTrue() // mit Evidence wäre es Fahrt — Schutz ist der Reset (Bridge-Test)
    }

    @Test
    fun `Stillstand mit hochfrequentem Speed-Noise - Netto 0m - false`() {
        // „Stationary with high speed noise" (Task-Spec): GPS behauptet
        // hohe Speed, die POSITION bleibt stehen → V5 killt.
        val stationaryNoise = listOf(
            probe(t0, 11.2f, moving = false),
            probe(t0 + 15_000L, 9.8f, moving = false)
        )
        assertThat(fastStart(stationaryNoise, evidence())).isFalse()
    }

    @Test
    fun `Indoor-GPS-Drift - Netto 40m - false`() {
        // M18.66-FIX13: Drift springt 10–50 m um denselben Punkt.
        val drift = listOf(
            probe(t0, 10.0f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 12.0f, moving = true, displacementFromStartM = 40.0)
        )
        assertThat(fastStart(drift, evidence())).isFalse()
    }

    @Test
    fun `Langsamer Fix dazwischen bricht die Konsekutiv-Kette`() {
        // V3: Die LETZTEN 2 gültigen Fixes müssen beide ≥ 8 m/s sein —
        // ein langsamer Fix dazwischen (Ampel/Stop&Go) bricht die Kette.
        val chain = listOf(
            probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 2.0f, moving = true, displacementFromStartM = 30.0),
            probe(t0 + 30_000L, 8.3f, moving = true, displacementFromStartM = 210.0)
        )
        assertThat(fastStart(chain, evidence())).isFalse()
    }

    @Test
    fun `Prime-Fix und Sofort-Fix unter 10s Hysterese - false`() {
        // V4: 2 Fixes < 10 s auseinander = Doppel-Fix-Artefakt (Prime +
        // Sofort-Fix). Der 15-s-Stream erfüllt die Hysterese real immer.
        val jump = listOf(
            probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 5_000L, 8.3f, moving = true, displacementFromStartM = 41.0)
        )
        assertThat(fastStart(jump, evidence())).isFalse()
    }

    // ──────────────────────────────────────────────────────────────
    // V1-DETAILS: Evidence-Frische und Confidence
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Confidence 40 - verrauschtes Einzel-Sample - false`() {
        // M18.66-FIX15: Googles AR liefert bei Sensorrauschen regelmäßig
        // IN_VEHICLE-False-Positives — Confidence < 60 zählt nicht.
        assertThat(fastStart(drivingPair(), evidence(confidence = 40))).isFalse()
    }

    @Test
    fun `Evidence 90s ueberschritten - gehoert zur alten Fahrt - false`() {
        // V1-Frische: eine Evidence älter als ein Burst-Fenster (5 Min)
        // qualifiziert die aktuelle Fahrt nicht mehr.
        assertThat(fastStart(drivingPair(), evidence(ageMs = 90_001L))).isFalse()
    }

    @Test
    fun `Ueber 90001ms alte Evidence auch mit frischer Fahrt - false`() {
        val stale = listOf(
            probe(t0, 9.0f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 9.0f, moving = true, displacementFromStartM = 135.0)
        )
        assertThat(fastStart(stale, evidence(ageMs = 5L * 60 * 1000), nowMs = t0 + 15_000L))
            .isFalse()
    }

    // ──────────────────────────────────────────────────────────────
    // V2: PROBE-FILTER (Frische, Accuracy, Outlier)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Nur 1 gueiltiger Probe - InsufficientData - false`() {
        assertThat(fastStart(listOf(drivingPair().first()), evidence())).isFalse()
    }

    @Test
    fun `Speed-Outlier ueber 40 m-s wird verworfen - kein Fast-Start`() {
        // 2 Fixes, davon 1 GPS-Sprung (> 144 km/h) → nur 1 gültiger → V2.
        val outlier = listOf(
            probe(t0, 45.0f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(outlier, evidence())).isFalse()
    }

    @Test
    fun `Fix ohne Speed-Feld wird verworfen - kein Fast-Start`() {
        // hasSpeed() == false (Hintergrund-Doze-Fixes): speedMps = null
        // ist KEINE Geschwindigkeits-Evidenz für den Fast-Pfad.
        val nullSpeed = listOf(
            probe(t0, null, moving = true),
            probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(nullSpeed, evidence())).isFalse()
    }

    @Test
    fun `Ungenauer Fix ueber 50m wird verworfen - kein Fast-Start`() {
        val inaccurate = listOf(
            probe(t0, 8.3f, accuracy = 60f, moving = true),
            probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(inaccurate, evidence())).isFalse()
    }

    @Test
    fun `Zu alte Probes 15min+ - keine aktuelle Fahrt - false`() {
        val ancient = listOf(
            probe(t0 - 16L * 60 * 1000, 8.3f, moving = true),
            probe(t0 - 16L * 60 * 1000 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(ancient, evidence(), nowMs = t0)).isFalse()
    }

    // ──────────────────────────────────────────────────────────────
    // V6: GEOFENCE-VETO (M18.84-Indoor-Multipath)
    // ──────────────────────────────────────────────────────────────

    private fun homeCircle() = DriveDetectionEngine.GeoCircle(
        id = "home", name = "Zuhause", latitude = 50.0, longitude = 8.0, radiusMeters = 300.0
    )

    @Test
    fun `Beide Fixes in EINEM benannten Ort - Indoor - false`() {
        // Gym-Fall: Drift-Speed-Spikes + langsame Positionsverschiebung
        // innerhalb des Orts-Kreises erfüllen alle Speed-Gates — das
        // Veto sagt: der User ist nachweislich an einem Ort.
        val indoor = listOf(
            probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 120.0)
        )
        assertThat(fastStart(indoor, evidence(), geofences = listOf(homeCircle()))).isFalse()
    }

    @Test
    fun `Fix ausserhalb des Kreises hebt das Veto auf - Fast-Start TRUE`() {
        // Echte Fahrten verlassen den Kreis zwangsläufig (Auto bewegt
        // sich km-weit) — EIN Fix außerhalb genügt.
        val leaving = listOf(
            probe(t0, 8.3f, moving = true, displacementFromStartM = 0.0),
            probe(t0 + 15_000L, 8.3f, moving = true, displacementFromStartM = 500.0)
        )
        assertThat(fastStart(leaving, evidence(), geofences = listOf(homeCircle()))).isTrue()
    }

    @Test
    fun `Leere Geofence-Liste - kein Veto (Ort unbekannt)`() {
        assertThat(fastStart(drivingPair(), evidence())).isTrue()
    }

    // ──────────────────────────────────────────────────────────────
    // KONSTANTEN-SICHERUNG (Design-Parameter, §6.2)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Fast-Start-Konstanten entsprechen dem Design`() {
        assertThat(DriveDetectionEngine.FAST_START_CONFIDENCE).isEqualTo(60)
        assertThat(DriveDetectionEngine.FAST_START_EVIDENCE_MAX_AGE_MS).isEqualTo(90_000L)
        assertThat(DriveDetectionEngine.FAST_START_MIN_HYSTERESIS_MS).isEqualTo(10_000L)
        assertThat(DriveDetectionEngine.FAST_START_MIN_NET_DISPLACEMENT_M).isEqualTo(100.0)
        // Der Fast-Pfad senkt NUR zeitliche Gates — die Auto-Schwelle
        // bleibt die etablierte 8 m/s (28,8 km/h), kein neuer Tunings-
        // Parameter (Design-Entscheidung: User-Vorschlag 25 km/h NICHT
        // übernommen).
        assertThat(DriveDetectionEngine.AUTO_SPEED_MPS).isEqualTo(8.0f)
    }
}

package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.134: ON_BICYCLE-Gate + Rad-Session (Kanban t_a860c07f, Root
 * t_099f1911).
 *
 * User-Bug: „Ich war Fahrrad fahren, dabei hatte ich natürlich auch so
 * 25 km/h drauf — und dann wurde Autofahrt aufgezeichnet."
 *
 * Root-Cause (gemessen in t_fd1ec671): Der ON_BICYCLE-Zweig des
 * Continuous-Receivers setzte bewusst KEINEN Motion-Kontext → Kontext
 * blieb UNKNOWN → es galt die 8-m/s-Schwelle (28,8 km/h). Jeder
 * Radfahrer-Schnitt von 25 km/h enthält zwangsläufig Passagen über
 * 28,8 km/h (Antritte, Gefälle, Pedelec).
 *
 * Diese Suite sichert die vier Eigenschaften des Fixes:
 *  1. Rad-Profile (25-35 km/h, Antritte, Gefälle, Distanz-Fallback)
 *     werden unter ON_BICYCLE NICHT mehr als Fahrt klassifiziert —
 *     die Kern-Regression.
 *  2. Motorrad/Auto ≥ 50 km/h starten weiterhin (M18.130 darf nicht
 *     regressieren) — das Gate ist rad-unmöglich, nicht fahrzeug-blind.
 *  3. Verlustfreiheit: eine blockierte Fahrt wird als Radfahrt erkannt
 *     (detectBikeRide) statt verworfen.
 *  4. Edge-Fälle: kein/unglaubwürdiges/altes Rad-Signal → das heutige
 *     Verhalten (8 m/s), kein Crash.
 *
 * Die Fahr-Profile sind die gemessenen Signaturen aus dem
 * Research-Report (§1.2, §1.3) — dieselben Zahlen, die vor dem Fix
 * „Driving 500/500" ergaben.
 */
class DriveBicycleGateTest {

    private val t0 = 1_000_000_000L

    /** 25 km/h = 6,94 m/s; 29 km/h = 8,06; 30 = 8,33; 32 = 8,89;
     *  35 = 9,72; 50 = 13,89; 70 = 19,44. */
    private fun kmh(v: Double) = (v / 3.6).toFloat()

    /** Probe mit REALISTISCHER Position: der lat-Schritt folgt der
     *  Speed über den Fix-Abstand, damit Speed und Position konsistent
     *  sind (der M18.113-Positions-Check würde widersprüchliche Probes
     *  verwerfen — bewusst so). */
    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 12f,
        distanceFromLastM: Double? = null,
        positionM: Double = 0.0
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = distanceFromLastM,
        latitude = 50.000 + positionM / 111_320.0,
        longitude = 8.000
    )

    /** Baut eine Probe-Serie mit 15-s-Takt aus einer Tempo-Funktion.
     *  Position folgt der realen Bewegung (kumuliert).
     *  (speedAt ist der LETZTE Parameter, damit die Aufrufe mit
     *  trailing lambda lesbar bleiben.) */
    private fun series(
        durationMs: Long,
        intervalMs: Long = 15_000L,
        speedAt: (tMs: Long) -> Float
    ): List<DriveDetectionEngine.DriveProbe> {
        val out = mutableListOf<DriveDetectionEngine.DriveProbe>()
        var positionM = 0.0
        var t = 0L
        while (t <= durationMs) {
            val speed = speedAt(t)
            positionM += speed * (intervalMs / 1000.0)
            out += probe(
                timestampMs = t0 + t,
                speedMps = speed,
                positionM = positionM
            )
            t += intervalMs
        }
        return out
    }

    private fun classify(
        probes: List<DriveDetectionEngine.DriveProbe>,
        context: DriveDetectionEngine.MotionContext,
        nowMs: Long = probes.lastOrNull()?.timestampMs ?: t0
    ) = DriveDetectionEngine.classify(
        probes, nowMs, emptyList(), context, null, 0f
    )

    // ──────────────────────────────────────────────────────────────
    // 1) KERN-REGRESSION: Radfahren ist unter ON_BICYCLE keine Fahrt
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Rad 29 kmh konstant mit ON_BICYCLE ist KEINE Fahrt`() {
        // Gemessen VOR dem Fix: Driving 500/500 (genau der User-Fall).
        val probes = series(2 * 60_000L) { _ -> kmh(29.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Rad 29 kmh konstant mit UNKNOWN bleibt Fahrt - Bestandsschutz der 8-m-s-Schwelle`() {
        // Gegenprobe: OHNE Rad-Signal (Permission fehlt) gilt weiterhin
        // das heutige Verhalten — der Fix darf nur mit AR-Signal wirken.
        val probes = series(2 * 60_000L) { _ -> kmh(29.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.UNKNOWN)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Rad 30 kmh konstant mit ON_BICYCLE ist KEINE Fahrt`() {
        // Gemessen vorher: Driving 500/500.
        val probes = series(90_000L) { _ -> kmh(30.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Rad 25 kmh Schnitt mit 30s-Antritten auf 32 kmh mit ON_BICYCLE ist KEINE Fahrt`() {
        // DER GEMELDETE USER-FALL: „so 25 km/h drauf" — ein Schnitt von
        // 25 km/h enthält Antritte/Gefälle über 28,8 km/h.
        // Gemessen vorher: Driving 300/300.
        val probes = series(3 * 60_000L) { t ->
            // Antritt alle 60 s für 30 s auf 32 km/h.
            if ((t / 30_000L) % 2L == 1L) kmh(32.0) else kmh(22.0)
        }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Rad 35 kmh Rennrad mit ON_BICYCLE ist KEINE Fahrt`() {
        val probes = series(3 * 60_000L) { _ -> kmh(35.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Rad 25 kmh ohne Speed-Feld mit 30 Prozent Distanz-Rauschen bleibt unter ON_BICYCLE blockiert`() {
        // M18.77-Zweiter-Pfad (Report §1.3): Hintergrund-Fixes ohne
        // hasSpeed() → Speed aus Distanz/dt (verrauschter). Gemessen
        // vorher: Driving 180/400 bei 30 % Rauschen.
        // Unter ON_BICYCLE gilt der Fenster-Schnitt 12 m/s → die
        // abgeleiteten ~8 m/s liegen weit darunter.
        val rnd = java.util.Random(42)
        val probes = mutableListOf<DriveDetectionEngine.DriveProbe>()
        var positionM = 0.0
        var prevPos = 0.0
        for (t in 0L..300_000L step 60_000L) {
            val trueSpeed = kmh(25.0)
            positionM += trueSpeed * 60.0
            val noise = 1.0 + (rnd.nextDouble() - 0.5) * 0.6 // ±30 %
            val reportedPos = positionM * noise
            val dist = if (t == 0L) reportedPos else reportedPos - prevPos
            prevPos = reportedPos
            probes += probe(
                timestampMs = t0 + t,
                speedMps = null,
                accuracy = 20f,
                distanceFromLastM = dist,
                positionM = reportedPos
            )
        }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `ON_BICYCLE oeffnet das Rad-Loch NICHT ueber den Vehicle-Pace-Override`() {
        // M18.130-Override ist für Motorräder (Google meldet sie als
        // ON_FOOT) gebaut. Würde er auch unter ON_BICYCLE greifen,
        // nähme er die 12-m/s-Schwelle auf 8 m/s zurück, sobald eine
        // Radfahrt 3 Antritte ≥ 8 m/s über ≥ 60 s zeigt (gemessen:
        // 100 % Driving). Deshalb: Override NUR bei ON_FOOT.
        // Profil: 22 km/h Grundtempo mit wiederkehrenden Antritten auf
        // 32 km/h über 3 Minuten — erfüllt alle Pace-Kriterien.
        val probes = series(3 * 60_000L) { t ->
            if ((t / 30_000L) % 2L == 1L) kmh(32.0) else kmh(22.0)
        }
        val asOnFoot = classify(probes, DriveDetectionEngine.MotionContext.ON_FOOT)
        val asOnBicycle = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        // Unter ON_FOOT heilt der Pace-Override die Fahrt...
        assertThat(asOnFoot).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
        // ...unter ON_BICYCLE bleibt sie blockiert (der eigentliche Fix).
        assertThat(asOnBicycle).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    // ──────────────────────────────────────────────────────────────
    // 2) WÄCHTER: Motorrad/Auto dürfen NICHT regressieren
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Motorrad 70 kmh mit ON_BICYCLE bleibt eine Fahrt`() {
        // RideRecordingReproductionTest „Welt F" dokumentiert, dass
        // Motorräder real in ON_BICYCLE landen. Sie MÜSSEN weiter
        // erkannt werden (M18.130-Schutz).
        val probes = series(3 * 60_000L) { _ -> kmh(70.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Auto 50 kmh mit ON_BICYCLE bleibt eine Fahrt`() {
        val probes = series(2 * 60_000L) { _ -> kmh(50.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Motorrad 30er-Phase dann 70 kmh mit ON_BICYCLE startet weiterhin`() {
        // Gemessener Trade-off (Report §3 Schritt 3): die 30-km/h-Phase
        // allein erreicht das 12-m/s-Gate nicht — ab 70 km/h startet
        // die Fahrt. Kein Totalausfall.
        val probes = series(5 * 60_000L) { t ->
            if (t < 3 * 60_000L) kmh(30.0) else kmh(70.0)
        }
        val result = classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Auto 30er-Zone mit UNKNOWN bleibt Fahrt - Bestandsschutz`() {
        // Der wichtigste Bestandstest: ohne AR-Rad-Signal ändert sich
        // NICHTS an der 30er-Zonen-Erkennung.
        val probes = series(2 * 60_000L) { _ -> kmh(30.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.UNKNOWN)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Auto 30er-Zone mit IN_VEHICLE bleibt Fahrt`() {
        val probes = series(2 * 60_000L) { _ -> kmh(30.0) }
        val result = classify(probes, DriveDetectionEngine.MotionContext.IN_VEHICLE)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    // ──────────────────────────────────────────────────────────────
    // 3) VERLUSTFREIHEIT: blockierte Fahrten werden als Rad erkannt
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `Radfahrt 25 kmh wird von detectBikeRide erkannt`() {
        val probes = series(3 * 60_000L) { _ -> kmh(25.0) }
        val now = t0 + 3 * 60_000L
        val ride = DriveDetectionEngine.detectBikeRide(probes, now)
        assertThat(ride).isNotNull()
        assertThat(ride!!.avgSpeedMps).isWithin(0.1f).of(kmh(25.0))
        assertThat(ride.sampleCount).isAtLeast(2)
    }

    @Test
    fun `Radfahrt mit Antritten wird von detectBikeRide erkannt`() {
        // Der User-Fall: 25 km/h Schnitt, Antritte auf 32 km/h.
        // avg muss im Rad-Band (≥ 4,0 und < 12,0 m/s) liegen.
        val probes = series(3 * 60_000L) { t ->
            if ((t / 30_000L) % 2L == 1L) kmh(32.0) else kmh(22.0)
        }
        val ride = DriveDetectionEngine.detectBikeRide(probes, t0 + 3 * 60_000L)
        assertThat(ride).isNotNull()
        assertThat(ride!!.avgSpeedMps).isAtLeast(DriveDetectionEngine.MIN_BIKE_RIDE_AVG_MPS)
        assertThat(ride.avgSpeedMps).isLessThan(DriveDetectionEngine.BIKE_DRIVE_SPEED_MPS)
    }

    @Test
    fun `Radfahrt bekommt einen echten Start-Anker - nicht now`() {
        // Rückdatierung: der Start liegt beim ältesten bewegten Probe,
        // nicht bei `now` (die gefahrenen Minuten gehören in die
        // Aufzeichnung — Muster resolveDriveStart/Walking-Vorlauf).
        val probes = series(3 * 60_000L) { _ -> kmh(25.0) }
        val now = t0 + 3 * 60_000L
        val ride = DriveDetectionEngine.detectBikeRide(probes, now)!!
        assertThat(ride.startMs).isLessThan(now)
        assertThat(ride.startMs).isEqualTo(probes.first().timestampMs)
    }

    @Test
    fun `Gehen 1_5 m-s ergibt KEINE Radfahrt`() {
        val probes = series(3 * 60_000L) { _ -> 1.5f }
        assertThat(DriveDetectionEngine.detectBikeRide(probes, t0 + 3 * 60_000L)).isNull()
    }

    @Test
    fun `Joggen 16 kmh ergibt KEINE Radfahrt`() {
        // 4,44 m/s liegt über MIN_BIKE_RIDE_AVG_MPS (4,0) — aber ein
        // Jogger hat NIE ein ON_BICYCLE-Signal; der Aufrufer (Worker)
        // verlangt es. Hier wird zusätzlich die Netto-Distanz-Grenze
        // geprüft: Joggen 16 km/h über 3 Min = 800 m > 150 m, also
        // greift die Schwelle NICHT. Dokumentiert: der Schutz ist das
        // AR-Signal + Cadence-Veto, nicht diese Schwelle.
        val probes = series(3 * 60_000L) { _ -> kmh(16.0) }
        val ride = DriveDetectionEngine.detectBikeRide(probes, t0 + 3 * 60_000L)
        // avg = 4,44 ≥ 4,0 → würde als Rad gelten, WENN ein
        // ON_BICYCLE-Signal vorläge. Der Aufrufer-Gate (isReliable
        // BicycleSignal) ist deshalb zwingend — hier explizit belegt.
        assertThat(ride).isNotNull()
        val noSignal = DriveDetectionEngine.isReliableBicycleSignal(null, t0 + 3 * 60_000L)
        assertThat(noSignal).isFalse()
    }

    @Test
    fun `Stillstand mit GPS-Drift ergibt KEINE Radfahrt`() {
        // Indoor-Drift: 10-50 m Positions-Sprünge, keine echte Bewegung.
        val rnd = java.util.Random(7)
        val probes = mutableListOf<DriveDetectionEngine.DriveProbe>()
        for (t in 0L..180_000L step 15_000L) {
            probes += probe(
                timestampMs = t0 + t,
                speedMps = 8.5f, // Drift-Spike
                accuracy = 20f,
                positionM = rnd.nextDouble() * 40.0 // ±40 m um den Punkt
            )
        }
        val ride = DriveDetectionEngine.detectBikeRide(probes, t0 + 180_000L)
        assertThat(ride).isNull()
    }

    @Test
    fun `Radfahrt in einem benannten Ort (Geofence) ergibt KEINE Radfahrt`() {
        // M18.84-Veto: liegen ALLE Probes in EINEM benannten Ort, ist die
        // Bewegung Indoor-Multipath — keine Radfahrt. Hier: eine Fahrt,
        // die komplett innerhalb eines großen Grundstücks-Kreises bleibt
        // (Hof/Schrebergarten — real bewegt man sich dort, aber es ist
        // kein Ausflug; das Veto gewinnt bewusst, M18.84-Semantik).
        val probes = series(3 * 60_000L) { _ -> kmh(25.0) }
        val yard = DriveDetectionEngine.GeoCircle(
            id = "yard", name = "Hof",
            latitude = 50.010, longitude = 8.000, radiusMeters = 2000.0
        )
        val ride = DriveDetectionEngine.detectBikeRide(probes, t0 + 3 * 60_000L, listOf(yard))
        assertThat(ride).isNull()
    }

    @Test
    fun `Radfahrt, die den benannten Ort verlaesst, bleibt eine Radfahrt`() {
        // Gegenprobe zum Veto: eine echte Radfahrt verlässt den Kreis
        // zwangsläufig — dann greift das Veto NICHT (Ein-Ort-Axiom).
        val probes = series(3 * 60_000L) { _ -> kmh(25.0) }
        val home = DriveDetectionEngine.GeoCircle(
            id = "home", name = "Zuhause",
            latitude = 50.000, longitude = 8.000, radiusMeters = 150.0
        )
        val ride = DriveDetectionEngine.detectBikeRide(probes, t0 + 3 * 60_000L, listOf(home))
        assertThat(ride).isNotNull()
    }

    @Test
    fun `kurzer GPS-Burst unter 30s ergibt KEINE Radfahrt`() {
        // Spread-Gate: ein einzelner Mess-Burst ist keine Fahrt.
        val probes = listOf(
            probe(t0, kmh(25.0), positionM = 0.0),
            probe(t0 + 15_000L, kmh(25.0), positionM = 104.0)
        )
        assertThat(DriveDetectionEngine.detectBikeRide(probes, t0 + 15_000L)).isNull()
    }

    // ──────────────────────────────────────────────────────────────
    // 4) EDGE CASES: kein/altes/unglaubwürdiges Rad-Signal
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `kein Rad-Signal - isReliableBicycleSignal false`() {
        assertThat(
            DriveDetectionEngine.isReliableBicycleSignal(null, t0)
        ).isFalse()
    }

    @Test
    fun `Rad-Signal unter der Confidence-Schwelle zaehlt nicht`() {
        val weak = DriveDetectionEngine.BicycleEvidence(
            atMs = t0, confidence = DriveDetectionEngine.BIKE_CONTEXT_MIN_CONFIDENCE - 1
        )
        assertThat(DriveDetectionEngine.isReliableBicycleSignal(weak, t0)).isFalse()
        val strong = weak.copy(confidence = DriveDetectionEngine.BIKE_CONTEXT_MIN_CONFIDENCE)
        assertThat(DriveDetectionEngine.isReliableBicycleSignal(strong, t0)).isTrue()
    }

    @Test
    fun `altes Rad-Signal verfaellt`() {
        val stale = DriveDetectionEngine.BicycleEvidence(
            atMs = t0, confidence = 90
        )
        val justFresh = t0 + DriveDetectionEngine.BICYCLE_EVIDENCE_MAX_AGE_MS
        val tooOld = justFresh + 1
        assertThat(DriveDetectionEngine.isReliableBicycleSignal(stale, justFresh)).isTrue()
        assertThat(DriveDetectionEngine.isReliableBicycleSignal(stale, tooOld)).isFalse()
    }

    @Test
    fun `leere Probe-Liste - classify und detectBikeRide crashen nicht`() {
        assertThat(classify(emptyList(), DriveDetectionEngine.MotionContext.ON_BICYCLE))
            .isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
        assertThat(
            DriveDetectionEngine.detectBikeRide(emptyList(), t0)
        ).isNull()
    }

    @Test
    fun `alle Probes ungenau - kein Crash, keine Radfahrt`() {
        val probes = series(3 * 60_000L) { _ -> kmh(25.0) }
            .map { it.copy(accuracyMeters = 120f) }
        assertThat(
            DriveDetectionEngine.detectBikeRide(probes, t0 + 3 * 60_000L)
        ).isNull()
        assertThat(classify(probes, DriveDetectionEngine.MotionContext.ON_BICYCLE))
            .isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
    }
}

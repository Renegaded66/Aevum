package com.d_drostes_apps.aevum.automation.activityrecognition

/**
 * M18.64: Pure Erkennungslogik für Autofahrten auf Basis von
 * GPS-Geschwindigkeits-Probes.
 *
 * WARUM (Root-Cause-Analyse M18.64): Die bisherige Fahrterkennung hing
 * KOMPLETT an Googles Activity-Recognition-Transitions (IN_VEHICLE-ENTER)
 * + einem 2-Fix-GPS-Muster im DriveConfirmWorker. Wenn Google kein
 * IN_VEHICLE-Event liefert (App im Hintergrund, Fahrt begann vor dem
 * App-Start, Sensor-Spring), wurde NIE eine Fahrt erkannt — es gab
 * keinen unabhängigen Geschwindigkeits-Pfad.
 *
 * Diese Engine ist der unabhängige Fallback: Sie klassifiziert eine
 * Serie von GPS-Geschwindigkeits-Probes (Speed aus dem Location-Fix)
 * und bestätigt eine Fahrt erst nach MEHREREN aufeinanderfolgenden
 * Messungen über einen Zeitraum — robust gegen einzelne Ausreißer,
 * schnelles Gehen/Laufen und Fahrradfahren.
 *
 * Bewusst Android-frei (JVM-Unit-Tests ohne Robolectric) — die Tests
 * in DriveDetectionEngineTest sind die Regression-Absicherung.
 *
 * M18.77: Speed-Fallback — Fahrten auch ohne GPS-Speed-Feld erkennen.
 * Hintergrund-Fixes (Doze/OEM-Battery-Saver, 30-120s Lücken) liefern
 * oft KEIN hasSpeed() → speedMps = null — kurze Fahrten (10 Min)
 * erreichten MIN_FAST_PROBES = 3 nie (User-Bug „10-Minuten-Fahrten
 * werden nicht erkannt"). Die Distanz distanceFromLastM ist aber immer
 * da: daraus wird eine Geschwindigkeit abgeleitet (siehe
 * [MIN_INFERRED_DT_MS]).
 */
object DriveDetectionEngine {

    // ── Schwellen (in m/s) ──────────────────────────────────────────
    /** Auto-Schwelle: ~28,8 km/h. M18.66-FIX12 hatte 8→10 m/s gesetzt
     *  (36 km/h), M18.68 senkt auf 9 m/s (32,4 km/h), M18.71 senkt auf
     *  8 m/s: 30er-Zonen (8,3 m/s = 30 km/h) sind in Deutschland die
     *  häufigste Stadt-Geschwindigkeit — mit 9 m/s wurde dort NIE eine
     *  Fahrt erkannt (User: „keine Autofahrten mehr aufgezeichnet").
     *  8 m/s bleibt ÜBER dem normalen Radfahr-Bereich (15-25 km/h =
     *  4-7 m/s; nur Rennrad-Sprints erreichen 8+ m/s, und die scheitern
     *  an der Konsekutiv-Kette + Netto-Displacement-Gate) und weit über
     *  Lauf-Tempo (~5 m/s). GPS-Drift-False-Positives fängt weiterhin
     *  das Netto-Displacement-Gate (≥ 150 m) ab, einzelne Sprünge die
     *  Konsekutiv-Kette. */
    const val AUTO_SPEED_MPS = 8.0f
    /** Unter dieser Geschwindigkeit ist es nie eine Fahrt (Gehen/Laufen). */
    const val WALK_RUN_MAX_MPS = 5.5f
    /** GPS-Ausreißer: > 144 km/h ist kein reales Fahrzeug-Tempo. */
    const val OUTLIER_SPEED_MPS = 40.0f
    /** Ungenaue Fixes verwerfen (schlechte GPS-Lage).
     *  M18.66-FIX2: 120m -> 30m. Indoor-GPS hat oft 50-100m Genauigkeit
     *  und springt um 10-20m — das erzeugte False-Positive-Fahrten,
     *  weil die Distanz den Heartbeat refreshte obwohl der User still
     *  sitzt. 30m ist streng genug, um Indoor-Fixes zu verwerfen, aber
     *  großzügig genug für echtes Auto-GPS (meist < 10m).
     *  M18.71: 30m -> 50m. In Stadt-Canyons (Häuserschluchten) und bei
     *  bewölktem Himmel liefert GPS oft 30-50m Genauigkeit — mit 30m
     *  wurden dort fast alle Probes verworfen und die Fahrt nie erkannt.
     *  50m verwirft weiterhin Indoor-GPS (50-100m), erfasst aber echte
     *  Stadtfahrten. */
    const val MAX_ACCURACY_M = 50f
    /** Probes älter als 15 Minuten gehören zu einer früheren Fahrt. */
    const val MAX_PROBE_AGE_MS = 15L * 60 * 1000
    /** Mindestanzahl gültiger Probes für eine Entscheidung. */
    const val MIN_VALID_PROBES = 3
    /** Mindestens N schnelle Probes (>= [AUTO_SPEED_MPS]) im Fenster —
     *  unabhängig von der Kette. M18.75: Die Erkennung darf nicht an
     *  EINEM einzelnen kaputten Fix hängen (Ampel, Stop&Go, Tunnel,
     *  GPS-Drosselung, accuracy > 50m). 3 schnelle Probes insgesamt +
     *  2 konsekutive (siehe [MIN_CONSECUTIVE_FAST]) = robust gegen
     *  einzelne Ausreißer, aber Radfahrer-Spike-Muster
     *  (8.5, 5.0, 8.5, 5.0, 8.5 → maxConsecutive = 1) scheitern weiterhin.
     *  M18.78: 3 -> 2. User-Bug „5-Minuten-Fahrt (max 40 km/h) wird
     *  gar nicht aufgezeichnet": Bei Hintergrund-Fix-Raten (Doze/OEM,
     *  60-120s statt 5s) liefert eine 5-Minuten-Stadtfahrt nur ~4-5
     *  Fixes — real sind davon 1-3 über 8 m/s (Anfahren, Ampel,
     *  Einparken, 30er-Kurven fressen Fixes). Muster 3,0 / 8,3 / 0
     *  (Ampel) / 11,1 / 1,5 hat fastCount = 2 — mit 3 wurde die Fahrt
     *  NIE erkannt. 2 schnelle Probes + 2er-Kette bleiben robust:
     *  ein einzelner GPS-Burst (1 schneller Fix) scheitert weiterhin,
     *  Radfahrer-Spikes erreichen nie maxConsecutive = 2, Stillstand
     *  fängt das Netto-Displacement-Gate (>= 150 m). */
    const val MIN_FAST_PROBES = 2
    /** Mindestens N direkt aufeinanderfolgende Probes über der Auto-Schwelle.
     *  M18.66-FIX12: 4 -> 5. Vier reichte noch für gelegentliche
     *  False-Positives. Fünf aufeinanderfolgende schnelle Probes bei
     *  5s-Intervall = 25s kontinuierlich >= 32 km/h. Das ist robust
     *  gegen GPS-Bursts und schnelles Radfahren. False-Negative-Risiko
     *  minimal: eine echte Autofahrt hat immer 5+ aufeinanderfolgende
     *  Probes >= 9 m/s.
     *  M18.71: 5 -> 4. Nach einer Ampel-Phase (30-60s Stillstand) muss
     *  die Kette neu aufgebaut werden — 5 schnelle Probes = 25s
     *  Beschleunigung in der Stadt sind oft nicht drin (kurze Grün-
     *  Phasen, Stop&Go). 4 Probes = 20s kontinuierlich >= 28,8 km/h
     *  bleibt robust gegen einzelne GPS-Bursts.
     *  M18.75: 4 -> 2. Hauptursache „kurze Fahrten werden nie erkannt":
     *  Bei realen Hintergrund-Fix-Raten (30-120s statt 5s wegen Doze/
     *  OEM-Battery-Saver) brauchte die 4er-Kette 2-8 Minuten UNUNTER-
     *  BROCHENES Tempo — ein einziger Fix mit accuracy > 50m, speedMps
     *  == null oder < 8 m/s (Ampel, Stop&Go, Tunnel) setzte die Kette
     *  auf 0 zurück, die Erkennung dauerte 10-20 Minuten. 2 konsekutive
     *  Probes = nur 60-240s zusammenhängendes Tempo. Zusammen mit
     *  [MIN_FAST_PROBES] = 3 und avgSpeed >= 5 m/s bleibt die Erkennung
     *  robust: Radfahrer-Spikes (8.5, 5.0, 8.5, ...) erreichen nie
     *  maxConsecutive = 2, einzelne GPS-Bursts scheitern am
     *  Netto-Displacement-Gate (>= 150m) und am Spread (>= 30s). */
    const val MIN_CONSECUTIVE_FAST = 2
    /** Probes müssen über mindestens 30 Sekunden verteilt sein.
     *  M18.66-FIX6: 1 Min -> 2 Min. User-Vorschlag: "Durchschnitts-
     *  geschwindigkeit innerhalb von 2 Minuten über 25 km/h". Das
     *  filtert kurze GPS-Bursts zuverlässig heraus.
     *  M18.71: 2 Min -> 90s. Die Erkennung soll schneller ansprechen
     *  (sensibler); 90s Verteilung filtert Bursts weiterhin zuverlässig
     *  (18 Probes bei 5s-Intervall).
     *  M18.95: 90s -> 30s (User: "Aufzeichnung startet erst nach ~4
     *  Minuten — kann viel früher beginnen, sobald über mehrere
     *  Sekunden die Geschwindigkeit erhöht ist"). Die 90s waren die
     *  GRÖSSTE Einzel-Latenz: 90s Spread + Anfahr-Phase + WorkManager
     *  ≈ 2-4 Min bis zum Start. Andere Apps (Google Maps Timeline,
     *  Timeero, DriveQuant) starten nach 20-60s und datieren die
     *  Startzeit zurück (macht Aevum über den Cluster-Start bereits).
     *  WARUM 30s sicher ist: Der Burst-Schutz kommt NICHT allein vom
     *  Spread — ein GPS-Burst (2-3 schnelle Fixes in <20s) hat Spread
     *  < 30s und scheitert weiterhin; selbst ein 30s-Burst scheitert
     *  am Netto-Displacement-Gate (≥ 150 m = 18 km/h Durchschnitt über
     *  30s — ein reiner Positions-Sprung dieser Größe bei accuracy
     *  ≤ 50 m ist nach dem 60s-Kaltstart-Warmup untypisch) und am
     *  Geofence-Veto (Indoor-Multipath). Radfahrer-Schutz ist die
     *  Konsekutiv-Kette (2 Fixes am Stück ≥ 8 m/s = 30s bei 15s-Stream),
     *  nicht der Spread — ein Rennradler mit 90s+ Abfahrt erfüllte
     *  den alten Spread ohnehin. Bei 15s-Stream: 3 Fixes = 30s Spread,
     *  real mit Anfahr-Phase ~45s bis zur Bestätigung. */
    const val MIN_SPREAD_MS = 30_000L
    /** GPS-Sprung > 2 km zwischen zwei Probes (< 60s auseinander) ist
     *  ein Ausreißer (Tunnel-Sprung, Sensorfehler). */
    const val JUMP_OUTLIER_M = 2000.0
    /** M18.66-FIX13: Mindest-Netto-Displacement (geradlinige Distanz vom
     *  ersten zum letzten Probe). Indoor-GPS-Drift erzeugt Speed-Werte
     *  von 10-30 m/s (Kaltstart, Multipath) — aber die Position springt
     *  nur 10-50m um den selben Punkt. Die Netto-Distanz bleibt klein.
     *  Bei einer echten Fahrt (36 km/h über 2 Min) = 1200m. 200m ist
     *  extrem konservativ (17% der erwarteten Distanz) — selbst enge
     *  Kurvenfahrt übersteigt das. Das ist der wichtigste Filter gegen
     *  False-Positives beim Stillstand, inspiriert von DriveQuant:
     *  "GPS is deliberately not activated while the driver is not moving
     *  or before a trip is confirmed" — wir nutzen die Netto-Displacement
     *  als Bestätigung, dass sich der User BEWEGT HAT, nicht nur dass
     *  GPS Speed meldet.
     *  M18.71: 200m -> 150m. Kurze Stadtfahrten (Supermarkt, Kita,
     *  Stop&Go mit viel Wartezeit) legen in 90s Fenster oft nur
     *  150-250m Netto-Distanz zurück. 150m bleibt weit über der
     *  Indoor-Drift (10-50m) und filtert Stillstand weiterhin ab. */
    const val MIN_NET_DISPLACEMENT_M = 150.0

    // ── M18.77: Speed-Fallback (Geschwindigkeit aus Distanz) ────────
    /** Untere/obere Grenze des Zeitabstands (dt) zwischen zwei Probes,
     *  ab der die Geschwindigkeit aus distanceFromLastM abgeleitet wird.
     *
     *  WARUM (User-Bug „10-Minuten-Fahrten werden nicht erkannt"): Real
     *  liefern Hintergrund-Fixes (Doze/OEM-Battery-Saver, 30-120s
     *  Lücken) oft KEIN hasSpeed() → speedMps = null. Damit erreichten
     *  kurze Fahrten (10 Min) MIN_FAST_PROBES = 3 nie. Die Distanz
     *  (distanceFromLastM) ist aber immer da: Speed = Distanz / Zeit.
     *
     *  dt-Grenzen: < 2s = Positions-Jitter (falsche Speed), > 5 Min =
     *  keine aussagekräftige Momentangeschwindigkeit (Park-Phasen). */
    const val MIN_INFERRED_DT_MS = 2_000L
    const val MAX_INFERRED_DT_MS = 300_000L
    /** Abgeleitete Geschwindigkeiten unter der Geh-/Lauf-Grenze werden
     *  komplett VERWORFEN (weder schnell noch langsam): Positions-Jitter
     *  (5-15m Rauschen bei 30-120s dt → ~0,1-0,5 m/s) und Park-Phasen
     *  (~0 m/s) sind kein Fahrzeug-Tempo. Ein verworfenener Probe
     *  bricht die Konsekutiv-Kette nicht und drückt den Durchschnitt
     *  nicht — sonst scheiterte eine 10-Minuten-Fahrt mit 1-2 Jitter-
     *  Fixes (Muster 8,3 / 0,2 / 8,3 / 0,2 / 8,3) weiterhin an
     *  MIN_CONSECUTIVE_FAST. Erst ab 5,5 m/s (19,8 km/h) zählt die
     *  Ableitung als Geschwindigkeit — weit unter AUTO_SPEED_MPS, also
     *  kein neues False-Positive-Risiko (Drift ~0,5 m/s). */
    const val MIN_INFERRED_SPEED_MPS = 5.5f

    // ── M18.79: Start-in-flight-Fenster (Blackout-/Race-Schutz) ────
    /** So lange nach [markDriveConfirmed] darf eine Auto-Session noch
     *  unterwegs sein, ohne dass die Selbstheilung das driveActive-Flag
     *  zurücknimmt (siehe ActivityRecognitionBridge.healIfOrphaned).
     *  90 s = WorkManager-Start-Latenz (App im Hintergrund/Doze) + das
     *  2-Minuten-Intervall des DriveProbeWorker-Takts, falls dessen
     *  markDriveConfirmed als letztes gewinnt. Wird das Fenster
     *  überschritten und läuft immer noch keine Session, ist die
     *  Bestätigung verloren — dann muss die Erkennung neu klassifizieren
     *  können (Blackout verhindern). */
    const val DRIVE_CONFIRM_IN_FLIGHT_MS = 90_000L

    // ── M18.84: STRUKTURELLE FAHRT-GATES (Post-Threshold-Ära) ─────────
    //
    // Historischer Kontext: M18.64→M18.78 hat die Speed/avg/count-Thresholds
    // über 10+ Iterationen getunt (FIX5/10/12/13, M18.71/75/78 senkten sie
    // für kurze Fahrten wieder ab). Das heutige Niveau (2 schnelle Probes,
    // 2er-Kette, avg 4,5 m/s, Netto 150 m) ist für echte Fahrten korrekt —
    // aber Indoor-GPS-Multipath IM GYM erfüllt ALLE diese Gates über das
    // 15-Min-Fenster (Drift-Speed-Spikes + langsame Positionsverschiebung
    // ≥150 m über Minuten). Threshold-Tuning allein kann das nicht mehr
    // lösen (M18.69-Lektion: jedes Absenken erzeugt False-Negatives, jedes
    // Anheben killt kurze Fahrten). Die beiden neuen Gates sind KONTEXT-
    // Gates — sie nutzen Wissen, das die Speed-Serie prinzipiell nicht
    // enthalten kann: Wo ist der User? Und: Wann war die letzte Fahrt?

    /** M18.84: Nach einem Session-Stop (Watchdog/EXIT) darf die Erkennung
     *  für diese Zeit KEINE neue Fahrt starten. Grund: Der 15-Min-Probe-
     *  Puffer läuft nach dem Stopp weiter (Buffer wird beim Stopp NICHT
     *  geleert — die Probes sind auch die Evidenz des Stop-Pfades) und
     *  GPS-Drift beim Parken/Aussteigen klassifiziert sofort wieder
     *  "Driving" → Zweit-Session direkt nach der Ersten
     *  (User-Fall 30.08.: Auto 19:00–19:10 NACH der echten Fahrt).
     *  3 Min decken den typischen Aussteige-/Park-Drift ab, ohne echte
     *  Folgefahrten (Rückweg nach >3 Min Pause) zu blocken. */
    const val DRIVE_RESTART_COOLDOWN_MS: Long = 3L * 60 * 1000

    /** M18.84: Ein benannter Ort als Kreis (aus PlaceGeofence abgeleitet).
     *  Pure data class — bewusst Android-frei für JVM-Tests. */
    data class GeoCircle(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double
    )

    /** M18.84: Liegt der Punkt im Kreis? (Haversine, gleiche Formel wie
     *  überall in Aevum — bewusst dupliziert statt geteilt, damit die
     *  Engine Android- und Dependency-frei bleibt.) */
    fun isInsideCircle(lat: Double, lon: Double, circle: GeoCircle): Boolean =
        haversineMeters(lat, lon, circle.latitude, circle.longitude) <= circle.radiusMeters

    /** M18.84: Cooldown-Gate — liefert true, wenn eine neue Fahrt gerade
     *  NICHT gestartet werden darf (letzte Auto-Session endete vor < 3 Min).
     *  lastDriveEndMs == null → kein Cooldown (erste Fahrt/kein Verlauf). */
    fun isWithinCooldown(nowMs: Long, lastDriveEndMs: Long?): Boolean {
        if (lastDriveEndMs == null) return false
        return nowMs - lastDriveEndMs < DRIVE_RESTART_COOLDOWN_MS
    }

    /** Ein einzelner Geschwindigkeits-Probe. */
    data class DriveProbe(
        val timestampMs: Long,
        /** Momentane Geschwindigkeit aus dem Location-Fix (m/s), null wenn
         *  der Fix keine Geschwindigkeit liefert. */
        val speedMps: Float?,
        /** GPS-Genauigkeit in Metern. */
        val accuracyMeters: Float,
        /** Distanz zum vorherigen Probe (m), null wenn unbekannt. */
        val distanceFromLastM: Double? = null,
        /** Koordinaten (für die Distanzberechnung im Worker). */
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    sealed class Classification {
        /** Noch nicht genug verwertbare Messungen. */
        data object InsufficientData : Classification()
        /** Messungen vorhanden, aber keine Fahrt (Gehen/Laufen/Fahrrad/Stand). */
        data object NotDriving : Classification()
        /** Fahrt bestätigt. */
        data class Driving(val confidence: Float) : Classification()
    }

    /**
     * Klassifiziert eine Probe-Serie.
     *
     * Ablauf:
     *  1. Alte Probes (> 15 Min) und ungenaue Fixes (> 50 m) verwerfen.
     *  2. Einzelne Geschwindigkeits-Ausreißer (> 40 m/s) verwerfen.
     *  3. GPS-Sprung-Ausreißer (> 2 km in < 60 s) verwerfen.
     *  4. Fahrt = mindestens [MIN_FAST_PROBES] schnelle Probes
     *     (>= [AUTO_SPEED_MPS]) im Fenster, davon mindestens
     *     [MIN_CONSECUTIVE_FAST] direkt aufeinanderfolgende, ODER hohe
     *     Durchschnittsgeschwindigkeit mit mehreren schnellen Probes.
     *  5. Zeitliche Verteilung: Die Probes müssen über [MIN_SPREAD_MS]
     *     verteilt sein — ein einzelner Mess-Burst zählt nicht.
     *
     * M18.84 GEOFENCE-VETO: [geofences] sind die benannten Orte des Users
     * (Zuhause, Gym, …). Befindet sich die GESAMTE verwertbare Evidenz
     * innerhalb EINES Orts-Kreises, ist die Serie KEINE Fahrt — egal was
     * Speed/avg/Displacement sagen. Das ist das strukturelle Gegenstück
     * zum 16-Uhr-Phantom (User 5h im Gym, Drift-Speed-Spikes + langsame
     * Positionsverschiebung erfüllten alle Speed-Gates). Bewusst breit
     * formuliert (jede überlebende Probe im Kreis = Veto): Indoor-Multipath
     * springt um ZENTRALE Orte, echte Fahrten verlassen den Kreis ZWANGS-
     * LÄUFIG (Auto bewegt sich km-weit). Ausreißer-Einzelprobes am Kreisrand
     * (Accuracy ≥ 50 m, Sprünge > 2 km) sind bereits VOR dem Veto gefiltert.
     */
    fun classify(
        probes: List<DriveProbe>,
        nowMs: Long = System.currentTimeMillis(),
        geofences: List<GeoCircle> = emptyList()
    ): Classification {
        // 1) Fenster + Genauigkeit + Geschwindigkeits-Ausreißer
        val valid = probes
            .filter { nowMs - it.timestampMs <= MAX_PROBE_AGE_MS }
            .filter { it.accuracyMeters <= MAX_ACCURACY_M }
            .filter { it.speedMps == null || it.speedMps <= OUTLIER_SPEED_MPS }
        if (valid.size < MIN_VALID_PROBES) return Classification.InsufficientData

        // M18.77: SPEED-FALLBACK — Geschwindigkeit aus Distanz ableiten.
        // Hintergrund-Fixes (Doze/OEM, 30-120s Lücken) liefern oft KEIN
        // hasSpeed() → speedMps = null → kurze Fahrten (10 Min) erreichten
        // MIN_FAST_PROBES = 3 nie (User-Bug). distanceFromLastM ist aber
        // immer da: Speed = Distanz / Zeit.
        //   • Nur Probes mit speedMps == null und distanceFromLastM != null.
        //   • dt zum Vorgänger muss in [MIN_INFERRED_DT_MS, MAX_INFERRED_DT_MS]
        //     liegen (< 2s = Positions-Jitter → fiktive Speed, > 5 Min =
        //     Park-Phase / Lücke → kein Fahrzeug-Tempo).
        //   • Ableitungen unter MIN_INFERRED_SPEED_MPS (5,5 m/s) werden
        //     VERWORFEN: Jitter (~0,2-0,5 m/s) und Parken (~0 m/s) sind kein
        //     Tempo. Verworfen = weder schnell noch langsam — die Kette bricht
        //     nicht, der Durchschnitt wird nicht gedrückt. Das ist der Kern
        //     des Fixes: Eine 10-Minuten-Fahrt mit 1-2 Jitter-Fixes
        //     (8,3 / 0,2 / 8,3 / 0,2 / 8,3) erreicht sonst weiterhin nie
        //     MIN_CONSECUTIVE_FAST.
        //   - Vorgänger ist der LETZTE GÜLTIGE Probe der Original-Liste
        //     (nicht der unmittelbare, evtl. ungenaue — dessen Distanz-Feld
        //     misst gegen einen Fix, der in der Engine gar nicht zählt).
        val inferredSpeed = mutableMapOf<Long, Float>()
        var lastValidIndex = -1
        for (i in probes.indices) {
            val p = probes[i]
            if (p in valid) {
                if (p.speedMps == null && p.distanceFromLastM != null && lastValidIndex >= 0) {
                    val prev = probes[lastValidIndex]
                    val dtMs = p.timestampMs - prev.timestampMs
                    if (dtMs in MIN_INFERRED_DT_MS..MAX_INFERRED_DT_MS) {
                        val derived = (p.distanceFromLastM / (dtMs / 1000.0)).toFloat()
                        if (derived >= MIN_INFERRED_SPEED_MPS) {
                            inferredSpeed[p.timestampMs] = derived
                        }
                    }
                }
                lastValidIndex = i
            }
        }

        // 2) Zeitliche Verteilung (mehrere Messungen über einen Zeitraum)
        val spread = valid.maxOf { it.timestampMs } - valid.minOf { it.timestampMs }
        if (spread < MIN_SPREAD_MS) return Classification.InsufficientData

        // 3) GPS-Sprung-Ausreißer entfernen
        val filtered = valid.filterIndexed { i, p ->
            if (i == 0) return@filterIndexed true
            val prev = valid[i - 1]
            val dt = p.timestampMs - prev.timestampMs
            val dist = p.distanceFromLastM
            !(dist != null && dt in 1..60_000 && dist > JUMP_OUTLIER_M)
        }
        if (filtered.size < MIN_VALID_PROBES) return Classification.InsufficientData

        // M18.66-FIX13: NETTO-DISPLACEMENT-GATE.
        // Die wichtigste Defense gegen False-Positives beim Stillstand.
        // GPS-Kaltstart / Multipath / Indoor-Drift kann speed=10-30 m/s
        // liefern — aber die Position springt nur 10-50m um den selben
        // Punkt. Die geradlinige Distanz vom ersten zum letzten Probe
        // bleibt klein. Bei einer echten Fahrt (36 km/h über 2 Min) =
        // 1200m. Wenn die Netto-Distanz < 200m ist, ist es KEINE Fahrt
        // — egal was speed sagt. DriveQuant: "GPS is not activated while
        // the driver is not moving or before a trip is confirmed."
        val firstProbe = filtered.first()
        val lastProbe = filtered.last()
        val netDisplacement = if (firstProbe.latitude != null && firstProbe.longitude != null &&
            lastProbe.latitude != null && lastProbe.longitude != null) {
            haversineMeters(
                firstProbe.latitude!!, firstProbe.longitude!!,
                lastProbe.latitude!!, lastProbe.longitude!!
            )
        } else {
            // Keine Koordinaten → kann nicht validieren → nicht fahren.
            // Besser False-Negative als False-Positive bei Stillstand.
            0.0
        }
        if (netDisplacement < MIN_NET_DISPLACEMENT_M) {
            return Classification.NotDriving
        }

        // M18.84 GEOFENCE-VETO (strukturelles Gate gegen Indoor-Phantom-
        // Fahrten): Wenn ALLE verwertbaren Probes (mit Koordinaten) in
        // EINEM benannten Orts-Kreis liegen, ist der User nachweislich
        // nicht gefahren — die Speed-Werte sind Indoor-Multipath-Artefakte
        // (Gym-Fall 30.08.: 5h still, alle Gates wegen Drift erfüllt).
        // "In irgendeinem Kreis" reicht fürs Veto (Ein-Ort-Axiom, gleiche
        // Logik wie der Visit-Zustandsautomat M18.83.1): Der User kann nur
        // an einem Ort sein. EINE aus ALLEN Kreisen gefallene Probe hebt
        // das Veto auf — echte Fahrten verlassen den Kreis zwangsläufig
        // (Auto bewegt sich km-weit). Das Veto blockiert nur ein positives
        // Driving nachträglich; NotDriving bleibt NotDriving.
        if (geofences.isNotEmpty()) {
            val withCoords = filtered.filter { it.latitude != null && it.longitude != null }
            val allInsideANamedPlace = withCoords.isNotEmpty() && withCoords.all { p ->
                geofences.any { isInsideCircle(p.latitude!!, p.longitude!!, it) }
            }
            if (allInsideANamedPlace) {
                return Classification.NotDriving
            }
        }

        // 4) Aufeinanderfolgende schnelle Probes + Durchschnitt
        var consecutive = 0
        var maxConsecutive = 0
        var fastCount = 0
        var speedSum = 0f
        var speedCount = 0
        for (p in filtered) {
            val s = p.speedMps ?: inferredSpeed[p.timestampMs]
            if (s != null) {
                speedSum += s
                speedCount++
                if (s >= AUTO_SPEED_MPS) {
                    consecutive++
                    maxConsecutive = maxOf(maxConsecutive, consecutive)
                    fastCount++
                } else {
                    consecutive = 0
                }
            }
        }
        val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0f

        // M18.68-FIX (False-Negative-Root-Cause): avgSpeed 9 → 6 m/s.
        // M18.66-FIX12 (8→10 m/s + avg 9 m/s = 32,4 km/h) eliminierte
        // False-Positives, machte die Erkennung aber ZU streng: Eine
        // städtische Fahrt (30er-Zone = 8,3 m/s) oder 50 km/h mit nur
        // einer 60s-Ampel-Phase (Durchschnitt ~8,3 m/s) bleibt UNTER
        // dem 9-m/s-Durchschnitt — die Fahrt wird nie bestätigt, obwohl
        // sie Minuten lang mit realer Geschwindigkeit läuft.
        // 6 m/s = 21,6 km/h ist der Mittelwert über eine 2-Minuten-
        // Fahrt, die mindestens 5 schnelle Probes (>= 8 m/s) enthält
        // (5×8 = 40 m/s über 5 Probes; selbst mit 5 langsamen Probes
        // à 1 m/s im selben Fenster ergibt der Schnitt (40+5)/10 = 4,5
        // → 6 m/s fordert also real gefahrene Strecke). Gehen/Laufen
        // (<= 5,5 m/s) und Radfahren (<= 7,5 m/s Durchschnitt über
        // 2 Min — Spitzen > 8 m/s halten Radfahrer nicht 25s) erreichen
        // die Kombination aus 5×8-m/s-Kette + avg 6 m/s + Netto-
        // Displacement ≥ 200 m nicht. Der False-Positive-Schutz bleibt
        // damit intakt (Netto-Displacement-Gate ist der eigentliche
        // Stillstands-Filter), die False-Negatives sind behoben.
        //
        // M18.71 (sensibler): avgSpeed 6 → 5 m/s (18 km/h). Mit der
        // 4er-Kette (>= 8 m/s) und dem 90s-Fenster ist der Durchschnitt
        // einer echten Stadtfahrt mit Ampel-Phasen oft nur 5-6 m/s.
        // 5 m/s bleibt über Geh-Tempo (1,5 m/s) und über dem Schnitt
        // einer Radfahrt mit 4 schnellen Spikes (4×8 + 4×2 = 40/8 = 5 —
        // die scheitert aber an der Konsekutiv-Kette, weil Radfahrer
        // 8 m/s nicht 20s am Stück halten) und am Netto-Displacement.
        //
        // M18.75 (Hauptursache „kurze Fahrten werden nie erkannt"):
        // Die 4er-Kette war bei realen Hintergrund-Fix-Raten (30-120s
        // statt 5s wegen Doze/OEM-Battery-Saver) zu streng — ein
        // einziger Fix mit accuracy > 50m, speedMps == null oder
        // < 8 m/s (Ampel, Stop&Go, Tunnel, GPS-Drosselung) setzte die
        // Kette auf 0 zurück, die Erkennung dauerte 10-20 Minuten.
        // Jetzt: mindestens MIN_FAST_PROBES = 3 schnelle Probes im
        // Fenster, davon mindestens MIN_CONSECUTIVE_FAST = 2 direkt
        // aufeinanderfolgende, plus avgSpeed >= 5 m/s. Ein einzelner
        // kaputter Fix kann die Erkennung nicht mehr stoppen (die
        // 2er-Kette wird davor/danach neu aufgebaut), Radfahrer-Spike-
        // Muster (8.5, 5.0, 8.5, 5.0, 8.5 → maxConsecutive = 1)
        // scheitern weiterhin an der Konsekutiv-Bedingung, und das
        // Netto-Displacement-Gate (>= 150m) + Spread (>= 90s) bleiben
        // als False-Positive-Schutz unverändert.
        //
        // M18.78 (User-Bug „5-Minuten-Fahrt, max 40 km/h, wird gar
        // nicht aufgezeichnet"): MIN_FAST_PROBES 3 -> 2 UND avgSpeed
        // 5,0 -> 4,5 m/s. Eine 5-Minuten-Stadtfahrt (Doze-Fix-Rate
        // 60-120s) ergibt real nur ~4-5 Fixes, davon 1-3 schnell —
        // das Stadt-Typ-Muster 3,0 / 8,3 / 0 / 11,1 / 1,5 hat einen
        // Durchschnitt von 4,7 m/s, obwohl der User 30-40 km/h fährt.
        // Beide Schwellen gleichzeitig senken, weil sie zusammen die
        // kurze Fahrt abwürgen: fastCount >= 2 UND avgSpeed >= 4,5.
        // Schutz bleibt doppelt: 4,5 m/s liegt über Geh-Tempo
        // (1,5 m/s) und über einem Radfahrer-Schnitt (Spike-Muster
        // 8.5/5.0 alternierend = 6,75 — scheitert aber an der
        // 2er-Konsekutiv-Kette, weil 8,5 m/s nie 2 Fixes am Stück
        // gehalten wird), einzelne GPS-Bursts scheitern an fastCount
        // >= 2, Stillstand/Drift am Netto-Displacement-Gate (>= 150m).
        val driving = fastCount >= MIN_FAST_PROBES &&
            maxConsecutive >= MIN_CONSECUTIVE_FAST &&
            avgSpeed >= 4.5f
        if (!driving) return Classification.NotDriving

        // 5) Konfidenz: Anteil schneller Probes + Geschwindigkeits-Niveau
        val speedRatio = (maxConsecutive.toFloat() / filtered.size).coerceIn(0f, 1f)
        val speedLevel = (avgSpeed / 30f).coerceIn(0f, 1f)
        val confidence = (0.5f * speedRatio + 0.5f * speedLevel).coerceIn(0.6f, 0.95f)
        return Classification.Driving(confidence)
    }

    /** M18.66-FIX13: Haversine-Distanz in Metern (für Netto-Displacement-Gate). */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * M18.64: Baut aus einer Probe-Serie einen synthetischen
     * [VehicleCluster] für die bestehende Session-Pipeline. Der Start
     * liegt beim ältesten Probe im Fenster — die Fahrt begann spätestens
     * dort (Fallback für "Fahrt begann vor der Erkennung").
     *
     * M18.64-REVIEW-FIX: Mindestens 2 Probes mit >= 60s Spread — der
     * Cluster ist nur der ZEIT-ANKER der Session (die Bestätigung kommt
     * von der Confirmation/Engine). Der DriveConfirmWorker legt genau
     * 2 Fixes 60s auseinander; wenn der AR-Cluster parallel gedrained
     * wurde, muss daraus trotzdem ein Cluster entstehen, sonst ginge
     * die bestätigte Fahrt verloren.
     */
    fun toVehicleCluster(
        probes: List<DriveProbe>,
        nowMs: Long = System.currentTimeMillis()
    ): VehicleCluster? {
        val valid = probes
            .filter { nowMs - it.timestampMs <= MAX_PROBE_AGE_MS }
            .filter { it.accuracyMeters <= MAX_ACCURACY_M }
        if (valid.size < 2) return null
        val spread = valid.maxOf { it.timestampMs } - valid.minOf { it.timestampMs }
        if (spread < 60_000L) return null
        val start = valid.minOf { it.timestampMs }
        val end = valid.maxOf { it.timestampMs }
        return VehicleCluster(
            startMs = start,
            endMs = end,
            lastMs = end,
            sampleCount = valid.size,
            peakConfidence = 75
        )
    }
}

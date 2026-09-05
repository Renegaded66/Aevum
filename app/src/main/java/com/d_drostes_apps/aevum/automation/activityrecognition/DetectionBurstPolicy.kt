package com.d_drostes_apps.aevum.automation.activityrecognition

/**
 * M18.104: Ereignisgetriebene GPS-Burst-Politik — pure Entscheidungslogik.
 *
 * Kontext (Akku-Redesign, User: "Akkuverbrauch drastisch reduzieren,
 * Architektur voll überdenken, Aufzeichnung muss zuverlässig bleiben"):
 * Der DriveDetectionService hielt bisher einen 24/7-GPS-Stream
 * (PRIORITY_HIGH_ACCURACY, 15s) am Leben — der GPS-Chip schlief NIE,
 * egal ob der User stillstand. Das war der Haupt-Akku-Fresser
 * (20% in 5h bei 5 Min Bildschirmzeit).
 *
 * Neue Architektur (Recherche: Android-Doku "Optimize location use for
 * battery life" + Life360/DriveQuant/HyperTrack-Ansatz):
 *   • Activity-Recognition-Transitions und Geofences sind die DAUER-
 *     Signale (OS-managed, Sensor-Hub, ~0 Akku).
 *   • GPS läuft NUR in kurzen BURST-Fenstern zur Bestätigung eines
 *     Verdachts (AR-ENTER, Geofence-EXIT, App-Öffnen) und während
 *     einer bestätigten Aufzeichnung (Track). Life360: "does not
 *     simply leave the GPS chip active at all times"; DriveQuant:
 *     "GPS is deliberately not activated while the driver is not
 *     moving or before a trip is confirmed".
 *
 * Diese Klasse kapselt die reinen Schwellen/Entscheidungen, damit sie
 * JVM-unit-testbar ist (gleiches Muster wie DriveDetectionEngine /
 * WalkingDetectionEngine — bewusst Android-frei).
 */
object DetectionBurstPolicy {

    /** Service-Betriebsmodi (Zustandsmaschine im DriveDetectionService). */
    enum class Mode {
        /** Kein Stream, kein Service (alles aus). */
        IDLE,
        /** Bestätigungs-Burst: HIGH_ACCURACY 15s-Stream, zeitlich
         *  begrenzt. Klassifiziert die Probe-Serie (DriveDetectionEngine
         *  + Walking-Displacement). */
        CONFIRM,
        /** Walking-Check: BALANCED 60s-Stream (WLAN/Cell, kein GPS-Chip-
         *  Dauerbetrieb), begrenzt. Misst Netto-Displacement (300 m). */
        WALKING_CHECK,
        /** Bestätigte Aufzeichnung läuft (Auto/Walking-Session trackbar):
         *  Stream läuft so lange die Session lebt (Tick-Verlängerung). */
        TRACK
    }

    /** CONFIRM-Burst-Fenster. 6 Min: GPS_WARMUP (60s) + ~5 Min verwertbare
     *  Fixes. Reicht für DriveDetectionEngine (MIN_SPREAD 30s + 2 schnelle
     *  Probes + Netto 150m) selbst bei Kaltstart (Assisted GPS 20-60s,
     *  Cold GPS 60-120s) UND für langsames Losfahren (Stau direkt nach
     *  Start). Danach endet der Burst ergebnislos — kein Dauerzustand.
     *  Historischer Vergleich: Der 24/7-Stream brauchte für dieselbe
     *  Klassifikation ~45-90s WARM-Zeit — der Burst zahlt nur die
     *  Kaltstart-Minute drauf, spart aber die restlichen 23h53m. */
    const val CONFIRM_WINDOW_MS = 6L * 60 * 1000

    /** WALKING_CHECK-Fenster. 8 Min: Die Walking-Schwelle ist 5 Min
     *  Phase + Netto-Displacement ≥ 300 m (WALKING_MIN_GPS_DISTANCE_M).
     *  8 Min deckt die Schwelle + GPS-Latenz ab, ohne Dauerbetrieb.
     *  BALANCED-Priorität: Fixes alle 60s = 8 Fixes pro Fenster. */
    const val WALKING_CHECK_WINDOW_MS = 8L * 60 * 1000

    /** TRACK-Tick: Alle 2 Min prüft der Service, ob die trackbare
     *  Session noch lebt. Nein → Stream aus. Das deckt ALLE
     *  Session-Ende-Pfade (Watchdog, Google-EXIT, manueller Stop,
     *  PAUSE-Split) ab — ohne dass jeder Worker den Service stoppen
     *  müsste (robust gegen zukünftige Call-Sites, gleiche Lektion
     *  wie die M18.76-Blackout-Selbstheilung). */
    const val TRACK_TICK_MS = 2L * 60 * 1000

    /** Gnadenfrist nach CONFIRM-Ende: Ist die Bestätigung gesetzt
     *  (markDriveConfirmed) aber die Session startet noch (Worker-
     *  Latenz), wartet der Service diese Frist, bevor er den Stream
     *  abschaltet. Gleiches Fenster wie DRIVE_CONFIRM_IN_FLIGHT_MS
     *  (Start-in-flight-Schutz M18.79). */
    const val GRACE_EXTENSION_MS = 90_000L

    /** Cooldown für ergebnislose CONFIRM-Bursts. Verhindert Burst-
     *  Kaskaden: AR-ENTER-Flapping (Google meldet IN_VEHICLE mehrfach
     *  bei Sensor-Rauschen) oder Geofence-Flapping (Debouncer-Ausreißer)
     *  dürfen nicht alle 30s einen 6-Min-GPS-Burst starten. Ein zweiter
     *  Burst auf denselben Verdacht bringt keine neuen Erkenntnisse —
     *  die Engine hat bereits alle Gates geprüft. Neue TRIGGER (neuer
     *  AR-ENTER NACH Cooldown, Geofence-EXIT) starten frei.
     *  3 Min analog DRIVE_RESTART_COOLDOWN_MS (M18.84). */
    const val BURST_COOLDOWN_MS = 3L * 60 * 1000

    /** Cooldown für ergebnislose WALKING_CHECK-Bursts. Google feuert
     *  WALKING-ENTER bei JEDEM Raumwechsel (Wohnzimmer→Küche) — ohne
     *  Cooldown liefe für jeden Gang ein 8-Min-FGS. 10 Min > typische
     *  Raumwechsel-Sequenzen; ein echter Spaziergang (ENTER beim
     *  Losgehen → Burst läuft → 300m erfüllt → Start) wird nicht
     *  blockiert, weil der erfolgreiche Burst KEINEN Cooldown setzt. */
    const val WALKING_BURST_COOLDOWN_MS = 10L * 60 * 1000

    /** Maximale Verlängerungen pro CONFIRM-Episode durch wiederholte
     *  Trigger (AR-ENTER-Flapping, Geofence-Events) ODER durch das
     *  Bewegungs-Erneuerungs-Gate unten. Begrenzt die Gesamtdauer einer
     *  Episode auf (1 + MAX) × CONFIRM_WINDOW_MS — Flapping darf keinen
     *  Dauerzustand erzeugen, aber echtes Stop&Go muss durchkommen. */
    const val MAX_CONFIRM_EXTENSIONS = 2

    /** Maximale Verlängerungen pro WALKING-Episode (Raumwechsel-Flapping
     *  von Google-WALKING-ENTERs darf die Phase nicht endlos am Leben
     *  halten). */
    const val MAX_WALKING_EXTENSIONS = 1

    /** Bewegungs-Erneuerungs-Gate (CONFIRM): Lief der Burst ergebnislos
     *  ab, ABER die Probe-Serie zeigt echte Bewegung (Netto-Displacement
     *  ≥ [DriveDetectionEngine.MIN_NET_DISPLACEMENT_M] ODER Durch-
     *  schnitt ≥ 2 m/s = 7,2 km/h — Stau/Kriech-Tempo/Anfahren, das
     *  die 8-m/s-Gates der Engine noch nicht erfüllt), wird das Fenster
     *  verlängert statt den Stream abzuschalten. OHNE dieses Gate würde
     *  eine Fahrt im Stockverkehr nach 6 Min "ergebnislos" enden und
     *  erst nach Cooldown + neuem Trigger wieder erkannt — die alte
     *  24/7-Architektur hätte weiter klassifiziert. Stillstand (Drift
     *  < 150 m, Speed ~ 0) verlängert NIE → der Burst endet und der
     *  Cooldown greift. */
    const val EXTENSION_MIN_AVG_SPEED_MPS = 2.0f

    // ── M18.104: BEWEGUNGS-VERDACHT (Fallback-Pfad) ──────────────────
    // Der 24/7-Stream fing Fahrten ab, wenn Google KEINE AR-Transition
    // lieferte (M18.64-Root-Cause: "Wenn Google kein IN_VEHICLE-Event
    // liefert (App im Hintergrund, Sensor-Spring)"). Der Verdachts-Check
    // schließt diese Lücke OHNE Dauer-GPS: Er hängt sich an den
    // bestehenden 5-Min-Geofence-Check (ProactiveGeofenceCheckWorker —
    // der Fix liegt ohnehin an, NULL zusätzliche GPS-Kosten) und
    // vergleicht den Standort mit dem Fix von vor ~5 Minuten:
    //   • ≥ 1500 m Netto (≈ 18 km/h Durchschnitt) → Fahrzeug-Verdacht
    //     → CONFIRM-Burst (Speed-Gates entscheiden).
    //   • ≥ 200 m Netto (≈ 2,4 km/h — nachhaltige OUTDOOR-Bewegung;
    //     Indoor-Drift pendelt ±10-50 m um denselben Punkt, Netto ~0)
    //     → WALKING_CHECK-Burst (300m-Displacement-Gate entscheidet).
    // Gehen (0,4 km/5 Min) und Drift lösen den Fahrzeug-Pfad NIE aus;
    // 200 m Netto in 5 Min ist nachhaltige Ortsveränderung, kein Drift.
    // Burst-Kaskaden fangen die Cooldowns (3/10 Min) ab.
    const val DRIVE_SUSPICION_MIN_DISPLACEMENT_M = 1500.0
    const val WALK_SUSPICION_MIN_DISPLACEMENT_M = 200.0
    /** Verdachts-Fenster: Beide Fixes müssen 2–15 Min auseinanderliegen
     *  (zu nah = Positions-Jitter, zu weit = Drift-Baseline veraltet). */
    const val SUSPICION_MIN_DT_MS = 2L * 60 * 1000
    const val SUSPICION_MAX_DT_MS = 15L * 60 * 1000

    /**
     * Darf ein CONFIRM-Burst gestartet werden?
     * Cooldown gilt NUR nach ergebnislosem Burst (letzter Burst lief
     * ab, ohne dass eine Session/Bestätigung daraus entstand). Ein
     * laufender Burst frischt sein Fenster (REPLACE), läuft aber nie
     * über CONFIRM_WINDOW_MS hinaus.
     */
    fun confirmBurstAllowed(
        nowMs: Long,
        lastResultlessConfirmEndMs: Long
    ): Boolean =
        lastResultlessConfirmEndMs == 0L ||
            nowMs - lastResultlessConfirmEndMs >= BURST_COOLDOWN_MS

    /**
     * Darf ein WALKING_CHECK-Burst gestartet werden? (analog, eigenes
     * Fenster — Raumwechsel sind viel häufiger als Fahrzeug-Verdachte).
     */
    fun walkingBurstAllowed(
        nowMs: Long,
        lastResultlessWalkingEndMs: Long
    ): Boolean =
        lastResultlessWalkingEndMs == 0L ||
            nowMs - lastResultlessWalkingEndMs >= WALKING_BURST_COOLDOWN_MS

    /**
     * Welches Fenster gehört zum Modus? (Timer im Service).
     */
    fun windowMsFor(mode: Mode): Long = when (mode) {
        Mode.CONFIRM -> CONFIRM_WINDOW_MS
        Mode.WALKING_CHECK -> WALKING_CHECK_WINDOW_MS
        Mode.TRACK -> TRACK_TICK_MS
        Mode.IDLE -> 0L
    }
}
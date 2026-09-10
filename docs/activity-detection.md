# Activity Detection — Audit & Redesign Proposal

**Status:** Audit abgeschlossen, Design-Vorschlag (Basis für Implementierung)
**Betroffene Komponenten:** `automation/activityrecognition/` (DriveDetectionEngine, DriveDetectionService, WalkingDetectionEngine, ActivityRecognitionWorker, DriveWorkers, WalkingWorkers, DetectionBurstPolicy)
**Anlass:** User-Report (Kanban t_50a4847b): „Mal wurde Spazieren in einer 30er Zone mit dem Auto aufgezeichnet, mal Auto fahren beim Spazieren. … ich bin gerade schnell joggen gewesen bei 16 km/h … 16 km/h dauerhaft reicht definitiv nicht aus um es als Autofahren zu deklarieren. … die Bewegung des Handys beim Joggen sollte wohl ganz klar darauf schließen lassen dass ich nicht gerade Auto fahre.“

---

## 1. Zusammenfassung

Die aktuelle Erkennung ist **rein GPS-Geschwindigkeits-basiert** (plus Googles Activity-Recognition-Transitions als Trigger). Sie hat **keinen Motion-Sensor-Pfad**: Die Bewegung des Handys (Schrittfrequenz beim Joggen, Vibration im Auto) fließt nirgends in die Klassifikation ein. Dadurch sind drei Fehlklassifikationen möglich, von denen zwei reproduziert und eine als verbleibende Lücke nachgewiesen wurden:

| # | Fall | Ergebnis heute | Root Cause |
|---|---|---|---|
| 1 | **Joggen 16 km/h + 2 GPS-Multipath-Spikes** | ❌ **Autofahren** (reproduziert, Simulation) | `AUTO_SPEED_MPS = 8 m/s` liegt im Spike-Bereich; der M18.113-Positions-Arbiter (2,5 m/s) kann Joggen nicht von Fahren unterscheiden, weil die Position beim Joggen real 4,4 m/s legt |
| 2 | **Spazieren in 30er-Zone** | ⚠️ teils gefixt (M18.110/M18.113), Restrisiko bei schnellem Gehen/Joggen | Walking-Phase-Veto (8 m/s) liegt exakt auf 30er-Zonen-Tempo; Drive-Pfad klassifiziert 8,3 m/s als Fahrt — ohne Motion-Gate ist ein Fußgänger mit Spikes nicht unterscheidbar |
| 3 | **Autofahren während laufender Walking-Session** | ❌ **Spazieren läuft weiter** (Lücke im Code verifiziert) | Der TRACK_WALK-Heartbeat wird durch JEDE Bewegung ≥ 10 m zwischen Fixes refresht — Fahrzeug-Bewegung (500 m/60 s) hält die Walking-Session am Leben; das Fahrzeug-Veto greift nur in der Phasen-Erkennung, nicht im Live-Heartbeat |

**Kern-Vorschlag:** Ein **Motion-Gate** (Googles Activity-Recognition-Typ als Kontext) hebt die Auto-Schwelle auf 12 m/s (43 km/h), sobald das Handy „zu Fuß“ (WALKING/RUNNING/ON_FOOT) meldet, plus eine **Schrittfrequenz-Schätzung** aus dem Beschleunigungssensor (Cadence 2,2–3,2 Hz = Joggen) als zweites, unabhängiges Signal. Dazu zwei Härtungen: Fahrzeug-Speed-Veto im Walking-Heartbeat und ein geschwindigkeitsbasiertes statt fixem Displacement-Veto.

---

## 2. Ist-Zustand: Schwellen & Heuristiken (Audit)

### 2.1 DriveDetectionEngine (`DriveDetectionEngine.kt`)

Reine JVM-Klasse, klassifiziert eine Serie von GPS-Probes. Aktuelle Schwellen (Stand M18.113, HEAD `70debbe`):

| Konstante | Wert | Bedeutung |
|---|---|---|
| `AUTO_SPEED_MPS` | **8,0 m/s (28,8 km/h)** | Auto-Schwelle. Historie: 10 → 9 → 8 m/s (M18.66-FIX12 → M18.68 → M18.71), bewusst auf 30er-Zonen-Niveau gesenkt |
| `WALK_RUN_MAX_MPS` | 5,5 m/s (19,8 km/h) | Obergrenze Gehen/Laufen (nur für Speed-Fallback-Ableitung) |
| `OUTLIER_SPEED_MPS` | 40 m/s (144 km/h) | GPS-Ausreißer |
| `MAX_ACCURACY_M` | 50 m | Genauigkeits-Gate (30 → 50 m, M18.71) |
| `MAX_PROBE_AGE_MS` | 15 min | Probe-Fenster |
| `MIN_VALID_PROBES` | 2 | Mindest-Probes |
| `MIN_FAST_PROBES` | 2 | Schnelle Probes im Fenster (3 → 2, M18.78) |
| `MIN_CONSECUTIVE_FAST` | 2 | Konsekutive schnelle Probes (5 → 4 → 2) |
| `MIN_SPREAD_MS` | 30 s | Zeitliche Verteilung (2 Min → 90 s → 30 s, M18.95) |
| `MIN_NET_DISPLACEMENT_M` | 150 m | Netto-Displacement-Gate (200 → 150 m) |
| `MIN_INFERRED_SPEED_MPS` | 5,5 m/s | Speed-Fallback aus Distanz/dt (M18.77) |
| `WALK_AVG_VETO_MPS` | 3,0 m/s | M18.113: Fenster-Schnitt < 3 m/s + Spike-Minderheit = Multipath-Spaziergang |
| `MIN_CONFIRMED_POS_SPEED_MPS` | 2,5 m/s | M18.113: Positions-Cross-Check für schnelle Probes |
| `DRIVE_RESTART_COOLDOWN_MS` | 3 min | Kein Neustart direkt nach Session-Ende |
| avgSpeed-Anforderung | **≥ 4,5 m/s** | Fenster-Durchschnitt (9 → 6 → 5 → 4,5 m/s) |

**Entscheidungslogik (classify):** Filter (Alter, Accuracy, Ausreißer) → Speed-Fallback → Spread ≥ 30 s → Sprung-Ausreißer → Netto-Displacement ≥ 150 m → Geofence-Veto → M18.113-Positions-Konsistenz → `fastCount ≥ 2 && maxConsecutive ≥ 2 && avgSpeed ≥ 4,5 m/s` → Driving(confidence).

### 2.2 WalkingDetectionEngine (`WalkingDetectionEngine.kt`)

| Konstante | Wert | Bedeutung |
|---|---|---|
| `WALKING_THRESHOLD_MS` | 5 min | Mindest-Phase vor Auto-Start (mit Vorlauf-Rückdatierung) |
| `WALKING_WATCHDOG_NO_SIGNAL_MS` | 8 min | Stopp ohne Walking-Signal |
| `WALKING_VEHICLE_SPEED_MPS` | 8,0 m/s | Fahrzeug-Veto der Walking-Phase (M18.110) |
| `WALKING_DISPLACEMENT_VETO_M` | **350 m** | Phasen-Start-Veto: ≥ 350 m zwischen zwei Fixes (dt 30 s–2 min) = Fahrzeug |
| `WALKING_MAX_AVG_SPEED_MPS` | 5,0 m/s | MAX-Gate: Phasen-Netto-Schnitt ≥ 5 m/s = keine Wanderung |

### 2.3 Laufzeit-Pipeline (`DriveDetectionService.kt`, `ActivityRecognitionWorker.kt`, `DriveWorkers.kt`, `WalkingWorkers.kt`)

- **Trigger:** Google-AR-Transitions (IN_VEHICLE/WALKING/RUNNING/ON_BICYCLE/STILL) + Geofence-EXIT + 10-Min-Fallback-Check.
- **Bursts (M18.104, Akku-Redesign):** CONFIRM (HIGH, 15 s, 5 Min Fenster, bis zu 2 Verlängerungen), WALKING_CHECK (BALANCED, 60 s, 8 Min), TRACK (folgt der Session; TRACK_DRIVE 15 s HIGH, TRACK_WALK 60 s BALANCED).
- **Start:** IN_VEHICLE-ENTER → sofort DriveStartWorker (mit AR-Start-Gate: GPS-bestätigt ODER classify == Driving). WALKING/RUNNING-ENTER → 5-Min-Schwelle → WalkingStartWorker (RUNNING → Typ `joggen`, WALKING → `spazieren`).
- **Stopp:** DriveWatchdog 5 Min ohne Signal (AR-Sample ODER GPS-Bewegung ≥ 200 m/2 Min), WalkingWatchdog 8 Min ohne Signal (Transition ODER GPS-Bewegung ≥ 10 m zwischen Fixes).
- **Dauer-Anforderungen:** Fahrt: 30 s Spread + 2 schnelle Probes; Wanderung: 5 Min Phase + ≥ 300 m Netto-Displacement.

---

## 3. Root-Cause-Analyse der gemeldeten Fälle

### 3.1 Fall 1: Joggen 16 km/h → „Autofahren“ (reproduziert)

**16 km/h = 4,44 m/s.** Die Engine verlangt `fastCount ≥ 2 && maxConsecutive ≥ 2 && avg ≥ 4,5 m/s`. Ein Jogger allein (4,44 m/s konstant) scheitert an fastCount — **aber**: GPS-Multipath in Stadtlagen (Häuserschluchten, Bäume) erzeugt regelmäßig 2+ aufeinanderfolgende Speed-Spikes ≥ 8 m/s. Simulation mit realistischen Werten (Jogger 4,44 m/s, 2 Spikes à 8,5 m/s, Position bewegt sich real mit 4,44 m/s):

```
fast=2, maxConsecutive=2, avg=5,79 m/s, Netto=2669 m
→ ALLE Gates erfüllt → Driving(confidence)   ❌
```

Warum der M18.113-Arbiter hier **nicht** greift: `MIN_CONFIRMED_POS_SPEED_MPS = 2,5 m/s` prüft, ob die Position die behauptete Speed trägt. Beim Spaziergang (1,4 m/s) widerspricht die Position dem Spike (→ gefixt). Beim **Joggen** bewegt sich die Position real mit 4,44 m/s ≥ 2,5 m/s — der Spike gilt als „positionsbestätigt“. Der Arbiter kann Joggen prinzipiell nicht von Fahren unterscheiden, weil er nur GPS kennt. **Es fehlt genau das Signal, das der User nennt: die Handy-Bewegung.**

Zusätzlich: `WALK_AVG_VETO_MPS = 3,0 m/s` greift nicht (avg 5,79 ≥ 3,0), und der Joggen-Pfad (RUNNING-AR → `joggen`) startet zwar parallel, aber die Drive-Engine gewinnt das Rennen um die Session („neue Aufzeichnung stoppt alte“, M18.66-FIX21) — Ergebnis: Autofahren statt Joggen.

### 3.2 Fall 2: Spazieren in 30er-Zone → „Autofahren“

Zwei Teilursachen, eine davon bereits behoben:

1. **Walking-Phase als Fahrt erkannt (behoben M18.110/M18.113):** Die GPS-Walking-Phase maß Fahrzeug-Displacement (5 Min × 8,3 m/s = 2.500 m ≫ 300 m) als Wanderung und die Drive-Engine klassifizierte 8,3 m/s als Fahrt. Fix: Fahrzeug-Speed-Veto (direkt + abgeleitet) in `updateWalkingPhase`.
2. **Restrisiko (dieser Audit):** Das Veto liegt mit 8,0 m/s **exakt auf 30er-Zonen-Tempo**. Ein Fußgänger, dessen GPS 2+ Spikes ≥ 8 m/s liefert (Position bewegt sich mit Geh-Tempo 1,4 m/s), ist seit M18.113 geschützt. Ein **schneller Geher/Jogger** (Position ≥ 2,5 m/s) ist es nicht — identische Mechanik wie Fall 1. Ohne Motion-Signal ist „Fußgänger mit Spikes“ von „Auto in der 30er-Zone“ per GPS nicht sicher unterscheidbar.

### 3.3 Fall 3: Autofahren → „Spazieren“ (Lücke im Code verifiziert)

Zwei Teilursachen:

1. **Google-AR meldet WALKING während Stop&Go-Fahrten** (Anfahren/Kriechen wird als Gehen klassifiziert). Behandelt: WALKING-ENTER während aktiver Fahrt wird ignoriert (M18.84), Walking-Phase wird bei Fahrzeug-Tempo verworfen (M18.110/113), `effectiveWalkingSince` kappt die Phase am Fahrt-Ende.
2. **Verbleibende Lücke — Live-Heartbeat ohne Speed-Veto:** In `DriveDetectionService.handleFix` refresht der TRACK_WALK-Modus den Walking-Heartbeat bei **jeder** Bewegung ≥ 10 m zwischen Fixes:

```kotlin
if (mode == StreamMode.TRACK_WALK && distance != null && distance >= MIN_PROBE_MOVEMENT_M) {
    bridge.markWalkingSignal(now)   // ← kein Speed-Check!
}
```

Eine laufende Walking-Session wird durch eine anschließende Autofahrt **am Leben gehalten**: 30 km/h = 500 m/60 s ≫ 10 m → Heartbeat refresht alle 60 s → der 8-Min-Watchdog läuft nie ab. Die Fahrzeug-Vetos (M18.110/113) laufen nur in `updateWalkingPhase`, das bei aktiver Walking-Session gar nicht mehr aufgerufen wird (`!isWalkingActive()`-Guard). Die Fahrt wird nur dann korrekt übernommen, wenn die Drive-Engine parallel eine Driving-Klassifikation schafft und die Session per `trimOverlappingForNewSession` kürzt — bei Stop&Go (avg < 4,5 m/s) oder fehlenden Speed-Feldern passiert das nicht, und die Walking-Session frisst die Fahrt.

### 3.4 Weitere Befunde

- **`WALKING_DISPLACEMENT_VETO_M = 350 m` ist dt-abhängig, aber fix:** Bei 60-s-Fixes (WALKING-Stream) legt Joggen 16 km/h 266 m zurück (ok). Bei 120-s-Fixes (Lücken, Doze) sind es 533 m ≥ 350 m → die Phase wird als „Fahrzeug-Verdacht“ verworfen, obwohl der User joggt. Der Kommentar verspricht Joggen-Schutz, die Geometrie hält ihn bei dt = 2 Min nicht.
- **Kein Motion-Sensor-Pfad:** Weder `SensorManager` (Beschleunigungssensor) noch die kontinuierliche AR-Klassifikation (`getActivityUpdates`) fließen in `classify()` ein. Die AR-Transitions dienen nur als Trigger; der AR-**Typ** (WALKING vs. IN_VEHICLE) wird bei der Drive-Entscheidung nicht als Kontext genutzt.

---

## 4. Neuer Ansatz: GPS-Speed + Motion-Sensoren

### 4.1 Architektur-Prinzip

Drei unabhängige Signal-Familien, die sich gegenseitig **vetoieren** statt nur zu addieren:

```
GPS-Speed (bestehend)          Motion-Kontext (NEU)              Schrittfrequenz (NEU)
Probe-Serie, Gates             AR-Typ: IN_VEHICLE /              Beschleunigungs-Sensor:
                               WALKING / RUNNING /               Cadence 1,7–3,2 Hz
                               ON_FOOT / UNKNOWN                 (Schrittfrequenz)
        └──────────┬──────────────────┴──────────────────┬──────────┘
                   ▼                                     ▼
        Drive-Klassifikation                    Joggen/Walking-Bestätigung
        (Speed-Gates, kontext-                  (Cadence-Band + Speed-Band)
        abhängige Auto-Schwelle)
```

**Kernregel:** Die Auto-Schwelle wird **kontextabhängig**. Meldet das Handy „zu Fuß“ (WALKING/RUNNING/ON_FOOT), ist 8 m/s keine ausreichende Evidenz für Autofahren — ein Mensch kann 8 m/s nur Sekunden halten, nie Minuten. Meldet es IN_VEHICLE, bleibt die bewährte 8-m/s-Schwelle (30er-Zonen-Erkennung bleibt erhalten).

### 4.2 Konkrete Schwellen & Logik

#### 4.2.1 Motion-Gate in `DriveDetectionEngine.classify()` (Kern-Fix)

Neuer Parameter `motionContext: MotionContext` (Enum, Android-frei für JVM-Tests):

```kotlin
enum class MotionContext {
    UNKNOWN,        // kein AR-Signal (Default — Verhalten wie heute)
    ON_FOOT,        // AR: WALKING, RUNNING, ON_FOOT (auch STILL→UNKNOWN)
    IN_VEHICLE      // AR: IN_VEHICLE
}
```

| Konstante | Wert | Begründung |
|---|---|---|
| `MOTION_GATED_DRIVE_SPEED_MPS` | **12,0 m/s (43,2 km/h)** | Auto-Schwelle bei ON_FOOT-Kontext. 43 km/h ist von keinem Läufer (Weltrekord-Sprint ~37 km/h über 100 m, nie über Minuten) und keinem Radfahrer im Dauerbetrieb erreichbar; jede echte Stadt-/Landfahrt erreicht sie. Joggen 16 km/h (4,44 m/s) bleibt mit riesigem Abstand darunter |
| `MOTION_GATED_MIN_CONSECUTIVE_FAST` | **3** | Bei ON_FOOT-Kontext braucht es 3 konsekutive ≥ 12-m/s-Probes (45 s bei 15-s-Stream) — ein einzelner GPS-Burst (2 Fixes) reicht nicht, auch wenn der Kontext schon IN_VEHICLE war |
| `MOTION_GATED_MIN_FAST_PROBES` | 2 | unverändert (Fenster-Zählung) |
| `MOTION_GATED_AVG_SPEED_MPS` | **6,0 m/s (21,6 km/h)** | Fenster-Schnitt bei ON_FOOT: über jedem Lauf-/Jogging-Schnitt (4,44 m/s bei 16 km/h), unter jeder echten Fahrt mit 12-m/s-Spitzen |

**Logik in `classify()`** (nach dem bestehenden Netto-Displacement-Gate, vor der Kette):

```kotlin
val driveSpeed = if (motionContext == MotionContext.ON_FOOT)
    MOTION_GATED_DRIVE_SPEED_MPS else AUTO_SPEED_MPS
val minConsec = if (motionContext == MotionContext.ON_FOOT)
    MOTION_GATED_MIN_CONSECUTIVE_FAST else MIN_CONSECUTIVE_FAST
val minAvg = if (motionContext == MotionContext.ON_FOOT)
    MOTION_GATED_AVG_SPEED_MPS else 4.5f
// Positions-Konsistenz, Kette, fastCount, avg — wie bisher, nur mit den
// kontextabhängigen Schwellen
```

**Wirkung auf die Fälle:**
- Joggen 16 km/h + 2 Spikes, AR = RUNNING → ON_FOOT → 12-m/s-Schwelle → Spikes (8,5 m/s) zählen nicht → NotDriving. ✅
- Spazieren in 30er-Zone, AR = WALKING → ON_FOOT → 12 m/s → NotDriving. ✅
- Echte 30er-Zone-Fahrt, AR = IN_VEHICLE → 8 m/s unverändert → Driving (Regression-Test M18.113 bleibt grün). ✅
- AR = UNKNOWN (kein Signal, Permission fehlt): Verhalten wie heute (8 m/s) — kein neues False-Negative-Risiko. ✅

**Kontext-Quelle (Android):** `ActivityRecognitionClient.getActivityUpdates()` (kontinuierliche Samples, Sensor-Hub, ~0 Akku — kein GPS) mit 30-s-Intervall; der **zuletzt gemeldete Typ mit Confidence ≥ 60** ist der Kontext. IN_VEHICLE gewinnt bei Gleichzeitigkeit (Fahrgast im Bus: AR meldet IN_VEHICLE, nicht ON_FOOT). Der Kontext wird in der `ActivityRecognitionBridge` als `@Volatile`-Feld gehalten (gleiches Muster wie `cachedDriving`) und von `classify()`-Aufrufern (Service, DriveProbeWorker, DriveStartWorker) gelesen. Fallback: Wenn die AR-Permission fehlt → UNKNOWN.

#### 4.2.2 Schrittfrequenz (Cadence) — das „Handy bewegt sich“-Signal des Users

**Android:** `SensorManager` + `TYPE_ACCELEROMETER`, nur während CONFIRM-Bursts und TRACK_DRIVE (batteriegebunden an ohnehin laufende GPS-Fenster, kein Dauerbetrieb). Schätzung der Schrittfrequenz über Autokorrelation des Beschleunigungs-Betrags (oder Zero-Crossings der vertikalen Komponente nach High-Pass, 0,5 Hz) in 10-s-Fenstern.

| Konstante | Wert | Begründung |
|---|---|---|
| `JOGGING_CADENCE_MIN_HZ` | **2,2 Hz (132 Schritte/min)** | Untergrenze Joggen (lockeres Joggen ~140 spm; 132 spm = 2,2 Hz) |
| `JOGGING_CADENCE_MAX_HZ` | **3,2 Hz (192 spm)** | Obergrenze (schnelles Laufen ~190 spm) |
| `WALKING_CADENCE_MAX_HZ` | 2,0 Hz (120 spm) | Gehen liegt bei 90–120 spm |
| `CADENCE_CONFIRM_WINDOW_S` | 30 s | Mindest-Fenster für eine stabile Schätzung |
| `CADENCE_MIN_VALID_FRACTION` | 0,6 | 60 % der Fenster müssen im Band liegen |

**Entscheidung:** Liegt die Cadence im Jogging-Band (2,2–3,2 Hz) **und** die GPS-Speed im Bereich 2,5–8,0 m/s (9–29 km/h), ist die Bewegung **Joggen** — die Drive-Klassifikation wird in diesem Fenster blockiert (Veto), unabhängig vom AR-Kontext. Fahrzeug-Vibration ist hochfrequent (> 10 Hz) und niederamplitudig — sie erzeugt keine 2,2-Hz-Periode und fällt durch die Autokorrelations-Schwelle. **iOS-Äquivalent:** `CMMotionActivityManager` (walking/running/automotive) + `CMAccelerometerData` mit derselben Cadence-Schätzung — die Logik ist plattformneutral als pure Funktion implementierbar.

**Wichtig (Akku):** Der Sensor läuft NUR in ohnehin aktiven Burst-/Track-Fenstern. Kein 24/7-Sensor-Stream — das M18.104-Akku-Prinzip bleibt unangetastet.

#### 4.2.3 Härtung: Fahrzeug-Veto im Walking-Heartbeat (Fall 3)

In `DriveDetectionService.handleFix` bekommt der TRACK_WALK-Heartbeat-Refresh dasselbe Veto wie die Phasen-Erkennung:

```kotlin
if (mode == StreamMode.TRACK_WALK && distance != null && distance >= MIN_PROBE_MOVEMENT_M) {
    val directVehicle = WalkingDetectionEngine.isVehicleSpeed(speed)          // ≥ 8 m/s
    val derivedVehicle = derivedSpeedFrom(prevFix, loc) >= WALKING_VEHICLE_SPEED_MPS
    if (!directVehicle && !derivedVehicle) {
        bridge.markWalkingSignal(now)                                          // nur Geh-/Lauf-Tempo hält die Session
    } else {
        // Fahrzeug-Tempo: Walking-Session beenden + Drive-Pfad anstoßen
        bridge.clearWalkingActive()
        bridge.clearWalkingSignal()
        live.stop()   // über WalkingStopWorker-Pfad
        DriveDetectionService.start(context, ACTION_CONFIRM)                  // Fahrt-Verdacht prüfen
    }
}
```

Damit endet eine Walking-Session beim Einsteigen in ein Fahrzeug sofort (statt nach 8 Min), und die Fahrt wird aktiv geprüft. Der Watchdog bleibt als Fallback.

#### 4.2.4 Härtung: Geschwindigkeitsbasiertes Displacement-Veto (Joggen-Schutz)

`WALKING_DISPLACEMENT_VETO_M = 350 m` (fix) wird durch eine Geschwindigkeits-Bedingung ersetzt — konsistent mit dem MAX-Gate `WALKING_MAX_AVG_SPEED_MPS = 5,0 m/s`:

```kotlin
// Statt: dist >= 350 m
// Neu:   dist / dt >= 5,0 m/s   (dt im Fenster 30 s–2 Min)
```

Joggen 16 km/h = 4,44 m/s bleibt unter 5,0 m/s — auch bei 120-s-Fix-Lücken (533 m, aber 533/120 = 4,44 m/s). Fahrzeug-Tempo (8,3 m/s) wird weiterhin verworfen. Die Konstante `WALKING_DISPLACEMENT_VETO_M` entfällt zugunsten von `WALKING_DISPLACEMENT_VETO_SPEED_MPS = 5.0f` (Wert = `WALKING_MAX_AVG_SPEED_MPS`, eine Quelle).

### 4.3 Vollständige Schwellen-Tabelle (neu)

| Signal | Konstante | Wert | Gilt für |
|---|---|---|---|
| GPS | `AUTO_SPEED_MPS` | 8,0 m/s (28,8 km/h) | Drive-Klassifikation bei AR = IN_VEHICLE oder UNKNOWN |
| GPS | `MOTION_GATED_DRIVE_SPEED_MPS` | **12,0 m/s (43,2 km/h)** | Drive-Klassifikation bei AR = ON_FOOT (WALKING/RUNNING/ON_FOOT) |
| GPS | `MOTION_GATED_MIN_CONSECUTIVE_FAST` | **3** | Konsekutiv-Kette bei ON_FOOT |
| GPS | `MOTION_GATED_AVG_SPEED_MPS` | **6,0 m/s (21,6 km/h)** | Fenster-Schnitt bei ON_FOOT |
| GPS | `MIN_FAST_PROBES` / `MIN_SPREAD_MS` / `MIN_NET_DISPLACEMENT_M` | 2 / 30 s / 150 m | unverändert |
| Motion | `JOGGING_CADENCE_MIN_HZ` / `MAX_HZ` | **2,2 / 3,2 Hz** | Cadence-Veto (Joggen blockiert Drive) |
| Motion | `WALKING_CADENCE_MAX_HZ` | 2,0 Hz | Abgrenzung Gehen |
| Motion | `CADENCE_CONFIRM_WINDOW_S` / `MIN_VALID_FRACTION` | 30 s / 0,6 | Stabilität der Schätzung |
| Walking | `WALKING_VEHICLE_SPEED_MPS` | 8,0 m/s | unverändert, **neu auch im TRACK_WALK-Heartbeat** |
| Walking | `WALKING_DISPLACEMENT_VETO_SPEED_MPS` | **5,0 m/s** (ersetzt fixe 350 m) | Phasen-Start-Veto, dt-unabhängig |
| Walking | `WALKING_THRESHOLD_MS` / `WATCHDOG_NO_SIGNAL_MS` | 5 / 8 Min | unverändert |
| Drive | `DRIVE_WATCHDOG_NO_SIGNAL_MS` / `DRIVE_MIN_PROBE_MOVEMENT_M` | 5 Min / 200 m | unverändert |

### 4.4 Pseudocode der neuen Drive-Entscheidung

```
classify(probes, now, geofences, motionContext, cadence):
    # 1–3: bestehende Filter (Alter, Accuracy, Ausreißer, Spread, Sprünge)
    # 4:   Netto-Displacement ≥ 150 m, Geofence-Veto — unverändert
    # 5:   CADENCE-VETO (neu):
    #      wenn cadence im Jogging-Band (2,2–3,2 Hz, ≥ 60 % der Fenster)
    #      UND avgSpeed in 2,5–8,0 m/s  → NotDriving (Joggen, kein Auto)
    # 6:   KONTEXT-SCHWELLEN (neu):
    #      driveSpeed  = ON_FOOT ? 12,0 : 8,0
    #      minConsec   = ON_FOOT ? 3    : 2
    #      minAvg      = ON_FOOT ? 6,0  : 4,5
    # 7:   Positions-Konsistenz + Kette + fastCount + avg — mit (6)
    # 8:   Driving ⇔ fastCount ≥ 2 && maxConsecutive ≥ minConsec && avg ≥ minAvg
```

### 4.5 Edge Cases

| Fall | Verhalten |
|---|---|
| AR-Permission fehlt / kein AR-Signal | `motionContext = UNKNOWN` → heutiges Verhalten (8 m/s). Kein neues False-Negative |
| Fahrgast im Bus/Zug (AR = IN_VEHICLE, 30er-Zone) | 8-m/s-Schwelle wie heute — bewusst unverändert (Mobilität wird als Autofahren-Typ aufgezeichnet, User kann Kategorie wechseln; kein neues Risiko) |
| Rennrad-Abfahrt (AR = ON_BICYCLE) | ON_BICYCLE ist weder ON_FOOT noch IN_VEHICLE → UNKNOWN-Verhalten (8 m/s). Radfahrer-Spike-Muster scheitert weiterhin an der Konsekutiv-Kette; reine 12-m/s-Forderung wäre hier zu streng — bewusste Entscheidung, ON_BICYCLE nicht in ON_FOOT zu zwingen |
| Joggen ohne AR-Signal (Permission fehlt) | Cadence-Veto greift (Sensor braucht keine AR-Permission) — der wichtigste Fall ist damit auch ohne AR abgedeckt |
| Stop&Go-Fahrt, AR flackert WALKING | Kontext-Wechsel ON_FOOT → 12 m/s: Bei Stop&Go erreicht die Fahrt selten 43 km/h → Fahrt wird evtl. später erkannt (wenn IN_VEHICLE wieder meldet). Trade-off akzeptiert: False-Positive (Joggen als Fahrt) ist der gemeldete User-Bug; False-Negative heilt der nächste IN_VEHICLE-ENTER. Zusätzlich: AR-Kontext mit 60-s-Hysterese (Kontext wechselt erst nach 2 aufeinanderfolgenden Samples) gegen Flapping |
| Walking-Session live, User steigt ins Auto | Heartbeat-Veto (4.2.3) beendet die Session sofort + CONFIRM-Burst prüft die Fahrt |
| Schnelles Joggen > 5,0 m/s (18 km/h) | MAX-Gate der Walking-Phase verwirft die Phase — Joggen > 18 km/h über 5 Min ist extrem selten; RUNNING-AR-Pfad (markWalkingSignal) ist davon unabhängig und startet weiterhin `joggen` |

---

## 5. Umsetzungsplan (für die Implementierungs-Task)

1. **`DriveDetectionEngine`:** `MotionContext`-Enum + `motionContext`-Parameter in `classify()` (Default `UNKNOWN`), kontextabhängige Schwellen, Cadence-Veto als pure Funktion (`isJoggingCadence(cadenceHz, validFraction)`). Alle bestehenden Tests bleiben ohne Änderung grün (Default-Parameter).
2. **`ActivityRecognitionBridge`:** `@Volatile motionContext` + `updateMotionContext(activityType, confidence)`; `ActivityRecognitionClient.getActivityUpdates()`-Registrierung im `ActivityRecognitionRegistrar` (30-s-Intervall, Sensor-Hub) mit 60-s-Hysterese; Fallback UNKNOWN.
3. **`DriveDetectionService`:** Kontext in `classify()`-Aufrufe durchreichen; TRACK_WALK-Heartbeat-Veto (4.2.3); Cadence-Sampling via `SensorManager` nur in CONFIRM/TRACK_DRIVE-Fenstern (10-s-Autokorrelations-Fenster, Ergebnis als `cadenceHz` an die Engine).
4. **`WalkingDetectionEngine`:** `WALKING_DISPLACEMENT_VETO_M` → geschwindigkeitsbasiertes Veto (4.2.4).
5. **Tests (JVM, Android-frei):**
   - Joggen 16 km/h + 2 Spikes + ON_FOOT → NotDriving (Kern-Regression)
   - Joggen 16 km/h + 2 Spikes + UNKNOWN → NotDriving via Cadence-Veto
   - 30er-Zone-Fahrt + IN_VEHICLE → Driving (bestehender Test bleibt grün)
   - 30er-Zone-Fahrt + ON_FOOT (AR-Flackern) → NotDriving
   - Joggen-Cadence 2,4 Hz + 4,44 m/s → NotDriving; Auto-Vibration (12 Hz) → kein Cadence-Match
   - Walking-Heartbeat: Fix mit 8,3 m/s refresht NICHT; Fix mit 1,4 m/s refresht
   - Displacement-Veto: 533 m / 120 s (Joggen) → kein Veto; 1000 m / 120 s (Fahrzeug) → Veto
6. **Verifikation:** `./gradlew testDebugUnitTest` + bestehende Drive-/Walking-Testsuiten (DriveDetectionEngineTest, DriveDetectionArbiterTest, WalkingVehicleVetoTest, DriveStructuralGatesTest).

## 6. Offene Punkte / Risiken

- **AR-Kontext-Zuverlässigkeit:** Googles AR-Typ ist nicht perfekt (WALKING während Stop&Go). Die 60-s-Hysterese + 12-m/s-Schwelle absorbieren Flackern; falls der Kontext dauerhaft falsch liegt (z. B. AR meldet RUNNING während der Fahrt), wird die Fahrt erst bei 43 km/h erkannt — bewusster Trade-off zugunsten des gemeldeten False-Positive-Bugs.
- **Cadence-Schätzung:** Autokorrelation auf 10-s-Fenstern ist robust, aber nicht validiert auf echten Geräten — Feldtest (Task t_b0026496) mit Joggen 16 km/h ist Pflicht vor Freigabe.
- **iOS:** Dieses Repo ist Android (Kotlin). Die Logik ist plattformneutral (pure Funktionen), die Core-Motion-Anbindung (CMMotionActivityManager/CMAccelerometerData) ist das 1:1-Äquivalent für eine iOS-Variante — hier nicht implementiert.
- **Akku:** Sensor-Sampling nur in Burst-/Track-Fenstern; worst case +10 s Sensor-Zeit pro CONFIRM-Burst (5 Min Fenster). Kein 24/7-Sensor-Stream.

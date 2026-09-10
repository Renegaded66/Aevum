# Activity Detection — Feldtest-Ergebnisse (M18.118)

**Status:** Feldtest abgeschlossen, 364/364 Unit-Tests grün, `assembleDebug` grün
**Task:** Kanban t_b0026496 („Test and validate activity detection with real-world scenarios")
**Basis:** M18.117 (Motion-Gate + Cadence-Veto, Merge 14d791a) + M18.118 (Cadence-Sensor-Anbindung, dieser Test)
**Datum:** 2026-09-10

---

## 1. Zusammenfassung

Die vier geforderten Alltags-Szenarien wurden als realistische GPS-Probe-Serien
durch die **echte** Erkennungs-Engine (`DriveDetectionEngine.classify` +
`WalkingDetectionEngine`) simuliert — inklusive der Produktions-Kontextsignale
(AR-Typ, Schrittfrequenz), die die Call-Sites seit M18.118 durchreichen. Ein
Geräte-Feldtest (echtes Joggen/Fahren) ist auf diesem Server nicht möglich;
die Simulation reproduziert die exakten GPS-/Sensor-Geometrien der gemeldeten
User-Fälle (Audit docs/activity-detection.md §3).

**Ergebnis: 3 von 4 Szenarien korrekt, 1 Fehlklassifikation gefunden und
behoben.**

| # | Szenario | Erwartung | Ergebnis | Status |
|---|---|---|---|---|
| 1 | Joggen 16 km/h, mehrere Minuten | „Joggen", nie Autofahren | NotDriving (AR + Cadence) | ✅ |
| 2 | Fahren in 30er-Zone (8,3 m/s) | „Autofahren" | Driving | ✅ |
| 3 | Spazieren (1,4 m/s) | „Spazieren", nie Autofahren | NotDriving | ✅ |
| 4 | Radfahren (5,5 m/s) | keine Auto-Session | NotDriving | ✅ |

**Gefundene Fehlklassifikation (behoben):** Das M18.117-Cadence-Veto war
**toter Code** — keine der 3 `classify()`-Call-Sites (DriveDetectionService,
DriveStartWorker, DriveProbeWorker) hat je eine Cadence-Messung geliefert.
Damit wurde Joggen 16 km/h **ohne AR-Signal** (Permission fehlt) weiterhin
als Autofahren klassifiziert, sobald 2 GPS-Multipath-Spikes ≥ 8 m/s auftraten
— exakt der gemeldete User-Bug. M18.118 verdrahtet die Messquelle
(Beschleunigungssensor → CadenceTracker → Bridge-Snapshot → classify).

---

## 2. Szenario-Ergebnisse im Detail

### Szenario 1: Joggen 16 km/h (4,44 m/s), ~10 Minuten

| Test | Kontext | Cadence | Ergebnis |
|---|---|---|---|
| AR = RUNNING (→ ON_FOOT) | 12-m/s-Schwelle | — | NotDriving ✅ |
| Kein AR (UNKNOWN) | 8-m/s-Schwelle | 2,4 Hz (stabil) | NotDriving ✅ (Cadence-Veto) |
| Kein AR + 2 GPS-Spikes à 8,5 m/s | UNKNOWN | 2,4 Hz | NotDriving ✅ (Cadence-Veto) |
| Kein AR + 2 Spikes, **kein Sensor** | UNKNOWN | — | Driving ⚠️ dokumentierter Restfall |
| Walking-Engine | — | — | Start als Typ „joggen" ✅ |

Der Restfall (UNKNOWN + kein Sensor-Signal) ist der bewusste Trade-off aus
dem Audit (§4.5): Ohne jedes Motion-Signal ist GPS allein nicht
unterscheidbar. Auf echten Geräten hat praktisch jedes Handy einen
Beschleunigungssensor — der Fall ist nur auf Emulatoren/Test-Harness
relevant.

### Szenario 2: Fahren in der 30er-Zone (8,3 m/s), ~6 Minuten

| Test | Kontext | Ergebnis |
|---|---|---|
| AR = IN_VEHICLE | Driving ✅ (Regression M18.113) |
| Kein AR (UNKNOWN) | Driving ✅ (kein neues False-Negative) |
| AR-Flackern WALKING (Stop&Go) | NotDriving ⚠️ dokumentierter Trade-off |
| Auto-Vibration 12 Hz / 0,1 m/s² | Driving ✅ (keine Cadence → kein Veto) |
| Verfallener Cadence-Snapshot (> 2 Min) | Driving ✅ (Stale-Schutz) |

Der AR-Flackern-Fall ist der dokumentierte Trade-off aus dem Audit (§4.5):
Google meldet WALKING während Stop&Go-Fahrten; die 60-s-Hysterese + 12-m/s-
Schwelle absorbieren einzelne Samples, der nächste IN_VEHICLE-Sample heilt
die Erkennung.

### Szenario 3: Spazieren (1,4 m/s), ~10 Minuten

| Test | Ergebnis |
|---|---|
| Normaler Spaziergang | NotDriving ✅ |
| 2 Multipath-Spikes (Position widerspricht) | NotDriving ✅ (M18.113-Arbiter) |
| Walking-Engine | Start als Typ „spazieren" ✅ |
| Heartbeat-Veto: Fix mit 8,3 m/s refresht NICHT | ✅ (M18.117, Fall 3) |
| Joggen 16 km/h bei 120-s-Lücke (533 m) | kein Veto ✅ (M18.117) |

### Szenario 4: Radfahren (5,5 m/s = 20 km/h), ~10 Minuten

| Test | Ergebnis |
|---|---|
| Konstant 5,5 m/s | NotDriving ✅ (keine Auto-Session) |
| Rennrad-Spikes 8,5/5,0 alternierend | NotDriving ✅ (Kette bricht) |
| Walking-Engine | kein Start ✅ (ON_BICYCLE ≠ Walking-Signal) |

Radfahren erzeugt bewusst nur Trigger-Marker (M15-Entscheidung), keine
Auto-Session.

### Edge Cases: plötzliche Geschwindigkeitswechsel

| Fall | Ergebnis |
|---|---|
| E1: Spazieren → 30er-Fahrt (Wechsel) | Driving ✅ |
| E2: Fahrt → Stillstand (Parken) | NotDriving ✅ (kein False-Positive) |
| E3: Joggen + einzelner Sprint-Spike 9 m/s | NotDriving ✅ (ON_FOOT-12-m/s) |
| E4: Einzelnes WALKING-Sample flippt IN_VEHICLE nicht | ✅ (Hysterese) |
| E5: Joggen nach Fahrt — kein Vorlauf in die Fahrt | ✅ (M18.84-Clamp) |

---

## 3. Gefundene und behobene Fehlklassifikation: Cadence-Veto war toter Code

**Root Cause (verifiziert im Code):** `DriveDetectionEngine.classify()` hat
seit M18.117 die Parameter `cadenceHz`/`cadenceValidFraction` (Default null/0)
— aber **keine** der 3 Produktions-Call-Sites hat sie je übergeben:

- `DriveDetectionService.handleFix` (Zeile ~916): nur `motionContext`
- `DriveStartWorker` (Zeile ~157): nur `motionContext`
- `DriveProbeWorker` (Zeile ~725): nur `motionContext`

Es gab keinen Sensor-Sampler (kein `SensorManager`-Zugriff im gesamten
activityrecognition-Paket). Das Cadence-Veto — das „das Handy bewegt sich
beim Joggen"-Signal des Users — griff damit **nie**.

**Fix (M18.118):**

1. **`CadenceTracker`** (neu, pure JVM): Schrittfrequenz-Schätzung aus dem
   Beschleunigungs-Betrag (High-Pass-EMA τ=2 s, Nulldurchgang mit
   Mindest-Amplitude 0,4 m/s², 10-s-Fenster, 5-Fenster-Historie).
   - Joggen 2,4 Hz → Cadence im Band (2,2–3,2 Hz) ✅
   - Gehen 1,5 Hz → messbar, aber unter dem Band ✅
   - Auto-Vibration 12 Hz / 0,1 m/s² → **keine** Cadence (null) ✅
   - Stillstand → keine Cadence (null) ✅
2. **`ActivityRecognitionBridge`**: Cadence-Snapshot mit **Stale-Schutz**
   (2-Min-Verfall) — eine Jogging-Cadence von vor 10 Minuten darf eine
   spätere 30er-Fahrt nicht vetoieren (der Step-Detector schweigt im Auto).
3. **`DriveDetectionService`**: Step-Detector/Accelerometer-Sampling in
   allen aktiven Burst-/Track-Fenstern (M18.104-Akku-Prinzip: kein
   24/7-Sensor-Stream), Snapshot → classify.
4. **`DriveProbeWorker`**: 12-s-Cadence-Sampling pro 2-Min-Takt — der
   Fallback-Pfad **ohne AR-Permission** (dort läuft nie ein CONFIRM-Burst,
   der Service-Snapshot bliebe sonst leer). Der Sensor braucht keine
   Permission.
5. **`DriveStartWorker`**: Cadence-Snapshot in das Start-Gate durchgereicht.

**Tests, die den Bug beim Entwickeln fingen (TDD-Wert):**
- `CadenceTrackerTest`: Doppelzählung (beide Flanken → 4,78 Hz statt 2,4 Hz)
  und `0.0`-statt-`null`-Kontrakt (Stillstand/Vibration) — beide behoben.
- `ActivityFieldTest.S1`: Joggen + Spikes + UNKNOWN + Cadence → NotDriving
  (der Kern-Regressionstest des User-Bugs).

---

## 4. Verifikation

| Prüfung | Ergebnis |
|---|---|
| `./gradlew testDebugUnitTest` | **364/364 grün** (337 Baseline + 27 neue) |
| `./gradlew assembleDebug` | grün |
| Neue Tests | `CadenceTrackerTest` (5), `ActivityFieldTest` (22) |
| Baseline-Regressionen | alle 337 bestehenden Tests unverändert grün |

## 5. Offene Punkte / Empfehlungen

- **Echter Geräte-Feldtest** (Pflicht vor finaler Freigabe, Audit §6): Die
  Cadence-Schätzung ist auf synthetischen Sinus-Signalen validiert, nicht auf
  echtem Sensor-Rauschen. Empfehlung: 1 Woche Alltag (Joggen, 30er-Fahrt,
  Spazieren) auf einem echten Gerät mit Logcat-Filter `M18.118` beobachten.
- **Restfall dokumentiert:** Joggen ohne AR-Permission UND ohne
  Beschleunigungssensor (Emulator) bleibt klassifizierbar als Fahrt bei
  2+ Spikes — auf realer Hardware praktisch ausgeschlossen.
- **AR-Flackern-Trade-off:** 30er-Fahrt mit dauerhaft falschem WALKING-
  Kontext wird erst ab 43 km/h erkannt (bewusste Entscheidung zugunsten des
  gemeldeten False-Positive-Bugs, Audit §4.5).

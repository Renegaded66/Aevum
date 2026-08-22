# M18.72 — Wanderungen automatisch aufzeichnen (5-Minuten-Schwelle + Vorlauf)

**Commit:** `b02d415`
**Tests:** 161 grün (150 Bestand + 11 neue), 0 failures/errors
**Änderungen:** 7 Dateien, +762 / −42

## User-Spec (aus Task)

(a) Erst wenn man 5 Minuten am Stück unterwegs ist (Bewegung — nicht jeder
    Gang zum Kühlschrank) wird die Wanderung automatisch aufgezeichnet.
(b) Die 5 Minuten Vorlaufzeit werden mit aufgezeichnet
    (startedAt = now − 5 min).
Analog M18.70 ScreenRecordingEngine (Schwelle + Vorlauf). Nur nicht-
überlappend mit anderen Auto-Aufzeichnungen. Session mit sourceType
`WALKING_AUTO`.

## Umsetzung

### 1. `WalkingDetectionEngine` (neu, pure Logik — M18.70-Muster)
- `shouldStartWalking(walkingSinceMs, now, walkingEnabled, anythingRecording)`:
  5-Minuten-Schwelle, Gate, „nichts anderes zeichnet auf".
- `recordingStartTime(now) = now − 5 min` (Vorlauf, Spec (b)).
- `shouldStopWalking(lastWalkingSignalMs, now)`: Stopp erst nach 5 Minuten
  ohne Signal (kurze Pausen beenden die Wanderung nicht).

### 2. `WalkingWorkers.kt` (neu, Muster DriveWorkers M18.66)
- `WalkingStartWorker`: prüft Engine + Gate + Duplikat-Schutz, beendet eine
  andere Live-Session via `forceFinishForAuto` (M18.71-Overlap-Regeln im
  `start()`-Pfad), startet Session mit `sourceType = "WALKING_AUTO"`,
  `activityTypeId = "spazieren"` (RUNNING-Events → `"joggen"`/„Joggen"),
  `startedAt = now − 5 min`. Startet LiveActivityService + Watchdog.
- `WalkingStopWorker`: sofortiger Stopp bei bestätigtem WALKING-EXIT.
- `WalkingWatchdogWorker`: 5 Minuten ohne Signal → Stopp. Jedes Signal
  (Bridge-Heartbeat ODER GPS-Bewegung ≥ 10 m zwischen 2 Fixes) refresht
  den Timer (REPLACE).

### 3. Erkennungs-Pfade (zwei unabhängige Signale)
- **Google-Transitions** (`ActivityTransitionReceiver`): WALKING/RUNNING-
  ENTER → `bridge.markWalkingSignal(now)` + `WalkingStartWorker.schedule()`
  (Engine entscheidet nach 5 Minuten); EXIT → Phase zurücksetzen +
  `WalkingStopWorker` (bestätigtes Ende → sofortiger Stopp). Der alte
  M18.43-5-Minuten-Trigger-Timer (nur Marker) wurde durch den Auto-Start
  ersetzt; ON_BICYCLE bleibt Trigger-Marker (M15, unzuverlässig).
- **GPS-Pfad** (`DriveDetectionService`): Walking-Phase über
  Netto-Displacement — ≥ 300 m geradlinig vom Phasenstart, frühestens nach
  5 Minuten, Genauigkeit ≤ 50 m, Stillstand > 5 min verwirft die Phase
  (kein akkumuliertes Gedächtnis). Netto-Displacement statt kumulierter
  Distanz schützt gegen Indoor-GPS-Drift (Lektion M18.66-FIX13).
  Der Service läuft jetzt, wenn Autofahrt ODER Walking aktiv ist.

### 4. Nicht-Überlappung (Spec)
- Engine: `anythingRecording == true` → kein Start.
- Worker: Doppel-Start-Schutz per SourceType, `forceFinishForAuto` für
  andere Live-Sessions — es existiert nie zwei Live-Sessions (M18.71-
  Overlap-Resolver greift zusätzlich im `start()`-Pfad).
- GPS-Walking-Phase läuft nur, wenn weder Fahrt noch Wanderung aktiv
  (`!isDriveActive() && !isWalkingActive()`).

### 5. UI/Plumbing
- `AUTO_SOURCES` + `"WALKING_AUTO"` (Timeline/Dashboard/Live-Card/
  Notification markieren die Session automatisch als „Auto").
- `M12RegressionTest.autoSourcesContainsAllAutomaticTypes` aktualisiert.

## Geänderte Dateien

| Datei | Änderung |
|-------|----------|
| `automation/activityrecognition/WalkingDetectionEngine.kt` | **neu** (pure Logik) |
| `automation/activityrecognition/WalkingWorkers.kt` | **neu** (Start/Stop/Watchdog) |
| `automation/activityrecognition/DriveDetectionService.kt` | Walking-Phase (GPS-Displacement), Gate: driving ODER walking |
| `automation/activityrecognition/ActivityRecognitionWorker.kt` | Bridge: Walking-Signale; Receiver: WALKING/RUNNING → Auto-Start |
| `ui/screens/timeline/TimelineViewModels.kt` | `AUTO_SOURCES` + `WALKING_AUTO` |
| `test/.../WalkingDetectionEngineTest.kt` | **neu** (11 Tests) |
| `test/.../M12RegressionTest.kt` | AUTO_SOURCES-Assertion erweitert |

## Verbleibende Risiken / Validation
1. **Google-EXIT-Zuverlässigkeit:** OEM-ROMs liefern EXITs oft verspätet —
   Fallback ist der 5-Minuten-Watchdog (GPS-Stillstand). 
2. **Walking-Displacement-Fehlalarme:** 300 m Netto in 5 min ist ein
   konservatives Gate; kurze Erledigungswege (Supermarkt 200 m) bleiben
   unter der Schwelle — bewusst (User-Spec: „nicht jeder Gang zum
   Kühlschrank").
3. **Validation auf Gerät:** 5 min zu Fuß → Session „Spazieren" startet
   mit 5-min-Vorlauf; stehen bleiben > 5 min → Session stoppt; Autofahrt
   während Wanderung → Fahrt übernimmt, Wanderung endet am Fahrtstart
   (M18.71-Overlap).

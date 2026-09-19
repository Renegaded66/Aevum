# M18.133 — Fahrt-Stopp beim Gehen: Aussteigen beendet die Aufzeichnung sofort

**Datum:** 19.09.2026
**Version:** 1.0.15 → **1.0.16** (versionCode 17), DB v42 → **v43**
**Tests:** 748 Tests, 0 Failures, 0 Errors (67 Testklassen; neu: 2 Klassen, 36 Tests)

## Nutzer-Auftrag (wörtlich)

> „Die automatische Auto-Aufzeichnung startet mittlerweile sehr schnell, das
> passt. Aber die läuft immer noch sehr lange weiter, obwohl man mit der
> Autofahrt aufgehört hat. Ungefähr 10 Minuten nach Ende der Autofahrt hört
> irgendwann die Aufzeichnung auf. Das ist viel zu lang. Also nach 5 Minuten
> keiner Erkennung kann pauschal immer gestoppt werden, weil keine Ampel
> 5 Minuten rot ist. Aber mir würde es gefallen, wenn die Aufzeichnung noch
> schneller stoppt. Es gibt doch bspw. Activity-Erkennung von Android aus.
> Sobald ich aus dem Auto aussteige und gehe, bin ich ja offensichtlich nicht
> mehr am Autofahren und die Aufzeichnung kann gestoppt werden. Dazu muss aber
> auch bestimmt die Berechtigung erteilt werden. Das sollte über die
> Einstellungen passieren können. Und falls die Berechtigung erteilt ist, soll
> dann die Aufzeichnung automatisch stoppen, sobald Schritte bzw. Gehen
> erkannt wird."

---

## 1. Ursachen-Analyse (evidenzbasiert, keine Vermutungen)

### Befund 1 — Der ~10-Minuten-Nachlauf hat ZWEI zusammenspielende Ursachen

**(a) Der 5-Minuten-Watchdog verlängert sich selbst.**

`DriveWatchdogWorker` (DriveWorkers.kt) läuft alle 5 Minuten. Findet er im
ersten Check **kein** frisches AR-Sample, macht er einen **blockierenden**
GPS-Bewegungs-Check: Fix → 2 Minuten warten → Fix → Haversine-Distanz.

Bei ≥ **200 m** in 2 Minuten gilt die Fahrt als lebendig und der Watchdog
plant sich um weitere 5 Minuten neu (`ExistingWorkPolicy.REPLACE`).

**Rechenfehler im Kommentar (der eigentliche Root-Cause):**
Der Code begründet den Wert 200 m mit *„bleibt über Geh-Tempo (max 1,5 m/s
= 180 m)"*. Das ist falsch: 200 m / 120 s = **1,67 m/s = 6,0 km/h**. Ein
zügiger Fußgänger läuft 1,5–1,8 m/s — er überschreitet die Schwelle
physikalisch. Mit Parkhaus-/Häuserschlucht-Drift auf dem Weg zur Haustür
wird sie fast immer überschritten.

Kombiniert ergibt das: 5 Min Watchdog + 2 Min Check + 5 Min Verlängerung
+ WorkManager-Jitter ≙ die beobachteten **~10 Minuten**.

**(b) Der bestehende Walk-Stop-Detector (M18.127) hängt allein an Google.**

`WalkStopDetector` bezieht seine Evidenz ausschließlich aus GMS-AR-Samples
(`requestActivityUpdates`, 30 s). Zwei Praxis-Lücken:
- Google liefert im Hintergrund oft gar keine WALKING-Samples
  („The latency of event detection might vary by device").
- Der 75-s-Gnadenfrist-Mechanismus greift nur, wenn diese Samples ankommen.

Ergebnis: Die Geh-Erkennung existiert, feuert aber nicht zuverlässig genau
in der Ausstiegs-Situation.

### Befund 2 — Der Hardware-Step-Detector war bereits verfügbar, aber falsch genutzt

`DriveDetectionService` registriert im TRACK_DRIVE-Modus **ohnehin**
`TYPE_STEP_DETECTOR` (M18.118/M18.126) — für das Cadence-Veto (Joggen ist
kein Autofahren). Der Event-Strom war damit kostenlos vorhanden, wurde aber
ausschließlich als **Start**-Veto genutzt, nie als **Stop**-Signal.

Zusätzlich fehlte der Permission-Gate: `TYPE_STEP_DETECTOR` ist seit API 29
an `ACTIVITY_RECOGNITION` gebunden. Ohne Grant lieferte der Sensor still
keine Events — eine unsichtbare Lücke.

---

## 2. Umsetzung

### 2.1 Neu: `StepWalkStopDetector` (pure Logik, JVM-testbar)

`automation/activityrecognition/StepWalkStopDetector.kt`

Nutzt Hardware-Schritte als physikalisches, Google-unabhängiges
Ausstiegs-Signal. Schritte gibt es im Fahrzeug nicht.

**Regeln (alle mit physikalischer Begründung):**

| Regel | Schwelle | Begründung |
|---|---|---|
| Geh-Kette | 10 Schritte / 15 s (0,67 Hz ≈ 40 Schritte/Min) | Deutlich unter Gehtempo (100–120/Min) — Aussteigen + wenige Schritte genügen |
| Fahrzeug-Veto A | Fahrt-Herzschlag < 45 s alt | Herzschlag wird im TRACK alle 15 s erneuert, solange der Fix ≥ 2 m/s zeigt — ein fahrendes Auto hat ihn immer |
| Fahrzeug-Veto B | Frischer Fix ≥ 8 m/s (direkt oder dist/dt) | Ein Fußgänger erreicht 8 m/s nie (M18.117-Argumentation) |
| Ratengrenze | max. 3,5 Hz in der Kette | Vibrations-Fehlzählung (M18.126 belegt); ein Gehender schafft das nicht |
| Notbremse | max. 60 Schritte/Fenster | Defekter Zähler |
| Echo-Schutz | min. 150 ms zwischen Schritten | Doppel-Events sind keine zweiten Schritte |

**Bewusst NICHT als Veto:** Der AR-Motion-Context `IN_VEHICLE`. Google
meldet nach dem Parken oft minutenlang weiter IN_VEHICLE — genau der
~10-Minuten-Nachlauf. Ein Veto darauf würde das Feature zerstören.

**Korrektur während der Umsetzung (dokumentiert, weil lehrreich):** Die
erste Fassung hatte nur die absolute Notbremse (60 Schritte). Ein
5-Hz-Vibrationszähler hätte damit nach 2 s einen Falsch-Stop ausgelöst —
die Notbremse hätte erst nach 12 s gegriffen. Die Ratengrenze prüft
deshalb schon beim 10. Schritt, ob der Kettenspan physiologisch möglich
ist (Span ≥ 2,571 s). Verifiziert durch Simulation aller Testfälle vor
dem Bau.

### 2.2 Bridge (`ActivityRecognitionWorker.kt`)

- `onStepWalkStopStep(nowMs)` — füttert den Detektor, inkl. Herzschlag-Veto
- `hasStepWalkingEvidence(nowMs)` / `stepsInWalkStopWindow(nowMs)` — für Watchdog + Log
- `isStepWalkStopEnabled()` — Setting aus dem bestehenden 30-s-Cache
- `resetWalkStopEvidence()` verwirft **beide** Detektoren (AR + Schritte)

### 2.3 Service (`DriveDetectionService.kt`)

- `stepDetectorListener` ruft `onStepForWalkStop()` **vor** dem
  Cadence-Tracker-Zugriff (sonst wäre der Pfad tot, wenn kein Tracker existiert)
- Gates: nur bei `isDriveActive()` **und** `isStepWalkStopEnabled()`
- Stop läuft über den bewährten **`DriveStopWorker`** (sauberes Stop-Paket:
  Cooldown markieren, Puffer leeren, Walking-Phase beenden, Notification
  entfernen, Geofence-Re-Enter) — kein Direkt-Stop
- Kompletter Körper in `try/catch`: Der Listener läuft im
  Sensor-System-Callback, ein Wurf killt den Prozess (M18.126-Lehre)
- **Wall-Clock-Zeit** statt `event.timestamp` (der basiert auf
  `elapsedRealtimeNanos` und ist mit den Probe-Zeitstempeln nicht
  vergleichbar — ein Mix würde die 90-s-Fenster sinnlos machen). Die
  Cadence behält die monotone Sensor-Zeit (NTP-sicher)
- Permission-Gate: `TYPE_STEP_DETECTOR` nur mit `ACTIVITY_RECOGNITION`;
  der Accelerometer-Fallback bleibt **permissionfrei** für die Cadence (M18.118)

### 2.4 Watchdog-Fix (`DriveWorkers.kt`) — die 10-Minuten-Ursache

Der GPS-Bewegungs-Check unterscheidet jetzt, **wie** Bewegung entsteht:

```
≥ 200 m/2 Min?
  ├─ Schritte erkannt UND kein Fahrzeug-Tempo → GEHEN → Stop
  └─ sonst (Fahrzeug-Tempo / keine Schritte) → Fahrt lebt → verlängern
```

`hasFreshVehiclePace()` prüft ≥ 8 m/s (direkt oder abgeleitet, Accuracy
≤ 50 m, keine Ausreißer) — identische Semantik wie die Detektor-Vetos.

Die 200-m-Schwelle bleibt als grobe Plausibilität (Kriechstau), ist aber
nicht mehr das alleinige Kriterium. `DRIVE_WATCHDOG_NO_SIGNAL_MS` bleibt
bei 5 Minuten (User-Spezifikation: „keine Ampel ist 5 Minuten rot").

### 2.5 Settings-UI + DB

- **DB v42 → v43:** `MIGRATION_42_43` — `ALTER TABLE automation_settings
  ADD COLUMN walk_stop_on_steps_enabled INTEGER NOT NULL DEFAULT 1`
  (Default Pflicht bei NOT NULL; 1 = AN)
- **Setting:** `AutomationSettings.walkStopOnStepsEnabled` (Default `true`)
- **UI:** Neuer Toggle „Stopp beim Gehen" im Bewegungs-Block der
  Trigger-Settings, mit demselben Permission-Gate wie Autofahren/Walking/
  Radfahren. Ohne `ACTIVITY_RECOGNITION` leitet der Toggle in den
  Permission-Dialog (`pendingTrigger = "step_walk_stop"`) — kein Silent-Fail
- **Strings:** DE + EN

---

## 3. Verifikation

### Tests (Unit, JVM)

**Neu: `StepWalkStopDetectorTest`** (19 Tests) — alle 4 Regeln:
Geh-Kette, Fahrzeug-Vetos (Herzschlag, direkter/abgeleiteter Speed,
Accuracy-Filter, Alter), Ratengrenze (Vibration vs. Gehen vs. Sprint),
Echo-Schutz, Reset, Diagnose.

**Neu: `StepWalkStopWiringRegressionTest`** (15 Tests) — Struktur-Scans der
Android-gebundenen Pfade: Sensor-Verdrahtung (Reihenfolge!), Stop-Worker,
Gates, Permission, Session-Grenzen, Watchdog-Latenz-Fix, UI, DB-Migration.

Ergebnis: **748 Tests, 0 Failures, 0 Errors** (67 Testklassen) — `:app:testDebugUnitTest` BUILD SUCCESSFUL.

### Ehrliche Grenzen

- **„Built + code-verified" ≠ „device-confirmed".** Die Sensor-Events und
  das reale Ausstiegs-Timing können nur am Gerät bestätigt werden.
- Kein angeschlossenes Gerät/Emulator in dieser Umgebung (`connectedDebugAndroidTest`
  blockiert) — konsistent mit den vorherigen Meilensteinen.

### Geräte-Verifikation (Logcat-Tags)

```
adb logcat -s DriveDetectionSvc:V      # "M18.133: Gehen erkannt (...) -> sofortiger Fahrt-Stopp"
adb logcat -s DriveStopWorker:V        # Stop-Paket
adb logcat -s DriveWatchdogWorker:V    # "M18.133: ... MIT Schritten ... -> Gehen erkannt"
adb logcat -s ArContinuousSamples:V    # AR-Pfad (M18.127) als zweiter Weg
```

Test: (a) Auto fahren → aussteigen → wenige Schritte → Session endet
innerhalb von Sekunden (nicht Minuten). (b) Fahrt mit Ampelstopps →
GEIN Stop (Herzschlag-Veto). (c) Motorrad-Fahrt → kein Stop durch
Vibrations-Fehlzählung. (d) Ohne ACTIVITY_RECOGNITION → Toggle zeigt
„Berechtigung fehlt", kein stiller Ausfall.

---

## 4. Berührte Dateien

| Datei | Art |
|---|---|
| `automation/activityrecognition/StepWalkStopDetector.kt` | **neu** |
| `automation/activityrecognition/ActivityRecognitionWorker.kt` | Bridge-API + Setting-Cache |
| `automation/activityrecognition/DriveDetectionService.kt` | Sensor-Verdrahtung + Gates |
| `automation/activityrecognition/DriveWorkers.kt` | Watchdog-Latenz-Fix |
| `data/model/AutomationSettings.kt` | neues Feld |
| `data/db/AppDatabase.kt` | v43 + MIGRATION_42_43 |
| `di/DatabaseModule.kt` | Migration registriert |
| `ui/screens/settings/TriggerSettingsScreen.kt` | Toggle + VM-Setter |
| `res/values/strings_settings_automation.xml` (+en) | Strings |
| `app/src/test/.../StepWalkStopDetectorTest.kt` | **neu** |
| `app/src/test/.../StepWalkStopWiringRegressionTest.kt` | **neu** |

## 5. Nicht Bestandteil

- Keine Änderung an `DRIVE_WATCHDOG_NO_SIGNAL_MS` (bleibt 5 Min, User-Vorgabe)
- Kein Eingriff in die Start-Erkennung (funktioniert laut User „sehr schnell")
- Keine neue Berechtigung (nutzt das bestehende `ACTIVITY_RECOGNITION`)
- Kein 24/7-Sensor-Stream (der Step-Detector läuft ohnehin im TRACK-Fenster;
  Zusatzkosten = 0)
- Die AR-basierte Geh-Erkennung (M18.127) bleibt unverändert als zweiter Pfad

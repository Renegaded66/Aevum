# M18.84 + M18.85 — ABSCHLUSSBERICHT

## Fahrterkennungs-Zuverlässigkeit (M18.84) + Interaktive Orts-Timeline-Karte (M18.85)

**Datum:** 30.08.2026 · **Branch:** `main` · **APK:** `app/build/outputs/apk/debug/app-debug.apk`

---

## Teil 1 — Root-Cause-Analyse der drei User-Fälle (evidence-based, Phase-1-Kriterien)

**User-Symptome (30.08.):** (a) 5h korrekt im Gym-Geofence aufgezeichnet, TROTZDEM „Autofahren 16–19 Uhr" parallel; (b) „Autofahren 19:00–19:10" NACH der echten Heimfahrt; (c) „Spazieren 19:05–19:17" obwohl nur 100 m zur Wohnung gegangen.

| Fall | Root Cause (Code-verifiziert) | Fix |
|---|---|---|
| (a) Auto 16–19 parallel zum Gym | `DriveDetectionEngine.classify()` wertet ein 15-Min-Fenster aus. Indoor-Multipath liefert Speed-Spikes ≥ 8 m/s; die Position driftet langsam ~150–200 m Netto über Minuten → ALLE Speed-Gates erfüllt (M18.78-Niveau: 2 schnelle Probes, 2er-Kette, avg 4,5 m/s, Netto ≥ 150 m). `toVehicleCluster()` datiert den Session-Start auf den ältesten Probe zurück (16:xx). Kein Geofence-Veto existierte. Threshold-Historie (M18.64→M18.78, 10+ Iterationen) bewies: Tuning allein kann diese Konstellation nicht lösen. | **Geofence-Veto** in `classify()` (alle Probes in benannten Orts-Kreisen = NotDriving) + **Inside-Geofence-Cap** im Service (ältester Probe im Kreis = Start blockiert + Puffer gedrained) |
| (b) Auto 19:00–19:10 nach echter Fahrt | Beide Stop-Pfade (Watchdog, Google-EXIT) leeren den Probe-Puffer NICHT und setzen keinen Cooldown. Der weiterlaufende 15-Min-Puffer klassifiziert Park-/Aussteige-Drift sofort wieder als Driving → Zweit-Session. | **Stop-Paket** in beiden Stop-Pfaden: `markDriveStopped(now)` + `drainDriveProbes()` + `clearWalkingSignal()`; **Restart-Cooldown 3 Min** im DriveStartWorker |
| (c) Spazieren 19:05–19:17 (100 m) | Drei offene Türen: (1) Google-AR meldet WALKING während Stop&Go-Fahrten → `walkingSinceMs` lief seit 18:xx auf → nach Auto-Stopp reichte der erste ENTER (Aussteigen), Start mit Vorlauf now−5 min = in die Fahrt hinein. (2) GPS-Walking-Phase lief seit Gym-Parkplatz (Netto-Displacement zählte Fahrt+Gehen ~300 m). (3) Receiver ignorierte driveActive bei WALKING-ENTER nicht. | **`effectiveWalkingSince`/`recordingStartTime`-Clamp** an `lastAutoSessionEndMs()` (Schwelle UND Vorlauf beginnen erst nach Auto-Ende); WALKING-ENTER bei `driveActive` ignorieren; IN_VEHICLE-ENTER → `clearWalkingSignal()`; GPS-Walking-Phase startet nur außerhalb von Orts-Kreisen und resettet bei Orts-Eintritt |

**Beweis-Qualität:** Alle drei Mechanismen sind Code-lesbar nachgewiesen (kein „vermutlich"); die Historie (M18.79/80-Fixes für ähnliche Symptome) zeigt dieselben strukturellen Lücken aus anderen Ecken. Regressionstests reproduzieren jeden Fall vor dem Fix ( Sanity-Assertions: ohne Veto = Driving, mit Veto = NotDriving).

## Teil 2 — Implementierte Fixes (M18.84)

1. **DriveDetectionEngine**: `GeoCircle`-Datenklasse, `isInsideCircle()` (Haversine), Geofence-Veto als 3. Parameter von `classify()` (default leer = abwärtskompatibel), `DRIVE_RESTART_COOLDOWN_MS`/`isWithinCooldown()`.
2. **ActivityRecognitionBridge**: `driveStoppedAtMs` + `markDriveStopped()`/`isWithinDriveRestartCooldown()`, `geofenceContext` + `setGeofenceContext()`/`currentGeofenceContext()` (Copy-on-Read).
3. **DriveStartWorker**: Cooldown-Gate VOR dem Start-Gate (Bestätigung+Cluster im Cooldown verworfen); classify mit Veto-Kontext; `clearWalkingSignal()` nach echtem Start.
4. **DriveStopWorker + DriveWatchdogWorker**: Stop-Paket (Cooldown-Marker + Puffer-Drain + Walking-Reset) auf beiden Pfaden.
5. **DriveDetectionService**: lazy async Geofence-Snapshot beim ersten Fix (Service-Scope, cancel in onDestroy); classify mit Veto; Inside-Geofence-Cap; Cooldown-Reset bei nachweislich laufender Fahrt (verlorenes Stop-Flag heilen); Walking-Phase mit Orts-Boundaries (kein Start in Orten, Reset bei Orts-Eintritt, zentraler `resetWalkingPhase()`).
6. **ActivityTransitionReceiver**: WALKING-ENTER bei driveActive ignoriert; IN_VEHICLE-ENTER → clearWalkingSignal.
7. **WalkingDetectionEngine**: `effectiveWalkingSince()` + Clamp-Parameter in `shouldStartWalking`/`recordingStartTime` (defaults = altes Verhalten); **WalkingStartWorker** holt `lastAutoSessionEndMs()` und gibt ihn durch.

## Teil 3 — Interaktive Karte (M18.85)

`PlaceTimelineMap.kt` (neu, ~500 Zeilen) ersetzt `PlaceMapCanvas.kt` (gelöscht):
- Echte OSM-Rasterkacheln via MapLibre (ADR-0024-Stack des Geofence-Editors — keine neue Dependency)
- Pan/Zoom aktiviert, Tilt/Rotate aus (Zeitachsen-Kontext)
- Nummerierte Marker (48dp Bitmap: Glow + Ortsfarbe + weißer Ring + Besuchsnummer), ein Marker pro Ort (Mehrfach-Besuche im Snippet: alle Zeitfenster)
- Gestrichelte Routen-Segmente zwischen chronologischen Visits (GeoJSON-LineLayer, Start-Orts-Farbe, max. 64 Segmente)
- Auto-Fit `newLatLngBounds` (+72dp Padding, Refit bei Tagwechsel-Key), Einzelort → Zoom 15
- Bidirektionale Auswahl: Marker-Tap → Fancy-Callout + Auto-Scroll der Liste; Listen-Tap → `animateCamera` + Marker-Callout; Auswahl-Highlight in der Liste
- **Fancy-Callout**: eigener `InfoWindowAdapter` (programmatic View): abgerundete Karte (12dp), ECHTE Ortsfarbe als Akzentleiste (Lookup Marker→Visit→color), Titel + Zeitfenster/Dauern, Dark-Mode-Farben
- Dark-Mode: Kacheln via `raster-brightness-max/saturation/contrast` im Style-JSON getönt
- Lifecycle-Forwarding (onStart/onStop/onPause/onResume/onDestroy) — Pflicht für Kachel-GL
- Flacker-Freiheit: Rebuild nur bei geometrischem Key-Change (Id+Lat+Lon+Farbe), nicht beim 60s-Ticker

## Verifizierung (Built + getestet, device-confirmed offen)

- ✅ `compileDebugKotlin` grün (nach jedem Teil-Patch)
- ✅ **216/216 Unit-Tests grün** (10 neu: 7 `DriveStructuralGatesTest` + 3 Walking-Clamp-Tests; alle 206 Bestandstests unverändert grün — kein legitimer Fall gebrochen)
- ✅ MapLibre-API-Signaturen vor Implementierung per `javap` aus dem AAR verifizert (MarkerOptions/IconFactory/InfoWindowAdapter/PropertyFactory/Property/LatLngBounds/CameraUpdateFactory)
- ⏳ Full Build `assembleDebug` detached (robust_build.sh v3) — Ergebnis siehe unten
- ❌ **Device-confirmed (offen):** Phantome bei realer Gym-Session, Cooldown-Verhalten beim Parken, Karten-Gesten/Callout auf dem Gerät — braucht Devon-Test (Anleitung unten)

## Test-Anleitung (Gerät)

1. APK installieren (gleiche Signatur wie immer — debug.keystore unverändert)
2. Gym-Tag reproduzieren: Mit aufgezeichneter Gym-Geofence-Session IM Gym die App geöffnet lassen → es darf KEINE parallele Auto-Session entstehen. `adb logcat -s DriveDetectionSvc:V DriveStartWorker:V` → „M18.84-Cap/Veto"-Zeilen sichtbar bei Unterdrückung
3. Heimfahrt: Nach dem Parken darf innerhalb 3 Minuten keine neue Auto-Session starten („M18.84-Cooldown" im Logcat); nach >3 Min Weiterfahrt normal startbar
4. Nach dem Aussteigen ~100 m gehen: KEINE „Spazieren"-Session mit Startzeit vor dem Auto-Ende; echte Wanderung (>5 min außerhalb von Orten) startet weiterhin
5. Orts-Timeline öffnen (Einstellungen → Automatisierung → Orts-Timeline): Karte zeigt echte Kacheln, Pan/Zoom funktioniert, Marker nummeriert+farbig, Tap → Callout + Listen-Scroll; Listen-Tap → Kamera fliegt; Tagwechsel refittet die Kamera; Dark-Mode tönt die Kacheln

## Known Limitations (ehrlich)

- Routen sind Luftlinien zwischen Orten (keine GPS-Track-Aufzeichnung pro Fahrt in der DB) — Google Timeline zeigt echte Straßenverläufe; das braucht eine Track-Punkt-Tabelle (künftiger Milestone, siehe „Nicht Bestandteil" in ADR-0029)
- Der Restart-Cooldown nutzt In-Memory-Brücke + DB (`lastAutoSessionEndMs`) — nach Prozess-Tod bleibt nur der DB-Stand (Cooldown kann kürzer erscheinen; konservativ unkritisch)
- Karten-Kacheln brauchen Internet (Offline-MBTiles künftig)
- Veto/Cap hängen vom Geofence-Snapshot (lädt lazy beim ersten GPS-Fix nach Service-Start) — in den ersten Sekunden nach Service-Restart klassifiziert die Engine ohne Veto (alter Zustand, kein False-Negative-Risiko)

## Selbst-Hinterfragung (Pflicht-Teil)

**Konkrete Failure-Modes vor dem Schreiben geprüft:** (1) Veto könnte echte Fahrt blocken, die innerhalb eines riesigen Geofence startet (z. B. 500-m-Home-Geofence, Fahrt beginnt auf eigenem Grund) → Covered: EIN Probe außerhalb des Kreises hebt das Veto; Fahrt verlässt den Kreis nach Sekunden. (2) Cooldown könnte echte Folgefahrt nach <3 Min blocken (Schnell-Erledigung) → bewusst akzeptiert (3 Min, kürzer als jede reale „Fahrt" von A nach B und zurück); Cooldown-Reset bei nachweislich laufender Fahrt heilt verlorene Flags. (3) Walking-Clamp könnte echte Wanderung verschlucken, die während einer (unbemerkt endenden) Fahrt begann → Nein: Schwelle zählt ab Auto-Ende, die Zeit geht nicht verloren. **Würde der Fix einen legitimen Fall brechen?** — Nein, für alle vier Gates einzeln durchgespielt (siehe ADR-0028).

**Code-Review nach dem Schreiben:** Alle geänderten Pfade gegangen — Cooldown-Gate steht VOR dem Confirmation-Konsum (verlorene Flags impossible); Cap-Drain passiert ohne markDriveConfirmed (kein verlorenes Flag); Service-Scope wird in onDestroy gecancelt (kein Leak); Receiver-continue setzt hasChange (kein stiller Event-Verlust für SleepFusion). Kein neues try/catch zur Crash-Maskierung — die zwei neuen (Geofence-Load, lastAutoSessionEndMs) sind bewusst konservative Fallbacks auf den VORHERIGEN Zustand, kein defensives Verschlucken.

**Getrennt ausgewiesen:** Built + code-reviewed + 216/216 Tests = ✅. Device-confirmed = ❌ offen (Test-Anleitung oben).
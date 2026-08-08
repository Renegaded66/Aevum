# PROJECT_STATE

> Stand: 2026-07-21T18:00:00Z
> Produktname: **Aevum**
> Paketname: `com.d_drostes_apps.aevum`
> Status: **M7.1 — Core UX & Reliability abgeschlossen**.

## Aktueller Entwicklungsstand

- [x] M2–M7: Alle vorherigen Meilensteine abgeschlossen
- [x] M7.1 Core UX & Reliability

## M7.1 — Core UX & Reliability

**Status:** **Abgeschlossen.**

### Product Owner Review

Vor M8 (Health Connect) müssen alle bekannten UX- und Zuverlässigkeitsprobleme beseitigt sein. Fünf kritische Bugs blockierten die tägliche Nutzung.

### Behobene Bugs

| # | Bug | Fix |
|---|---|---|
| 1 | **Goal Editor: Alles doppelt** — GoalForm + 4 separate Sections zeigten Zeitraum/Zieltyp/Wert/Aktivitätstyp jeweils zweimal | Screen komplett neu: Nur noch eine Section pro Feld. Keine Duplikate. |
| 2 | **Goal Editor: Activity Type nicht auswählbar** — setActivityType setzte keinen Namen, Button blieb immer "Aktivitätstyp auswählen" | setActivityType(id, name) — Name wird gespeichert und im Button angezeigt |
| 3 | **Goal Editor: Speichern navigiert nicht zurück** — GoalEditorUiState.saved war immer false | saved wird jetzt aus form.saved gemappt; LaunchedEffect navigiert korrekt |
| 4 | **Geofence Map: Marker springt** — addOnCameraMoveListener feuerte bei jedem Frame | Komplett neu: Crosshair fixiert in Bildschirmmitte, addOnCameraIdleListener (nur bei Stop), Google-Maps-ähnliches Bediengefühl |
| 5 | **Geofence: Android 15 Kompatibilität** — targetSdk 35 braucht Foreground Service für Background-Geofencing | GeofenceForegroundService (location type), automatisch gestartet vor Geofence-Registrierung |

### Neue Komponenten

- **GeofenceForegroundService** — Minimaler Foreground Service für Android 15+. Leise Notification, `FOREGROUND_SERVICE_TYPE_LOCATION`.
- **GeofenceDebugLogger** — In-Memory-Log (200 Einträge) für die gesamte Pipeline: Receiver → Processor → Raw → Detection → Trigger → Candidate.
- **Debug-Log im Debug-Screen** — Letzte 20 Einträge mit Zeitstempeln live sichtbar.

### Verifikation

```bash
git diff --check  # OK
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug \
  --no-daemon --console=plain --max-workers=1 \
  -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis: **BUILD SUCCESSFUL** (Tests, AndroidTest-Kompilierung, Lint, APK).

APK: `app/build/outputs/apk/debug/app-debug.apk` — 80.9 MB, v2 signiert, minSdk 29, targetSdk 35.

### Bekannte Einschränkungen

- Release-Signing noch nicht eingerichtet
- Geofence-Ereignisse nur auf realem Gerät mit Google Play Services testbar
- Connected Android Tests blockiert (kein Gerät/Emulator)
- Activity Recognition, Health Connect, UsageStats folgen in M8
- `GeofenceDebugLogger` ist in-memory — überlebt keinen Prozess-Neustart. Persistenz wäre M7.2.

### Empfehlung für M7.2 / M8

**M7.2 (optional, klein):** Persistenter Debug-Log via Room oder DataStore. Erlaubt Post-Mortem-Analyse nach App-Neustart.

**M8: Health Connect / Sleep & UsageStats.** Die Pipeline (Trigger → Candidate → Review) ist jetzt stabil und debugbar. Neue Datenquellen können sicher integriert werden.

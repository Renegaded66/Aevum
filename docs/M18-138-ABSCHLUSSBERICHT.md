# M18.138 — Bildschirm-Aufzeichnung vs. Bildschirmzeit

**Datum:** 2026-10-01/02
**Commit:** ca2ff54 (Renegaded66/Aevum, main)
**APK:** app/build/outputs/apk/debug/app-debug.apk (1.0.19-debug, kanonischer Key verifiziert)

## Gemeldeter Fehler

Digital-Balance-Tab zeigte 2,5 h Bildschirmzeit, das Dashboard über 5 h.
Zusätzlich: Aufzeichnungen liefen weiter, wenn das Handy weggelegt wurde.

## Ursachenkette (verifiziert im Code)

1. **Stop-Verzug (M18.71):** Die Aufzeichnung wurde erst 30 s nach
   Screen-OFF beendet. Ein Block, der über das Weglegen hinauslief,
   sammelte Zeit, in der der Bildschirm bereits aus war.
2. **Zwei parallele Digital-Zeiten:** Die SCREEN_AUTO-Sessions flossen als
   echte Zeit in **alle** Statistiken ein — Dashboard (Erfasst/Qualität),
   Insights, Weekly Review, Todos, Ziele, Life-View. Der Digital-Balance-Tab
   liest dagegen die gemessene Zeit aus UsageStatsManager. Beide Zahlen
   standen nebeneinander, ohne dass eine die andere kannte.

Ergebnis: 5 h Aufzeichnung vs. 2,5 h Messung. Keine der beiden war
„falsch berechnet" — sie waren zwei verschiedene Dinge mit demselben Namen.

## Fix

### 1. Aufzeichnung stoppt sofort bei Screen-OFF
- `ScreenRecordingEngine.SCREEN_OFF_STOP_DELAY_MS = 0` (M18.71-Karenz von
  30 s aufgehoben).
- `ScreenEventReceiver`: bei `OFF` wird die laufende SCREEN_AUTO-Session
  **direkt** gestoppt (inkl. LiveActivityService.stop + Kalender-Resume).
- `ScreenOffStopWorker` bleibt als Sicherheitsnetz, feuert jetzt mit
  Delay 0.

### 2. Statistiken = ausschließlich Digital-Balance-Zeit
Neue Kapselung in `domain/digital/`:

| Datei | Rolle |
|---|---|
| `ScreenStatisticsPolicy.kt` | Rollen-Modell (Aufzeichnung vs. Messung), `forStatistics()`, Balance-Beitrag je Tag mit 24h-Clamp |
| `StatisticsSessionSource.kt` | Der EINE Merge für alle Statistik-Sichten |
| `DigitalBalanceSource.kt` | Flow-Quelle (60 s-Takt) über `ScreenTimeProvider` |
| `ScreenTimeProvider.kt` | Interface — entkoppelt die Sichten von UsageStatsManager |

Verdrahtet in: Dashboard (Headline, Trend, Qualität, Duration-Todos),
Insights (inkl. Vergleichsperiode), Weekly Review, Todos, Ziele
(`GoalProgressAnalytics`), Life-View.

`AppUsageAggregator.dailyTotalsForRange(start, end)` liefert die
Bildschirmzeit je Tag für beliebige Zeiträume (Mitternachts-Clipping wie
`dailyTotals`), damit Heatmap und Tagesdurchschnitte korrekt zuordnen.

### 3. Vorlaufzeit wirkt nur auf die Timeline
Die Einstellung (x Minuten) verschiebt weiterhin nur, wann die Aufzeichnung
beginnt — Fragmente aus 10-Sekunden-Nutzungen entstehen nicht. Auf
Kennzahlen hat sie keinen Einfluss mehr.

## Was unverändert bleibt

- Die Aufzeichnung ist weiterhin in der **Timeline** sichtbar (Rohdaten).
- Manuelle Sessions, Garmin-Importe, Kalender-Sessions: unverändert.
- Der Balance-Beitrag existiert **nur im Speicher** und wird nie
  persistiert — er darf nie an Timeline/Tagesfluss/Kalender geraten.

## Tests

40 neue/angepasste Tests, volle Suite **925 Tests grün** (0 failures):

- `ScreenStatisticsPolicyTest` (15): Quellen-Erkennung, Trennung,
  Balance-Beiträge, 24h-Clamp, keine Session bei 0/negativ,
  „ohne Messung keine erfundene Digitalzeit".
- `ScreenTimeSplitScenarioTest` (6): der gemeldete Fall 5,5 h Aufzeichnung
  → 2,5 h Statistik; mehrere Tage; Vorlaufzeit ändert die Statistik nicht;
  Timeline behält die Aufzeichnung.
- `ScreenRecordingEngineTest` (19): Sofort-Stop statt 30 s-Karenz.
- `InsightsWiringIntegrationTest`: Balance-Quelle im Test-Modus verdrahtet.

Build: `BUILD SUCCESSFUL`, APK-Signatur = kanonischer Key.

## Nebenbefund (Umgebung)

`/etc/passwd` dieser Build-Maschine ist truncated → `user.home = "?"`.
Robolectric bricht dann ab mit „Couldn't create lock file
?/.robolectric-download-lock" — **alle 40 Robolectric-Tests waren nicht
lauffähig** (auch auf dem Baseline-Stand, unabhängig von diesem Fix).
`testOptions.unitTests.all { systemProperty("user.home", ...) }` in
`app/build.gradle.kts` behebt das. Ein 1,7-GB-`?`-Verzeichnis im
Projekt-Root wurde entfernt.

## Nicht Bestandteil

- Keine Änderung an der Aufzeichnungs-Schwelle selbst (Slider 0..10,
  rechts = deaktiviert).
- Keine Migration/Persistierung der Balance-Beiträge.
- Keine Live-Tests am Gerät (bewusst, laut Auftrag).

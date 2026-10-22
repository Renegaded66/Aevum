# Bugs / Known Issues

Siehe `docs/KNOWN_ISSUES.md`.

Hauptrisiken:

- Automatische Erkennung kann falsch liegen → Candidate/Review/Edit UI.
- Background Location sensibel → optional und erklärend.
- UsageStats Sonderzugriff → App bleibt ohne nutzbar.
- Lebensdaten sensibel → offline-only, Backup/Export bewusst behandeln.

- M23 behoben: Fahrt übernahm bis zu 5 Minuten Gehzeit vor dem Losfahren
  (Startanker nahm den ältesten Bewegungs-Probe aus dem GPS-Puffer). Fix:
  Fahrzeug-Anker ≥ 3,0 m/s, zwei Probes in 90 s; Fallback jüngster Probe.
- M23 behoben: App-Aufzeichnung startete nach dem Ende einer fremden Session
  (Fahrt/Sport/manuell) nicht, solange die getrackte App offen blieb.

- M24 behoben: Digital Balance – die große Zahl oben, Unlocks und der
  Stundenverlauf blieben beim Klick auf einen Tagesbalken auf „heute", weil
  `buildState` immer `aggregator.todayDetail()` rief. Fix: `detailForDay(date)`
  + `totalMs` statt `todayTotalMs`.

- M25 behoben: Nach dem Anlegen eines Geofences aus „Ort (unbenannt)" blieb
  die historische Unknown-Place-Zeile unbenannt. Fix: `PlaceTimelineEngine`
  matcht Unknown-Places per Koordinaten gegen Geofences und übernimmt
  Name/Icon/Farbe (kein DB-Backfill).

- M28 behoben: Auto-Fahrt beendete/kürzte eine laufende Aufzeichnung, weil
  `LiveActivityManager.start` die bestehende Live-Session trimmt. Fix:
  `PendingDrivePolicy.shouldDefer` + `markDrivePending`, nachgeholt von
  `PendingDriveStarter` (Start am Block-Ende, Evidenz ≤6 min, max 4 h).
- M28 behoben: Nutzungszugriff galt beim ersten Öffnen als „nicht erteilt"
  (`queryUsageStats` in 1-s-Fenster ohne Event). Fix: AppOps-Check in
  `UsageStatsCollector.hasPermission`.

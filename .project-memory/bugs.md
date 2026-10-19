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

# Roadmap

Aktueller Stand: M0–M19 abgeschlossen. **M19.3 hat das Projektmanagement
(M19.1/M19.2) wieder komplett entfernt** — die App hat es nicht mehr.
Maßgeblich ist `docs/ROADMAP.md` — diese Datei ist nur eine Kurzfassung.

Abgeschlossener Meilenstein: M2 Android-Projektgrundlage für Aevum.

Nächster Meilenstein: M3 Design System & Dashboard Skeleton.

Langfristige Roadmap:

- [x] M2 Android-Projektgrundlage
- [ ] M3 Design System & Dashboard Skeleton
- [ ] M4 Core Datenmodell & Room fachlich stabilisieren
- [ ] M5 Timeline & manuelle Sessions
- [ ] M6 automatische Erkennung v1
- [ ] M7 Health Connect / Sleep & UsageStats
- [ ] M8 Goals, Habits, Streaks
- [ ] M9 Bucket List & Life Progress
- [ ] M10 Polish/Performance/Release

Siehe `docs/ROADMAP.md`.

- [x] M19.1 Projektmanagement (Projekt/Unterprojekt/Kategorie/Aufgabe/Schritte,
      Tagesplan, Glas mit Juwelen, Feuerwerk, Fortschrittsbalken)
- [x] M19.2 Eisblau/Feuerrot-Theme + Schimmer; PM-Umbau (nur Projekte+Aufgaben,
      To-do-Listen mit Drag&Drop/Inline-Eingabe/Löschen, Dashboard nur heute,
      schmales Glas, generische Vorlage); Digital-Balance-Revival
- [x] M19.3 Projektmanagement-Rückbau (Dashboard-Knopf, Routen, Screens,
      DB v46 = DROP pm_node); App-Aufzeichnung zuverlässig (2-s-Poll,
      Nachprüfen + Nachfassen, Übergabe ≤5 s); Feuerrot nachgeschärft
      (#FF3B2E/#D32614)
- [x] PC-Tracker 1.4.0 + App 1.0.34: Live-Kanal (laufende Handy-Session im
      PC-Dashboard mit Stop/Pause/Fortsetzen, `POST /api/live`, Pull-Modell
      bleibt) + In-App-Update der App aus öffentlichen GitHub-Releases
      (ohne Token) + Sofort-Sync beim App-Öffnen (Gate 2 min/60 s,
      In-Process statt WorkManager) + Desktop-Update-Hinweis
      „Einstellungen bleiben erhalten“ (%APPDATA%-Daten, robocopy ohne
      /PURGE). Bericht `docs/PC-TRACKER-1.4-ABSCHLUSSBERICHT.md`,
      Protokoll §5e.

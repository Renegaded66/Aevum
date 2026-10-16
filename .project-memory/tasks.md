# Tasks

- [x] `/docs` erstellen
- [x] Skill-/Technologieanalyse dokumentieren
- [x] Architekturplanung dokumentieren
- [x] Produktdefinition Aevum einarbeiten
- [x] Appname/Paketname festlegen
- [x] Offline-first Entscheidung dokumentieren
- [x] M2 Android-Projektgrundlage erstellen
- [x] Gradle/Kotlin/Compose/Hilt/Room/DataStore/Navigation/Testsetup einrichten
- [x] leere App-Shell bauen und testen
- [x] Debug APK bauen und verifizieren
- [ ] M3 Design System & Dashboard Skeleton starten
- [x] PC-Tracker: Windows-App (`desktop/`, Avalonia) + Aevum-Client (Discovery, Kopplung, Sync, Import) + Protokoll `docs/PC-TRACKER-PROTOCOL.md`
- [x] PC-Tracker 1.1 nach Praxis-Test: Regel-Activity-Bug (ComboBox-Selection), Edit, Hintergrund-Modus (while_running), Aktivitäts-Spiegel (`POST /api/activities`), Sync-Zeile + Verbunden-Status, laufende Programme + manuell, EN-Default + Flaggen-Umschalter, Dashboard-Live-Animation; App 1.0.25 (Activity-Push + Install-Anleitung + Kopplungs-Layout); GitHub-Release `pc-tracker-1.1.0` verifiziert (Commit 16ffebe). APK-Auslieferung über /srv auf Wunsch gestrichen (APK liegt im Build-Output).
- [x] PC-Tracker 1.2: Auto-Update via öffentliche GitHub-Releases (ohne Tokens; Self-Update-Batch, Badge, Einstellungen-Karte) + Allgemeine Aufzeichnung (Toggle + Aevum-Activity-Dropdown, Idle 60 s ODER Audio, Regeln haben Vorrang, `__general__`) + App 1.0.26 (stiller WorkManager-Hintergrund-Sync 30 min + Schalter); 92/92 Windows-Tests + 1066/1066 App-Tests. Ausgeliefert: Release `pc-tracker-1.2.0` (ZIP 57.473.085 B), Commit `cbf9a3c`; Live-Beweis Update-Checker (findet 1.2.0 aus 1.1.0; „aktuell" aus 1.2.0). APK 1.0.26 (versionCode 27) im Build-Output.
- [x] M18.147 (App 1.0.27): Neues adaptives App-Icon (User-Artwork, `docs/store-assets/icon-512.png` für die Play-Console, Generator `docs/tools/gen_app_icon.py`) + App-Aufzeichnung flüssig (Scan raus aus dem Main-Thread → `InstalledAppsProvider` chunkweise, Interaktionen optimistisch via `AppTrackingOptimism`; 18 neue Tests) + PC-Sync zuverlässiger (15 min UNMETERED + gedrosselter stiller Kick bei App-Öffnen/Prozess-Start, `PcSyncGate`). 1084/1084 App-Tests; Debug-APK kanonisch signiert verifiziert, Release-APK 1.0.27 (versionCode 28, Aevum Upload Key) im Build-Output; Commit `083b378`, Bericht `docs/M18-147-ABSCHLUSSBERICHT.md`. Offen für den Publisher: 512-Icon in der Play-Console hochladen.
- [x] M18.149 (App 1.0.28, Icon-Fix): Artwork auf 76 % im 108er-Raster, zentriert auf gemittelter Randfarbe (14,15,20) — Devons Launcher-Maske schnitt den Ring der zehn Bereichs-Icons an (~46 dp Inhalt vs. ~41,6 dp Zuschnitt); neu ~35 dp Innenkante, Sichtprüfung mit simulierter Geräte-Maske (vorher halbe Icons → nachher komplett). Icon-only (Tests unverändert 1084/1084); Debug-APK kanonisch verifiziert, Release-APK 1.0.28 (versionCode 29) im Build-Output; Commit `b0e2f03`; Generator `docs/tools/gen_app_icon.py` (`ART_SCALE=0.76`). Merksatz: Content nie randlos im adaptiven Raster (aggressive Launcher-Masken bis ~77 %).

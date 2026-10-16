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
- [x] M18.150 (App 1.0.29, Play-Console-Auflage): `android.r8.optimizedResourceShrinking=true` (war seit M18.136 false) — Console beanstandete „Optimierte Entfernung von Ressourcen ist nicht aktiviert" im Release „Google play policy fix". Vorab geprüft: keine getIdentifier()-Zugriffe. Nachweis: Ressourcen-Einträge Release-APK 1950 → 1779. Neu: Build liefert auch **AAB** (`bundleRelease`, Upload-Key) für die Console. versionCode 30; Release-APK (51,97 MB) + AAB (24,2 MB) im Build-Output; Commit `5fe7f04`.
- [x] M18.151 (App 1.0.30, App-Aufzeichnung × Digital): Übergabe-Regeln der Smartphone-Aufzeichnungen — getrackte App übernimmt SOFORT von "Digital" (SCREEN_AUTO) via zentraler `AppTrackingTakeoverPolicy` (pure, 11 Tests) + Race-sicherem `LiveActivityManager.stopIfLive`; App-Wechsel A→B direkt; Screen-OFF beendet die App-Session sofort; nach App-Ende plant der Service Digital mit der Vorlaufzeit neu an (`ScreenRecordingWorker.schedule`, ein zentraler Einplan-Punkt, den auch der ScreenEventReceiver nutzt); Digital-Worker startet nie über einer getrackten App (ForegroundAppProbe + Poll-Schnappschuss `recentPollSnapshot`, Nudge an den Service); jede App-Session startet jetzt die Live-Notification. Rangfolge strikt: Geofence/Fahrt/Sport/manuell/Kalender nie angetastet. 30 neue Tests (1114/1114 grün, 0 Fehler); Debug-APK 123,4 MB kanonisch signiert, Release-APK 51,97 MB (Upload Key) + AAB 24,2 MB (versionCode 31) im Build-Output; Commit `2107384`; Nachtrag (verwaiste App-Tracking-Sessions zaehlen per `sourceType == APP_TRACKING` zur Smartphone-Familie — Wechsel/Stop auch nach Service-Kill) Commit `fb6baa9`, finaler Build `aevum-m18151b` (4m06s, Exit 0, identische Version); Bericht `docs/M18-151-ABSCHLUSSBERICHT.md`.

- [x] M19.1 (App 1.0.31): Projektmanagement — Baum (`pm_node`, DB v44),
      Dashboard-Einstieg mit Zähler, Sheet von unten, Tages-Karussell mit
      Swipe, Glas mit Juwelen + Feuerwerk (Feier-Gate), Backlog,
      Tag-planen-Sheet, Sammel-Eingabe, Detailseite, Editor.
      Spec `docs/M19-1-PROJEKTMANAGEMENT.md`, Bericht `docs/M19-1-ABSCHLUSSBERICHT.md`.

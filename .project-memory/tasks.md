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
- [x] M19.2 (App 1.0.32): Theme Eisblau/Feuerrot + Schimmer; PM-Umbau auf
      nur Projekte+Aufgaben (To-do-Listen mit Drag&Drop/Inline-Eingabe/
      Löschen, Dashboard nur heute, schmales Glas, generische Vorlage,
      DB v45 reine Daten-Migration); Digital-Balance-Revival (Boot/
      PACKAGE_REPLACED + 15-min-Watchdog + Screen-On-Nudge). Bericht
      `docs/M19-2-ABSCHLUSSBERICHT.md`.
- [x] M19.3 (App 1.0.33): Projektmanagement KOMPLETT zurückgenommen
      („Ich will das ganze PM Management doch nicht. Also alles davon.")
      — Dashboard-Knopf, Settings-Eintrag, Routen, Screens, Domäne,
      Repository, Vorlage, PM-Komponenten und Strings entfernt; DB v46
      (MIGRATION_45_46 = DROP pm_node) mit SchemaV46Test +
      PmRemovalMigrationTest. App-Aufzeichnung zuverlässig: 2-s-Poll
      statt 15 s, onRepeatedForeground + onStopRace (Übergabe ≤5 s,
      nichts anderes zeichnet parallel auf). Feuerrot nachgeschärft
      (#FF3B2E/#D32614), Schimmer auf dem Dashboard-Plus-Knopf.
      Bericht `docs/M19-3-ABSCHLUSSBERICHT.md`.
- [x] PC-Tracker 1.3.0: Aktivitäten am PC starten („activitys starten …
      entweder direkt oder mit vorlaufzeit") — Dashboard-Karte mit
      Handy-synchronisierten Aktivitäts-Chips, Sofort/+5/+15/+30 mit
      Countdown; Engine: manuelle Sessions (`__manual__`) mit Vorrang,
      blockieren Regel+General, Idle-Ausnahme, Stop-Knopf/Pause/App-Ende,
      ResetVisibilityClock gegen rückwirkende Überlappung. Live-Karte
      mit Stop + „Manuell gestartet"; Sync-Status zeigt Aktivitäten-Stand
      („der pc soll regelmäßig mit dem handy synchronisieren" — App holt
      im WLAN alle 15 Min automatisch, Architektur unverändert).
      104/104 Tests (+12 ManualSessionTests), Xvfb-Smoke-Test mit
      gefundenen+gefixten Bugs (CommandParameter, Sichtbarkeits-Vorrang).
      Bericht `docs/PC-TRACKER-1.3-ABSCHLUSSBERICHT.md`, Protokoll §5d.
- [x] PC-Tracker 1.4.0 + App 1.0.34: Live vom Handy — Desktop-Live-Karte
      (PhoneLiveRegistry: TTL 15 s stale/45 s gone, Befehls-Wiederholung bis
      Bestätigung, Resume = neuer Session-Split) + Android-Relay im
      LiveActivityService (7 s/15 s, ohne Session kein HTTP, Stop-Befehl =
      Notification-Pfad inkl. driveActive-Reset via performStopFromOutside).
      E2E unter Xvfb verifiziert (curl-Handy: pause/resume/stop alle ✔, Karte
      verschwindet bei session:null). In-App-Update App (AppUpdateChecker/
      Installer, 12 Tests, REQUEST_INSTALL_PACKAGES + FileProvider nur
      files/updates; Debug→Debug-APK, Release→Store-APK). Sofort-Sync beim
      App-Öffnen (MainActivity In-Process + Gate 10→2 min/60 s fresh).
      Desktop: s.upd_keep-Hinweis „Einstellungen bleiben erhalten“ (Daten in
      %APPDATA%\AevumPcTracker, robocopy ohne /PURGE). Tests: 1158/1158
      Android, 113/113 Desktop; ZIP 57.305.209 B; Tag pc-tracker-1.4.0.
- [x] Nachtrag (Nutzerbefund): PC-Tracker 1.4.1 — Update-INSTALLATION
      repariert: App.ShutdownForUpdate() (AllowClose + Dienst-Stop +
      Environment.Exit) statt nur desktop.Shutdown(); BuildSwapScriptLines
      wartet auf Starter-PID (tasklist-Poll, taskkill-Fallback nach 45 s),
      robocopy /MIR (alte Version wird ersetzt). 116/116 Tests. Release-ID
      415765752. Übergang 1.3.0: einmal manuell entpacken (alter Code führt
      sonst sein kaputtes Update aus). App 1.0.35 (vc36): In-App-Updater
      wieder ENTFERNT (Update-Knopf nur PC-App, Handy über Play Store);
      Sync-Features bleiben; 1148/1148 Tests.
- [x] PC-Tracker 1.5.0: Handy-Vorrang (Handy-Aufzeichnung stoppt JEDE
      PC-Session sofort; nichts startet währenddessen; Sichtbarkeit danach
      frisch; manuelle Starts abgelehnt; Quelle = Live-Kanal, Protokoll §5f)
      + General-Activity VOR dem Schalter wählbar + Verlauf-Entfernen
      (DeleteSessionById nur completed, zweistufige UI, gelöschte nicht mehr
      ausgeliefert) + deutliche Aufnahme-Anzeige (Türkis-Rahmen, RECORDING-
      Badge, Schimmer auf beiden Live-Karten). 127/127 Tests; Xvfb-E2E:
      Handy-Meldung beendete General (1,1 s) und Manual, Start blockiert,
      Löschung 8→7, Schimmer sichtbar — 1 Anzeige-Bug gefunden+gefixt.
      Release-ID 415785691, ZIP 57.309.054 B.
- [x] PC-Tracker 1.5.1 (Nutzerbefund: 1.4.1-Update kopierte nichts, startete
      wieder 1.4.1; 2x): Austausch-Mechanismus von Batch auf PowerShell
      ersetzt — echtes Warten (Get-Process, 90 s), robocopy /MIR +
      Copy-Item-Fallback, Größen-Verifikation der neuen EXE, update.log
      (jede Station), pending-update.txt + rotes Fehlerband in den
      Einstellungen bei Fehlschlag (nie mehr still). Extract als Ursache
      ausgeschlossen (.NET-8-Nachstellung: 362/362). 131/131 Tests.
      Release-ID 415812156, ZIP 57.312.239 B. WICHTIG: PS-Skript konnte auf
      Windows nicht ausgeführt werden (kein Wine/pwsh; pwsh-Download
      geblockt) — Restrisiko dokumentiert, Log+Fehlerband decken es auf.
- [x] PC-Tracker 1.5.2 (Befund: PC zeichnete jede Minute kurz digital trotz
      laufender Handy-Aufzeichnung + Wunsch große Dauer-Anzeige): Vorrang
      gab nach 45 s frei → Sendelücken erzeugten Falsch-Sessions im
      Minutentakt. Fix: PriorityWindowMs=5min für Vorrang; Mini-Artefakte
      <2 s verworfen; phone-priority.log (Übergänge mit Age). Feature:
      große tickende Timer (Monospace/türkis) auf PC-+Handy-Karte, Pause
      friert ein; „Letzte Meldung vor X". 134/134 Tests. Xvfb-E2E: 65 s
      Funkstille + General AN → 0 Sessions; Timer 2:02:13/00:07 belegt.
      Release-ID 415864490, ZIP 57.313.609 B.
      WICHTIG fürs nächste Mal: Handy-Relay effektiv oft langsamer als
      7–15 s (Standby) — PC-Politik muss Sendelücken aushalten (getan);
      phone-priority.log bei erneutem Befund zuerst prüfen.
- [x] M19.4 (App 1.0.36, vc 37): Dashboard-Hero 2.0 „Tag-Cockpit" nach
      Devons Skizze — 180°-Güte-Gauge (fester Rot→Grün-Farbraum, Marker +
      Nadel + Puls-Glow), drei versetzte Glas-Kacheln mit Schimmer und
      Zähler-Animation, Zonen-Chip, 24h-Tagesleiste mit Stundenlabels und
      gedimmter Zukunft, Aufnahme-Status-Balken (grün/amber/bereit).
      Karten-Drumherum in EINEM Sheet mit 28dp-Oberkante, das sich beim
      Scrollen über den gepinnten Hero schiebt (gemessene Hero-Höhe,
      Parallax, Peek durch die Ecken). Atmosphere auf Eisblau/Feuerrot/Gold.
      Keine DB-/Nav-Änderung. 1165/1165 Tests (0 Fehler; +17: 6 Metrik-,
      11 UI-Tests inkl. 3 Rendering-Screenshots über Robolectric
      View-Draw mit fixer Test-Uhr — captureToImage/PixelCopy existiert
      dort nicht!). Voll-Build Exit 0 (9m58s), Debug-APK kanonisch
      signiert, Release-APK + AAB im Build-Output; Screenshot-Kopien auf
      dem APK-Server. Bericht `docs/M19-4-ABSCHLUSSBERICHT.md`.
      Commit `2b6e31d` (14 Dateien, +1973/−549), gepusht auf main.
- [x] M19.5 (App 1.0.37, vc 38): Devon-Feinschliff + Maskottchen —
      Pinning (Hero exakt gegen Scroll kompensiert; Integrationstest
      scrollBy 400dp → Position ±4px konstant), Blubber-Banner in den
      Hero unter das Gauge (Headline + alter Status-Balken entfernt),
      Emoji-Zentrierung ⏸/⇄/■ (contentPadding 0 + includeFontPadding
      false; Screenshot „perfekt zentriert"), Maskottchen-Feature in
      Einstellungen (Default aus): Canvas-Wesen im Gauge (lächelt mit
      Güte, atmet/blinzelt/schaut, 3 Tipp-Reaktionen, Nachtschlaf 22–6
      außer bei Aufzeichnung, 8 Farben/3 Ohren/Bäckchen), Kacheln
      flacher (Icon neben Wert) + Gauge größer (248×148). Tests +22 →
      1187/1187 grün (0 Fehler; 6 Metrik, 19 Hero-UI inkl. Pinning-Test
      + 6 Screenshots, 7 Mascot-View inkl. 4 Screenshots, 7 MascotBehavior).
      Voll-Build Exit 0 (9m12s), Debug-APK 123.626.258 B kanonisch
      signiert (apksigner-bestätigt), Release 52.027.783 B, AAB
      24.283.749 B; 10 Screenshots als aevum-1.0.37-*.png auf dem
      APK-Server (HTTP 200). Bericht `docs/M19-5-ABSCHLUSSBERICHT.md`.
      WICHTIG fürs nächste Mal: Screenshot-Sichtprüfung bei sehr
      großen Bildern → Crop als eigene Datei (PIL) statt Region-Param;
      Pinning-Beweis = scrollBy-Integrationstest (boundsInRoot.top ±4px).
- [x] M19.6 (App 1.0.38, vc 39): Maskottchen im PAG-Vorbild (Devon:
      „Halte dich vielleicht an das maskottchen aus PAG. Das fand ich
      gut.") — Canvas-Wesen 1:1 im PAG-Stil (runder Körper ohne Umriss,
      Antenne mit leuchtender Gold-Spitze, weiße Augen mit Pupillen +
      Blick-Wanderung, Smile mit Güte-Tiefe, rosa Wangen, subtiles
      PAG-Atmen ~10,7s); Personalisierung leicht gehalten: 8 Farben
      (Vorgabe PAG-Blau #5E7CE8), Antennen-Spitze Kugel/Stern/Blitz
      (ersetzt die M19.5-Ohren; Alt-Werte validieren auf Kugel),
      Bäckchen; Reaktionen herzfrei (Sternchen-Hüpfer ★, Wackel+Funkeln
      ✦, Bäckchen-Pop mit Linien-Augen); im Dashboard 108dp (größer),
      Settings-Vorschau 120dp. Screenshot-Freeze 3,65s (Blick-Wanderung
      in Mittelstellung — sonst wirkt die Antenne „verrutscht").
      1187/1187 Tests grün, Voll-Build Exit 0 (8m41s), Debug-APK
      123.626.270 B kanonisch signiert (apksigner), Release 52.027.795 B,
      AAB 24.284.037 B; 10 Screenshots aevum-1.0.38-* auf dem APK-Server
      (HTTP 200). Bericht `docs/M19-6-ABSCHLUSSBERICHT.md`.
      Commit `2cd96e6` (18 Dateien, +670/−324), gepusht auf main.
- [x] PC-Tracker 1.6.0 + App 1.0.39 (Verifikations-Gate): Devon: „erst
      erlauben … wenn die desktop app verifiziert hat, das auf dem handy
      nichts läuft, ansonsten meldung." — Desktop fail-closed: ohne frische
      „nichts läuft"-Bestätigung (≤ 30 min, IdleVerificationWindowMs) startet
      NICHTS Neues (Regel/allgemein/manuell; ManualStartOutcome); veraltetes
      „zeichnet auf" zählt nie mehr als frei; Gate erst nach erstem
      Handy-Kontakt (PhoneKnownAtMs); laufende Sessions überleben einen
      Bestätigungs-Abriss; UI „HANDY NICHT VERIFIZIERT" + gesperrte
      Start-Karte (Disabled-Styles — Fund aus Xvfb) + „✓ Handy verifiziert";
      Urteils-Wechsel im phone-priority.log. App 1.0.39: Verifikations-Ping
      nach jedem erfolgreichen Sync (Auto + App-Öffnen-Kick) über den
      bestehenden /api/live-Endpunkt; gemeinsamer Snapshot-Bauer PcLiveState.
      Tests: Desktop 149/149 (+15 Gate-Tests), Android 1187/1187.
      Xvfb-Smoke: 4 Zustände + Klick-Tests + Auto-Stop-Beweis (DB: manuelle
      Session durch Handy-Vorrang geschlossen). Release `pc-tracker-1.6.0`
      (Release-ID 415980506; ZIP 57.495.461 B; Asset-Upload + Updater-
      Simulation verifiziert: Client 1.5.2 findet 1.6.0, Client 1.6.0 findet
      nichts) + `Aevum-1.0.39-Debug.apk` (vc 40, kanonisch, HTTP 200).
      Bericht `docs/PC-TRACKER-1.6.0-ABSCHLUSSBERICHT.md`. Commit `91983b6`.
      WICHTIG fürs nächste Mal: nie 2 App-Instanzen im Smoke-Test (zweite
      bindet Nachbarport; ihr Fenster lag über der ersten — „tote" UI war
      nur die Instanz ohne API-Traffic; Settings-Blob-Persist einer
      Altinstanz überschreibt außerdem die Datei komplett).

- [x] M23 (App 1.0.43, vc 44): Fahrt-Vorlauf (Fahrzeug-Anker statt ältester
      Bewegungs-Probe), App-Tracking-Zuverlässigkeit (Fremd-Session-Ende),
      „Ruhiger Start" entfernt, Ziel-Überschreitung rot+⚠, DB v48
      `source_app_package` + Timeline-App-Icon/Name + Uhren/Zeitstrahl,
      2x1-Homescreen-Widget (Duolingo) mit Start/Stopp, Activity-Picker und
      Maskottchen, Status-Refresh nach Settings-Rückkehr.

- [x] M24 (App 1.0.44, vc 45): Digital-Balance-Tageswechsel lädt Gesamtzeit,
      Unlocks und Stundenverlauf für den geklickten Balken (neue
      `detailForDay`), Kopf zeigt das Datum; Orts-Timeline → Detail-Sheet →
      „Geofence hinzufügen" mit vorausgefülltem Standort/Namen.

- [x] M25 (App 1.0.45, vc 46): Nachträglich angelegter Geofence benennt
      bestehende Unknown-Places in der Orts-Timeline (auch frühere/spätere
      Tage) über Haversine-Match; außerhalb des Radius bleibt unbenannt.

- [x] M26 (App 1.0.46, vc 47): Anruf-Erkennung — Berechtigung
      (`READ_PHONE_STATE`) als Zeile in der Berechtigungs-Karte, Karte „Anruf"
      mit Schalter + Aktivitäten-Zuweisung (Default `social` = „Soziales"),
      FGS `specialUse` `call_state_detection` mit Telefonie-Callback
      (OFFHOOK startet, IDLE beendet), Rangfolge als Allowlist
      (`CallDetectionPolicy`: nur SCREEN_AUTO/APP_TRACKING/PC_TRACKER/PC_REMOTE
      unterbrechbar), Wiederbelebung via App-Start/Boot/15-min-Watchdog,
      DB v49 (`call_detection_enabled`, `call_activity_type_id`),
      `CALL_AUTO` in `AUTO_SOURCES`. +35 Tests (1324 gesamt).
      Kein Geräte-Test möglich (kein Telefonie-Emulator) — Prüfschritte im
      Abschlussbericht §8.

- [x] M27 (App 1.0.47, vc 48, DB v50): Sucht-Detektor als dritte Balance-Seite
      (beobachtete Apps, Öffnungen, Sitzungslängen, 7T/4W/6M-Verlauf,
      Nutzungsdruck, Stundenbalken, App-Details, App-Auswahl), Erhebung über
      UsageStatsManager + Screen-Off-Faltung, 45-Tage-Backfill, 30-min-Worker,
      400-Tage-Retention. Backup-Audit: dateibasiertes Backup erfasst neue
      Features automatisch; eingefrorene Schema-Konstante 40 entfernt,
      ältere Backups werden migriert statt abgelehnt (`BackupVersionPolicy`).
      AT_MOST-Tagesziele werden erst nach Tagesende gewertet („Wertung folgt",
      Popup erst ab 2 Tagen in Folge); Perioden vor Ziel-Erstellung zählen
      nicht mehr. +? Tests, Voll-Build siehe `docs/M27-ABSCHLUSSBERICHT.md`.

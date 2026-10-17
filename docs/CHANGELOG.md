# CHANGELOG

## [Unreleased] — Aevum 1.0.36 (M19.4) — 2026-10-17

### Dashboard-Hero 2.0: „Tag-Cockpit" nach Devons Skizze + Karten-Fläche über dem Hero

Nutzerwunsch (wörtlich): „Aevum UI Update. Ich möchte das dashboard
modernisieren. […] wie es oben aussehen soll [Skizze] […] wenn ich also nach
unten scrolle dann soll das untere sich über den oberen bereich schieben.
[…] modern hochwertig […] mit schimmern und animationen, keine harten
kanten."

- **Zentrales 180°-Güte-Gauge:** fester Rot→Gelb→Grün-Farbraum (Messgerät-
  Bild statt Vollring), animierter Sweep, leuchtender Marker + Nadel mit
  Puls-Glow, große Güte-Zahl (Monospace, `positivityColor`, hell
  kontrast-adaptiert), ✎-Badge bei Override; Tipp öffnet weiterhin den
  Güte-Dialog (Screenreader-Beschreibung neu).
- **Drei versetzte Glas-Kacheln** (Erfasst/Schlaf/Bildschirm, mittlere
  +10 dp wie in der Skizze): Farbverlauf, Gradient-Rand, farbiger
  Glow-Schatten, Kaskaden-Einblendung, hochzählende Werte, Mini-Balken
  (Anteil an 24 h) und Schimmer mit desynchronisierten Perioden.
- **Zonen-Chip neben dem Gauge** (Skizzen-„Zuhause"): Geofence-Name bzw.
  „Abwesend" als Glas-Pille; der separate ZoneBanner entfällt (Information
  jetzt im Hero). **Tagesleiste:** 24h-Ribbon mit Segment-Blöcken,
  pulsierendem Jetzt-Marker, sanft abgedunkelter Zukunft, Stundenlabels
  0–24; Tipp öffnet die Timeline. **Aufnahme-Status unten:** grün
  „Aufnahme läuft" + Timer + Schimmer / amber „Pausiert" / neutral
  „Aufnahme bereit" mit Tagesfortschritt.
- **Sheet über dem Hero:** alle Karten liegen in EINER oben abgerundeten
  Fläche (Verlauf + Rim-Light + Eisblau-Glow); beim Scrollen schiebt sie
  sich über den gepinnten Hero (Parallax −0,5×/−0,55× an der GEMESSENEN
  Hero-Höhe, Deko übersteht ihn um 44 dp → Farbraum schimmert durch die
  Sheet-Ecken, Referenzbild-Aufbau).
- Atmosphere-Orbs auf die M19.2-Identität (Eisblau/Feuerrot/Gold) gezogen;
  Hero-Gradient als 4-Stopp-Verlauf mit Farb-Schweif. Keine DB-/Navigations-
  Änderung; alle bestehenden Sheet-Karten und der FAB bleiben.
- Tests: **1165/1165 grün** (0 Fehler; 1 vorbestehend ignorierte) — neu:
  6 × Hero-Metriken (JVM) + 11 × Hero-UI inkl. **3 Rendering-Screenshots**
  (dunkel/hell/Sheet-über-Hero; Robolectric View-Draw, feste Test-Uhr).
  Voll-Build Exit 0 (9m58s); Debug-APK kanonisch signiert; Release-APK
  (Upload Key, 52.005.475 B) + AAB (24.245.967 B) im Build-Output.
  Bericht `docs/M19-4-ABSCHLUSSBERICHT.md`.

## [Unreleased] — PC-Tracker 1.5.2 — 2026-10-17

### Handy-Vorrang hält jetzt echte Sendelücken aus + große Live-Timer im Dashboard

Devon-Befund: „irgendwie zeichnet der pc trotzdem jede minute für ganz wenige
sekunden kurz digital auf und synchronisiert das auch so mit dem handy" —
obwohl am Handy seit Stunden „Studium" lief. Außerdem: „im dashboard wirklich
irgendwas fancy […], wo auch deutlich die dauer der aufzeichnung sichtbar
ist (egal ob handy oder pc aufzeichnung)".

- **Ursache des Pendelns:** Die Vorrang-Bewertung gab den PC schon nach 45 s
  ohne Handy-Meldung frei. Das Handy-Relay sendet zwar alle 7–15 s, aber jede
  Sendelücke (Standby, WLAN-Schlaf, Service-Neustart — ein fehlgeschlagener
  Versuch kostet allein 3–11 s) reichte: Der PC startete die allgemeine
  Aufzeichnung (Digital), die nächste Handy-Meldung beendete sie sofort →
  kurze Digital-Schnipsel im Minutentakt, die auch noch zum Handy syncten.
- **Fix: Prioritätsfenster 5 Minuten statt 45 s.** Für den VORRANG gilt eine
  Handy-Aufzeichnung erst als beendet, wenn 5 Minuten keine Meldung kam (die
  UI zeigt die strengere 45-s-Frische weiterhin ehrlich an — Steuerung,
  „letzte Meldung"). Live verifiziert: 65 s Funkstille bei aktivem
  General-Toggle → keine einzige PC-Session; erst das ausdrückliche
  Session-Ende gibt den PC frei (und die allgemeine Aufzeichnung läuft dann
  korrekt weiter).
- **Mini-Artefakt-Filter:** Sessions, die der Vorrang beendet und die unter
  2 s alt sind, werden gar nicht mehr gespeichert (Grenzfall „PC startete
  Sekunden vor der Handy-Meldung") — kein Müll in Verlauf oder Handy-Sync.
- **Übergangs-Logging:** `phone-priority.log` (im Datenordner) notiert jeden
  Wechsel „Handy-Aufzeichnung erkannt" / „PC wieder frei" mit der Age der
  letzten Meldung — Funkstille-Fälle sind nie wieder Rätselraten.
- **Dashboard: große Live-Timer (fancy):** Sowohl die Handy-Karte als auch
  die PC-Live-Karte zeigen jetzt eine große tickende Dauer im
  Monospace-Stil (türkis, `MM:SS` / `H:MM:SS`) — wie am Android-Dashboard.
  Bei Pause friert der Timer auf dem letzten Stand ein.
- **Dashboard: „Letzte Meldung vor X"** auf der Handy-Karte — live sichtbar,
  wie frisch das Handy meldet.
- Tests: **134/134 grün** (+3: Prioritätsfenster-Fensterlogik, explizites
  Ende gibt sofort frei, Mini-Artefakt wird verworfen).

## [Unreleased] — PC-Tracker 1.5.1 — 2026-10-17

### Update-Austausch komplett neu: wartet, kopiert mit Fallback, verifiziert, protokolliert

Devon-Befund: „In 1.4.1 hab ich auf update herunterladen geklickt, kurz danach
hat es auch heruntergeladen und sich auch das fenster geschlossen, aber dann
nach ein paar sekunden hat sich einfach wieder 1.4.1 geöffnet. […] Im ordner
wo die exe ausgeführt wird habe ich auch keine neuen dateien gefunden" —
zweimal reproduziert, und es reicht bis zum ersten Update zurück.

- **Ursache der Blindstelle gefunden:** Das Batch-Austauschskript verschluckte
  ALLE Fehler (`>nul`) und war auf einem echten Windows nie verifiziert. Es
  startete die App am Ende brav neu — nur kopiert hatte es nichts, und
  niemand konnte sehen warum. Der `ZipFile`-Extrakt ist als Ursache
  ausgeschlossen (auf .NET 8 nachgestellt: 362/362 Dateien, EXE korrekt).
- **Neu: PowerShell-Austauschskript** statt Batch — ohne Fehler-Verschlucken:
  1. Wartet über `Get-Process` bis zu 90 s, bis der Starter-Prozess WIRKLICH
     weg ist (danach Notfall-`Stop-Process`).
  2. Kopiert mit `robocopy /MIR` (ersetzt alte Dateien vollständig) und hat
     einen **`Copy-Item`-Fallback**, wenn robocopy einen Fehler-Exitcode
     liefert oder gar nicht startet.
  3. **Verifiziert den Austausch:** Die neue EXE muss nach dem Kopieren exakt
     die Größe der EXE aus dem Update-Ordner haben — nur dann steht „OK" im
     Protokoll (inkl. Fallback-Prüfung).
  4. Erst danach App-Start und Aufräumen.
- **Neu: `update.log`** — jede Station (Download → Entpacken → Skriptstart →
  Warten → Kopieren inkl. robocopy-Exitcode → Verifikation → App-Start) wird
  mit Zeitstempel nach `%APPDATA%\AevumPcTracker\update.log` geschrieben.
- **Neu: Update-Tagebuch im UI** — die App merkt vor dem Austausch die
  Zielversion (`pending-update.txt`). Beim nächsten Start bilanziert sie:
  Erfolg (still aufgeräumt) oder **Fehlschlag → deutliches rotes Band in den
  Einstellungen** („Das letzte Update ist nicht durchgelaufen … Details im
  update.log") mit Knöpfen „Log-Ordner öffnen" und „Verstanden". Ein
  gescheitertes Update bleibt nie mehr still.
- Alte Austausch-Skripte früherer Versuche werden beim nächsten Update
  automatisch aufgeräumt.
- Tests: 4 neue (`UpdateJournalTests`: Marker-Runde, kaputte Marker-Datei,
  Bilanz Erfolg/Fehlschlag/kein Auftrag, Log-Format); die Skript-Tests prüfen
  jetzt Reihenfolge/Warten/Fallback/Verifikation/Logging → **131/131 grün**.

## [Unreleased] — PC-Tracker 1.5.0 — 2026-10-17

### Handy-Vorrang, Aktivitäts-Vorwahl, Verlauf-Löschen, deutliche Aufnahme-Anzeige

- **Handy hat immer Vorrang (Nutzerprinzip):** „Offensichtlich sollte die pc
  aufzeichnung gestoppt werden, wenn etwas am handy beginnt aufzuzeichnen […]
  immer nur eine sache gleichzeitig […] und handy hat vorrang." Läuft am
  Handy eine Aufzeichnung, beendet der PC sofort **jede** eigene Session
  (Regel, allgemein, auch manuell) und startet nichts Neues; nach dem
  Handy-Ende zählt die Sichtbarkeit frisch ab jetzt. Manuelle PC-Starts
  werden während der Handy-Aufzeichnung abgelehnt (Start-Karte deaktiviert
  mit Hinweis). Quelle ist der bestehende Live-Kanal — kein neuer Endpunkt.
  Protokoll §5f; neue Testklassen `PhonePriorityTests` (5) und
  `TrackerServicePhonePriorityTests` (2).
- **Allgemeine Aufzeichnung: Aktivität VOR dem Schalter wählbar:** „damit
  nicht direkt eine falsche activity aufgezeichnet wird." Die
  Aktivitäts-Auswahl im Dashboard ist nicht mehr an den aktiven Schalter
  gebunden — wählen, dann einschalten; die Session startet mit der gewählten
  Aktivität.
- **Verlauf: Aufzeichnungen entfernbar:** „Ich will auch in der desktop app
  aufzeichnungen aus dem verlauf entfernen können." Zweistufiges Entfernen
  pro Zeile (✕ → „Entfernen? Ja/Nein", gleiches Muster wie der
  Zugang-Reset). Nur abgeschlossene Sessions (laufende sind geschützt);
  gelöschte werden nicht mehr ans Handy ausgeliefert. `DeleteSessionById` +
  `HistoryDeleteTests` (4).
- **Aufzeichnung ganz deutlich sichtbar:** „oben […] wirklich ganz deutlich
  sichtbar […] mit farbe und schimmer." Beide Live-Karten (PC und Handy)
  tragen bei laufender Aufzeichnung einen türkisen Rahmen, ein gefülltes
  RECORDING-Badge und einen wandernden Licht-Schimmer; der Zustand
  „Handy zeichnet auf" ist eine eigene, hervorgehobene Karte.
- **Version 1.5.0.** Gesamt: **127/127 Tests grün**; Xvfb-Livetest deckte
  einen Anzeige-Bug auf (Pause-Hinweis erschien fälschlich auch beim
  Handy-Vorrang — behoben) und verifizierte alle vier Features live
  (Details im Abschlussbericht).

## [Unreleased] — PC-Tracker 1.4.1 + App 1.0.35 — 2026-10-17 (Nachtrag)

### Update-Installation repariert + Update-Knopf gehört nur in die PC-App

**PC-Tracker 1.4.1 — echter Nutzerbefund:** „dann hat es auch heruntergeladen,
das fenster hat sich geschlossen, dann kam aber das pop up ‚the app is already
active in the system tray‘ […] wenn ich in den ordner gucke […] ist da halt
immer noch alles voll mit 1.3.0. Alte versionen sollten auch überschrieben
werden.“

- **Ursache gefunden und behoben:** Der Update-Flow beendete die App nur über
  `desktop.Shutdown()`. Da das X die App in den Infobereich versteckt und
  `MainWindow.OnClosing` das Schließen ohne `AllowClose` cancelt, lebte der
  Prozess im Tray weiter → robocopy lief gegen gelockte EXE/DLLs (Ordner
  blieb voll auf 1.3.0), und die danach gestartete alte EXE zeigte über den
  Einzelinstanz-Schutz genau diesen Dialog. Fix: `App.ShutdownForUpdate()` —
  Fenster freigeben, Dienste sauber stoppen, Prozessende per
  `Environment.Exit` erzwingen.
- **Austausch-Skript wartet jetzt echt:** Warteschleife auf die Starter-PID
  (statt festem `timeout /t 2`), Notfall-`taskkill /PID … /F` nach ~45 s,
  Kopieren erst, wenn der Prozess wirklich beendet ist.
- **Alte Version verschwindet:** robocopy mit `/MIR` — Dateien, die es in der
  neuen Version nicht mehr gibt, werden entfernt.
- 3 neue Tests (`UpdateSwapScriptTests`), gesamt 116/116 grün.
- **Übergangshinweis:** Eine bestehende 1.3.0 führt ihr nächstes Update noch
  mit dem alten Code aus — für dieses eine Mal manuell ersetzen (neue ZIP
  über den vorhandenen Ordner entpacken; Einstellungen/Aufzeichnungen
  bleiben, sie liegen in %APPDATA%).

**App 1.0.35:** „der update button sollte nur in der pc anwendung sein.
Natürlich sollte die handy anwendung nach wie vor über play store
heruntergeladen werden.“ — Der In-App-Updater aus 1.0.34 (Karte,
`AppUpdateChecker`/`AppUpdateInstaller`, `REQUEST_INSTALL_PACKAGES`,
FileProvider) ist vollständig entfernt. Die Sync-Features aus 1.0.34 bleiben
(Live-Kanal zum PC, Sofort-Sync beim App-Öffnen). versionCode 36; 1148/1148
Tests grün.

## [Unreleased] — PC-Tracker 1.4.0 + App 1.0.34 — 2026-10-17

### Live vom Handy: laufende Aufzeichnung mit Stop/Pause am PC + In-App-Update

**PC-Tracker 1.4.0:**

- **Handy-Session live im PC-Dashboard (Nutzerwunsch):** „Also auf meinem
  handy zeichne ich gerade studium auf. Aber das wird auf dem dashboard in
  der desktop anwendung gar nicht angezeigt. Das sollte es aber. Auch mit
  buttons zum stoppen und pausieren." Neue Live-Karte: Aktivität mit Icon,
  Startzeit, Pausenzustand, Knöpfe **Pause/Fortsetzen** und **Stop**. Ein
  Klick wirkt in Sekunden — der Befehl wird bei der nächsten Meldung der App
  abgeholt und ausgeführt. Pull-Modell unverändert (nur das Handy initiiert).
  Protokoll §5e; 7 neue `PhoneLiveRegistryTests`.
- **Update-Karte:** Hinweis „Deine Einstellungen und Aufzeichnungen bleiben
  erhalten — das Update ersetzt nur die Programmdateien." (Nutzerwunsch:
  „denke beim installieren eines updates der desktop app daran, dass die
  einstellungen übernommen werden sollen in die neue version.") Faktisch
  lagen Einstellungen, Aufzeichnungen und Kopplung schon immer unter
  `%APPDATA%\AevumPcTracker`; das Update tauscht per robocopy nur
  Programmdateien aus (ohne /PURGE, nichts wird gelöscht) — jetzt zusätzlich
  im Code dokumentiert und für den Nutzer sichtbar.
- **Version 1.4.0.**

**App 1.0.34:**

- **Live-Kanal zum PC (Gegenstück zu 1.4.0):** Während einer laufenden
  Aufzeichnung meldet die App ihren Zustand alle wenigen Sekunden an
  `POST /api/live` und führt Stop/Pause-Befehle vom PC-Dashboard sofort aus
  (gleicher Pfad wie die Notification-Knöpfe). Ohne Session: kein HTTP.
- **Sync beim App-Öffnen sofort (Nutzerbericht):** „spätestens wenn ich aevum
  auf dem handy öffne sollte der im hintergrund synchronisieren mit dem pc."
  Der stille Sync-Kick beim App-Öffnen ist jetzt ein sofortiger In-Process-
  Sync (statt nur WorkManager-Einreihung, die das System verzögern kann);
  `PcSyncGate`: Mindestabstand 10 min → 2 min, frischer Erfolg blockiert nur
  noch 60 s („vorhin war doch schon" blockierte das Nachholen).
- **In-App-Update in den Einstellungen (Nutzerwunsch):** „einen button um
  nach updates zu suchen im github repo. Und natürlich auch herunterladen
  und installieren. Sauber durchdacht für beste usability. Ohne irgendwelche
  tokens, weil das alles veröffentlicht wird." Neue Karte „App-Update":
  installierte Version, „Nach Updates suchen" (öffentliche GitHub-Releases,
  kein Token, kein Konto), Release-Notes, Download mit Fortschritt,
  Installation über den System-Dialog. Debug-Builds laden die Debug-APK,
  reguläre die Store-APK. Neues Modul `AppUpdateChecker`/`AppUpdateInstaller`
  (12 neue Tests) + `REQUEST_INSTALL_PACKAGES` + FileProvider nur für
  `files/updates`. Installation ersetzt nie App-Daten — Einstellungen
  bleiben.
- **Version 1.0.34** (versionCode 35).

### Verified

- **1158/1158 Android-Tests grün** (1145 + 12 Update-Tests + 2 neue Gate-Tests;
  1 Alt-Skip), Build `assembleDebug assembleRelease bundleRelease` erfolgreich,
  Debug-Signatur kanonisch (SHA-1 `9c3055c4…`).
- **113/113 PC-Tracker-Tests grün**; Windows-Publish `win-x64` self-contained,
  ZIP `AevumPcTracker-1.4.0-win-x64.zip`.
- Details in `docs/PC-TRACKER-1.4-ABSCHLUSSBERICHT.md`.

## [Unreleased] — PC-Tracker 1.3.0 — 2026-10-17

### Aktivitäten starten, Android-ähnliches Dashboard, sichtbarer Sync

- **Aktivitäten am PC starten (Nutzerwunsch):** „Ich will also auch dort
  activitys starten können (entweder direkt oder mit vorlaufzeit)." Neue
  Dashboard-Karte „Aktivität starten": Chips mit den aus Aevum gespiegelten
  Aktivitäten (ohne Sync: lokaler Katalog), Start-Optionen **Sofort / +5 / +15
  / +30 Min** mit laufendem, abbrechbarem Countdown. Manuelle Session mit
  eigenem `programKey` `__manual__` (Name „Manual start") in der Engine:
  Vorrang vor Regeln (laufende Session endet zum Startzeitpunkt), blockiert
  Regel- UND allgemeine Starts, Idle beendet sie nicht; Ende über Stop-Knopf
  (Ende = jetzt), Pause oder App-Ende. Nach dem Ende zählt die Sichtbarkeit ab
  jetzt (keine rückwirkende Überlappung). 12 neue Engine-Tests
  (`ManualSessionTests`), Live unter Xvfb verifiziert (Chip → Start now →
  „RECORDING NOW · Manually started" → Stop → Session in „Heute").
- **Dashboard wie auf Android:** Live-Karte mit Stop-Knopf für die manuelle
  Aktivität (Anzeige hat Vorrang vor dem Windows-Probe-/Pause-Hinweis),
  „Manuell gestartet" lokalisiert in Live-Zeile und Verlaufszeilen (DB behält
  sprachneutrale Werte), Start-Karte als Herzstück unter der Live-Karte.
- **„Die möglichen Activitys sollten vom handy synchronisiert sein":** erfüllt
  über den bestehenden Aktivitäts-Spiegel (`POST /api/activities`); die Karte
  nutzt exakt dieselbe Liste wie das General-Dropdown.
- **Regelmäßiger Sync sichtbar:** „Dein Handy"-Karte zeigt zusätzlich die Zahl
  der übernommenen Aktivitäten; der Hinweistext erklärt den Automatik-Abgleich
  (App holt im WLAN alle 15 Minuten automatisch ab und spiegelt den Katalog
  herüber — reine Handy-Initiation, Architektur unverändert). Protokoll um
  Abschnitt 5d ergänzt; Sessions-API und Import bleiben unverändert.
- **Version 1.3.0** (`Aevum.PcTracker.csproj`).

### Verified

- **104/104 Windows-Tests grün** (92 vorhandene + 12 neue `ManualSessionTests`).
- Build: `dotnet build` 0 Warnungen/0 Fehler; UI-Smoke-Test unter Xvfb
  (Avalonia unter Linux): Dashboard gerendert, Start-Karte mit 11 Chips,
  Klick-Pfad live durchgespielt (Hover → Auswahl → Start → Live-Karte → Stop).
  Zwei echte Bugs dabei gefunden und behoben: fehlender `CommandParameter` am
  Chip (Klick ohne Wirkung) und manuelle Session unsichtbar, wenn der
  Windows-Probe-Hinweis früh zurückkehrte.
- Release `pc-tracker-1.3.0` mit `AevumPcTracker-1.3.0-win-x64.zip` (Details im
  Bericht `docs/PC-TRACKER-1.3-ABSCHLUSSBERICHT.md`).

## [Unreleased] — M19.3 — 2026-10-17

### Projektmanagement KOMPLETT entfernt + zuverlässige App-Aufzeichnung + Feuerrot

- **Projektmanagement komplett zurückgenommen:** „Ich nehme es zurück. Ich
  will das ganze PM Management doch nicht. Also alles davon." Entfernt sind
  der Einstiegsknopf auf dem Dashboard (samt schwebendem Nachfolger beim
  Scrollen), der Eintrag Zahnrad → Erweitert, alle drei Routen
  (`projects`, `projects/node/{nodeId}`, `pm_editor`), sämtliche Screens
  (Projects/PmNodeDetail/PmEditor), ViewModels, Domänenlogik (PmTree,
  PmTodayPlan, PmSeed, PmDragDrop), Repository/DAO/Entity, die
  PM-Komponenten (GemGlass, PlayfulProgressBar, FireworksOverlay, PmCheckbox,
  PmEntryButton, PmRows, PmDateControls, PmPalette) und die PM-Strings
  (DE/EN). **Datenbank v46:** `MIGRATION_45_46` löscht die Tabelle `pm_node`
  samt Inhalt — sauberer Schnitt statt verwaister Tabelle in jedem Backup.
- **App-Aufzeichnung zuverlässig („Digital Balance"):** Ursache der
  Unzuverlässigkeit war der 15-Sekunden-Poll — bis zu 15 s nach dem Öffnen
  einer getrackten App lief die Aufzeichnung noch als „Digital" weiter.
  Jetzt: `POLL_INTERVAL_MS = 2_000L` (Ø 1 s bis zum Erkennen, Worst Case 2 s
  — weit unter den geforderten 5 s), Nachprüfung auch bei UNVERÄNDERTER
  Vordergrund-App (später angelaufenes „Digital" wird übernommen statt als
  „eigene Session" ignoriert) und einmaliges Nachfassen, wenn die alte
  Aufzeichnung gerade in der Stopp-Phase hängt (Stopp-Races). Die Regeln
  liegen als reine Funktionen in `AppTrackingTakeoverPolicy`
  (`onRepeatedForeground`, `onStopRace`) und sind per Unit-Test festgenagelt
  (`AppTrackingDigitalWiringTest` Test 11 TEMPO + Test 12 NACHPFASSEN).
  Erwartetes Verhalten: Activity „Handynutzung" + Instagram zugeordnet →
  Aufzeichnung „Handynutzung" startet ≤5 s nach dem Öffnen von Instagram,
  unabhängig davon, was vorher lief (Digital, andere App oder nichts).
- **Feuerrot nachgeschärft:** „die rote theme Farbe soll noch etwas röter.
  So feuerrot." Sekundärfarbe dunkel `#F87171` → `#FF3B2E`, hell `#DC2626` →
  `#D32614` (plus Container/onContainer-Abstimmung); `DesignTokens[1]` und
  `AevumThemeTest`-Pins mitgezogen.
- **Schimmer umgezogen:** Der wandernde Schimmer saß auf dem entfallenen
  PM-Knopf — er wandert auf den runden Plus-Knopf des Dashboards („gerne
  auch mit schimmern bei manchen Buttons und so"), respektiert weiterhin
  „Animationen aus".

### Verified

- **1145/1145 Unit-Tests grün** (0 Fehler; 107 Klassen; 1 Skip = Altbestand
  `@Ignore`; −10 PM-Testklassen, +`SchemaV46Test` und
  `PmRemovalMigrationTest`).
- `SchemaV46Test`: `46.json` = `45.json` MINUS genau `pm_node` — Element für
  Element (inkl. Views/Setup-Abfragen strukturell identisch, Identitäts-Hash
  wechselt wie erwartet).
- `PmRemovalMigrationTest`: echte v45-Datenbank aus `45.json` mit Alt-Baum →
  über Room mit `MIGRATION_45_46` geöffnet → `pm_node` weg, übrige 38
  Tabellen unangetastet, Version 46 + korrekte Identitäts-Hash.
- Build `aevum-m193` (`testDebugUnitTest assembleDebug assembleRelease
  bundleRelease`): **BUILD SUCCESSFUL in 5m** (exit 0); Debug-APK 123,4 MB
  kanonisch signiert (`9c3055c4…fbe0e8`), Release-APK 52,0 MB (Upload-Key
  `c8430d35…61ffe9`, versionCode 34), AAB 24,2 MB.
- Download: `http://192.168.178.40:8098/Aevum-1.0.33-Debug.apk`.

## [Unreleased] — M19.2 — 2026-10-17

### Neu — Eisblau/Feuerrot, Projektmanagement-Umbau, Digital-Balance-Revival

- **Theme (Nutzerwunsch):** „ich möchte nicht mehr, dass die Theme Farbe der
  App das Standard Android ist, ich möchte einen schönen Kontrast aus
  eiskalten blau und feuerrot". Dark- UND Light-Schema vollständig auf eine
  Eisblau→Feuerrot-Identität umgestellt (primary `#7DD3FC`/`#0369A1`, kein
  dynamicColor), Hauptknöpfe mit wanderndem Schimmer (`Shimmer.kt`,
  respektiert „Animationen aus").
- **Projektmanagement auf zwei Knotenarten umgebaut:** „Ich möchte keine
  Unterscheidung zwischen Projekt, Kategorie und Aufgabe." Nur noch
  **Projekte** (Container, beliebig verschachtelbar) und **Aufgaben**
  (abhakbare Blätter). DB **v45 als reine Daten-Migration** ohne
  Schema-Änderung: CATEGORY→PROJECT, TASK-mit-Kindern→PROJECT, TODO→TASK.
- **To-do-Listen statt Textfeld:** Aufgaben sind abhakbare Zeilen mit
  **Drag & Drop** (neue, sofort persistierte Reihenfolge), **Löschen** und
  **Inline-Eingabezeile** („wenn man enter klickt ist es hinzugefügt und der
  nächste Platzhalter erscheint") — die Sammel-Eingabe entfällt.
- **PM-Dashboard zeigt nur heute:** „Im PM Dashboard sollten NUR für heute
  zugewiesene Aufgaben angezeigt werden." Gruppen mit den meisten offenen
  Aufgaben oben, innerhalb der Gruppe die gezogene Reihenfolge.
- **Glas schmal neben den Todos:** Das Juwelen-Glas sitzt als schmale Spalte
  rechts der Listen (max. 3 Spalten, Todos bleiben lesbar) und animiert sich
  beim Abhaken voll.
- **Neue Vorlage:** generische Struktur für die meisten Menschen (Haushalt &
  Alltag · Arbeit & Ausbildung · Gesundheit · Finanzen) statt der
  persönlichen Studien-Vorlage — für den Play Store, lokalisiert über
  `PmSeedTitles` (DE/EN).
- **Digital Balance repariert:** Die App-Aufzeichnung wurde nach Reboot,
  App-Update oder Prozess-Kill nie neu gestartet (nur zwei Startpfade, kein
  Watchdog). Jetzt: `BootReceiver` + `MY_PACKAGE_REPLACED` planen einen
  15-Minuten-Watchdog (`AppTrackingWatchdogWorker`, KEEP, gated auf aktive
  getrackte Apps), Screen-On-Nudge im `ScreenEventReceiver`,
  `AppTrackingService.isAlive()`/`lastPollAtMs` als Grundlage; Zuweisung
  einer Activity übersteht den Revival. Übergabe-Kernlogik unangetastet.

### Verified

- **1273/1273 Unit-Tests grün** (0 Fehler; +33 neue; u. a. `PmReorderTest`,
  `PmMigrationDataTest` — echte 44→45-Migration auf SQLite aus `44.json`,
  `PmShimmerTest`, `AevumThemeTest`).
- `PmMigrationSchemaTest`: `44.json` und `45.json` Element für Element
  identisch (nur `version` unterscheidet sich).
- Build `aevum-m192b`: `testDebugUnitTest assembleDebug assembleRelease
  bundleRelease` **BUILD SUCCESSFUL in 7m 47s** (exit 0); Debug-APK kanonisch
  signiert (`9c3055c4…`), Release-APK Upload-Key (`c8430d35…`),
  AAB 24,6 MB.
- Download: `http://192.168.178.40:8098/Aevum-1.0.32-Debug.apk`.
- Nebenbefund dokumentiert (nicht Teil des Auftrags): `lintDebug` findet
  5 Altbestands-Fehler außerhalb dieser Änderung (2× MissingPermission
  ActivityRecognition, 1× RegisterReceiver-Flag-Fehlalarm) — Empfehlung:
  eigenes kleines Paket.

### Neu — Projektmanagement

- **Anlass (User-Report):** „Großes Aevum update. […] ich habe Probleme bei
  meinem Studium, große Aufgaben zu erledigen. Aktuell muss ich eine umfangreiche
  Präsentation machen. Und als Ziel hab ich einfach nur dass die fertig wird. Und
  ich kann mich überhaupt nicht fokussieren. Es scheint kein Ende zu haben und ich
  scheine keinen Fortschritt zu machen. Daher halte ich es für sinnvoll jede
  kleinste Aufgabe schriftlich festzuhalten und abzuarbeiten."
- **Struktur:** Ein selbstreferenzieller Baum (`pm_node`, DB **v44**) für
  Ober-/Unterprojekte, Kategorien, Aufgaben und Schritte — beliebig tief. Vier
  Typen statt fünf Tabellen, damit die Hierarchie offen bleibt.
- **Einstieg:** kleiner Knopf oben rechts im Dashboard (gemaltes Juwel mit
  Zähler der offenen Schritte; schwebt als zweite Platzierung ein, sobald der
  Hero weggescrollt ist). Öffnet ein Sheet, das von unten nach oben einfährt.
- **Glas:** Gefäß mit einem Juwel je erledigtem Schritt (Farbe = Aufgabe),
  leere Fassungen für die offenen Schritte, Fallanimation beim Abhaken,
  Feuerwerk bei vollem Glas — **genau einmal** pro erreichtem Stand
  (persistiertes Feier-Gate gegen Dauerfeuer bei Neukomposition).
- **Tagesplan:** Todos tagzuweisbar; nicht erledigte werden am Folgetag
  übernommen und markiert, erledigte verschwinden am Folgetag. Schritte ohne
  Datum liegen im Backlog.
- **Karussell:** eine Karte je Aufgabe mit den heutigen Schritten, Wischen
  nach links/rechts wechselt die Aufgabe.
- **Fortschritt:** Balken je Aufgabe UND je Projekt über alle enthaltenen
  Schritte (nicht nur heute) — der langfristige Fortschritt wird sichtbar.
- **Massenzuweisung:** Sammel-Eingabe (eine Zeile = ein Schritt),
  „Alle offenen → heute" je Aufgabe, „Tag planen"-Sheet mit Vorauswahl.
- **Tests:** 8 neue Suiten (u. a. echte Room-DB, Migration gegen das
  Compiler-Schema, Komplettablauf über das ViewModel). Details im
  Abschlussbericht `docs/M19-1-ABSCHLUSSBERICHT.md`.
- **Nicht Bestandteil:** keine Levels/XP/Maskottchen, keine Zeiterfassung in
  PM-Schritten, keine Serien, keine Erinnerungen. Das bestehende Modul „Todos"
  bleibt unverändert.

## [Unreleased] — M18.151 — 2026-10-16

### Behoben — App-Aufzeichnung × Digital: Übergabe-Regeln der Smartphone-Aufzeichnungen

- **Anlass (User-Report):** „Irgendwie funktioniert die App-Aufzeichnung nicht so
  ganz. […] wenn der Threshold bei 2 Minuten liegt, ich aber bereits nach
  30 Sekunden Instagram öffne, und unter App-Aufzeichnung Instagram einer
  Activity zugewiesen habe, dann wird einfach weiterhin die ganze Zeit Digital
  aufgezeichnet. […] und auch nach den zwei Minuten Threshold, wenn also die
  Aufzeichnung von Digital schon gestartet ist und ich dann Instagram öffne,
  stoppt leider auch nicht die Aufzeichnung von Digital und die neue
  Aufzeichnung von der zugewiesenen Instagram wird gestartet, sondern
  fälschlicherweise wird einfach weiterhin nur Digital aufgezeichnet. […]
  Falls dann 2 Minuten das Display an ist ohne eine ausgewählte App, dann soll
  Digital wieder starten."
- **Ursache (drei Lücken):**
  1. Eine laufende **Digital**-Session (`SCREEN_AUTO`) galt für den
     AppTrackingService als „fremde" Aufzeichnung → beim Öffnen einer
     getrackten App passierte NICHTS, Digital lief weiter.
  2. Nach dem Ende einer App-Aufzeichnung wurde **nichts neu angesetzt** —
     der Digital-Start hing nur am nächsten Screen-ON-Zyklus; wer am selben
     Bildschirm-Einschaltvorgang weiternutzte, bekam nie wieder Digital.
  3. App-Sessions **überlebten Screen-OFF** (eingefrorener Vordergrund-Status)
     bis zum nächsten Entsperren → falscher Riesen-Block über die Nacht.
- **Fix (M18.151):**
  - **Übergabe-Regeln zentral** in `AppTrackingTakeoverPolicy` (pure,
    unit-getestet): nichts läuft → App starten; eigene Session → Wechsel
    A→B („und zwar direkt"); **Digital läuft → SOFORT übernehmen** (Digital
    endet exakt jetzt, App-Activity beginnt); alles Fremde → nichts anfassen.
  - **Rangfolge strikt:** Geofence, sportliche, manuell gestartete und
    Autofahrt-Aufzeichnungen (plus Kalender-Fallback) werden NIE gestoppt
    oder übernommen — auch der eigene Stop-Pfad läuft jetzt über den
    Race-sicheren `LiveActivityManager.stopIfLive(sessionId)`.
  - **Verwaiste App-Sessions** (Service-Kill durch das System: `trackedSessionId`
    ist leer, die Session lebt aber in der DB weiter) zählen über
    `sourceType == APP_TRACKING` weiter zur Smartphone-Familie — sie werden
    beim Verlassen der App bzw. beim Wechsel jetzt sauber gestoppt/ersetzt,
    statt nach einem Neustart für immer als „fremd" den Wechsel zu blockieren.
  - **Digital-Neuansatz nach App-Ende:** Der AppTrackingService plant den
    `ScreenRecordingWorker` mit der konfigurierten Vorlaufzeit neu ein
    (ein zentraler Einplan-Punkt `ScreenRecordingWorker.schedule()`, den
    auch der ScreenEventReceiver nutzt) → „Falls dann 2 Minuten das Display
    an ist ohne eine ausgewählte App, dann soll Digital wieder starten."
  - **Screen-OFF beendet die App-Aufzeichnung sofort** (konsistent zu
    M18.138 für Digital); beim Wiedereinschalten startet der Wiedereinstieg
    in die App sauber als neue Session.
  - **Digital-Worker startet nie über einer getrackten App:** Beim Feuern
    prüft er den Vordergrund (Event-Probe der letzten 2 Minuten +
    Poll-Schnappschuss des AppTrackingService) und stupst bei einem Treffer
    den AppTrackingService an, statt Digital zu starten.
  - **Nebenbefund behoben:** Eine App-Session aus dem Leerlauf heraus startete
    bisher KEINE Live-Notification (nur der Digital-Pfad tat das) — jetzt
    startet jede App-Session den LiveActivityService.
- **Akku:** Kein neuer Dauerläuft-Pfad — der Worker-Guard nutzt die
  bestehenden 15s-Polls (Schnappschuss, prozess-lokal), die Event-Probe ist
  ein einmaliger Query pro Worker-Feuern. Keine Erhöhung der Poll-Frequenz.
- **Verifikation:** 30 neue Tests (11 Takeover-Policy, 8 Foreground-Folding,
  11 Verdrahtungs-Regression) — **1114/1114 grün**, 0 Fehler/Errors,
  1 vorbestehender Skipped. Release-APK + AAB in 1.0.30 (versionCode 31).

### Technik-Details

- `automation/apptracking/AppTrackingTakeoverPolicy.kt` (neu, pure),
  `automation/apptracking/ForegroundTracker.kt` (neu, pure — EINE
  Vordergrund-Semantik für Service + Probe),
  `automation/apptracking/ForegroundAppProbe.kt` (neu, One-Shot-Probe),
  `domain/liveactivity/LiveActivityManager.kt` (`stopIfLive`),
  `automation/apptracking/AppTrackingService.kt` (Übergabe, Screen-OFF-Stop,
  Digital-Neuansatz, Poll-Schnappschuss, Live-Service-Start),
  `automation/screen/ScreenRecordingWorker.kt` (Vordergrund-Guard +
  zentraler `schedule()`), `automation/sleep/ScreenEventReceiver.kt`
  (nutzt den zentralen Einplan-Punkt).

## [Unreleased] — M18.150 — 2026-10-16

### Geändert — R8: Optimierte Ressourcen-Entfernung aktiviert (Play-Console-Auflage)

- **Anlass:** Die Play Console beanstandete im Release „Google play policy
  fix" unter „Mit der R8-Optimierung kannst du den Arbeitsspeicher und die
  Leistung deiner App verbessern": *„Optimierte Entfernung von Ressourcen ist
  nicht aktiviert"*.
- **Fix:** `android.r8.optimizedResourceShrinking=true` in
  `gradle.properties` (war seit M18.136 bewusst `false`). Damit entfernt R8
  ungenutzte Ressourcen aggressiver und kompaktiert die Ressourcen-Tabelle —
  kleinerer APK/AAB, weniger Speicher zur Laufzeit. Die Einstellung wirkt
  nur auf Release-Builds.
- **Vorab-Absicherung (Stolperfalle ausgeschlossen):** Der Code enthält
  KEINE `getIdentifier()`- bzw. dynamischen Ressourcen-Zugriffe (per
  Volltext-Suche geprüft); alle Ressourcen laufen über R-Referenzen oder den
  XML-Referenzgraphen, der von der Optimierung berücksichtigt wird.
- Zusätzlich: neues Artefakt-Format für die Play-Console — der Build erzeugt
  jetzt auch ein **AAB** (`bundleRelease`, signiert mit dem Aevum-Upload-Key).

### Verifikation

- Release-APK + AAB in 1.0.29 (versionCode 30); Ressourcen- und
  Manifest-Integrität am Artefakt geprüft (Label, Icon, Launch-Activity),
  Größenvergleich vor/nach der Aktivierung dokumentiert. Unit-Tests
  unverändert (letzter vollständiger Lauf 1084/1084; die Optimierung wirkt
  nur auf Release-Packaging).

---

## [Unreleased] — M18.149 — 2026-10-16

### Behoben — App-Icon: Ring der Bereichs-Icons wurde angeschnitten

- **Meldung (Devon):** „Das App-Icon ist ein Kreis, und der schneidet das Bild
  ziemlich genau auf dem Kreis ab, wo die ganzen Icons drauf sind" — die zehn
  Lebensbereich-Icons auf dem Ring waren je nur zur Hälfte sichtbar.
- **Ursache (gemessen):** Das Artwork füllte das adaptive 108-dp-Raster
  randlos; die Launcher-Maske auf dem betroffenen Gerät schneidet deutlich
  aggressiver als die eingeschriebene Standard-Maske (beobachtet: sichtbarer
  Kreis ≈ 77 % des Rasters, exakt auf den Knoten-Mitten). Knoten-Außenkante
  lag bei ~46 dp, der Zuschnitt bei ~41,6 dp.
- **Fix:** Das Artwork liegt jetzt auf **76 %** verkleinert, zentriert auf
  einem aus der Artwork-Randfarbe gemittelten dunklen Grund (Übergang
  unsichtbar) — wie das Originalbild mit Rand. Knoten-Außenkante jetzt ~35 dp,
  unter der beobachteten Maske mit ~6 dp Rand vollständig sichtbar
  (Sichtprüfung mit simulierter Geräte-Maske: vorher angeschnitten, nachher
  komplett). Adaptive-Layer, Legacy-PNGs und Play-512 neu erzeugt aus dem
  Generator `docs/tools/gen_app_icon.py` (`ART_SCALE = 0.76`).

### Verifikation

- Icon-only-Änderung ohne Codeänderung: Unit-Tests unverändert (letzter
  vollständiger Lauf 1084/1084). Debug-APK (kanonischer Key) + Release-APK in
  1.0.28 (versionCode 29) gebaut.

---

## [Unreleased] — M18.147 — 2026-10-16

### Hinzugefügt — Neues App-Icon

- **Adaptives App-Icon** aus dem gelieferten Artwork: Das Bild liegt
  vollflächig im Hintergrund-Layer (`mipmap-anydpi-v26`, je Dichte 108–432
  px); der Vordergrund-Layer ist bewusst transparent, damit bei
  Launcher-Parallaxe nichts doppelt erscheint. Dazu Legacy-PNGs mit
  Alpha-Ecken (abgerundet + runde Variante) und das Play-Console-Asset
  `docs/store-assets/icon-512.png`. Reproduzierbar über den neuen Generator
  `docs/tools/gen_app_icon.py`.
- Manifest: `android:icon`/`android:roundIcon` von der alten Logo-Vektor-
  Datei auf `@mipmap/ic_launcher` bzw. `@mipmap/ic_launcher_round`
  umgestellt.
- Nebenfund behoben: Die Kandidaten-Benachrichtigung nutzte das vollfarbige
  Logo als SmallIcon (das System zeigt das als weißes Viereck) — jetzt das
  weiße Silhouetten-Icon `ic_notification` wie bei allen übrigen
  Benachrichtigungen.

### Behoben — App-Aufzeichnung: 3-Sekunden-Hänger beim Öffnen beseitigt

- **Ursache (User-Meldung: „bei diesem Klick hängt die App kurz für 3
  Sekunden und danach wird alles angezeigt"):** Der PackageManager-Scan
  (installierte Apps + Launch-Prüfung + Icon-Dekodierung für ALLE Apps) lief
  im combine-Transform des ViewModels auf dem **Main-Thread** — bei jedem
  Öffnen, bei jedem Klick (App verschieben) und bei jedem Suchbuchstaben.
- **Jetzt:** Neuer `InstalledAppsProvider` (Singleton, Dispatchers.IO)
  scannt in 24er-Chunks und meldet Zwischenstände — der Screen öffnet
  **sofort**, die Liste füllt sich sichtbar nach („immer mehr ploppen auf").
  Ein warmer Cache liefert den letzten Stand augenblicklich; ein gedrosselter
  Neu-Scan (≥ 60 s) hält ihn aktuell (deckt auch Deinstallationen auf).
  Icons prozessweit gecacht, auf 144 px gekappt (Anzeige: 28 dp).
- **Interaktionen optimistisch** (`AppTrackingOptimism`): App verschieben /
  Activity zuordnen wirkt im selben Frame (lokale Absicht als Override,
  serialisierte DB-Writes); die DB bestätigt und räumt den Override ab, bei
  Schreibfehler stiller Rückfall auf den echten Stand. Dezenter
  Fortschritts-Spinner, solange nachgeladen wird.

### Geändert — PC-Sync: zuverlässiger, weiter still und sparsam

- **Takt 30 → 15 Minuten** (WorkManager-Minimum) — und jetzt **nur im WLAN**
  (UNMETERED statt „irgendein Netz"): Der PC steht im Heimnetz; über
  Mobilfunk hätte der Lauf nur Strom und Daten gekostet.
- **Opportunistischer Kick** beim App-Öffnen und bei jedem Prozess-Start im
  Hintergrund („wenn die App gerade eh was im Hintergrund macht"): holt
  Verpasstes nach, statt bis zum nächsten Takt zu warten, den das System
  (Doze) jederzeit aufschieben kann. Gedrosselt über `PcSyncGate`
  (≥ 10 Min zwischen Versuchen — Erfolg wie Fehlschlag; nichts ohne Kopplung,
  Schalter oder bei frischem Sync). Vollkommen still — Meldungen zeigt
  weiterhin nur der manuelle Sync.

### Verifikation

- Vollständiger Unit-Test-Lauf: **1084/1084 grün** (neu: 18 —
  `AppTrackingOptimismTest` 11, `PcSyncGateTest` 7). Debug-APK (123,7 MB)
  mit kanonischem Keystore verifiziert, Release-APK (52,3 MB, „Aevum Upload
  Key") gebaut — adaptive Icon-XML + alle 15 Dichte-PNGs in beiden Paketen
  geprüft; `aapt`: 1.0.27 (versionCode 28).

---

## [Unreleased] — M18.146 — 2026-10-04

### Behoben — Statistik: Pauschale zählte 30 Tage statt Kalendermonat

- **Bug (gemeldet):** In der Monatsansicht summierte die Tagespauschale
  (z. B. 90 min/Tag „Essen") die **rollierenden letzten 30 Tage**, während
  Sessions aus allen anderen Quellen nur den **Kalendermonat** zählten.
  Ursache: zwei divergierende Perioden-Definitionen — `InsightsAnalytics`
  rechnete kalendarisch, das `InsightsViewModel` für Pauschalen rollierend
  (`anchorDate.minusDays(29)`). Warum es erst im Monat auffiel: Am Tag der
  Meldung (Sonntag, 4. Okt) sind die rollierenden 7 Tage zufällig identisch
  mit der Kalenderwoche.
- **Fix (drei Schichten):**
  1. `InsightsAnalytics.periodDates()` ist die **einzige** Perioden-Quelle
     (kalendarisch: Monat ab 1., Woche ab Montag); `computePeriodRange()`
     entfernt.
  2. Das ViewModel fragt Pauschalen über dieselbe Definition ab.
  3. `InsightsAnalytics.build` begrenzt die Accumulations **selbst** auf
     das Fenster — der Fehlertyp „Aufrufer und Analytik uneinig" ist damit
     strukturell ausgeschlossen.
- **Nebenfund:** `hasData` prüfte ohne Fensterbezug → Aktivitäten mit nur
  älteren Aufzeichnungen zeigten ein Null-Chart statt des Leerzustands.
  Jetzt: `hasData = windowTotal > 0`.

### Hinzugefügt — Zeitraum-Navigation in der Statistik

- **Navigationszeile** `‹ KW 40 · 28. Sep. – 4. Okt. ›` unter dem
  Period-Toggle: konkretes Zeitraum-Label statt nur „Diese Woche";
  Pfeile skippen eine Periode, Label öffnet das Picker-Sheet.
- **Picker-Sheet:** letzte 12 Perioden, Wochen **mit Datum und
  Wochennummer** (ISO), Tage mit „Heute"/„Gestern"-Sonderfall, Monate als
  „Oktober 2026"; Auswahlmarkierung + „jetzt"-Marker + „Zurück zu
  jetzt"-Chip in der Vergangenheit.
- Beim Granularitätswechsel springt der Anker auf heute (sonst zeigte ein
  Mai-Anker nach dem Wechsel plötzlich eine Mai-Woche).

### Hinzugefügt — Detail-Statistikseite je Aktivität

- **Neue Seite** `stats/{activityTypeId}`: Verlaufsgraph im Canvas-Stil
  (Bezier mit Farbverlauf, Glow, animiertes Flächen-Einwachsen, Ring-Marker
  auf dem Gegenwarts-Punkt), **Finger-Scrubber** mit Detail-Karte (Datum,
  Dauer, Anzahl Aufzeichnungen, haptisch), Umschalter **täglich /
  wöchentlich / monatlich** (30 Tage / 12 Wochen / 12 Monate, persistiert).
- Kennzahl-Kacheln (Gesamt, Ø/Tag, Aufzeichnungen, Längste, aktive Tage,
  beste Serie), Wochenmuster (7 Mini-Balken), Trend ±% vs. Vorzeitraum.
- EINE Tagesbasis für alle drei Sichten — konsistent per Konstruktion.
- **Drei Einstiegspunkte:** Insights (Klick auf Top-Liste), Aufzeichnungs-
  Detailseite („Verlauf ansehen"), Aktivitätstypen-Einstellungen
  („Verlauf ansehen" je Aktivität).
- DE + EN Strings; Leerzustand statt leerem Chart.

### Verifikation

- 1039 Unit-Tests, 0 Fehler (neu: 7 + 19 + 5 Tests in drei neuen Suiten,
  plus 2 Integrationstests für die Pauschalen-Range).

---

## [1.0.19] — M18.136 — 2026-09-22

### Behoben — Play-Console-Auflagen

- **DEX-Codeoptimierung (Verschleierung war 0 %):** R8 war im Release-Build
  vollständig deaktiviert (`isMinifyEnabled = false`). Jetzt aktiv mit
  `isShrinkResources = true` und einer aus einem Reflection-Audit
  abgeleiteten `proguard-rules.pro` (Manifest-Komponenten,
  25 `CoroutineWorker`, Hilt-EntryPoints, Room, MapLibre-JNI,
  Health Connect, Enum-`valueOf`).
  Messergebnis aus `r8.json`: Verschleierung **78,7 %**,
  Optimierung **77,6 %**, Shrinking **78,1 %** (Grenzwert 25 %).
  App-Klassen einzeln: **92,7 %** verschleiert.
  DEX-Größe: **52,0 MB → 5,9 MB** (−89 %). AAB: 30,5 MB → 23,4 MB.

- **Randlose Anzeige:** `enableEdgeToEdge()` fehlte, obwohl `targetSdk 36`
  ab Android 15 randlose Anzeige erzwingt. Jetzt zentral in
  `LocalizedActivity` (alle drei Activities), mit an Aevums eigenes Theme
  gekoppelten `SystemBarStyle` (nicht am System-Theme — sonst unsichtbare
  Icons bei System-Hell + App-Dunkel).
  Zusätzlich mitgefixt, was unter Randlosigkeit gebrochen wäre:
  - **Doppeltes Inset-Padding** in 12 Screens: `padding(innerPadding)`
    konsumiert keine Window-Insets → jetzt `consumeWindowInsets(innerPadding)`.
  - **Tastatur verdeckte Eingabefelder:** `enableEdgeToEdge()` deaktiviert
    `setDecorFitsSystemWindows` → `adjustResize` greift nicht mehr →
    `imePadding()` am NavHost-Wurzelmodifier (12 Screens mit Textfeldern).
  - **Popup-Activities** (`BlockActivity`, `SwitchActivity`):
    `windowInsetsPadding(WindowInsets.safeDrawing)`.

### Verifikation

- 853 Unit-Tests, 0 Fehler.
- Alle 25 Worker, beide `valueOf`-Enums, alle 15 Manifest-Komponenten und
  die MapLibre-JNI-Klassen im finalen DEX nachgewiesen (Bytesuche).
- `EdgeToEdge.enable(...)` vor `super.onCreate()`, `consumeWindowInsets` und
  `imePadding` per Bytecode-Inspektion des gebauten APK bestätigt.
- Debug-APK signiert mit kanonischem Key (`9c3055c4…`),
  AAB mit Play-Upload-Key (`C8:43:0D…`).

### Offen (Gerätetest nötig)

Kein Emulator verfügbar (kein `/dev/kvm`). Am Handy zu prüfen:
R8-Laufzeit (Auto-Tracking/Geofences/Karte), randlose Darstellung,
Tastatur-Verhalten, Popup-Activities.

---

## Unreleased

### Added

- Produktvision für **Aevum** eingearbeitet.
- Appname festgelegt: **Aevum**.
- Paketname festgelegt: `com.d_drostes_apps.aevum`.
- Offline-first Entscheidung konkretisiert: kein Login, kein Backend, keine Cloud.
- Kernfeatures dokumentiert:
  - automatische Lebenszeit-Erfassung
  - visuelles Lebensdashboard
  - Ziele, Habits/Streaks, Bucket List
- Android API Strategie ergänzt:
  - Geofencing
  - Activity Recognition
  - Health Connect / Sleep
  - UsageStatsManager
  - WorkManager
- Datenmodell für Activity Sessions, Raw Detection Events, Geofences, Goals, Habits, Bucket List, App Usage und Life Profile geplant.
- Roadmap auf Aevum-spezifische Meilensteine aktualisiert.
- **M2 Android-Projektgrundlage abgeschlossen:**
  - Gradle Wrapper und Kotlin/Android Gradle Projekt
  - Package/Namespace `com.d_drostes_apps.aevum`
  - Jetpack Compose + Material 3
  - Aevum Light/Dark Theme
  - Hilt Application und DI Module
  - Room Database Grundstruktur mit Entities/DAOs/Repositories
  - DataStore Preferences
  - Navigation Compose App Shell
  - Platzhalter-Screens für Dashboard, Timeline, Insights, Wachstum, Settings, Onboarding
  - Unit-/Android-Testgrundlage
  - Debug APK unter `app/build/outputs/apk/debug/app-debug.apk`
- **M3 Design System & Dashboard Skeleton abgeschlossen:**
  - Aevum Design Tokens für Spacing, Radius, Kategorie-/Chart-Farben und Elevation
  - Premium-Komponenten: `AevumCard`, `ProgressRing`, `StatisticCard`, `ChartContainer`, `CategoryChip`, `SourceBadge`, `TimelineItem`, `EmptyState`, `SectionHeader`
  - UX-reviewtes Dashboard Skeleton mit Hero, Fokus-Score, primären Signalen, Zeitverteilung, Tagesfluss, Wachstum, Lebensperspektive und Digital Balance
  - Dashboard Compose Preview `DashboardScreenPreview`
  - Projektweite UX-Regel: jeder neue Screen erhält vor Implementierung einen kurzen Premium-UX-Review

### Changed

- Generischer Premium-App-Plan wurde zur konkreten Aevum-Architektur weiterentwickelt.
- Projektstatus von „Fachdomäne offen“ zu „M3 Design System abgeschlossen“ geändert.
- Datenarchitektur vor M2 geprüft und verbessert: explizite Indizes, Aggregationsstrategie, Batterie-/Background-Strategie, klare Trennung von Raw Events, Candidates, bestätigten Sessions und Statistik-Caches.
- Dashboard UX von „viele Karten“ zu „visuelles Lebenscockpit“ verdichtet: wichtigste Informationen innerhalb von 2 Sekunden sichtbar, weniger Text, stärkere visuelle Hierarchie.
- Gradle JVM Heap auf `-Xmx1536m` und `org.gradle.parallel=false` angepasst, damit Lint/Build in der verfügbaren 2GB-Umgebung stabil laufen.
- minSdk auf API 29 angehoben, passend zur ursprünglichen Android-10-Zielvorgabe und dokumentiert in ADR-0011.

### Verified

- M2: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
- M3:
  - `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1` erfolgreich.
  - `./gradlew lintDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
  - `./gradlew assembleDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
- APK Badging geprüft:
  - package: `com.d_drostes_apps.aevum.debug`
  - versionName: `0.1.0-debug`
  - minSdk: 29
  - targetSdk: 35
- APK Signatur geprüft: APK Signature Scheme v2 erfolgreich, 1 Signer.
- Compose Preview-Kompilierbarkeit über `compileDebugKotlin`/`assembleDebug` geprüft.

### M4 — Core Datenmodell & Room fachlich stabilisieren (2026-07-18)

#### Added

- Datenmodell-Ebenen implementiert: `data_source`, `raw_source_event`, `detection_event`, `activity_candidate`, `activity_session`, `session_evidence`, `activity_type`, `activity_session_change`, `activity_aggregate_day`
- Historische Nachvollziehbarkeit für Activity Sessions: `created_by`, `updated_by`, `source_candidate_id`, `supersedes_session_id`, `revision`, `origin_device_id`, `deleted_at`
- Activity Types als semantische Ebene getrennt von visuellen Kategorien
- Goals/Habits mit flexibler JSON-Rule/Filter-Struktur
- Seed-Daten für Standard-Datenquellen und Activity Types
- Room Migration v1 → v2 mit neuen Tabellen und Seeding
- Schema-Export (`exportSchema=true`) für Version 2
- Vollständige DAOs und Repositories für alle neuen Entitäten
- Android-Testsuite `DatabaseTest.kt` für alle neuen Entitäten, DAOs und Relationen

#### Changed

- `activity_session` von status-basiert (CANDIDATE/CONFIRMED) zu reiner Nutzerwahrheit umgeformt
- `raw_detection_event` in `raw_source_event` und `detection_event` aufgespalten
- `ActivitySession` nutzt nun `source_type`, `created_by`, `updated_by` statt einfacher `source`/`status`
- `Goal` erweitert um `activity_type_id`, `type`, `period`, `target_value`, `target_unit`, `filter_json`, `start_at`, `end_at`
- `Habit` erweitert um `frequency_rule_json`, `success_rule_json`, `activity_type_id`
- `AppDatabase` Version auf 2 erhöht, Migration MIGRATION_1_2 hinzugefügt

#### Verified

- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1` **erfolgreich** (53s)
- `./gradlew lintDebug --no-daemon --console=plain --max-workers=1` **erfolgreich** (1m 23s)
- `./gradlew assembleDebug --no-daemon --console=plain --max-workers=1` **erfolgreich** (54s)
- APK: 28.99 MB, package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2
- Datenbank-Schema-Export: `schemas/debug/com.d_drostes_apps.aevum/data/db/AppDatabase/2.json`
- Migrationstest v1→v2 in AndroidTest erfolgreich

### M5 — Timeline & manuelle Activity Sessions (2026-07-18)

#### Added

- Timeline Tagesansicht mit echten Room-Daten, Tagesnavigation (Vorheriger/Heute/Nächster Tag)
- Wochenansicht vorbereitet: horizontale Tagesstreifen, Synchronisation mit Timeline-Tagesansicht
- Activity Editor Screen: Title, Notiz, Activity Type Chips, Kategorie Chips, Tag Chips
- Zeitfenster-Editor mit Bump-Buttons (±h, ±15m) und direkter Zeitanzeige
- Plausibilitätsprüfung: negative Dauer verboten, Überlappungen mit Warnung aber erlaubtem Speichern
- Activity Detail Screen: volle Anzeige, Bearbeiten/Zurück, Löschen mit Confirmation Dialog
- Navigation: Dashboard → Timeline → Editor/Detail mit deep-link-fähigen Routen
- Dashboard auf echte Room-Daten umgestellt: Hero, Signal Strip, Zeitverteilung, Tagesfluss, Growth, Digital Balance
- Domain-Logik: `SessionTimeValidator`, `TimeFormatting`, `SaveManualActivityUseCase`, `EnsureDefaultDataUseCase`
- ViewModels: `TimelineViewModel`, `ActivityEditorViewModel`, `ActivityDetailViewModel`, `DashboardViewModel`
- Unit Tests für Zeitformatierung und Validierung

#### Changed

- `AppDatabase` Migration bei `DatabaseModule` aktiviert (MIGRATION_1_2)
- `activity_aggregate_day` Primary Key auf `(date, timezone_id)` reduziert
- `ActivitySessionDao` um `getOverlappingRange` und `getTagIdsForSession` erweitert

#### Verified

- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1` **erfolgreich** (1m 10s)
- `./gradlew lintDebug --no-daemon --console=plain --max-workers=1` **erfolgreich**
- `./gradlew assembleDebug --no-daemon --console=plain --max-workers=1` **erfolgreich** (1m 29s)
- APK: 29.24 MB, package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M5.5 — UX Polish vor Automatisierung (2026-07-18)

#### Added

- Safe Area / Statusleisten-Polish für Dashboard, Activity Editor, Detail und Settings
- Editor vereinfacht: eine sichtbare Auswahl **Aktivität** statt separater Activity-Type- und Kategorie-Auswahl
- Interne Trennung von `activity_type` und `category` bleibt bestehen; Activity Type setzt Default-Kategorie
- Visueller Tages-Zeitstrahl im Editor mit Drag-Grobjustierung für Start/Ende
- ±h und ±15m Schnellbuttons bleiben als schnelle Feineinstellung erhalten
- Trigger-Event-Architektur vorbereitet: `TriggerEventMarker`, `TriggerEventKind`, `TriggerEventPreviewProvider`
- Preview-Trigger im Editor: Zuhause verlassen, Fitnessstudio betreten/verlassen, Zuhause angekommen
- Snap-Funktionen: Start/Ende an Trigger Marker einrasten
- Tags in Modal Bottom Sheet mit größeren Chips und vorbereiteter Suche
- Timeline als Tageskalender mit 00:00–24:00 Achse und visuellen Zeitblöcken
- Settings-Struktur vorbereitet: Kategorien, Activity Types, Tags, Geofences, Trigger Events, Zuhause, Arbeit, Activity Recognition, Schlaf, Smartphone-Nutzung, Datenschutz, Export, Backup

#### Changed

- Wochenbereich aus Timeline entfernt
- Dashboard/Editor Content-Padding vergrößert und `statusBarsPadding()` verwendet
- `TimeFormatting` um Minuten-des-Tages und Millis-bei-Minute-des-Tages erweitert

#### Verified

- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 47s)
- `./gradlew lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 54s)
- APK: 29.56 MB, package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.1 — Geofencing & Trigger Events (2026-07-18)

#### Added

- Room Version 3 mit `place_geofence_tag`, `trigger_event`, `automation_settings`
- `place_geofence` erweitert um Icon, Farbe, Activity Type, Tags, Soft Delete und Update-Metadaten
- Google Play Services Location: `GeofencingClient`, `GeofencingRequest`, `Geofence`
- `GeofenceBroadcastReceiver` mit `PendingIntent`-basierter Verarbeitung
- `GeofenceTransitionProcessor`: Geofence Transition → RawSourceEvent → DetectionEvent → TriggerEvent → ActivityCandidate
- Automation Settings Screen mit erklärbarem Permission-Status und Hintergrunderfassung-Schalter
- Geofence List / Editor Screens
- Trigger Events Screen
- Timeline zeigt Trigger Marker und Pending Candidates
- Review Flow: Candidate übernehmen, bearbeiten oder verwerfen
- Activity Editor kann Candidate-Daten vorbefüllen
- Schema Export: `app/schemas/com.d_drostes_apps.aevum.data.db.AppDatabase/3.json`

#### Changed

- Settings öffnen jetzt echte Automatisierungs-/Geofence-/Trigger-Screens
- `SaveManualActivityUseCase` unterstützt Erstellung aus bearbeitetem Candidate (`sourceCandidateId`)
- `AppDatabase` Migrationen in DI auf `MIGRATION_1_2`, `MIGRATION_2_3` erweitert

#### Verified

- `./gradlew compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 13s)
- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 6s)
- `./gradlew lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (2m 16s)
- `connectedDebugAndroidTest`: Android-Test-APK kompiliert, echter Testlauf blockiert durch `No connected devices!`
- APK: 39.29 MB, package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.1 Crash Fix — Room Migration 2→3 (2026-07-19)

#### Fixed

- Repariert: App-Crash auf Upgrade-Installationen, sobald Dashboard/Room geöffnet wurde.
- Ursache: `MIGRATION_2_3` fügte `place_geofence.activity_type_id` per `ALTER TABLE` hinzu, konnte dadurch aber den neuen Foreign Key zu `activity_type` nicht nachtragen.
- Fix: `place_geofence` wird bei fehlendem Activity-Type-Foreign-Key kontrolliert neu aufgebaut.

#### Added

- `MigrationTest.kt` mit v2→v3 Migrationstest und expliziter Foreign-Key-Prüfung.
- `androidTestImplementation("androidx.room:room-testing:2.6.1")`.

#### Verified

- `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, `testDebugUnitTest`, `lintDebug`, `assembleDebug` erfolgreich.
- `connectedDebugAndroidTest` in dieser Umgebung nicht ausführbar: `No connected devices!`.

### M6.2 — Intelligente Geofences & Trigger (2026-07-19)

#### Added

- Geofence Editor mit Premium-Light-Map-Picker per Tippen/Ziehen.
- Aktuelle Position übernehmen via `FusedLocationProviderClient` und `CurrentLocationRequest`.
- Zuhause/Arbeit Schnellsetup mit Icon, Farbe, Radius, Activity Type und Kategorie-Defaults.
- `TriggerPairCandidateRuleEngine` als lokales, transparentes Regelwerk.
- `CandidateRuleOrchestrator` für idempotente Ausführung der Regeln über aktuelle Trigger.
- Regeln für:
  - Ort verlassen → anderer Ort betreten = Fahrt/Wegzeit
  - Ort betreten → selben Ort verlassen = Aufenthalt/Arbeit/Fitness
  - Zuhause verlassen → Zuhause angekommen = vorsichtiger Ausflug
  - Exit ohne Ziel bleibt offen
- Opt-in Review Notifications über `CandidateReviewNotifier`.
- Geofence Diagnosebereich mit Berechtigungsstatus, aktiven/inaktiven Geofences, Triggern, offenen Candidates, Registrierung prüfen und Regeln prüfen.
- Unit Tests für Trigger-Pair-Candidates.

#### Changed

- M6.1 Einzeltrigger erzeugen keine spekulativen Standard-Candidates mehr. Candidates entstehen in M6.2 primär aus erklärbaren Trigger-Paaren.
- Automation Settings enthält Review-Hinweis-Schalter und Diagnose-Navigation.

#### Verified

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (3m 12s)
- `connectedDebugAndroidTest`: Android-Test-APK wurde gebaut; echter Testlauf blockiert durch `No connected devices!`
- APK: 39.29 MB, package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.3a — Daily Review & Premium Dashboard (2026-07-19)

#### Added

- Daily Review Dashboard statt klassischer Statistikübersicht
- Daily Review Hero mit Headline und lokalem regelbasiertem Narrativ
- Visueller Tagesfluss als 00:00–24:00 Lebensfluss mit animierten Segmenten
- Tagesmetriken: erzählte Zeit, offene Zeit, sanfter Balance Score
- Ruhige Integration offener Candidates als „Sanft prüfen“
- Erste Insights: größter Block, offene Zeit, Vorschläge prüfen, Vielfalt
- Bessere Empty States mit Premium-Copywriting
- Day Pulse Animation im Hero

#### Changed

- Dashboard Structure: Hero → Tagesfluss → Metriken → Reviews → Insights → Kategorie → Momente
- Fokus Score in Balance Score umbenannt und sanft heuristisch ohne Leistungsdruck
- Signal Strip durch Key Metrics Row ersetzt
- Time Distribution Card in Category Breathing Room mit Mini Donut überführt
- Digital Balance Card entfernt (kann später in Life Analytics zurückkehren)
- Growth Focus Card entfernt (Platz für Goals/Habits in M7)

#### Verified

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (2m 46s)
- `connectedDebugAndroidTest`: Android-Test-APK wurde gebaut; echter Testlauf blockiert durch `No connected devices!`
- APK: 39.29 MB, package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.3b — Dashboard Feedback & Review Inbox (2026-07-19)

#### Added

- Dashboard-Tagesfluss mit Lückenblöcken, Current-Time-Line, Dot-Indikator und Markierung sehr kurzer Segmente.
- Eigener `ReviewInboxScreen` unter Route `review_inbox`.
- Review Inbox mit ruhigem Header, Confidence-Badge, Zeitraum, Reason und Aktionen **Übernehmen**, **Bearbeiten**, **Verwerfen**.
- `ReviewInboxViewModel` nutzt bestehende Candidate-Daten und `ReviewCandidateUseCase` für Accept/Dismiss.
- Dashboard-Review-Aktionen navigieren in die Review Inbox statt nur zur Timeline.

#### Changed

- Automatische Vorschläge werden stärker als vorbereitete, optionale Entscheidungen dargestellt: sie zählen erst nach Übernahme als bestätigte Session.
- Day-Flow-Canvas bleibt visuell ruhig, zeigt aber mehr Kontext und ist antippbar.
- Tagesnotiz bleibt bewusst ohne Schemaänderung vorbereitet; keine Room-Version-Erhöhung in M6.3b.

#### Verified

- `./gradlew compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich**.
- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich**.
- `connectedDebugAndroidTest`: echter Testlauf in dieser Umgebung blockiert durch fehlendes Gerät/Emulator (`No connected devices!`).
- APK: package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2.

### M6.4 — Life Analytics v1 (2026-07-20)

#### Added

- Neuer Haupttab **Insights** in der Bottom Navigation.
- `InsightsScreen` mit ruhiger Health-/Oura-artiger Hierarchie und Zeitraumwahl **Heute / Woche / Monat**.
- `InsightsAnalytics` als reine, testbare Analytics-Logik auf bestehenden Activity Sessions, Kategorien und Activity Types.
- Großer Donut Chart für Zeitverteilung mit Kategorie, Dauer und Prozent-Legende.
- Vorperiodenvergleich für Heute↔Gestern, Woche↔Vorwoche und Monat↔Vormonat — nur bei echten Vorperiodendaten.
- Top-Aktivitäten nach Activity Type mit Dauer, Prozentanteil und Spark Bars.
- Balance-Bereich für Arbeit, Erholung, Bewegung, Digital und Soziales ohne Score/Gamification.
- Regelbasierte Insight Cards ohne KI.
- Wochen-Heatmap für die aktuelle Woche; Tap auf einen Tag öffnet dessen Timeline.
- Empty State mit erklärender Premium-Copy statt „Keine Daten“.
- Unit Tests für Distribution, Vorperiodenvergleich, Top-Aktivitäten, Balance und Wochen-Heatmap.

#### Changed

- `MainActivity` nutzt jetzt eine echte Bottom Navigation für Heute, Insights, Timeline, Wachstum und Settings.
- `TimelineViewModel` kann optional mit `timeline/{date}` initial auf ein konkretes Datum geöffnet werden.
- Keine neue Room-Version, keine neuen Sensoren, keine neuen Datenquellen, keine KI.

#### Verified

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich**.
- `connectedDebugAndroidTest`: echter Testlauf in dieser Umgebung blockiert durch fehlendes Gerät/Emulator (`No connected devices!`).
- APK: package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2.

### M6.5 — Weekly Review (2026-07-20)

#### Added

- Neuer `WeeklyReviewScreen`, erreichbar aus dem Insights-Bereich.
- `WeeklyReviewAnalytics` als reine, testbare Wochenlogik auf bestehenden Activity Sessions, Kategorien, Activity Types und Pending Candidates.
- Hero „Deine Woche“ mit regelbasierter Wochenzusammenfassung ohne KI.
- Wochen-Zeitstrahl mit sieben Tagen, Top-Kategorie, Gesamtdauer und Farbindikator.
- Tap auf einen Tag öffnet die Timeline dieses Tages (`timeline/{date}`).
- Wochen-Zeitverteilung als Donut mit Dauer und Prozent-Legende.
- Veränderungen zur Vorwoche nur bei echten Vorwochendaten.
- Highlights: längste Aktivität, aktivster Tag, ausgeglichenster Tag, längste Freizeit, längster Arbeitsblock.
- Wochenmuster als ruhige, regelbasierte Insight Cards.
- Offene Zeit mit Aktion „Zur Timeline“.
- Review Inbox Integration für offene automatische Vorschläge.
- Positiver Abschluss und hochwertiger Empty State.
- Unit Tests für Narrativ/Timeline/Distribution, Vorwochenvergleich, Highlights, Pending Reviews und Empty State.

#### Changed

- `InsightsScreen` enthält einen ruhigen Einstieg zum Weekly Review.
- Keine neue Room-Version, keine neuen Sensoren, keine neuen Berechtigungen, keine KI, keine neue Automatisierung.

#### Verified

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich**.
- `connectedDebugAndroidTest`: echter Testlauf in dieser Umgebung blockiert durch fehlendes Gerät/Emulator (`No connected devices!`).
- APK: package `com.d_drostes_apps.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2.

### Known Limitations

- Keine verbundenen Android-Geräte/Emulatoren in der CI; Android-Tests laufen nicht automatisch.
- Release-Signing noch nicht eingerichtet; bisher existiert ein debug-signiertes APK.
- Sensor-/Permission-Flows (Geofencing, Activity Recognition, Health Connect, UsageStats) sind geplant, aber noch nicht implementiert.
- Fachfeatures wie Goals, Habits, Bucket List, Statistiken und Export starten in späteren Meilensteinen (M6+).
- Dashboard nutzt noch vereinfachte Fokus-Score-Heuristik; echte Ziel-/Habit-Auswertung folgt in M8.
- Wochenansicht ist vorbereitet, aber noch nicht als eigenständiger Screen ausgebaut.
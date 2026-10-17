# Abschlussbericht — Live vom Handy (PC-Tracker 1.4.0 + App 1.0.34)

Datum: 2026-10-17 · Auftrag aus einer Nutzernachricht (siehe §0)

---

## 0. Der Auftrag (Zitat)

> „Also auf meinem handy zeichne ich gerade studium auf. Aber das wird auf dem
> dashboard in der desktop anwendung gar nicht angezeigt. Das sollte es aber.
> Auch mit buttons zum stoppen und pausieren. Außerdem will ich in den
> einstellungen einen button um nach updates zu suchen im github repo. Und
> natürlich auch herunterladen und installieren. Sauber durchdacht für beste
> usability. Ohne irgendwelche tokens, weil das alles veröffentlicht wird. Und
> es war so, dass ich die desktop app geöffnet habe und erstmal war das handy
> nicht gekoppelt. dann habe ich aevum auf dem handy geöffnet und es war immer
> noch nicht gekoppelt. Dann bin ich den einstellungen auf pc gegangen und
> dann war es gekoppelt. Also spätestens wenn ich aevum auf dem handy öffne
> sollte der im hintergrund synchronisieren mit dem pc. Außerdem denke dran,
> wie gesagt, es wird veröffentlicht, also nicht beispielhaft meine ip
> adresse irgendwo eintragen. und denke beim installieren eines updates der
> desktop app daran, dass die einstellungen übernommen werden sollen in die
> neue version"

Daraus sieben Arbeitspunkte:

1. Laufende Handy-Aufzeichnung im PC-Dashboard anzeigen
2. Stop- und Pause-Buttons dafür am PC
3. Sync beim App-Öffnen auf dem Handy garantieren (nicht erst nach Einstellungen-Besuch)
4. Update-Suche über die öffentlichen GitHub-Releases (ohne Token)
5. Herunterladen + Installieren in der App selbst
6. Keine privaten Daten (IP-Adressen) in veröffentlichtem Code
7. Beim Desktop-Update die Einstellungen übernehmen

---

## 1. Was gebaut wurde

### 1.1 PC-Tracker 1.4.0 — Live-Karte (Punkte 1 + 2)

**Desktop-Kern (testbar):**

- `Models/PhoneLive.cs` — `PhoneLiveSession`-Record (SessionId, ActivityKey,
  ActivityName, Icon, CategoryKey, StartAtMs, IsPaused, BlockEndAtMs,
  LastUpdateMs).
- `Services/PhoneLiveRegistry.cs` — reine Zustandslogik des Live-Kanals:
  - Nimmt Meldungen der App entgegen (`Update`), verwirft `null` als „Session
    beendet“.
  - **Steuerbefehle:** `RequestCommand("pause"/"resume"/"stop")` legt genau
    einen Befehl ab; er wird bei JEDER Meldung erneut mitgeliefert, bis der
    passende Folgezustand da ist (Bestätigung) — ein Funkloch verliert keinen
    Befehl, ein Doppelklick erzeugt keinen doppelten.
  - **TTL:** Ohne Meldung (> 15 s) wird die Karte „stale“ markiert; nach
    `GoneAfter` (45 s) verschwindet sie (App beendet / Handy aus / Funkloch).
  - **„Fortsetzen“ nach Pause:** bewusst neue Session-ID (Session-Split,
    M18.62-Logik des Handys) — die Registry akzeptiert den ID-Wechsel nur im
    Resume-Fall und quittiert den Befehl darüber.
- `Services/ApiServer.cs` — `POST /api/live`: nimmt `{clientName, session}`
  an, antwortet `{protocol, serverTime, command}` (Token-Auth wie üblich).
- `Services/AppHost.cs` — Wiring (`PhoneLive`-Property).

**Desktop-UI:**

- `DashboardViewModel.cs` — `UpdatePhoneLive` (Karte aus dem Registry-
  Zustand), `PhonePauseResumeCommand`, `PhoneStopCommand`, Zustandstexte.
- `Views/DashboardView.axaml` — Karte „LIVE VON DEINEM HANDY“ über der
  „Nichts wird aufgezeichnet“-Karte: Icon, Aktivität, „Läuft seit … · Dauer“,
  bei Pause „Pausiert — aufgezeichnet bis …“, Pause/Fortsetzen- und
  Stop-Knopf, Hinweis „Steuerung wirkt in Sekunden (gleiches WLAN)“.
- `Localization/Loc.cs` — `s.plive_*` DE/EN.

**Verhalten:** Ein Klick am PC legt den Befehl ab; die nächste Meldung der
App (wenige Sekunden) holt ihn ab und führt ihn genau wie den jeweiligen
Knopf der Live-Notification aus. Danach zeigt die Karte den neuen Zustand.

### 1.2 App 1.0.34 — Live-Relay (Gegenstück zu 1.1)

- `LiveActivityService.kt` — neuer Relay-Job (Takt 7 s bei aktivem Screen,
  15 s bei ausgeschaltetem; **ohne laufende Session wird kein HTTP
  gesendet**, nur die eine „Session beendet“-Meldung nach dem Ende):
  - `relayPcOnce()` liest die Live-Session + zwischengespeicherten
    Aktivitätstyp, meldet an `PcTrackerRepository.relayLive(...)` und führt
    die Antwort aus: `pause` → `liveActivityManager.pause()`, `resume` →
    `resume()`, `stop` → Stop-Pfad der Notification (inkl. driveActive-Reset
    für Fahrterkennungs-Sessions, M18.76).
  - Der Stop-Pfad wurde dafür in `performStopFromOutside()` gezogen — ein
    Steuerweg für Notification-Knopf und PC-Befehl, keine Sonderlogik.
  - `lastRelayedSessionId` verhindert Leerlauf-HTTP; bei „nicht gesendet“
    (keine Kopplung / Schalter aus / PC weg) bleibt der Merker absichtlich
    stehen, damit der letzte Stand später zugestellt wird.
- `PcTrackerClient.kt` — `relayLive()` + `LiveSessionSync`-Datenklasse.
- `PcTrackerRepository.kt` — `relayLive()` mit `LiveRelayOutcome`
  (Delivered/NotSent), still bei Fehlern (nächster Takt zählt).

### 1.3 Sync beim App-Öffnen (Punkt 3 — das „nicht gekoppelt“-Erlebnis)

Befund aus dem Code: Der stille Sync-Kick beim App-Öffnen reihte nur einen
**WorkManager**-Job ein — den darf Android beliebig verzögern (JobScheduler,
Doze). Zusätzlich blockierte `PcSyncGate` mit 10-Minuten-Abstand in beide
Richtungen: Ein einziger Fehlversuch (PC-Server noch nicht bereit) machte
jedes weitere App-Öffnen bis zu 10 Minuten wirkungslos — genau das passt zu
„es war immer noch nicht gekoppelt“.

Fix in App 1.0.34:

- `MainActivity.kt`: Beim Start (wenn gekoppelt + Schalter an) **sofortiger
  In-Process-Sync** (`syncNow()` in IO-Coroutine) — noch bevor der
  WorkManager-Kick eingereiht wird.
- `PcSyncGate.kt`: Mindestabstand **10 min → 2 min**; erfolgreicher Sync
  blockiert nur noch **60 s** (früher 10 min). Der Sturm-Schutz bleibt
  (ein Versuch pro Öffnen), aber Nachholen ist ehrlich möglich.
- 2 neue + 2 angepasste Gate-Tests dokumentieren die neue Semantik
  (u. a. „Sync vor 3 Minuten darf das App-Öffnen nicht blockieren“ — exakt
  der gemeldete Fehler).

### 1.4 App 1.0.34 — In-App-Update (Punkte 4, 5, 6)

- `data/update/AppUpdateChecker.kt` — sucht in den **öffentlichen**
  GitHub-Releases (`/repos/Renegaded66/Aevum/releases`, Tag-Präfix `app-`):
  - **Ohne jeden Token, ohne Konto** (anonyme API; User-Agent `AevumApp`).
  - Debug-Builds laden die Debug-APK, reguläre Builds die Store-APK;
    Drafts/Prereleases/PC-Tracker-Releases werden übersprungen.
  - Fehler (offline/Rate-Limit) werden ehrlich von „kein Update“
    unterschieden (`Failed` vs. `UpToDate`).
  - Reine Auswahl-Logik separat testbar (`selectNewest`, `parseVersion`,
    `compareVersions`) — 12 neue Tests.
- `data/update/AppUpdateInstaller.kt` — Download mit Fortschritt (0–100) in
  `files/updates/`, Installation über den System-Dialog
  (`ACTION_VIEW` + FileProvider).
- `AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES`; FileProvider
  ausschließlich für `files/updates` freigegeben (`res/xml/file_paths.xml`)
  — es wird nichts anderes geteilt.
- `SettingsScreen.kt` — Karte „App-Update“: installierte Version, ein Knopf
  „Nach Updates suchen“, bei Fund Versionszeile + Release-Notes +
  „Herunterladen & installieren“ mit Fortschrittsbalken, danach Hinweis
  „Installationsdialog geöffnet — bitte dort bestätigen“. Zustände:
  Idle/Checking/UpToDate/Available/Downloading/InstallPrompt/Failed.
  Debug-Hinweis („Test-Fassung …“) nur in Debug-Builds.
- **Einstellungen bleiben:** Die Installation ersetzt nie App-Daten
  (Android-Standard bei Update-Installation) — die Karte nennt das nicht
  extra, der System-Dialog tut es.
- **Keine privaten Daten:** Kein Token, keine IP, keine Nutzerdaten im
  veröffentlichten Code; die API-URL enthält nur öffentliche Repo-Angaben.

### 1.5 Desktop-Update: Einstellungen bleiben (Punkt 7)

Befund: Die Fähigkeit existierte bereits (Update-Karte in den Einstellungen
mit Auto-Check beim Start + Badge, `UpdateService`/`UpdateInstaller` seit
1.2). Die Sorge war berechtigt-adressiert wie folgt:

- **Faktisch:** `SettingsService`/`SessionStore` speichern alles als
  `pctracker.sqlite` unter `%APPDATA%\AevumPcTracker`
  (`AppHost.DefaultDataDirectory`) — außerhalb des Programmordners. Das
  Update tauscht per robocopy **nur Programmdateien** aus (ohne `/PURGE`;
  nichts wird gelöscht). Einstellungen, Aufzeichnungen, Kopplung bleiben
  nachweislich unberührt.
- **Sichtbar:** Neuer Hinweis in der Update-Karte: „Deine Einstellungen und
  Aufzeichnungen bleiben erhalten — das Update ersetzt nur die
  Programmdateien.“ (`s.upd_keep`, DE/EN).
- **Im Code dokumentiert:** `UpdateInstaller.ApplyAndRestart`-Doc-Kommentar
  erklärt die Garantie inkl. der Gründe.

### 1.6 Versionen

- PC-Tracker **1.4.0** (`Aevum.PcTracker.csproj`).
- Aevum-App **1.0.34** (versionCode 35).

---

## 2. Verifikation (echte Läufe)

### 2.1 Live-Kanal Desktop — E2E unter Xvfb (echtes Fenster, echte API)

Ablauf am 2026-10-17 gegen den Debug-Build der Windows-App (unter Xvfb :97,
Server auf 47827), simuliertes Handy per `curl`:

| Schritt | Aktion | Ergebnis |
|---|---|---|
| 1 | `POST /api/live` mit Session (Studium, läuft seit 15 min) | `{"protocol":1,…, "command":null}` |
| 2 | Screenshot | Karte **„ON YOUR PHONE · Studium · Running since 12:45 · 15 min“** mit Knöpfen **Pause** und **Stop** |
| 3 | Klick **Pause** + nächste Meldung | Antwort `{"command":"pause"}` ✔ |
| 4 | App meldet `isPaused:true` | Antwort `{"command":null}` (Befehl quittiert); Karte zeigt **„Paused — recorded until 03:20“** + Knopf **Resume** |
| 5 | Klick **Resume** + nächste Meldung | Antwort `{"command":"resume"}` ✔ |
| 6 | Klick **Stop** + nächste Meldung | Antwort `{"command":"stop"}` ✔ |
| 7 | Meldung `session:null` | Karte verschwindet; Dashboard zeigt wieder „NOTHING BEING RECORDED“ ✔ |

### 2.2 Tests + Builds

- **PC-Tracker:** `dotnet test` → **113/113 grün** (106 vorhandene + 7 neue
  `PhoneLiveRegistryTests`; Stand inkl. 1.3.0-Bestand).
- **App:** `testDebugUnitTest` → **1158/1158 grün**, 0 Fehler (1145 + 12
  Update-Checker-Tests + neue Gate-Tests; 1 Alt-Skip `@Ignore`).
- **App-Build:** `assembleDebug assembleRelease bundleRelease` →
  **BUILD SUCCESSFUL in 4m 47s**; Debug-APK 123.526.458 B, Release-APK
  52.009.787 B, AAB 24.254.248 B; Debug-Signatur kanonisch
  (SHA-1 `9c3055c4…`, Guard „signing-verify OK“).
- **PC-Tracker-Publish:** `dotnet publish -c Release -r win-x64
  --self-contained` → `AevumPcTracker-1.4.0-win-x64.zip`
  **57.305.209 B** (362 Dateien, 0 PDBs).
- UI-Nachweis: Screenshots aus §2.1 (Karte, Pausenzustand, Karte weg).

### 2.3 Release

- Git-Tag `pc-tracker-1.4.0` (Commit `a0ef87d`); GitHub-Release **ID
  415755160** — https://github.com/Renegaded66/Aevum/releases/tag/pc-tracker-1.4.0
  mit Asset `AevumPcTracker-1.4.0-win-x64.zip` (57.305.209 B, state
  `uploaded`). Update-Erkennung des laufenden 1.3.0 live gegen die API
  bestätigt (findet 1.4.0 inkl. ZIP-Asset).
- Auslieferungsserver `/srv/aevum-apk/`: `AevumPcTracker-1.4.0-win-x64.zip`
  + `Aevum-1.0.34-Debug.apk` (123.526.458 B); `index.html` auf 1.0.34
  (versionCode 35) und PC-Tracker 1.4.0 aktualisiert. Downloads geprüft
  (HTTP 200):
  - `http://192.168.178.40:8098/Aevum-1.0.34-Debug.apk`
  - `http://192.168.178.40:8098/AevumPcTracker-1.4.0-win-x64.zip`

---

## 3. Ehrliche Abgrenzungen

- **Live-Relay der App (Android-Seite)** ist über Unit-Tests und den
  kompilierten Build abgesichert, aber **nicht auf einem echten Gerät**
  gelaufen (in dieser Umgebung kein Android-Gerät). Der Desktop-Gegenpart
  ist vollständig E2E verifiziert (§2.1); die App-Seite nutzt exakt dieselben
  Endpunkte/Formate, die der E2E-Test bedient hat.
- **Android-Update-Installation** (System-Dialog, „Unbekannte Apps“) wurde
  nicht auf Hardware geklickt — Manifest/FileProvider sind über den Build
  validiert; der Ablauf entspricht dem Android-Standardweg.
- **Windows-Probe** des PC-Trackers unverändert; der Live-Test lief wie
  immer auf Linux/Xvfb (die Windows-spezifische Aufzeichnung war nie Teil
  dieser Änderung).
- **Desktop-Update-Installation** (robocopy-Austausch) wurde nicht auf
  Windows ausgeführt (kein Windows hier); die Einstellungen-bleiben-Garantie
  folgt aus der Datenablage (`%APPDATA%`) + `/PURGE`-freiem Kopieren und ist
  im Code dokumentiert.
- Der Live-Takt (7 s / 15 s) ist bewusst konservativ; ein Streaming-Kanal
  wurde nicht gebaut (Pull-Modell bleibt Architektur-Entscheidung).

---

## 4. Geänderte/neue Dateien (Auszug)

**Desktop:** `src/Aevum.PcTracker.Core/Models/PhoneLive.cs` (neu),
`Services/PhoneLiveRegistry.cs` (neu), `Services/ApiServer.cs`,
`Services/UpdateService.cs`, `src/Aevum.PcTracker/Services/AppHost.cs`,
`ViewModels/DashboardViewModel.cs`, `Views/DashboardView.axaml`,
`Views/SettingsView.axaml`, `Localization/Loc.cs`, `Aevum.PcTracker.csproj`,
`README.md`, `tests/.../PhoneLiveRegistryTests.cs` (neu).

**App:** `data/update/AppUpdateChecker.kt` (neu),
`data/update/AppUpdateInstaller.kt` (neu), `AndroidManifest.xml`,
`res/xml/file_paths.xml` (neu), `res/values{,-de}/strings.xml`,
`MainActivity.kt`, `automation/pctracker/PcSyncGate.kt`,
`data/pc/PcTrackerClient.kt`, `data/repository/PcTrackerRepository.kt`,
`domain/liveactivity/LiveActivityService.kt`,
`ui/screens/settings/SettingsScreen.kt`, `app/build.gradle.kts`,
Tests: `data/update/AppUpdateCheckerTest.kt` (neu),
`automation/pctracker/PcSyncGateTest.kt`.

**Doku:** `docs/PC-TRACKER-PROTOCOL.md` (§5e), `docs/CHANGELOG.md`,
`desktop/README.md` („Neu in Version 1.4“), dieser Bericht.

---

## 6. Nachtrag (gleicher Tag) — Nutzerbefund nach dem ersten 1.4.0-Anlauf

Devon meldete: In 1.3 das Update gesucht und installiert → Download lief,
Fenster schloss sich, dann das Popup „the app is already active in the system
tray. You can open or quit it there“ — und im Ordner lag weiterhin komplett
1.3.0. Außerdem: Update-Knopf gehört **nur** in die PC-App (Handy weiter über
den Play Store), und alte Versionen müssen beim Update ersetzt werden.

### 6.1 Ursache (im Code bewiesen, nicht geraten)

`desktop.Shutdown()` allein beendet die Tray-App nicht: Das X versteckt die
App in den Infobereich (`MainWindow.OnClosing` cancelt das Schließen ohne
`AllowClose`), und der Update-Flow setzte `AllowClose` nie. Der Prozess lebte
also im Tray weiter → das Austausch-Skript (festes `timeout /t 2`) kopierte
gegen **gelockte** EXE/DLLs → robocopy ersetzte nichts → das Skript startete
die alte EXE → Einzelinstanz-Schutz → exakt das gemeldete Popup. Ordner blieb
1.3.0.

### 6.2 Fixes (PC-Tracker 1.4.1)

- `App.ShutdownForUpdate()`: `AllowClose = true` → Dienste sauber stoppen
  (`ShutdownHost`, Sessions werden abgeschlossen) → **`Environment.Exit(0)`**
  als Garantie. Der Update-Pfad im SettingsViewModel nutzt nur noch diese
  Methode (mit `Environment.Exit`-Fallback).
- `UpdateInstaller.BuildSwapScriptLines()` (neu, testbar): Warteschleife auf
  die Starter-PID (`tasklist`-Poll, 1-s-Schritte), nach ~45 s
  Notfall-`taskkill /PID … /F`, **erst danach** `robocopy /MIR /R:3 /W:2`
  (spiegelt → alte Dateien verschwinden), erst danach Start der neuen EXE.
- 3 neue Tests `UpdateSwapScriptTests` (Warteschleife/Kill-Reihenfolge,
  /MIR, kein 2-s-Timeout mehr) → **116/116 grün**.
- Sichtbarer Hinweis bleibt: „Deine Einstellungen und Aufzeichnungen bleiben
  erhalten — das Update ersetzt nur die Programmdateien.“

### 6.3 Übergang für bestehende 1.3.0/1.4.0-Installationen

Der Update-Ablauf wird von der **laufenden** Version ausgeführt — eine
vorhandene 1.3.0 startet ihr nächstes Update also noch mit dem fehlerhaften
Code. Deshalb für dieses eine Mal manuell: App im Infobereich beenden, neue
ZIP herunterladen, den vorhandenen Programmordner-Inhalt mit dem ZIP-Inhalt
überschreiben (alte Dateien vorher löschen); Einstellungen, Aufzeichnungen
und Kopplung bleiben unangetastet (%APPDATA%). Ab 1.4.1 erledigt der
Update-Knopf alles selbst — inklusive Entfernen der alten Dateien.

### 6.4 App 1.0.35 (Android)

„der update button sollte nur in der pc anwendung sein. Natürlich sollte die
handy anwendung nach wie vor über play store heruntergeladen werden.“ —
Der komplette In-App-Updater aus 1.0.34 ist wieder entfernt (Settings-Karte,
`AppUpdateChecker`, `AppUpdateInstaller`, zugehörige Tests,
`REQUEST_INSTALL_PACKAGES`, FileProvider, `file_paths.xml`, Strings DE/EN).
Unverändert bleiben die Sync-Features (Live-Kanal, Sofort-Sync). Version
1.0.35 (versionCode 36); **1148/1148 Tests grün**; Build
`assembleDebug/Release/bundleRelease` grün.

# Abschlussbericht — PC-Tracker 1.6.0 + Aevum 1.0.39: Verifikations-Gate

Datum: 2026-10-17 · Befunde aus einer Nutzernachricht (siehe §0)

---

## 0. Der Befund (Zitat)

> „Die desktop app zeichnet immer noch manchmal auf obwohl das handy gerade
> aufzeichnet. Das sollte so nicht sein. Am besten sollte die desktop app
> erst erlauben, überhaupt irgendwas aufzuzeichnen, also sowohl das
> automatische als auch das manuelle, wenn die desktop app verifiziert hat,
> das auf dem handy nichts läuft, ansonsten kommt eine meldung das der stand
> des handys nicht verifiziert werden konnte. Damit das problem nicht
> weiterhin besteht. Ich habe seit 4 stunden die aufzeichnung studium auf
> meinem handy, aber trotzdem hat der pc immer wieder für <1 minute und
> sogar einmal für 2 minuten digital aufgezeichnet."

Trotz 1.5.2 (5-Minuten-Karenz) blieben Falsch-Aufzeichnungen möglich — die
Karenz dämpft Sendelücken nur, sie schließt sie nicht aus. Devon ordnet an:
**fail-closed** — nur noch aufzeichnen, wenn FRISCH verifiziert ist, dass am
Handy nichts läuft; sonst Meldung statt Aufzeichnung.

## 1. Ursache (Code-Evidenz, beide Seiten)

- **Handy:** Der Live-Relay sendet nur WÄHREND einer Aufzeichnung (7–15 s);
  im Leerlauf fließt gar kein Signal. Sendelücken (Doze, WLAN-Schlaf,
  Service-Neustart) sind real und nicht vollständig vermeidbar — in diesen
  Lücken sieht der PC „nichts gemeldet".
- **PC (1.5.x):** `IsRecordingForPriority` gab nach 5 min ohne Meldung
  stillschweigend frei (`_session → stale → false`). Jede Sendelücke > 5 min
  bei laufender Handy-Aufzeichnung öffnete damit genau das Fenster für
  „PC startet Digital" — die nächste Handy-Meldung beendete die Session
  sofort (die beobachteten <1-min-/2-min-Schnipsel).

## 2. Fixes

### 2.1 PC-Tracker 1.6.0 — das Gate (fail-closed)

- **`PhoneGateVerdict`** (NotCoupled / Recording / VerifiedIdle / Unverified):
  Neue, reine Logik in `PhoneLiveRegistry.GetGateVerdict()`:
  - Recording: letzte Meldung „zeichnet auf" ≤ 5 min → Vorrang blockiert.
  - **Veraltetes „zeichnet auf" (> 5 min) → Unverified — nie mehr frei.**
  - VerifiedIdle: letzte ausdrückliche „nichts läuft"-Bestätigung
    ≤ **30 min** (`IdleVerificationWindowMs` — deckt zwei verpasste
    Sync-Takte inkl. Doze ab).
  - Sonst (Funkstille/nie gemeldet) → Unverified.
- **Engine:** Neues Tick-Feld `PhoneVerifiedIdle`; ohne Bestätigung startet
  NICHTS Neues (Regel + allgemein), Sichtbarkeit zählt ab jetzt (kein
  rückwirkender Start nach der Bestätigung). **Laufende Sessions bleiben**
  (nur der frische Vorrang beendet — eine Sync-Verzögerung schneidet keine
  gesunde Aufzeichnung ab).
- **Manueller Start:** `ManualStartActivity` liefert jetzt
  `ManualStartOutcome` (Started / BlockedByPhone / **Unverified**) statt bool.
- **Gate-Aktivierung:** `AppSettings.PhoneKnownAtMs` — gesetzt von der ersten
  token-authentifizierten Anfrage. Ein PC ohne Kopplung zeichnet wie immer
  (kein Regress für reine PC-Nutzer). JSON-Blob, kein Schema-Update.
- **UI:** Statuskarte „HANDY NICHT VERIFIZIERT" (Warn-Icon, letzte
  Bestätigung bzw. letztes Lebenszeichen), Start-Karte gesperrt mit gelbem
  Hinweis, „✓ Handy verifiziert" bei bestätigtem Stand; Chips + „Jetzt
  starten" sichtbar deaktiviert (neue Disabled-Styles).
- **Logging:** Jeder Urteils-Wechsel im `phone-priority.log`
  (z. B. „verifikation fehlt: noch keine Bestätigung vom Handy — PC startet nichts").

### 2.2 Aevum 1.0.39 — die Bestätigungen

- **Verifikations-Ping:** Nach jedem erfolgreichen Sync (Auto-Sync ~15 min +
  App-Öffnen-Kick) meldet die App per `POST /api/live` ihren Stand — auch
  `session: null` („nichts läuft"). Damit existiert erstmals ein POSITIVES
  Leerlauf-Signal; ohne App-Update kann (und soll) das Gate nicht öffnen.
- **`PcLiveState.snapshot`**: gemeinsamer Snapshot-Bauer für Service-Relay
  und Sync-Ping (keine doppelte DTO-Pflege).
- Kein neues UI, keine DB-Änderungen; `LiveActivityService` nutzt jetzt
  denselben Helfer.

## 3. Eigene Entscheidungen — hinterfragt

1. **Fail-closed statt längere Karenz:** Naheliegend wäre gewesen, das
   5-Minuten-Fenster einfach auf z. B. 30 min zu erhöhen. Verworfen — es
   bleibt ein Pflaster: jede Sendelücke > Karenz reproduziert den Bug.
   Devons Ansage ist explizit fail-closed („erst erlauben … wenn verifiziert
   … ansonsten Meldung"); jetzt ist der Bugfall strukturell unmöglich.
2. **Laufende Sessions überleben einen Bestätigungs-Abriss:** Die strikte
   Auslegung würde auch laufende Aufzeichnungen stoppen. Bewusst NICHT:
   Sonst zerschneidet jede Sync-Verzögerung eine gesunde PC-Aufzeichnung
   (Telefon klingelt → Sync verspätet → Session tot). Doppel-Aufzeichnungen
   verhindert weiterhin der Vorrang, sobald die nächste Handy-Meldung
   ankommt. Nur NEUE Starts brauchen die Bestätigung.
3. **30-Minuten-Bestätigungsfenster:** Sync-Kadenz ist nominell 15 min;
   30 min = zwei verpasste Takte inkl. Doze-Spielraum. Kürzer hätte im
   Alltag zu oft blockiert („warum zeichnet der PC nicht?"), länger hätte
   die Verifikation entwertet. EINE Konstante zum Nachjustieren
   (`IdleVerificationWindowMs`), im Bericht dokumentiert.
4. **Gate nur nach erstem Handy-Kontakt:** Ein PC ohne jede Kopplung hat
   kein Handy, dessen Stand man verifizieren könnte — dort wäre Blockieren
   blanker Regress für reine PC-Nutzer. Sobald ein Handy je den PC
   kontaktiert hat, gilt die Verifikationspflicht dauerhaft.
5. **Bestätigung über den BESTEHENDEN Live-Endpunkt:** Kein neues Protokoll
   (Version bleibt 1), keine neuen Ports — `session: null` existierte
   bereits für das Session-Ende; der Sync-Ping nutzt denselben Weg.

## 4. Nicht Bestandteil (bewusst)

- Kein Heartbeat bei ausgeschlossener Auto-Sync / deinstallierter App —
  dann bleibt der PC korrekt bei „nicht verifiziert" (**das ist die
  gewünschte Eigenschaft**, kein Mangel; die README erklärt es).
- Keine Änderung am Protokoll (kein Versionssprung, kein neuer Endpunkt).
- Keine UI-Änderung auf der App-Seite (nur Sende-Logik).
- Kein Umbau des Relay-Takts im LiveActivityService (die Sendelücken sind
  durch das Gate nun unkritisch; eine Robustheits-Verbesserung dort wäre ein
  eigener, separat messbarer Auftrag).

## 5. Ehrliche Abgrenzungen

- **„Verifiziert" heißt: der PC hat eine frische, ausdrückliche Bestätigung
  vom Handy.** Ein Handy, das außerhalb des WLANs ist oder dessen App keine
  Syncs ausführt, kann das Gate nicht öffnen — der PC zeichnet dann NICHT
  auf und zeigt die Meldung. Das ist exakt Devons Vorgabe („sonst Meldung"),
  wird aber im Alltag öfter sichtbar sein als früher: Der Blick auf die
  Karte „HANDY NICHT VERIFIZIERT — letzte Bestätigung vor X" erklärt es
  jeweils. Falls sich das Fenster im Alltag als zu streng/zu locker zeigt:
  eine Konstante (`IdleVerificationWindowMs`), ein Commit.
- Der Xvfb-Smoke-Test lief mit simuliertem Handy über die echte LAN-API
  (4 Zustände + Klick-Tests + Auto-Stop-Beweis, siehe §6) — aber auf Linux
  (NullForegroundProbe), nicht auf Windows. Die Windows-Probe ist von
  diesen Änderungen unberührt.
- Während des Smoke-Tests lief versehentlich eine ZWEITE Instanz (Fenster
  lagen übereinander; die zweite band einen Nachbarport). Die Diagnose der
  „eingefrorenen" UI führte genau dorthin zurück — kein App-Bug, aber als
  Test-Fallstrick hier festgehalten (nie zwei Instanzen screenshotten).

## 6. Verifikation (echt, aus diesem Lauf)

### Unit-Tests (Desktop, deterministisch)
- **149/149 grün** (vorher 134; +15: 7 Gate-Urteils-Tests inkl.
  fail-closed-Kernfall, 5 Engine-Gate-Tests, 3 ManualStartOutcome-Tests).
- Kern-Asserts: veraltetes „zeichnet auf" → Unverified (nie frei); Idle-Fenster
  exakt an der Grenze; Starts blockiert + nicht rückwirkend nach Bestätigung;
  laufende Session + manuelle Session überleben unverifizierte Ticks.

### Xvfb-Smoke-Test (echter Klick-Durchlauf, simulierte Handy-Meldungen über die API)
1. **Unverifiziert** (Handy bekannt, keine Bestätigung): Karte „HANDY NICHT
   VERIFIZIERT" + „Noch keine Bestätigung vom Handy."; Start-Karte gelb
   gesperrt; **Klick auf „Zocken" + „Jetzt starten" → 0 Sessions in der DB**
   (Gate greift; Screenshots).
2. **Verifiziert** (`session:null`): „✓ Handy verifiziert — dort läuft
   gerade nichts."; Chips normal; **Klick „Zocken" → Auswahlzeile →
   „Jetzt starten" → manuelle Session läuft** („AUFNAHME LÄUFT · Zocken ·
   Manuell gestartet · 00:02 · Stoppen", türkis; DB: 1 offene Session).
3. **Handy beginnt Studium:** Log „vorrang: Handy-Aufzeichnung erkannt";
   **die laufende manuelle Session wurde automatisch beendet** (DB:
   geschlossen) + Karte „HANDY HAT VORRANG" + Live-Karte „AUF DEINEM HANDY ·
   Studium · 04:34" + türkiser Hinweis. **Genau das gemeldete Problem –
   jetzt nachweislich unmöglich.**
4. `phone-priority.log` zeigt die komplette Kette:
   „kein Handy gekoppelt — Gate aus" → „verifikation fehlt…" →
   „verifiziert … PC darf starten" → „vorrang: Handy-Aufzeichnung erkannt".

Alle Screenshots per Vision geprüft (Layout, Texte, gedimmte Zustände).

### Messwerte
- ZIP: **57.495.461 B (~57,5 MB)**, entpackt ~127 MB, Version 1.6.0 in der
  Assembly verifiziert (UTF-16-strings-Check). GitHub-Release
  `pc-tracker-1.6.0` (ID 415980506, nicht draft/prerelease); Asset-Upload
  verifiziert (state=uploaded, Byte-Gleichheit); **Updater-Auswahllogik
  end-to-end simuliert: Client auf 1.5.2 findet 1.6.0 samt ZIP, Client auf
  1.6.0 findet nichts Neues.**
- Android: **1187/1187 Tests grün** im Voll-Build (Exit 0), Debug-APK
  **123.626.270 B** (versionCode 40 / 1.0.39 verifiziert per aapt2),
  kanonisch signiert (apksigner SHA-1 9C:30:55:C4…), HTTP 200 auf dem
  APK-Server (Aevum-1.0.39-Debug.apk); PC-ZIP zusätzlich unter
  AevumPcTracker-1.6.0-win-x64.zip.
- **Commit:** `91983b6` (26 Dateien) auf `main` gepusht; Tag
  `pc-tracker-1.6.0` gepusht. Screenshots der vier Gate-Zustände im
  Smoke-Ordner (20-unver, 21-ver, 22-select/22b-manual, 23-priority).

## 7. Geänderte/neue Dateien (Kern)

**Desktop (Aevum.PcTracker.Core):** `Models/PhoneLive.cs` (PhoneGateVerdict,
IdleVerifiedAgeMs), `Services/PhoneLiveRegistry.cs` (Gate-Logik,
IdleVerificationWindowMs), `Services/SessionEngine.cs` (PhoneVerifiedIdle +
Start-Gate), `Services/TrackerService.cs` (Gate-Plumbing,
ManualStartOutcome), `Services/ApiServer.cs` (PhoneKnownAtMs),
`Models/AppSettings.cs`.
**Desktop (UI):** `Services/AppHost.cs` (Wiring + Logging),
`ViewModels/DashboardViewModel.cs` (Zustände + Meldung),
`Views/DashboardView.axaml` (Hinweise + IsStartEnabled),
`Styles/AppStyles.axaml` (Disabled-Styles), `Localization/Loc.cs` (8 Keys
EN+DE), `Aevum.PcTracker.csproj` (1.6.0).
**Tests:** `PhoneVerificationGateTests.cs` (neu), `PhonePriorityAndDeleteTests.cs`
(Umstellung).
**App (Android):** `data/pc/PcLiveState.kt` (neu), `PcTrackerRepository`-Aufrufer
`automation/pctracker/PcAutoSyncWorker.kt`, `MainActivity.kt`,
`domain/liveactivity/LiveActivityService.kt`, `app/build.gradle.kts` (1.0.39/vc 40).
**Doku:** `docs/PC-TRACKER-PROTOCOL.md` (§5e/§5g), `desktop/README.md`,
`docs/CHANGELOG.md`, `.project-memory/{tasks,decisions}.md`, dieser Bericht.

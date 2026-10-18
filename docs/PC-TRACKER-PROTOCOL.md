# PC-TRACKER-PROTOCOL v1 — Aevum ↔ Aevum PC-Tracker

**Status:** verbindlich für die Windows-App (`desktop/`) und die Aevum-App (`app/`).
**Grundsatz:** Aevum bleibt offline-first. Die PC-Kopplung funktioniert ausschließlich
im **lokalen Netzwerk** (Handy und PC im gleichen WLAN/LAN). Es gibt **keinen**
Cloud-Dienst, **kein** Konto, **keine** Registrierung — die Daten wandern direkt
zwischen PC und Handy über das Heimnetz. Das ist eine bewusste Design-Entscheidung:
Die App ist im Play Store kostenlos für jedermann; ein Server-Betrieb für alle
Nutzer wäre weder kostenlos noch datenschutzfreundlich darstellbar.

---

## 1. Rollen

| Rolle | Wer | Was |
|---|---|---|
| **Tracker/Server** | Windows-App „Aevum PC-Tracker" | Erkennt Programme am PC, zeichnet Sessions auf, hält sie in SQLite, stellt sie über eine lokale HTTP-API bereit, antwortet auf Discovery |
| **Client/Pull** | Aevum (Android) | Findet den PC im Netz, koppelt sich (Code → Token), holt Sessions ab, importiert sie idempotent |

**Warum Pull (Handy holt, PC wartet):** Der PC läuft im Alltag durch (Tray +
Autostart). Das Handy holt die Daten, wann immer es im gleichen Netz ist — beim
Öffnen der PC-Tracking-Seite, per „Jetzt synchronisieren" und im Hintergrund-Takt.
Ein Push (PC sendet ans Handy) würde einen dauerhaften Empfangsdienst auf dem
Handy verlangen — Akku- und Zuverlässigkeitsproblem, deshalb bewusst nicht gebaut.

---

## 2. Discovery (UDP-Broadcast, Port 47826)

Damit der Nutzer **nichts abtippen muss**, sucht die Aevum-App den PC per
UDP-Broadcast:

1. Aevum sendet an `255.255.255.255:47826` und an die Broadcast-Adresse jedes
aktiven Netzwerk-Interfaces (UDP, ein Datagramm je Adresse):

```json
{ "magic": "AEVUM-PC-DISCOVER", "v": 1 }
```

2. Jeder PC-Tracker im Netz antwortet **per Unicast an die Quelladresse des
Absenders** — Sender und Empfänger nutzen denselben Socket (kein separater
replyPort nötig; das hält beide Seiten einfach und firewall-freundlich):

```json
{ "magic": "AEVUM-PC-ANNOUNCE", "v": 1, "deviceId": "…uuid…",
  "name": "DEVON-PC", "apiPort": 47825, "pairingActive": true }
```

3. Aevum zeigt die gefundenen PCs als Liste. Antippen → Pairing.

Enthält **keine Geheimnisse** — nur Name, Port, Geräte-ID. Die Discovery ist
Komfort; wenn sie im Netz scheitert (Firewall, Netz-Topologie), gibt es den
manuellen Weg (Adresse + Code eintippen). Beide Wege enden im selben Pairing.

**Rate-Limit Discovery:** max. 1 Anfrage/Sekunde (Client); Server antwortet nur
auf gültige `magic`-Datagramme ≤ 512 Bytes.

---

## 3. Pairing (Kurzcode → Token)

Der PC zeigt im Tab „Kopplung" einen **6-stelligen Code** (z. B. `482 913`) an.
Das Handy schickt ihn an den PC:

```http
POST /api/pair
Content-Type: application/json

{ "code": "482913", "clientName": "Devons Handy" }
```

Antwort bei Erfolg:

```json
{ "protocol": 1, "token": "…64 hex…", "deviceId": "…uuid…", "name": "DEVON-PC" }
```

- Der **Code ist nicht das Token.** Das Token (32 Byte hex) wird nur bei der
  Kopplung übertragen und danach in Aevum gespeichert; der Nutzer sieht es nie.
- Der Code gilt nur, solange der Kopplungsmodus aktiv ist (Kopplungs-Tab offen
  oder Schalter an) und **rotiert** nach jeder erfolgreichen Kopplung.
- **Rate-Limit:** max. 5 Fehlversuche pro 5 Minuten, dann 2 Minuten Sperre
  (Antwort `429` mit `retryAfterSeconds`). Fehlversuche werden gezählt, auch wenn
  der Code nach einer erfolgreichen Kopplung alt ist.
- Fehlerantwort immer `401` mit `{ "error": "invalid_code" }` (keine
  Unterscheidung „Code falsch" vs. „Kopplung nicht aktiv" nach außen — nur
  `pairingActive` in `/api/info` verrät den Modus).

---

## 4. HTTP-API (Port 47825, Token-Auth)

Basis: `http://<pc-ip>:47825`. Authentifizierung aller `/api/sessions`-Aufrufe:
Header `X-Aevum-Token: <token>` (oder `Authorization: Bearer <token>`).
`/api/info` und `/api/health` sind ohne Token (enthalten keine Daten).

### GET /api/health
```json
{ "ok": true }
```

### GET /api/info
```json
{ "app": "aevum-pc-tracker", "protocol": 1, "version": "1.2.0",
  "deviceId": "…uuid…", "name": "DEVON-PC", "apiPort": 47825,
  "pairingActive": false, "trackingPaused": false, "retentionDays": 365 }
```

### POST /api/pair
Siehe Abschnitt 3.

### POST /api/activities (ab PC-Tracker 1.1)
Die Aevum-App spiegelt bei jedem Sync ihre **Aktivitäten** zum PC — Grundlage
für das Aktivitäts-Dropdown im Regeln-Tab der Windows-App („Von deinem Handy").
Der PC ersetzt seinen Stand komplett (idempotenter Komplett-Abgleich):

```http
POST /api/activities
X-Aevum-Token: <token>
X-Aevum-Client: Devons Handy

{ "clientName": "Devons Handy",
  "activities": [ { "key": "gaming", "name": "Zocken", "icon": "🎮",
                    "categoryKey": "leisure" } ] }
```

Antwort: `{ "ok": true, "count": 12, "serverTime": … }`. Regeln, die auf eine
so gespiegelte Aktivität zeigen, werden in `/api/sessions` mit deren
`activityName`/`categoryKey`/`icon` ausgeliefert — so ordnet die App sie beim
Import per Namen wieder exakt derselben Aktivität zu. Eine ältere PC-Version
ohne diesen Endpunkt antwortet `404` — die App wertet das als „nicht
unterstützt" und macht mit dem Session-Sync weiter.

### Client-Name in Anfragen
Optional sendet die App bei `/api/sessions` und `/api/activities` den Header
`X-Aevum-Client: <Gerätename>` — die Windows-App zeigt damit „Handy verbunden —
<Name>" samt letztem Sync-Zeitpunkt an. Ohne Header bleibt nur der Zeitpunkt.

### GET /api/sessions?since=\<epochMs\>&limit=\<n\>
- `since` (optional, Default 0): liefert Sessions mit `endAt > since`.
- `limit` (optional, Default 200, max 500).
- Sortierung: `endAt` aufsteigend, dann `id` (stabil für Pagination).
- Antwort:

```json
{ "protocol": 1, "serverTime": 1760000000000,
  "device": { "id": "…uuid…", "name": "DEVON-PC" },
  "sessions": [
    { "id": "…uuid…", "program": "Minecraft", "programKey": "minecraft.exe",
      "activityKey": "gaming", "activityName": "Zocken",
      "categoryKey": "digital", "icon": "🎮",
      "startAt": 1759996800000, "endAt": 1759998600000,
      "timezoneId": "Europe/Berlin" }
  ] }
```

- `endAt` ist **immer gesetzt** (nur abgeschlossene Sessions). Eine Session,
  deren Ende noch aussteht (Threshold läuft), wird nicht geliefert.
- Pagination: Ist `sessions.length == limit`, holt der Client mit
  `since = endAt der letzten Session` weiter (Duplikate bei gleichem `endAt`
  sind harmlos — der Import ist idempotent, siehe Abschnitt 6).
- Ein **Pausen-/Leerlauf-Schnitt** (Inaktivität) ist ein normales Session-Ende;
  Aevum sieht nur mehrere Sessions desselben Programms hintereinander.
- **Allgemeine Aufzeichnung (ab 1.2.0):** Sessions der allgemeinen Aktivität
  tragen den festen `programKey` `__general__` (Anzeigename „General use") —
  ansonsten normale Sessions, der Import läuft unverändert.

### Fehlerformat
```json
{ "error": "unauthorized" }        // 401 — fehlendes/falsches Token
{ "error": "invalid_code" }        // 401 — Pairing
{ "error": "rate_limited", "retryAfterSeconds": 120 }  // 429
{ "error": "protocol_mismatch" }   // 409 — Client-Version passt nicht
```

---

## 5. Aktivitätskatalog (PC-Seite)

Die Windows-App bringt eine **fertige Auswahl** mit (Devon: „Alles was geht mit
Vorauswahl, so wenig wie möglich zum selber eingeben"). Stabile Keys, damit die
Zuordnung auch nach Umbenennungen hält:

| key | Name | categoryKey | icon |
|---|---|---|---|
| `gaming` | Zocken | digital | 🎮 |
| `work` | Arbeit | work | 💼 |
| `programming` | Programmieren | work | 💻 |
| `video` | Video schauen | digital | 📺 |
| `music` | Musik hören | leisure | 🎧 |
| `browsing` | Surfen | digital | 🌐 |
| `learning` | Lernen | learning | 📚 |
| `reading` | Lesen | leisure | 📖 |
| `communication` | Kommunizieren | relationships | 💬 |
| `creating` | Kreatives | leisure | 🎨 |
| `other` | Sonstiges | unknown | ⚙️ |

Der Nutzer ordnet jedem Programm **einmal** eine Aktivität zu
(Beispiel: `Minecraft.exe → gaming`). Die Sessions tragen `activityKey` +
`activityName` + `categoryKey` + `icon` — Aevum braucht dadurch **kein**
Vorwissen über den Katalog.

### 5b. Aktivitäts-Spiegel vom Handy (ab PC-Tracker 1.1)

Sobald die App synchronisiert hat (Abschnitt 4, `POST /api/activities`), zeigt
das Aktivitäts-Dropdown der Windows-App **zuerst die Aktivitäten aus Aevum**
(auch selbst angelegte, z. B. „Zocken"), darunter den lokalen Katalog ohne
Namens-Dubletten. So gilt überall dieselbe Zuordnung: Die App erstellt die
Aktivität, der PC wählt sie beim Anlegen einer Regel aus, und der Import findet
sie beim Sync über den Namen wieder.

### 5c. Aufnahme-Modus pro Regel (ab PC-Tracker 1.1)

Jede Regel hat einen Modus:

- **`foreground` (Standard):** Aufgezeichnet wird nur, solange das Programm das
  Vordergrundfenster ist (klassisches Verhalten, inkl. Threshold-Fenster).
- **`while_running`:** Aufgezeichnet wird, solange der **Prozess läuft** — auch
  minimiert/im Hintergrund (Musik, Spiele-Launcher). Spezifik:
  - Start: sobald der Prozess läuft (Start = erste Sicht; nicht rückwirkend).
  - Übernimmt ein **anderes zugeordnetes** Programm den Vordergrund, endet die
    Hintergrund-Session **zu diesem Zeitpunkt**; nach dessen Ende läuft sie
    weiter (Neustart an das Session-Ende gekappt, keine großen Überlappungen).
  - Ende wie sonst: Prozess weg länger als der Threshold bzw. Inaktivitäts-/
    Pausen-Regeln. Es bleibt immer höchstens **eine** Session aktiv.

### 5d. Manuell gestartete Aktivitäten (ab PC-Tracker 1.3.0)

Das PC-Dashboard hat eine Karte **„Aktivität starten"**: Die Aktivitäten kommen
aus Aevum (Spiegel aus 5b — wurde noch nie synchronisiert, zeigt die Karte den
lokalen Katalog als Rückfall). Ein Klick auf eine Aktivität öffnet die
Start-Optionen **Sofort / +5 / +15 / +30 Minuten**; bei Vorlaufzeit läuft ein
abbrechbarer Countdown. So startet der Nutzer am PC dieselben Aktivitäten wie
am Handy.

**Datenmodell:** Eine manuelle Session ist eine normale Session mit dem festen
`programKey` **`__manual__`** (Anzeigename „Manual start") und `activityKey` =
ActivityType-ID der App. Der Import in Aevum (Abschnitt 6) läuft **unverändert**:
`title = activityName`, `description = "Manual start"` (am PC lokalisiert
angezeigt: „Manuell gestartet"). Kein Protokoll-Sprung nötig — die Sessions-API
liefert manuelle Sessions wie jede andere, sobald sie abgeschlossen sind
(`endAt` gesetzt).

**Verhaltensregeln (in der Engine festgenagelt):**
- Manuelle Starts haben **Vorrang**: Eine laufende Regel-Session endet mit dem
Startzeitpunkt; die manuelle Session übernimmt.
- Während sie läuft, starten **weder Regel- noch allgemeine Aufzeichnung** —
es wird genau eine Aktivität aufgezeichnet.
- **Idle beendet sie nicht** (eine Ansage wie „Arbeit" gilt, bis der Nutzer sie
beendet); beendet wird sie über den **Stop-Knopf** im Dashboard (Ende =
jetzt), die **Pause** oder das **App-Ende** (Ende = letzte Sicht).
- Endet sie, zählt die Sichtbarkeit der Programme **ab jetzt** — eine folgende
Regel-Session beginnt nicht rückwirkend und überlappt die manuelle Session
nicht.
- Wird der PC während einer manuellen Session beendet/neu gestartet, schließt
die Start-Reparatur sie am letzten Lebenszeichen (wie jede offene Session).

**Regelmäßiger Abgleich:** Der Abruf bleibt vollständig auf der Handy-Seite
(Architektur-Entscheidung, siehe Abschnitt 1): Die Aevum-App synchronisiert im
WLAN **automatisch alle 15 Minuten** (WorkManager, UNMETERED) plus einem
**sofortigen** stillen Sync beim App-Öffnen (ab App 1.0.34: In-Process-Sync mit
2-Minuten-Drossel statt nur WorkManager-Kick) — sie holt Aufzeichnungen ab und
spiegelt zugleich den Aktivitätskatalog zum PC. Das PC-Dashboard zeigt den
Stand an („Letzte Synchronisierung …, N Aktivitäten vom Handy").

---

### 5e. Live-Kanal: laufende Handy-Aufzeichnung am PC (ab PC-Tracker 1.4.0 / App 1.0.34)

Devon: „Also auf meinem handy zeichne ich gerade studium auf. Aber das wird
auf dem dashboard in der desktop anwendung gar nicht angezeigt. Das sollte es
aber. Auch mit buttons zum stoppen und pausieren."

**`POST /api/live`** (Header `X-Aevum-Token`, `X-Aevum-Client` wie üblich)

Während einer laufenden Aufzeichnung meldet die App alle wenigen Sekunden
ihren Zustand; die Antwort enthält den ggf. am PC ausgelösten Steuerbefehl.
Das Pull-Modell bleibt: **Nur das Handy initiiert** — der PC hält weiterhin
nie eine Verbindung zum Handy offen.

Anfrage:

```json
{
  "clientName": "…",
  "session": {
    "sessionId": "…",          // null = keine Session (Karte verschwindet)
    "activityKey": "studium",
    "activityName": "Studium",
    "icon": "…",
    "categoryKey": "…",
    "startAtMs": 1760000000000,
    "isPaused": false,
    "blockEndAtMs": 0          // bei Pause: Ende des Aufzeichnungsblocks
  }
}
```

Antwort:

```json
{ "command": "pause" }   // "resume" | "stop" | null (nichts zu tun)
```

- **Stop/Pause am PC:** Das Dashboard zeigt die Handy-Session als Live-Karte
  mit Pause/Fortsetzen- und Stop-Knopf. Ein Klick legt den Befehl ab; die
  nächste Meldung (wenige Sekunden) holt ihn ab und führt ihn genau wie den
  jeweiligen Notification-Knopf aus.
- **Befehl bleibt bis zur Bestätigung erhalten:** Funkloch oder App noch
  nicht wach → bei jeder weiteren Meldung wird erneut geliefert; erst der
  passende Folgezustand räumt ihn ab.
- **„Fortsetzen" nach Pause** startet bewusst eine neue Session (Split,
  eigener Session-Fokus) — die PC-Seite übernimmt den neuen Stand automatisch.
- **Verschwinden:** Meldet die App `session: null` oder bleibt sie länger als
  die TTL still (App beendet, Handy aus), räumt der PC die Karte weg
  (`PhoneLiveRegistry.GoneAfter`).
- **Still ohne Kopplung:** Keine Kopplung gespeichert oder Schalter
  „Automatisch synchronisieren" aus → die App sendet nichts; ohne laufende
  Session löst auch der Relay-Takt kein HTTP aus. **Ab App 1.0.39** meldet
  die App zusätzlich nach jedem erfolgreichen Sync ausdrücklich ihren Stand
  — auch „nichts läuft" (Verifikations-Gate, siehe §5g).

---

### 5f. Handy-Vorrang: nur eine Aufzeichnung zur Zeit (ab PC-Tracker 1.5.0)

Devon: „Offensichtlich sollte die pc aufzeichnung gestoppt werden, wenn etwas
am handy beginnt aufzuzeichnen, dabei ist es egal ob es nur die allgemeine pc
aktivität (bspw Digital) ist, oder ein bestimmtes programm […] Also bedenke
dass immer nur eine sache gleichzeitig aufgezeichnet werden können soll und
handy hat vorrang."

- **Regel:** Läuft am Handy eine Aufzeichnung (frische Live-Meldung im Sinne
  von §5e), beendet der PC **jede** eigene Session sofort (Regel-Session,
  allgemeine Aufzeichnung — auch eine manuell gestartete) und startet nichts
  Neues, bis das Handy fertig ist. Danach zählt die Sichtbarkeit frisch ab
  dem Handy-Ende (keine rückwirkende Überlappung: die nächste PC-Session
  beginnt erst nach dem Handy, nicht bei der „ersten Sicht" von vorhin).
- **Quelle:** derselbe Live-Kanal (`POST /api/live`). Als „zeichnet auf"
  zählt eine Session, deren letzte Meldung nicht älter als die
  Stale-Schwelle ist (45 s) — bleibt die App länger stumm (Absturz, Handy
  aus), ist der PC wieder frei. Kein neuer Endpunkt.
- **Manueller PC-Start während Handy-Aufzeichnung:** wird abgelehnt (Engine
  und UI) — die Start-Karte zeigt „dein Handy hat Vorrang" und ist
  deaktiviert, damit keine Session aufblitzt, die sofort wieder enden müsste.
- **UI:** Die Live-Karte zeigt im Vorrang-Fall „Dein Handy zeichnet auf —
  der PC hält sich zurück"; eine laufende Aufzeichnung (PC oder Handy) ist
  mit türkisem Rahmen, RECORDING-Badge und wanderndem Schimmer deutlich
  markiert (§ 1.5.0, Devon: „oben sollte das wirklich ganz deutlich sichtbar
  sein, mit farbe und schimmer").

---

### 5g. Verifikations-Gate — der PC startet nur mit bestätigtem Handy-Stand (ab PC-Tracker 1.6.0 / App 1.0.39)

Devon: „Die desktop app zeichnet immer noch manchmal auf obwohl das handy
gerade aufzeichnet. […] Am besten sollte die desktop app erst erlauben,
überhaupt irgendwas aufzuzeichnen, also sowohl das automatische als auch
das manuelle, wenn die desktop app verifiziert hat, das auf dem handy
nichts läuft, ansonsten kommt eine meldung das der stand des handys nicht
verifiziert werden konnte."

- **Fail-closed:** Ohne FRISCHE Bestätigung „am Handy läuft nichts" startet
  der PC **nichts Neues** — Regel-, allgemeine und manuelle Aufzeichnung.
  Ein veraltetes „zeichnet auf" (> Prioritätsfenster) zählt NIE als frei;
  genau das macht die früheren Minuten-Falschaufzeichnungen strukturell
  unmöglich.
- **Quelle der Bestätigung:** `POST /api/live` mit `session: null` — die App
  sendet das jetzt ausdrücklich: (a) beim Session-Ende (unverändert),
  (b) nach jedem erfolgreichen Sync (Auto-Sync ~15 min + App-Öffnen-Kick —
  der neue Verifikations-Ping ab App 1.0.39). Gültigkeit:
  `IdleVerificationWindowMs` = 30 Minuten (deckt zwei verpasste Sync-Takte
  inkl. Doze ab).
- **Gate-Aktivierung:** erst nach dem ersten echten Handy-Kontakt
  (`AppSettings.PhoneKnownAtMs`, gesetzt von jeder token-authentifizierten
  Anfrage) — ein PC ganz ohne Kopplung zeichnet wie immer auf.
- **Laufende Sessions** werden NICHT abgeschnitten, wenn die Bestätigung
  abreißt; beendet werden sie weiterhin nur vom frischen Handy-Vorrang
  (§5f) bzw. den normalen Regeln. Grund: Eine Sync-Verzögerung darf keine
  gesunde PC-Aufzeichnung abschneiden; echte Doppel-Aufzeichnungen
  verhindert der Vorrang, sobald wieder eine Meldung ankommt.
- **UI:** Dashboard-Karte „HANDY NICHT VERIFIZIERT" (mit letzter
  Bestätigung bzw. letztem Lebenszeichen), Start-Karte gesperrt mit gelbem
  Hinweis, Chips + „Jetzt starten" sichtbar deaktiviert; bei bestätigtem
  Stand dezent „✓ Handy verifiziert". Jeder Urteils-Wechsel landet im
  `phone-priority.log`. Kein Protokoll-Sprung — derselbe Endpunkt.
- **PC 1.7.1 — Start-Schonfrist:** Upgrade aus einer Version vor 1.6 → das
  Feld `PhoneKnownAtMs` fehlt noch, das Gate wäre in den ersten Sekunden
  AUS und der PC zeichnete blind „Digital" auf (Devon-Befund). Jetzt gilt
  für Bestandsinstallationen (älter als 24 h) eine **60-s-Schonfrist** nach
  dem Start („nicht verifiziert", kein lokaler Start); kommt kein
  Handy-Stand, zeichnet der PC danach normal. Frische Installationen ohne
  Handy sind nicht betroffen. Zusätzlich schärft ein erfolgreiches
  `POST /api/pair` das Gate sofort (`PhoneKnownAtMs` = Kopplungszeit).

**Release-Kanal (wichtig für künftige Versionen):** „Nach Updates suchen" im
PC-Tracker liest die **öffentlichen GitHub-Releases** (`api.github.com/repos/
Renegaded66/Aevum/releases`, Tag-Präfix `pc-tracker-`, Asset `*win-x64.zip`).
Eine Version nur auf dem LAN-Server zu veröffentlichen reicht NICHT — sie
muss als Release `pc-tracker-<version>` mit dem Windows-ZIP existieren.

---

### 5h. Startbefehl vom PC — der Desktop befiehlt nur noch (ab PC-Tracker 1.7.0 / App 1.0.42)

Devon: „Die desktop app will ich echt so haben, dass der desktop nur befehle
ans handy sendet. […] Ich möchte stattdessen, wenn ich auf dem desktop was
aufzeichne, dass das auch auf dem handy sichtbar ist. Der desktop soll also
sozusagen nur ans handy senden ‚Du musst jetzt activity x starten/stoppen'.
Trotzdem soll es natürlich auch weiterhin auf dem desktop visualisiert
werden."

**`GET /api/commands`** (Header `X-Aevum-Token`; Antwort einmalig)

```json
{ "protocol": 1, "serverTime": 1760000000000,
  "command": "start", "activityKey": "learning", "issuedAt": 1760000000000 }
```

`command` ist `null`, wenn nichts anliegt. Ein Startbefehl wird **genau
einmal** ausgeliefert (kein Doppel-Start bei verlorener Antwort) und
verfällt nach `CommandTtlMs` (60 s) — ein viel später eintreffender Start
wäre eine Überraschung, keine Absicht mehr. Pull-Modell: Die App holt ab,
der PC baut nie eine Verbindung zum Handy auf.

- **Wann entsteht ein Befehl:** Der Nutzer startet im PC-Dashboard eine
  Aktivität und es ist ein Handy bekannt (`PhoneKnownAtMs > 0`). Der PC
  legt `start <activityKey>` ab und zeichnet **selbst nichts** auf. Ohne
  bekanntes Handy startet der PC wie bisher lokal.
- **`activityKey`** ist die ActivityType-ID der App (derselbe Schlüssel wie
  im Aktivitäts-Spiegel, §5b).
- **Abholung:** Der dauerhaft laufende App-Aufzeichnungs-Dienst der App
  pollt alle 6 s (Bild an) bzw. 45 s (Bild aus); zusätzlich nach jedem
  Auto-Sync als Fallback. Ist kein PC gekoppelt oder aus, passiert still
  nichts.
- **Ausführung am Handy:** Aktivität existiert → Session starten
  (`sourceType = PC_REMOTE`, Notiz „Vom PC gestartet") und Live-Dienst
  starten; dieselbe Aktivität läuft schon → nicht neu starten; Schlüssel
  unbekannt (gelöscht) → nichts tun und im Log vermerken.
- **Visualisierung:** unverändert über den Live-Kanal (§5e) — die vom PC
  gestartete Session erscheint als Handy-Karte samt Timer und
  Pause/Stop-Knöpfen. „Stop" am PC schickt `stop` über denselben Kanal.
- **Verifikations-Gate (§5g) gilt nur noch für LOKALE Starts.** Ein Befehl
  ans Handy kann nichts doppelt starten — das Handy entscheidet selbst.

---

## 6. Import in Aevum (idempotent)

**Ist-Stand der Umsetzung (bewusst minimal, kein Schema-Update):**

- **DataSource:** pro PC eine Zeile `data_source` — `id = "pc_<deviceId>"`,
  `type = "pc_tracker"`, `name = <PC-Name>`, `config_json = { "host", "port",
  "deviceId" }`. Der Sync-Stand liegt im `last_sync_at`-Feld der DataSource
  (und in den App-Prefs).
- **external_id der Session:** `"pc_<Session-UUID des PCs>"`.
  Gleiche externalId = gleicher Datensatz → **UPDATE statt INSERT** (idempotent,
  wiederholter Sync erzeugt keine Duplikate).
- **Zuordnung Aktivität → ActivityType:** 1) existierender ActivityType mit
  gleichem Namen (case-insensitive), 2) existierender Typ mit der stabilen ID
  `"pc_<activityKey>"`, 3) sonst **anlegen** (Name = `activityName`, Kategorie
  aus `categoryKey` wenn im Seed bekannt, Icon vom PC, `isSystem = false`).
  Selbst angelegte Aktivitäten aus Aevum werden dadurch automatisch gefunden —
  genau deshalb ist der Aktivitäts-Spiegel (5b) wichtig.
- **Session-Felder:** `title = activityName`, `description = programName`,
  `sourceType = "PC_TRACKER"`, `createdBy = "PC_IMPORT"`, `timezoneId` vom PC,
  `confidence = 1.0`, `externalId` siehe oben.
- **Nutzer-Änderungen gewinnen:** Wer eine importierte Session in Aevum
  bearbeitet hat (`isUserEdited`), wird bei späteren Syncs **nicht** überschrieben.
- **Cursor:** `lastSyncAt`, mit 1 h Überlappung beim `since`-Wert — Grenzfälle
  sind dank Idempotenz harmlos.
- **Kein Schema-Update:** Alles nutzt bestehende Tabellen (DB-Version bleibt).

---

## 7. Sicherheit (bewusst klein gehalten)

- Zugriff nur im LAN; Server bindet auf alle Interfaces, aber **jede** Daten-
  anfrage verlangt das Token (32 Byte Zufall, `RandomNumberGenerator`).
- Pairing nur mit aktivem Kurzcode + Rate-Limit; Token-Weitergabe nur bei
  erfolgreicher Kopplung.
- **Kein HTTPS:** Im LAN mit Token als ausreichend bewertet; ein selbst
  signiertes Zertifikat wäre für „jedermann" eine Fehlerquelle (Warn-Dialoge,
  Vertrauensfragen) — bewusst weggelassen statt halb umgesetzt.
- **Kein Cloud-Relay, keine Portfreigabe, kein Internet:** Funktioniert genau
  dann, wenn Handy und PC im gleichen Netz sind — andernfalls wartet der Sync
  (Daten bleiben lokal beim PC, nichts geht verloren).

## 8. Bewusst NICHT gebaut (statt halbherzig)

| Nicht gebaut | Warum |
|---|---|
| Cloud-Sync / Relay über Internet | Nicht kostenlos für „jedermann" darstellbar, widerspricht offline-first |
| QR-Kamera-Scan am Handy | Nicht live testbar in dieser Umgebung; stattdessen Discovery (testbar) + manuelle Eingabe als zweiter, unabhängiger Weg |
| Push vom PC ans Handy | Bräuchte Dauer-Empfangsdienst am Handy (Akku, Zuverlässigkeit) — gelöst über **Pull**: die App holt Befehle im Takt ab (`GET /api/commands`, §5h) |
| EXE-Icons aus Windows extrahieren | Fehleranfällige Shell-Interop für Kosmetik — stattdessen Icons am Aktivitätskatalog |
| HTTPS / Zertifikate | Siehe oben — Fehlerquelle ohne echten Mehrwert im LAN |

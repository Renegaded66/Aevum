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
{ "app": "aevum-pc-tracker", "protocol": 1, "version": "1.1.0",
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
| Push vom PC ans Handy | Bräuchte Dauer-Empfangsdienst am Handy (Akku, Zuverlässigkeit) |
| EXE-Icons aus Windows extrahieren | Fehleranfällige Shell-Interop für Kosmetik — stattdessen Icons am Aktivitätskatalog |
| HTTPS / Zertifikate | Siehe oben — Fehlerquelle ohne echten Mehrwert im LAN |

# M22 — Abschlussbericht: Ziele live, Dashboard-Feinschliff, Desktop befiehlt nur noch

**Datum:** 2026-10-18
**Versionen:** Aevum 1.0.42 (versionCode 43) · Aevum PC-Tracker 1.7.0
**DB-Schema:** unverändert v47 (keine Migration)

---

## 1. Anlass

Drei Punkte aus einer Nachricht:

1. „Bei den Zielen wird anscheinend nicht das mit einbezogen, was aktuell noch
   aufzeichnet. Ich habe studium seit 25 Minuten am aufzeichnen aber das ziel
   wurde nicht aktualisiert, erst als ich studium einmal pausiert habe."
2. „Unten der Punkt ‚Vielfalt dein tag verteilt sich auf 4 bereiche' kann
   komplett weg. Die Position des Güte verlaufs kann im dashboard nach ganz
   unten."
3. „Die desktop app will ich echt so haben, dass der desktop nur befehle ans
   handy sendet. Ich habe nämlich gerade am desktop ‚Studium' gestartet, aber
   da wurde das nur auf dem desktop angezeigt. Ich möchte stattdessen, wenn
   ich auf dem desktop was aufzeichne, dass das auch auf dem handy sichtbar
   ist. Der desktop soll also sozusagen nur ans handy senden ‚Du musst jetzt
   activity x starten/stoppen'. Trotzdem soll es natürlich auch weiterhin auf
   dem desktop visualisiert werden."

---

## 2. Ziele live mitzählen (Punkt 1)

### 2.1 Ursache (zwei Fehler, code-belegt)

1. **`GoalProgressAnalytics.buildBuckets` übersprang offene Sessions
   komplett:** `if (s.deletedAt != null || s.endAt == null) return@forEach`.
   Eine laufende Aufzeichnung hat `endAt == null` — sie trug also bis zum
   Pausieren/Stoppen **0 ms** bei. Erst der Pause-Pfad setzt `endAt` und
   machte die Zeit sichtbar (genau der beobachtete Effekt).
2. **Kein Neu-Rechnen ohne Datenbank-Änderung:** Die ViewModels kombinieren
   nur DB-Flows; die laufende Session bleibt EINE unveränderte Zeile, also
   kam keine neue Emission — selbst mit Fix 1 wäre die Anzeige eingefroren.

### 2.2 Fix

- Zeit-Ziele rechnen offene Sessions **bis „jetzt"** (`end = s.endAt ?: now`);
  pausierte Sessions haben `endAt` gesetzt und wachsen dadurch nicht weiter.
  Zähl-Ziele (Anzahl) bleiben bewusst bei „abgeschlossen" — eine angefangene
  Aufgabe soll nicht schon als erledigt zählen.
- Neuer [LiveRefreshTicker] (10-s-Signal) in **allen drei** Ziel-ViewModels
  (`GoalsViewModel`, `DashboardGoalsViewModel`, `GoalDetailViewModel`) — die
  Auswertung läuft im Takt neu, der Balken/Text füllt sich sichtbar weiter.
- Test: `M22 laufende Session ohne endAt zaehlt bis jetzt` (90 min → 1,5 h)
  und `M22 pausierte oder beendete Sessions wachsen nicht weiter`.

---

## 3. Dashboard (Punkt 2)

- **„Vielfalt"-Insight entfernt** („Dein Tag verteilt sich auf N Bereiche") —
  `buildInsights` kennt nur noch Reflexions-Hinweis bzw. Ruhe-Hinweis.
- **Güte-Verlauf ganz nach unten** verschoben (letzte Karte im Sheet), weiterhin
  nur am heutigen Tag sichtbar.

---

## 4. Desktop befiehlt nur noch (Punkt 3)

### 4.1 Architektur

Der PC hatte bisher nur einen **Live-Relay** (§5e): Während einer
HANDY-Session meldet die App ihren Zustand an `POST /api/live` und bekommt
Pause/Fortsetzen/Stopp zurück. Ein Start am PC lief komplett lokal — das
Handy erfuhr nichts.

Neu (PC 1.7.0 / App 1.0.42), **Pull-Modell bleibt** (der PC baut nie eine
Verbindung zum Handy auf):

```
PC-Dashboard: „Studium starten"
        │  (Handy bekannt?)
        ▼
PhoneLiveRegistry.RequestRemoteStart("learning")      ← Befehl liegt bereit (TTL 60 s)
        │
        ▼   GET /api/commands   (App-Aufzeichnungs-Dienst, alle 6 s / 45 s Bild aus)
App: PcTrackerClient.fetchCommand → PcRemoteCommandPolicy
        │
        ▼   Aktivität existiert? läuft sie schon?
LiveActivityManager.start(sourceType = PC_REMOTE, Notiz „Vom PC gestartet")
        │
        ▼   Live-Kanal §5e: App meldet Session an POST /api/live
PC-Dashboard: Live-Karte mit Timer + Pause/Stop (unverändert)
```

### 4.2 PC-Seite

- `PhoneLiveRegistry`: `RequestRemoteStart`, `HasRemoteStart`,
  `TakeRemoteStart` (einmalige Auslieferung, TTL wie Steuerbefehle).
- `GET /api/commands` (token-pflichtig): `{command:"start"|null,
  activityKey, issuedAt, serverTime}`.
- `TrackerService.StartManualActivity`: Ist ein Handy bekannt
  (`AppSettings.PhoneKnownAtMs > 0`), wird der Start als Befehl abgelegt →
  neues Ergebnis `SentToPhone`; der PC legt **keine** lokale Session an.
  Ohne Handy startet der PC wie bisher lokal (inkl. Verifikations-Gate).
- Dashboard: eigener Hinweis „✓ Ans Handy gesendet …" bis die Live-Karte
  erscheint; Erklärzeile „Die Aufzeichnung läuft auf deinem Handy …";
  Start-Karte bleibt bei gekoppeltem Handy immer bedienbar (das Gate gilt
  nur noch für lokale Starts). **Stop** beendet eine lokale Session oder —
  wenn keine läuft — die Handy-Session über den Live-Kanal.
- Jeder Befehl landet in `phone-commands.log` (Diagnose).

### 4.3 Handy-Seite

- `PcTrackerClient.fetchCommand` (GET /api/commands, Token).
- `PcRemoteCommandPolicy` (pure Logik): unbekannte Aktivität → nichts;
  dieselbe Aktivität läuft schon → nicht neu starten; sonst starten.
- `PcRemoteCommandRunner`: holt ab und führt aus (Session + Live-Dienst).
- **Abhol-Takt:** `AppTrackingService` (dauerhaft laufender FGS) pollt alle
  6 s bei aktivem Bildschirm, 45 s im Standby; zusätzlich nach jedem
  Auto-Sync (`PcAutoSyncWorker`) als Fallback, falls der Dienst nicht läuft.
- Fehler bleiben still (PC aus / WLAN weg ist der Normalfall).

### 4.4 Bewusst nicht geändert

- Die **automatische** Programm-Aufzeichnung des PCs (Fenster-Regeln) bleibt
  lokal + Sync — das kann das Handy nicht sehen (Windows-Fenster).
- Der PC zeichnet bei einem Remote-Start **nichts** lokal auf (kein
  Doppel-Zählen, kein doppelter Import).

---

## 5. Tests

- **Handy:** 1258 Tests, 0 Fehler, 1 skipped. Neu:
  `PcRemoteCommandPolicyTest` (6), `PcTrackerClientTest` +2 (`fetchCommand`),
  `GoalProgressAnalyticsTest` +2 (live/pausiert).
- **Desktop:** 156 Tests, 0 Fehler. Neu:
  `PhoneLiveRegistryTests` +2 (einmalige Auslieferung, TTL),
  `ApiServerTests` +3 (`/api/commands`: 401, Lieferung genau einmal, leer),
  `TrackerServiceTests` +2 (`SentToPhone` ohne lokale Session, lokaler
  Fallback ohne Kopplung).

---

## 6. Release

| Artefakt | Wert |
|---|---|
| APK | `Aevum-1.0.42-Debug.apk`, 124.328.926 B (118,6 MB), SHA-256 `4da87c42…be1d93`, kanonisch signiert |
| PC-Tracker | `AevumPcTracker-1.7.0-win-x64.zip`, 55 MB (self-contained win-x64) |
| Download | http://192.168.178.40:8098/ (beide HTTP 200) |
| Protokoll | `docs/PC-TRACKER-PROTOCOL.md` §5h neu |

## 7. Verifikation am Gerät

1. **Ziel live:** „Studium" starten, Ziele-Screen/Dashboard öffnen → der
   Fortschritt wächst alle ~10 s, ohne Pause/Stop.
2. **Dashboard:** kein „Vielfalt"-Block mehr; Güte-Verlauf als letzte Karte.
3. **Desktop → Handy:** PC-Tracker 1.7.0 starten, im Dashboard „Studium"
   wählen → „Jetzt starten". Handy in der Hand: Session startet dort in
   wenigen Sekunden (Notiz „Vom PC gestartet"), PC-Dashboard zeigt die
   Live-Karte. „Stop" am PC beendet sie am Handy.

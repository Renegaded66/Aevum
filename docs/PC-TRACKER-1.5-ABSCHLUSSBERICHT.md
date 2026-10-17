# Abschlussbericht — PC-Tracker 1.5.0: Handy-Vorrang, Aufräumen, Sichtbarkeit

Datum: 2026-10-17 · Auftrag aus einer Nutzernachricht (siehe §0)

---

## 0. Der Auftrag (Zitat)

> „Offensichtlich sollte die pc aufzeichnung gestoppt werden, wenn etwas am
> handy beginnt aufzuzeichnen, dabei ist es egal ob es nur die allgemeine pc
> aktivität (bspw Digital) ist, oder ein bestimmtes programm, sobald ich am
> handy was starte hat das vorrang und die pc aufzeichnung sollte gestoppt
> werden. Außerdem sollte bei Dashboard allgemeine Aufzeichnung auch schon
> die activity ausgewählt werden können bevor ich den schieberegler
> aktiviere, damit nicht direkt eine falsche activity aufgezeichnet wird.
> Ich will auch in der desktop app aufzeichnungen aus dem verlauf entfernen
> können. Und oben wenn gerade eine Aufzeichnung stattfindet dann sollte das
> wirklich ganz deutlich sichtbar sein, mit farbe und schimmer oder so.
> Also bedenke dass immer nur eine sache gleichzeitig aufgezeichnet werden
> können soll und handy hat vorrang"

Vier Features + ein Grundprinzip:
1. **Handy-Vorrang** (Grundprinzip): Handy-Aufzeichnung stoppt die PC-Aufzeichnung, egal welcher Art.
2. **Allgemeine Aufzeichnung**: Activity wählen können, BEVOR der Schalter aktiviert wird.
3. **Verlauf**: Aufzeichnungen einzeln entfernen.
4. **Sichtbarkeit**: Laufende Aufzeichnung oben ganz deutlich (Farbe + Schimmer).

---

## 1. Was gebaut wurde

### 1.1 Handy-Vorrang — „immer nur eine Sache, Handy zuerst"

**Engine (`SessionEngine`):** Neues Tick-Feld `PhoneRecording`. Steht es an,
gilt Schritt „0" VOR jeder anderen Logik:

- Jede laufende PC-Session endet **sofort** (Ende = jetzt) — Regel-Sessions,
  die allgemeine Aufzeichnung und auch eine **manuell** gestartete Session
  (der Vorrang ist eine Ansage des Nutzers: keine Ausnahme).
- Es startet **nichts Neues**, solange das Handy aufzeichnet; die
  Sichtbarkeits-Zähler werden jeden Tick frisch gestellt, sodass die nächste
  PC-Session erst NACH dem Handy-Ende beginnt — nicht rückwirkend bei der
  „ersten Sicht" von vorhin (sonst würde die PC-Session die Handy-Zeit
  doppelt beanspruchen).
- Der Block sitzt bewusst vor der Session-Pflege, damit die gerade beendete
  Session keinen Heartbeat-Doppeleintrag mehr bekommt (im Test aufgefallen
  und korrigiert).

**Tracker (`TrackerService`):** Optionaler Provider `phoneRecording(now)`.
AppHost verdrahtet ihn auf den Live-Kanal (`PhoneLiveRegistry`, §5e):
„zeichnet auf" = Session vorhanden UND Meldung frischer als die
Stale-Schwelle (45 s). Bleibt die App länger stumm (Absturz, Handy aus), ist
der PC automatisch wieder frei — ehrlich, ohne neue Endpunkte.

**Manueller PC-Start blockiert:** `StartManualActivity` liefert `false` und
startet nichts, solange das Handy aufzeichnet (kein 2-Sekunden-Aufblitzen,
das der nächste Tick sofort beenden müsste). Die Start-Karte ist deaktiviert
und zeigt den Hinweis „dein Handy hat Vorrang".

### 1.2 Allgemeine Aufzeichnung: Activity vor dem Schalter

Die Aktivitäts-Auswahl (`ComboBox`) im Dashboard hing bisher am Zustand des
Schalters (`IsVisible = GeneralEnabled`) — eingeschaltet wurde zuerst, und
ohne Auswahl griff automatisch die erste Aktivität. Jetzt ist die Auswahl
**immer sichtbar**, sobald gespiegelte Aktivitäten vorhanden sind: erst
wählen, dann einschalten. Die Session startet mit exakt der gewählten
Aktivität (live verifiziert, siehe §2).

### 1.3 Verlauf: Aufzeichnungen entfernen

- `SessionStore.DeleteSessionById(id)` — löscht **nur abgeschlossene**
  Sessions (`end_at IS NOT NULL`); laufende sind auf Store-Ebene geschützt
  (sie werden gerade geschrieben und kämen beim nächsten Heartbeat zurück).
- `SessionRowViewModel` ist jetzt beobachtbar und trägt die zweistufige
  Löschung: erster Klick (✕) zeigt die Inline-Bestätigung „Entfernen?
  Ja/Nein" (gleiches Muster wie der Zugang-Reset), „Ja" löscht und lädt den
  Verlauf neu. Ohne Handler (Dashboard-Zeilen) gibt es keinen Knopf.
- Gelöschte Aufzeichnungen werden vom nächsten Sync **nicht mehr** an das
  Handy ausgeliefert (die Sync-Auslieferung basiert auf den abgeschlossenen
  Sessions). Ehrliche Grenze: Bereits früher importierte Kopien bleiben am
  Handy — es gibt bewusst keinen Rückkanal.

### 1.4 Deutlich sichtbare Aufzeichnung

- Neue Styles: `liveFrame` (türkiser 2-px-Rahmen), `recBadge` (gefüllte
  Pille mit dunklem Text) und `shimmer` (wandernder Lichtstreifen,
  Endlos-Animation über eine eigene `TranslateTransform`).
- **Beide** Live-Karten (PC-Aufzeichnung und Handy-Aufzeichnung) tragen bei
  laufender Aufzeichnung: türkisen Rahmen + gefülltes Badge („● RECORDING"
  bzw. „● LIVE") + Schimmer + den bestehenden Puls am Icon.
- Der Vorrang-Zustand ist eine eigene, ruhige Karte: „HANDY HAT VORRANG /
  Dein Handy zeichnet auf — der PC hält sich zurück…" (bewusst VOR dem
  „Windows only"-Hinweis, weil er der wichtigere Zustand ist).

### 1.5 Version

PC-Tracker **1.5.0** (`Aevum.PcTracker.csproj`).

---

## 2. Verifikation (echte Läufe, Xvfb + curl als Handy)

### 2.1 Live-Szenario: PC zeichnet auf (Digital) → Handy startet

| Schritt | Aktion | Ergebnis |
|---|---|---|
| 1 | General-Aufzeichnung aktiviert, **vorher** „Digital" im Dropdown gewählt | Laufende Session in der DB: `activity_key=digital`, `program_key=__general__` ✔ (die VORHER gewählte Aktivität — kein „falsche Activity"-Start) |
| 2 | Handy meldet Session „Studium" (`POST /api/live`) | PC-General-Session `end_at` gesetzt — **1,1 s** nach der Meldung (nächster Tick) ✔ |
| 3 | Screenshot | Handy-Karte oben mit **● LIVE**, türkisem Rahmen und **sichtbarem Schimmer**; darunter „PHONE GOES FIRST / Your phone is recording"; Start-Karte: türkiser Hinweis „Your phone is recording right now — PC start waits…", Chips deaktiviert ✔ |
| 4 | Handy meldet Session-Ende (`session:null`) | Neue Digitale Session startet automatisch (Auto-Resume der allgemeinen Aufzeichnung) ✔ |

### 2.2 Live-Szenario: manuelle PC-Session → Handy startet

| Schritt | Aktion | Ergebnis |
|---|---|---|
| 1 | Chip „Arbeit" → „Start now" | Session `program_key=__manual__` läuft — die einzige offene Session (manueller Start schneidet die General-Session) ✔ |
| 2 | Screenshot | Live-Karte: Badge **● RECORDING** (türkise Pille), Titel „Arbeit", „Manually started · since 14:09", Stop-Knopf, **türkiser Rahmen + Schimmer** ✔ |
| 3 | Handy meldet Session | Manuelle Session in der DB `beendet` ✔ — Handy-Vorrang greift auch gegen die manuelle Session |

### 2.3 Live-Szenario: Verlauf entfernen

| Schritt | Aktion | Ergebnis |
|---|---|---|
| 1 | History-Tab, ✕ am obersten Eintrag | Inline-Bestätigung „Remove? [Yes] [No]" erscheint ✔ |
| 2 | „Yes" | Session aus der DB verschwunden (8 → 7 Sessions, ID nicht mehr auffindbar), Liste neu geladen ✔ |

### 2.4 Zusätzlich gefundener + behobener Bug (durch den Live-Test)

Der gelbe Hinweis „Aufzeichnung ist pausiert…" erschien fälschlich AUCH beim
Handy-Vorrang (er hing an „Start nicht möglich" statt an „wirklich pausiert").
Behoben: hängt jetzt am echten Pause-Zustand; Screenshot-Nachprüfung: weg ✔.

### 2.5 Tests + Builds

- **127/127 Core-Tests grün** (116 + 11 neue: `PhonePriorityTests` 5,
  `HistoryDeleteTests` 4, `TrackerServicePhonePriorityTests` 2).
- Debug-Build 0 Warnungen/0 Fehler; UI-Livetest komplett unter Xvfb
  (Screenshots zu jedem Schritt).
- Windows-Publish `win-x64` self-contained → ZIP (Details §3).

---

## 3. Release

(siehe `docs/CHANGELOG.md` und GitHub-Release `pc-tracker-1.5.0`)

- Git-Tag `pc-tracker-1.5.0`; GitHub-Release mit Asset
  `AevumPcTracker-1.5.0-win-x64.zip`.
- Auslieferungsserver `/srv/aevum-apk/` + Download-Seite aktualisiert.

---

## 4. Ehrliche Abgrenzungen

- **Der Live-Test lief unter Linux/Xvfb** mit simuliertem Handy (curl
  `POST /api/live`): Engine, Live-Kanal, DB und die komplette UI wurden real
  durchgespielt; die **Windows-Programm-Probe** selbst war wie immer nicht
  Teil des Tests (unverändert seit 1.0). Auf Linux greift zusätzlich der
  „Windows only"-Zustand — die Vorrang-Karte steht bewusst davor und war
  damit auch dort sichtbar.
- **Handy-Seite unverändert:** Der Vorrang nutzt den bestehenden
  Live-Kanal — die Aevum-App braucht kein Update (die 1.0.35/1.0.34-Fassung
  meldet bereits alle paar Sekunden).
- **Stale-Fenster (45 s):** Bleibt die App länger stumm, gibt der PC sich
  wieder frei (kein Dauer-Block nach Absturz). In dieser Zeit ist eine
  Doppelaufzeichnung theoretisch möglich — bewusst gewählter Kompromiss
  zwischen „Vorrang robust" und „PC nicht dauerhaft lahmlegen".
- **Gelöschte Sessions am Handy:** werden nicht rückwirkend entfernt (kein
  Rückkanal); ab der Löschung wird nichts mehr ausgeliefert.

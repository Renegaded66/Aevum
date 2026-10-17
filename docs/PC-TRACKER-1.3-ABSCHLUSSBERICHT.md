# PC-Tracker 1.3.0 — Abschlussbericht

**Datum:** 2026-10-17
**Produkt:** Aevum PC-Tracker (Windows-Begleiter, Avalonia/.NET 8)
**Version:** 1.3.0 (`Aevum.PcTracker.csproj`)
**Auftrag (Original-Wortlaut):**

> „Ich möchte bei der desktop app ein update. Und zwar soll das dashboard dort
> ähnlich sein wie das auf android. Ich will also auch dort activitys starten
> können (entweder direkt oder mit vorlaufzeit). Die möglichen Activitys
> sollten vom handy synchronisiert sein und der pc soll regelmäßig mit dem
> handy synchronisieren."

Vier Anforderungen also: **(1)** Dashboard ähnlich wie Android, **(2)** Aktivitäten
am PC starten — sofort oder mit Vorlaufzeit, **(3)** startbare Aktivitäten kommen
vom Handy (synchronisiert), **(4)** regelmäßiger Abgleich PC ↔ Handy.

---

## 1. Was gebaut wurde

### 1.1 Aktivitäten starten (Kern des Auftrags)

**Neue Dashboard-Karte „Aktivität starten"** (unter der Live-Karte, das
Herzstück der Seite):

- **Chips** mit allen Aktivitäten — Quelle ist der bestehende
  Aktivitäts-Spiegel des Handys (`GET /api/activities`-Gegenstück,
  `synced_activities`); wurde noch nie synchronisiert, zeigt die Karte den
  lokalen Katalog als Rückfall. Damit gilt wörtlich: „Die möglichen Activitys
  sollten vom handy synchronisiert sein."
- Klick auf eine Aktivität → **Start-Optionen**: „Jetzt starten" / „+5 Min" /
  „+15 Min" / „+30 Min".
- Bei Vorlaufzeit läuft ein **Countdown** („Arbeit startet in 4:32") mit
  **Abbrechen**; bei Ablauf startet die Aktivität automatisch. Den Countdown
  zählt das UI (Sekunden-Timer); die Engine bekommt nur den Start gemeldet.
  Ein App-Neustart verwirft einen laufenden Countdown bewusst — eine halb
  gestartete Aktivität wäre schlimmer als keine.
- Ist die Aufzeichnung pausiert, ist die Karte deaktiviert samt Hinweis.

**Engine (`SessionEngine`, testbar, ohne UI):**

- `StartManual(activityKey, timezoneId, nowMs)` — schneidet eine laufende
  Session (Regel wie allgemein) zum Startzeitpunkt (der Nutzerentscheid
  gewinnt) und startet die manuelle Session mit dem festen Key
  **`__manual__`** / Anzeigename „Manual start".
- `StopActive(nowMs)` — der **Stop-Knopf** beendet die laufende Session mit
  Ende = jetzt (anders als App-Ende: dort Ende = letzte Sicht).
- **Tick-Regeln:** Solange die manuelle Session läuft, starten weder Regel-
  noch allgemeine Aufzeichnung; Idle beendet sie nicht; sie überlebt als
  einzige Session (Heartbeat hält `last_seen` frisch).
- `ResetVisibilityClock` nach Start/Stop: Die nächste Regel-Session zählt ab
  dem Eingriff — **keine rückwirkende, überlappende** Session.

**Sichtbarkeit:** Live-Karte oben zeigt sie als „RECORDING NOW · «Aktivität» ·
Manually started · since … · Dauer" mit **rotem Stop-Button**; die manuelle
Anzeige hat Vorrang selbst dann, wenn die Windows-Probe fehlt oder pausiert
ist. In Verlauf und „Heute" erscheint die Zeile lokalisiert als „Manuell
gestartet" (Deutsch) — die Datenbank behält sprachneutral „Manual start" fürs
Handy.

**Übertragung ans Handy:** unverändert über die bestehende Sessions-API — die
manuelle Session ist eine normale Session mit `endAt` und `activityKey` = Handy-
Activity-ID; der Import in Aevum findet dieselbe Aktivität wieder
(`title = activityName`, `description = "Manual start"`).

### 1.2 Dashboard „ähnlich wie auf Android"

- Start-Karte als zentrale Aktion, Live-Karte mit Stop (wie das Live-Banner der
  App), Aktivitäten als antippbare Chips mit Icons — dieselbe Grammatik wie der
  Orbit-Starter der Android-App, ohne dessen Overkill (Sternenhimmel,
  exakte-Uhrzeit-Ring): am PC reichen Sofort + drei Vorlaufzeiten; bewusst
  NICHT gebaut: exakte Uhrzeit, Radial-Ring (dokumentierte Abgrenzung).
- Layout, lokalisierte Texte (EN/DE, live umschaltbar) und Karten-Stil
  konsistent mit dem restlichen Dashboard.

### 1.3 Regelmäßiger Abgleich — Bestandsaufnahme + Sichtbarkeit

Der Abgleich ist architektonisch **Handy-initiiert** (bewusste Entscheidung,
siehe Protokoll §1: ein Push bräuchte einen Dauer-Empfangsdienst am Handy).
Dieser Teil war **bereits gebaut und aktiv**:

- Die Aevum-App synchronisiert im WLAN **automatisch alle 15 Minuten**
  (`PcAutoSyncWorker`, WorkManager, NetworkType.UNMETERED) plus gedrosselte
  Zusatz-Kicks beim App-Öffnen (`PcSyncGate`, `triggerOpportunistic`).
- Jeder Sync **holt die PC-Aufzeichnungen ab UND spiegelt den Aktivitätskatalog
  zum PC** — genau die Datenbasis, die die neue Start-Karte nutzt.

Neu in 1.3.0 ist die **Sichtbarkeit** auf der PC-Seite: Die „Dein Handy"-Karte
zeigt zusätzlich „N Aktivitäten vom Handy" neben Zeitpunkt und Name des letzten
Syncs; der Hinweistext erklärt den Automatik-Abgleich. Damit sieht Devon sofort,
dass und wann abgeglichen wurde.

---

## 2. Zwei echte Bugs — vom Live-Test gefunden

Für diese Änderung gab es erstmals einen **UI-Smoke-Test unter Xvfb** (Xvfb +
xdotool + ffmpeg auf der Linux-VM; die App degradiert dort sauber auf
`NullForegroundProbe`). Der Klick-Pfad wurde live durchgespielt — und deckte
zwei Fehler auf, die Kompilat und Unit-Tests nicht sehen konnten:

1. **Chip-Klick ohne Wirkung:** Avalonia übergibt den DataContext **nicht**
   automatisch als `CommandParameter` — der Command bekam `null` und stieg aus.
   Fix: `CommandParameter="{Binding}"` am Chip (+ Kommentar im XAML, damit der
   Fehler nicht zurückkommt).
2. **Manuelle Session unsichtbar:** Der „Windows only"-Zweig der Live-Karte
   kehrte früh zurück, bevor die manuelle Session angezeigt wurde. Fix: Die
   manuelle Session wird jetzt **vor** allen Probe-/Pause-Zweigen angezeigt.

Beide Fixes sind im XAML/Code mit Begründung kommentiert.

---

## 3. Tests & Verifikation

- **104/104 Windows-Tests grün** (92 vorhandene + **12 neue** in
  `ManualSessionTests`): Start legt Session an · Start schneidet laufende
  Regel-Session · Start schneidet General-Session · Start ersetzt laufende
  manuelle Session · kein Regel-Start während manuell · kein
  Hintergrund-Regel-Start · kein General-Start · Idle beendet nicht · Stop mit
  Ende = jetzt · Pause beendet (Ende = letzte Sicht) · Flush beendet ·
  nach Stop keine rückwirkende Überlappung.
- `dotnet build`: 0 Warnungen, 0 Fehler.
- **Live-Smoke-Test (Xvfb, Linux):** Dashboard gerendert (Screenshot),
  Klick-Pfad komplett: Hover auf „Arbeit" → Klick → Start-Optionen → „Jetzt
  starten" → Live-Karte „RECORDING NOW · Arbeit · Manually started" + Stop →
  „Heute" zeigt die Session → Stop → Session beendet, Ruhezustand.
  **Vorlaufzeit:** „+5 Min" → Countdown „Arbeit startet in 4:57" → Ablauf →
  Session startet automatisch (per Screenshot belegt).
- Release: `pc-tracker-1.3.0` mit `AevumPcTracker-1.3.0-win-x64.zip`
  (siehe §4) — die App aktualisiert sich darüber selbst (1.2-Feature).

## 4. Auslieferung

- **Windows-Paket:** `AevumPcTracker-1.3.0-win-x64.zip` — **57.478.315 B
  (57,5 MB)**, self-contained `win-x64` (ohne PDB), Assembly-Version 1.3.0.0.
- **GitHub-Release:** Tag **`pc-tracker-1.3.0`** (Commit `aa8687c`, gepusht),
  Release „Aevum PC-Tracker 1.3.0" mit dem ZIP als Asset — per API angelegt,
  Asset-Upload verifiziert (Größe stimmt Byte für Byte, `state: uploaded`).
- **UpdateService live geprüft:** Die Auswahl-Logik findet aus Sicht einer
  installierten 1.2.0 das Release `pc-tracker-1.3.0` samt
  `AevumPcTracker-1.3.0-win-x64.zip` → „Herunterladen & neu starten"
  funktioniert für Bestandsinstallationen ohne manuellen Download.
- **Lokaler Download (Heimnetz):**
  `http://192.168.178.40:8098/AevumPcTracker-1.3.0-win-x64.zip`
  (`index.html` auf 1.3.0 aktualisiert, „Neu in 1.3"-Karte).

## 5. Ehrliche Abgrenzungen

- **Windows-Build auf Linux cross-published** (wie 1.0–1.2): `dotnet publish
  -r win-x64 --self-contained`. Der Live-Test lief auf Linux mit deaktivierter
  Windows-Probe; das Windows-Verhalten der Probe selbst ist unverändert und
  war in 1.0–1.2 in Betrieb.
- **Kein Autostart-/Tray-Test** in dieser Umgebung (Xvfb ohne Tray) — am
  Tray-Verhalten wurde nichts geändert.
- **Exakte Uhrzeit / Radial-Picker** bewusst nicht gebaut (siehe §1.2);
  bei Bedarf nachrüstbar.
- Die **Android-App wurde nicht angefasst** — der bestehende Sync und Import
  decken den neuen Session-Typ bereits vollständig ab (Protokoll §5d/§6).

## 6. Doku & Stand

- `docs/PC-TRACKER-PROTOCOL.md` §5d neu (manuelle Starts, Regeln, Sync-Takt),
  bestehende Abschnitte unverändert gültig.
- `desktop/README.md`: Download zeigt auf 1.3.0, „Neu in Version 1.3"-Block.
- `docs/CHANGELOG.md`: PC-Tracker-1.3.0-Abschnitt.

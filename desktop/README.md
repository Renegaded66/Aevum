# Aevum PC-Tracker (Windows)

Der PC-Tracker zeichnet auf deinem Windows-PC auf, welche Programme du benutzt
(z. B. Minecraft, Visual Studio Code, Outlook) und ordnet sie den Aktivitäten
deiner Aevum-App zu. Die Aevum-App auf dem Handy holt die Aufzeichnungen
direkt aus dem Heimnetz ab — kein Konto, keine Cloud, kein Server.

> **Voraussetzung:** Handy und PC müssen sich im **selben WLAN/Netzwerk**
> befinden. Ohne Heimnetz gibt es keine Übertragung — es wandert nichts ins
> Internet.

---

## Die fertige App herunterladen

**Wichtig:** In diesem Ordner (`desktop/`) liegt nur der **Quellcode** — hier
gibt es bewusst keine `.exe`-Datei. Auch das Quellcode-ZIP des Repositories
enthält keine fertige App. Die lauffähige Version gibt es als eigenes Paket
im **Releases-Bereich** des Repositories:

**➜ [github.com/Renegaded66/Aevum/releases](https://github.com/Renegaded66/Aevum/releases)**

Dort das Release **`pc-tracker-1.1.0`** öffnen und
**`AevumPcTracker-1.1.0-win-x64.zip`** (ca. 55 MB) herunterladen. Darin steckt
die komplette App inklusive Laufzeitumgebung — es muss **nichts zusätzlich
installiert** werden (kein .NET, kein Setup-Programm).

### Installieren in 3 Schritten

1. **ZIP herunterladen** (Link oben) und **entpacken** — am einfachsten
   Rechtsklick → „Alle extrahieren…" nach z. B. `C:\Programme\AevumPcTracker`.
   Wichtig: wirklich entpacken, nicht nur hineinschauen.
2. Im entpackten Ordner **`AevumPcTracker.exe`** doppelklicken.
3. Windows-Sicherheitshinweise einmalig bestätigen:
   - **SmartScreen** („Windows hat den Start dieser App verhindert"): normal,
     weil die App kein gekauftes Signaturzertifikat hat → **„Weitere
     Informationen" → „Trotzdem ausführen"**.
   - **Firewall** („Windows Defender Firewall hat einige Funktionen dieser App
     blockiert"): **„Zugriff zulassen"** anhaken — mindestens für *private
     Netzwerke*. Ohne diese Freigabe kann dein Handy den PC nicht finden.

Die App braucht **keine Installation** und **keine Administratorrechte**.
Sie startet im Infobereich (Tray) neben der Uhr; das X oben rechts versteckt
sie nur — beendet wird sie über das Tray-Symbol → **Beenden**.

**Update:** Wenn eine neue Version erscheint, einfach die neue ZIP aus dem
Releases-Bereich laden, entpacken und die alte EXE ersetzen. Deine
Einstellungen und Aufzeichnungen bleiben erhalten.

## Neu in Version 1.1

- **Sprache:** Standard **Englisch**. Oben rechts auf jeder Seite zeigt der
  Flaggen-Knopf die aktive Sprache — einfach anklicken, um zwischen
  🇩🇪 Deutsch und 🇬🇧 English zu wechseln.
- **Sync-Zeile (oben rechts, auf jeder Seite):** zeigt, wann dein Handy
  zuletzt Daten abgeholt hat und ob es gerade verbunden ist („Handy
  verbunden — *Name*“). Der ↻-Knopf lädt die Ansicht neu.
- **Neue Aktivitäten aus der App:** Erstelle eine Aktivität im Handy →
  in der App **„Jetzt synchronisieren“** antippen → sie erscheint hier in
  den Regeln im Dropdown unter **„Von deinem Handy“**.
- **Aufnahme-Modus pro Regel:** *Nur im Vordergrund* (Standard) oder
  *Auch wenn es im Hintergrund läuft* — z. B. für Musik oder Programm, das
  minimiert weiterarbeitet.
- **Laufende Programme:** Im Regeln-Tab **„Laufende Programme anzeigen“** —
  alles, was gerade offen ist, direkt anklicken und eine Regel daraus
  formulieren. Eigene Programme lassen sich auch per Name oder `.exe`
  eintragen.
- **Regeln bearbeiten:** Jede Zuordnung hat jetzt **Bearbeiten** und
  **Entfernen**.
- **Live-Ansicht:** Das Dashboard pulsiert, wenn gerade etwas aufgezeichnet
  wird — mit Programm, Aktivität und laufender Dauer.

## Erste Schritte

### 1. Regeln anlegen

Im Tab **Regeln** (englisch „Programs“) ordnest du Programme den Aktivitäten
zu. Drei Wege:

- **Vorschlags-Katalog:** Klick auf z. B. *Minecraft* → Aktivität wählen → **Save**.
- **Laufende Programme:** „Laufende Programme anzeigen“ → alles, was gerade
  offen ist, direkt anklicken.
- **Eigene Programme:** Name oder `.exe` eintragen (z. B. `obs64.exe`).

Im Dropdown stehen zuerst die **Aktivitäten aus deiner Aevum-App** („Von
deinem Handy“) — auch selbst angelegte — darunter der allgemeine Katalog.
Nur zugeordnete Programme werden aufgezeichnet — nichts anderes verlässt den PC.

- **Aufnahmefenster:** So lange muss ein Programm nach dem Schließen noch
  „nachlaufen“, bevor die Aufzeichnung endet (Standard: 1 Minute).
  Kurzes Hin- und Herschalten erzeugt so keinen Lärm in der Timeline.
- **Aufnahme-Modus:** *Nur im Vordergrund* (Standard) oder *Auch wenn es im
  Hintergrund läuft* (z. B. Musik, Spiele-Launcher).
- **Pause-Erkennung:** Keine Eingabe mehr? Nach der eingestellten Zeit (z. B.
  15 Minuten) endet die laufende Aufzeichnung automatisch.

### 2. Mit der Aevum-App koppeln

1. In Aevum: **Einstellungen → PC-Tracker** öffnen.
2. Dort **„PC im WLAN suchen"** — dein PC erscheint in der Liste.
   (Alternativ: Adresse manuell eintippen, sie steht im Tab **Kopplung**.)
3. Am PC den Tab **Kopplung** öffnen und den **Kopplungsmodus** einschalten.
   Es erscheint ein 6-stelliger Code.
4. Diesen Code in der Aevum-App eingeben → **Verbinden**.

Danach holt die Aevum-App die Aufzeichnungen automatisch ab, sobald du die
PC-Tracker-Seite öffnest — oder jederzeit über **„Jetzt synchronisieren"**.

### 3. Autostart (empfohlen)

In den **Einstellungen** → *Mit Windows starten* aktivieren. Die App läuft
dann still im Infobereich mit. Rüber und rückwärts: der PC muss laufen,
wenn du PC-Zeit aufzeichnen willst.

---

## Was passiert mit den Daten?

- **Alles bleibt lokal.** Aufzeichnungen liegen in einer lokalen Datenbank
  auf dem PC (Standard: `%APPDATA%\AevumPcTracker`). Der Ordner lässt sich in
  den Einstellungen öffnen.
- **Übertragung nur im Heimnetz:** Die App öffnet einen kleinen HTTP-Dienst
  im lokalen Netzwerk (Port 47825). Nur gekoppelte Geräte mit gültigem
  Zugangstoken bekommen Daten — der Token entsteht bei der Kopplung.
- **Kein Internet:** Die App kontaktiert von sich aus keine Server, sendet
  keine Telemetrie und braucht keine Anmeldung.
- **Aufgeräumt:** Alte Aufzeichnungen werden gemäß der Aufbewahrungs-
  Einstellung automatisch gelöscht (Standard: 365 Tage).
- **Zugang zurücksetzen:** Im Tab *Kopplung* kann der Zugang jederzeit neu
  vergeben werden — danach muss jedes Handy neu koppeln.

## Deinstallieren

1. In den Einstellungen **Autostart deaktivieren**.
2. Tray-Menü → **Beenden**.
3. Programmordner löschen. Optional: `%APPDATA%\AevumPcTracker` löschen
   (entfernt alle Aufzeichnungen vom PC — bereits importierte Aufzeichnungen
   in Aevum bleiben erhalten).

---

## Für Entwickler

- Protokoll: [`docs/PC-TRACKER-PROTOCOL.md`](../docs/PC-TRACKER-PROTOCOL.md)
- Projekte: `src/Aevum.PcTracker.Core` (Logik, testbar ohne UI) und
  `src/Aevum.PcTracker` (Avalonia-UI, Windows-App).
- Tests: `dotnet test tests/Aevum.PcTracker.Core.Tests`
- Build (Windows-x64, self-contained):
  `dotnet publish src/Aevum.PcTracker/Aevum.PcTracker.csproj -c Release -r win-x64 --self-contained true`
- ZIP fürs Release bauen: `cd` ins Projekt, dann `dotnet publish … -o publish`,
  PDB-Dateien weglassen und den `publish`-Ordner zippen.

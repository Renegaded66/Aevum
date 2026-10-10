# Aevum PC-Tracker (Windows)

Der PC-Tracker zeichnet auf deinem Windows-PC auf, welche Programme du benutzt
(z. B. Minecraft, Visual Studio Code, Outlook) und ordnet sie den Aktivitäten
deiner Aevum-App zu. Die Aevum-App auf dem Handy holt die Aufzeichnungen
direkt aus dem Heimnetz ab — kein Konto, keine Cloud, kein Server.

> **Voraussetzung:** Handy und PC müssen sich im **selben WLAN/Netzwerk**
> befinden. Ohne Heimnetz gibt es keine Übertragung — es wandert nichts ins
> Internet.

---

## Installation

1. ZIP entpacken (z. B. nach `C:\Programme\AevumPcTracker`).
2. `AevumPcTracker.exe` doppelklicken.
3. Windows zeigt beim ersten Start möglicherweise den SmartScreen-Dialog
   („Windows hat den Start dieser App verhindert") — das ist normal, weil die
   App kein gekauftes Signaturzertifikat hat. Auf **„Weitere Informationen" →
   „Trotzdem ausführen"** klicken.

Die App braucht **keine Installation** und **keine Administratorrechte**.
Sie startet im Infobereich (Tray) neben der Uhr; das X oben rechts versteckt
sie nur — beendet wird sie über das Tray-Symbol → **Beenden**.

## Erste Schritte

### 1. Regeln anlegen

Im Tab **Regeln** wählst du aus dem Vorschlags-Katalog die Programme, die
aufgezeichnet werden sollen (z. B. *Minecraft → 🎮 Zocken*). Nur zugeordnete
Programme werden aufgezeichnet — nichts anderes verlässt den PC.

- **Aufnahmefenster:** Erst wenn ein Programm länger als das eingestellte
  Fenster (Standard: 1 Minute) im Vordergrund ist, entsteht eine Aufzeichnung.
  Kurzes Hin- und Herschalten erzeugt also keinen Lärm in der Timeline.
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

# Abschlussbericht — PC-Tracker 1.5.1: Update-Austausch repariert & überwachbar

Datum: 2026-10-17 · Befund aus einer Nutzernachricht (siehe §0)

---

## 0. Der Befund (Zitat)

> „In 1.4.1 hab ich auf update herunterladen geklickt, kurz danach hat es auch
> heruntergeladen und sich auch das fenster geschlossen, aber dann nach ein
> paar sekunden hat sich einfach wieder 1.4.1 geöffnet. Dort habe ich
> testweise das gleiche nochmal gemacht, aber wieder hat sich 1.4.1 geöffnet.
> Im ordner wo die exe ausgeführt wird habe ich auch keine neuen dateien
> gefunden"

Zwei Versuche, identisches Ergebnis: Download ✓, App beendet sich ✓, aber es
wird **nichts kopiert** und die alte Version startet wieder.

---

## 1. Eingrenzung (was ausgeschlossen wurde)

- **Der ZIP-Extrakt ist es nicht.** Auf .NET 8 (dieselbe Laufzeit wie die
  App) nachgestellt: `ZipFile.ExtractToDirectory` entpackt das echte
  1.5.0-Release-ZIP einwandfrei — **362/362 Dateien, EXE korrekt in der
  Wurzel**. Auch die ZIP-Struktur ist flach und regulär.
- **Der Download ist es nicht.** Das Release-Asset wurde als identisch zum
  lokalen ZIP verifiziert (Größe + Inhalte beim Bau).
- **Das harte Beenden ist es nicht mehr.** Die App beendete sich (kein
  Tray-Dialog wie bei 1.3→1.4.0); die gestartete alte EXE kam vom
  `start`-Befehl des Skripts — d. h. das Skript lief bis zum Ende durch, nur
  **der Kopierschritt tat nichts, und alle Fehlermeldungen waren stumm
  (`>nul`)**.

Der exakte Fehler des Batch-Kopierschritts auf Windows ließ sich hier (Linux,
kein Windows, kein Wine) nicht mehr reproduzieren — **deshalb wurde der
Mechanismus nicht „gepatcht", sondern ersetzt und mit Selbstkontrolle
ausgestattet.**

## 2. Was gebaut wurde (PC-Tracker 1.5.1)

### 2.1 Austausch-Skript: PowerShell statt Batch

`UpdateInstaller.BuildSwapScriptLines` erzeugt jetzt ein PowerShell-Skript
(als `.ps1` mit BOM — Windows PowerShell 5.1 liest sonst Pfade falsch):

1. **Warten, die Erste:** `Get-Process`-Schleife, bis der Starter-Prozess
   wirklich beendet ist (max. 90 s), danach Notfall-`Stop-Process -Force`.
2. **Kopieren mit doppeltem Boden:** `robocopy /MIR` (spiegelt, ersetzt alte
   Dateien vollständig); bei Fehler-Exitcode (`>= 8` oder robocopy gar nicht
   gefunden) **`Copy-Item -Recurse -Force`-Fallback**.
3. **Verifikation:** Die EXE im Zielordner muss exakt die Größe der neuen EXE
   aus dem Update-Ordner haben — das Ergebnis („Verifikation OK" oder
   „VERIFIKATION FEHLGESCHLAGEN") steht im Log.
4. **Start + Aufräumen:** erst danach `Start-Process` der App.

### 2.2 Protokoll: `update.log`

Jede Station mit Zeitstempel nach `%APPDATA%\AevumPcTracker\update.log`:
Download → Entpacken (Dateizahl) → Skriptstart → Warten (Sekunden) →
robocopy-Exitcode → Fallback ja/nein → Verifikation (Byte-Größen) → App-Start.
Nichts wird mehr verschluckt.

### 2.3 Update-Tagebuch: Fehlschläge werden sichtbar

- Vor dem Austausch wird `pending-update.txt` (Zielversion) geschrieben.
- Beim nächsten Start bilanziert die App: **Erfolg** → still aufräumen +
  Log-Zeile. **Fehlschlag** → deutliches rotes Band in den Einstellungen
  („Das letzte Update ist nicht durchgelaufen … Details im update.log") mit
  „Log-Ordner öffnen" und „Verstanden" (`DismissUpdateFailed`).
- Ein gescheitertes Update bleibt damit **nie mehr still** — genau das war
  der Kern des Ärgernisses.
- Alte Austausch-Skripte früherer Versuche werden beim nächsten Update
  aufgeräumt.

### 2.4 Tests

- **131/131 grün.** Neu: `UpdateJournalTests` (4) — Marker-Runde, kaputte
  Marker-Datei wird ignoriert, Bilanz Erfolg/Fehlschlag/kein Auftrag,
  Log-Format. Die Skript-Tests prüfen jetzt: Reihenfolge (Stop → Kopieren →
  Start), `/MIR` + Copy-Item-Fallback, Größen-Verifikation, Log-Einbettung,
  und dass keine `>nul`-/Batch-Reste übrig sind.

---

## 3. Ehrliche Abgrenzungen

- **Das PowerShell-Skript konnte hier nicht ausgeführt werden** (kein
  Windows; die portable PowerShell ließ sich in dieser Umgebung nicht
  installieren — Download blockiert). Geprüft sind: der Generator
  (131 Tests), die Skript-Syntax Zeile für Zeile, und die verwendeten
  Befehle sind Standard-PowerShell 5.1. **Restrisiko bleibt**, aber jeder
  Restfehler wird jetzt protokolliert und beim nächsten Start gemeldet —
  nicht mehr verschwiegen.
- **Windows-Kopierverhalten** (robocopy/Copy-Item) wurde hier nicht
  ausgeführt.
- **Sofort-Weg für bestehende 1.4.1:** einmal manuell auf 1.5.1 entpacken
  (wie zuvor bei 1.4.1); ab dann ist der Update-Knopf geloggt und
  selbstprüfend.

## 4. Release

- Git-Tag `pc-tracker-1.5.1`; GitHub-Release mit Asset
  `AevumPcTracker-1.5.1-win-x64.zip`; Auslieferungsserver + Download-Seite
  aktualisiert (Details siehe `docs/CHANGELOG.md`).

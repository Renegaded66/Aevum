# M18.45 — Smarte Fahrterkennung, Schlaf-Fixes, QuickCreate-Erweiterung

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (112 MB) — **BUILD SUCCESSFUL**
**Commit:** `4e1eab7` (9 Dateien, +702/−70)

---

## 1. Fahrterkennung — die "smarte Kombi" (Activity + Standort)

**Vorher (Root Cause, mit Code-Beweis):** Der M18.42-Fix entfernte den 90s-Mindest-Check komplett. Jedes `IN_VEHICLE-ENTER` startete **sofort** eine Session (Zeile: `bridge.addSample(now, 75)` → Worker enqueued). Der Stop hing **allein** am `IN_VEHICLE-EXIT` — und Google liefert den oft nicht. Ergebnis: "Timer läuft seit 5 Minuten", kurze Fehl-Erkennungen wurden als Fahrt verbucht. Genau deine Beschwerde.

**Jetzt — 3-stufige Bestätigungs-Pipeline:**

| Stufe | Worker | Mechanik |
|---|---|---|
| **Bestätigen** | `DriveConfirmWorker` (neu) | 2 Min warten (Ampel-Start-Toleranz), dann **2 GPS-Fixes 60s auseinander**. ≥ 200 m Bewegung = echte Fahrt → Session startet **mit der ENTER-Zeit**. Kein GPS / keine Bewegung = False-Positive **verworfen** (kein Trigger, keine Session) |
| **Beobachten** | `DriveWatchdogWorker` NO_SIGNAL (neu) | Jedes IN_VEHICLE-Sample refresht einen 8-Min-Timer (REPLACE). Feuert er: **GPS-Bewegungs-Check** — bewegt sich der Standort, läuft die Fahrt weiter; steht er, **Stop** |
| **Stoppen** | `DriveWatchdogWorker` TRANSITION (neu) | `IN_VEHICLE-EXIT` oder Aktivitätswechsel (STILL/WALKING) → 90s Ampel-Toleranz → danach Stop + `DRIVING_ENDED`-Trigger |

**Weitere Fixes:**
- Receiver enqueued den Session-Starter **nicht mehr direkt** — nur noch der ConfirmWorker nach Bestätigung.
- **Duplikat-Schutz:** Läuft bereits eine Auto-Mobilitäts-Session, wird kein zweiter Start erzwungen.
- `InitialActivitySnapshotWorker` (App-Start-Probe) nutzt ebenfalls die Confirm-Pipeline.
- `DriveStartPoint`-Ansatz (erste Idee) wurde verworfen — die Bridge hält nur den Herzschlag, die GPS-Messung macht der Worker selbst (kein toter Code).

## 2. Schlaf-Duplikat (2× 22:54–07:57, 18h5m in der Statistik)

**Root Cause:** Der `SleepImportWorker` (Health Connect) deduplizierte **nur gegen PENDING-Candidates**. Nach dem Auto-Accept (Status ACCEPTED) war der Candidate aus dem Filter gefallen → der nächste Import legte eine **zweite, identische Session** an. 18h5m = exakt 2×9h02 ✓, 16h30 erfasst = 2×8h geclippt ✓.

**Fix (dreifach):**
1. **Breiter Dedup:** gegen ALLE Candidates (jeder Status) UND gegen bestehende Sessions (≥ 30 Min Überlappung).
2. **Bestands-Bereinigung:** identische `(startAt, endAt)`-Paare → die jüngere Session wird per softDelete entfernt (die ältere bleibt).
3. **Reflexions-Fix:** Ein erster Versuch hätte `importedIds` als Dedup genutzt — das hätte **alle** Imports verworfen (jeder Importierte ist per Definition in seiner eigenen Liste). Im Code kommentiert.

## 3. Wake-Zeit 07:50 statt 07:57

**Root Cause:** Seit Android 14 liefert das System `SCREEN_ON`-Broadcasts **nicht mehr** an Hintergrund-Apps. Aevum sah nur noch den `LIFECYCLE`-Fallback (App-Öffnung = 07:57) — die echte erste Nutzung (07:50) ging verloren.

**Fix:** Neuer `UsageWakeDetector` — liest `UsageStatsManager.lastTimeUsed` (die erste echte App-Nutzung nach dem Screen-Off). `SleepFusionEngine` **und** `SleepHeuristicEngine` korrigieren die Wake-Zeit, wenn die UsageStats-Nutzung VOR dem bisherigen Wake-Candidate liegt. Log: `Wake-Korrektur via UsageStats: 07:57 → 07:50`.

## 4. Dashboard-Zahlen

- **16h30 "Erfasste Zeit"** — kam vom Schlaf-Duplikat (2× geclippt). Behoben durch Fix 2; nach der nächsten Bestands-Bereinigung stimmen die Summen.
- **1h8m Bildschirmzeit** — `totalTimeInForeground` kumuliert seit **Mitternacht** (inkl. Nutzung vor dem Aufwachen). Fix: **Cap auf die Wachzeit** (seit erster Nutzung heute, via UsageWakeDetector). Der User sieht jetzt nur noch die Nutzung seit dem Aufstehen.

## 5. QuickCreate — Start- UND Zielzeit manuell

- **Startzeit editierbar** (eigener TimePicker in Sonnengold — antippbar in der Kopfzeile).
- **Segment-Umschalter** (fancy, aktiv = Primary): **"Mit Endzeit"** (fixe Session) oder **"● Weiter aufzeichnen"** (Session startet ab Startzeit, `endAt = null`, läuft weiter bis zum manuellen Stop).
- **Endzeit-Autokorrektur:** rutscht die Endzeit vor die (geänderte) Startzeit → automatisch Startzeit + 1h (keine ungültigen Sessions).
- Hinweistext im Weiterlaufen-Modus: "Die Aufzeichnung startet ab HH:MM und läuft weiter, bis du sie stoppst."

---

## Verifiziert
- `assembleDebug`: **BUILD SUCCESSFUL** (1m 48s), APK 112 MB
- DI-Kette geprüft: `UsageWakeDetector` (@Singleton), `EventDrivenLocationChecker` (Hilt-Binding vorhanden), `ActivityRepository.getOverlappingRange` existiert.
- Keine DB-Migration nötig (v22 bleibt).

## Test-Anleitung
1. **Fahrt:** Kurz ins Auto setzen (< 2 Min) → kein Trigger, keine Session. Echte Fahrt → Session startet nach ~2 Min Bestätigung, stoppt nach Stillstand (max. 8 Min + GPS-Check).
2. **Schlaf:** Health-Connect-Sync → keine zweite Nacht-Session. Bestehende Duplikate werden beim nächsten Import entfernt.
3. **Wake:** Morgen Handy entsperren, App später öffnen → Schlaf endet bei der ersten Nutzung (UsageStats).
4. **Bildschirmzeit:** Nach dem Aufstehen zeigt das Dashboard nur Nutzung seit dem Wake.
5. **QuickCreate:** Leere Stelle tippen → Startzeit ändern → "Erstellen" oder "● Aufzeichnen".

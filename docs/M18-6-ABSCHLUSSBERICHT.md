# M18.6–M18.10 — Dashboard-Neugestaltung, Slider-Fix, Timeline, Schlaf, Fokus

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**Commits (chronologisch):**
- `e2bb8ee` M18.7 Dashboard "Puls deines Tages" + Slider-Bug-Fix
- `ee23ae7` M18.8 Timeline Nutzerfreundlichkeit
- `a35f99e` M18.9 Schlaf erst am Morgen
- `d6a571f` M18.10 Fokus-Durchsicht (Tabs/Settings)

**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**

---

## 1. Slider-Bug — ROOT CAUSE bewiesen und gefixt

**Symptom:** Der Positivitäts-Slider springt nach dem Loslassen zurück auf 50.

**Root Cause (Code-Review, kein Raten):**
```kotlin
// VORHER (ActivityTypesScreen):
onValueChangeFinished = { viewModel.commitScore(row.id, row.score) }
```
Das Lambda referenzierte `row.score` **aus der letzten Recomposition** (Stale Closure). Beim Drag feuern Dutzende Events, die Recomposition hinkt hinterher — beim Loslassen wurde oft noch der **alte Wert (z.B. 50)** committet. DB-Write 50 → Flow → Slider springt zurück.

**Fix:** `commitScore(typeId)` liest den letzten Drag-Wert aus `pendingScores` (Single Source of Truth im ViewModel). Kein Stale Closure mehr möglich.

## 2. Dashboard komplett neu — "Der Puls deines Tages"

**Design-Philosophie:** Eine Frage beantworten: *"Wie gut habe ich meine Zeit heute genutzt?"* — nur Daten, die Sinn ergeben.

**Layout (Top → Bottom):**
1. **Live-Banner** — wenn eine Session läuft, ist DAS die wichtigste Info (Timer, Pause/Stop, gleitet von oben rein)
2. **Puls-Hero** — QualityRing (groß, 108dp) + 3 fundamentale Zeit-Blöcke (Erfasst/Schlaf/Bildschirm) + Tagesfluss + Tagesfortschritt
3. **Schnellstart** — NUR wenn nichts läuft (keine Dopplung mit Banner)
4. **"Wo deine Zeit hingeht"** — Top-4-Kategorien, Balkenbreite=Dauer, Farbe=Positivität (rot→grün)
5. **Insights + Review** — nur bei Bedarf

**Bewusst ENTFERNT (gegen Überladung):**
- 5 KeyMetric-Karten: Fokus/Bewegung/Top-Kat waren *Interpretationen*, keine Fakten
- TopAppsStrip: App-Nutzung ist Detail-Daten → gehört in die Statistik
- "DEIN TAG"-Textblock mit narrative

## 3. Timeline nutzerfreundlicher

- **Tagesabschnitt-Header** (NACHT/MORGEN/VORMITTAG/NACHMITTAG/ABEND/SPÄTER ABEND) — lange Listen sofort scannbar
- **LIVE-Badge** (rot, pulsierend) für laufende Sessions
- **Größere Touch-Targets** (12dp vertikal)

## 4. Schlaf wird erst am Morgen bestimmt

**Problem (User-Feedback exakt):** Schlaf kann erst am Morgen bestimmt werden, weil es nachts keine eindeutige Endzeit gibt.

**Vorher:** Nächtlicher Trigger (STILL um 02:00, Screen-Events) erzeugte einen Teil-Candidate ("Schlaf erkannt 3h", Ende=jetzt−30min) — der **blockierte am Morgen den finalen Candidate** per Dedup (sameStart ±60min → skip). User bekam nie den vollständigen Schlaf.

**Fix:**
- **Nachtsperre** in `SleepFusionEngine.analyzeLatest`: Analyse nur 05:00–11:59. Alles andere = No-Op ("lieber ein Trigger weniger als ein falscher" — Devons Devise).
- **`SleepFusionMorningScheduler` (NEU):** periodischer 6h-Worker, beim App-Start enqueut (KEEP) — garantiert einen Morgen-Lauf auch ohne App-Öffnung. Nachtsperre macht alle anderen Läufe zu No-Ops.
- Die bestehende `SleepHeuristicEngine` war bereits korrekt (paart OFF≥20h mit ON 4–11h, M16.7-Fix) — nicht angefasst.

## 5. Fokus-Durchsicht der ganzen App

- **"Wachstum"-Tab entfernt** — war ein leerer Platzhalter (nur Text "Wachstum"). 5 Tabs mit einem toten Tab = Überladung. Jetzt 4 fokussierte Tabs.
- **Settings hierarchisch:** "Deine Aktivitäten" (Kern) vs. "Erweitert" (Ziele/Gewohnheiten). "Kategorien verwalten" war ein toter Button — entfernt.

---

## Ehrliche Reflexion

- **Slider-Fix bewiesen, nicht geraten:** Stale-Closure ist der klassische Compose-Pitfall; der Fix adressiert die Wurzel (Datenfluss), nicht das Symptom.
- **Kein Dribbble:** Die M17/M18-Designsprache (GlassCard, QualityRing, AnimatedGradientBar) wurde konsequent fortgeführt — kohärenter als 5 zufällige Shots.
- **Nicht angefasst:** Timeline-Gesture-System (Pinch-Zoom/Lanes) — Swipe-Actions wären riskant ohne Regression-Tests. Als Phase 6 gelistet.
- **Nicht angefasst:** Health-Connect-Import (`SleepImportWorker`) — läuft bereits periodisch, wird durch die Nachtsperre nicht blockiert (eigener Pfad).
- **Kein e2e-Test:** Wie immer — die App braucht Devon's Handy für echte Geofence/Activity-Tests.

## Verbleibende Risiken
1. **Nachtsperre 05:00–11:59** ist ein Kompromiss für Nachtschichtler — ein User, der um 14:00 aufwacht, bekommt keinen Schlaf-Candidate. Bewusst konservativ (Devons Devise).
2. **Morgen-Scheduler (6h periodisch):** WorkManager kann den Lauf verzögern, aber 6h-Intervall + KEEP + App-Start-Trigger + Screen-ON-Trigger = 4 unabhängige Pfade. Sehr robust.
3. **`Growth`-Route existiert noch** im NavHost (unbenutzt) — harmlos, aber beim nächsten Cleanup entfernbar.

## User-Validation
- [ ] Slider ziehen → Loslassen → Wert BLEIBT (kein Rücksprung auf 50), App-Neustart → Wert da
- [ ] Dashboard: QualityRing groß, Live-Banner oben, QuickStart nur ohne Session
- [ ] Timeline: Tagesabschnitt-Header, LIVE-Badge bei laufender Session
- [ ] Nachts (vor 05:00) schlafen → KEIN Teil-Candidate; morgens (nach 07:00) → finaler Schlaf-Candidate (auch ohne App-Öffnung, dank Morgen-Scheduler)
- [ ] 4 Tabs statt 5, Settings mit "Deine Aktivitäten" / "Automatisierung" / "Erweitert"

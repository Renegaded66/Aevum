# M20 — ABSCHLUSSBERICHT

> Stand: 2026-10-18 (Build 00:04 Uhr) · App-Version **1.0.40** (versionCode 41)
> Umfang: **Ziel-Architektur** (neues Feature) + **Zuverlässigkeit der App-Aufzeichnung** (Bugfix)
> Datenbank: **v46 → v47** (rein additiv)

---

## 1) Nutzerwunsch (wörtlich)

> „Bei Aevum möchte ich eine Ziel Architektur haben. Also mit bspw dass ich am
> Tag digital unter 2 Stunden Aufzeichnung habe, oder pro Woche unter 10
> Stunden, oder Lernen mindestens 2 Stunden zwischen Montag und Freitag
> täglich, oder 4 mal zwischen Montag und Freitag Aufgaben machen. […]
> Lege unter den Einstellungen einen neuen Punkt fest und implementiere das.
> Für jedes Ziel mit geeigneten Statistiken, streaks, Diagrammen, und so
> weiter. Mache das richtig hochwertig vollständig […] Möglichst mit Dopamin,
> also wenn man abhakt mit Animation und Farben und Schimmer und so. Auch mit
> automatischer überwachung […] Also Entscheidung ob automatisch oder manuell
> Ziel Erfassung. Mit Zuweisung und so. Sowohl Activity als auch Kategorie.
> Mache dir Gedanken, gleiche Funktionen mit ähnlichen Apps ab.“

> „Und bei der automatischen App Aufzeichnung klappt immer noch nicht alles
> wie gewünscht. Vorhin habe ich das Handy eingeschaltet und nach 10 Sekunden
> Duolingo geöffnet. […] Nach 2 Minuten hat die Aufzeichnung digital gestartet.
> Statt dass von Anfang an lernen gestartet wäre. Und irgendwann hat dann
> irgendwie doch lernen angefangen aufzuzeichnen, aber nur die letzten paar
> Sekunden.“

---

## 2) Ziel-Architektur — was gebaut wurde

### 2.1 Alle vier Nutzer-Beispiele sind direkt abbildbar

| Wunsch | Abbildung in Aevum |
|---|---|
| „Digital unter 2 h am Tag" | Automatisch · Quelle **Bildschirmzeit** (echte Zeit, Digital Balance) · Höchstens · Täglich · 2 h |
| „Digital unter 10 h pro Woche" | dito, Zeitraum **Wöchentlich** |
| „Lernen mind. 2 h Mo–Fr täglich" | Automatisch · Quelle **Aktivität** (z. B. Lernen) · Mindestens · Täglich · Wochentage **Mo–Fr** |
| „4× zwischen Mo–Fr Aufgaben" | **Manuell** · Anzahl · Wöchentlich · 4 · Wochentage Mo–Fr — oder Automatisch über **erledigte Aufgaben** |
| frei: „Digital < 2 h/Tag, Wochenende frei" | wie 1., Wochentage z. B. Mo–Fr |

### 2.2 Datenmodell (DB v47, MIGRATION_46_47, rein additiv)

- `goal` + **`tracking_mode`** (`AUTO`/`MANUAL`, Default `AUTO`),
  **`schedule_days`** (Mo–So-Bitmaske, Default 127 = jeden Tag),
  **`source_kind`** (`ACTIVITY_TYPE`/`CATEGORY`/`DIGITAL`/`TODOS`, NULL =
  Alt-Verhalten exakt wie M18.138), **`accent`** (Farbwelt-Schlüssel).
- Neue Tabelle **`goal_checkin`** (pro manuellem Haken eine Zeile:
  Ziel, Zeitstempel, Datum, Anzahl) mit FK **ON DELETE CASCADE** und zwei
  Indizes. Rückgängig = letzte Zeile löschen; kein Zähler-Race.
- Beweis: `SchemaV47Test` (Element für Element: 47 = 46 + 4 Spalten +
  genau die neue Tabelle) und `GoalV47MigrationTest` (ECHTE SQLite-Datei
  v46 mit Bestandsdaten → Room validiert die Migration; Bestandsziele
  behalten Verhalten; CASCADE funktioniert; Version + Identitäts-Hash 47).

### 2.3 Auswertung ([GoalProgressAnalytics] v2)

- Fortschritt je Periode Tag/Woche/Monat (Kalender-Clipping exakt wie
  M18.138 — alle Alt-Tests unverändert grün).
- **Streaks:** aktuelle + beste Serie. Regeln (Praxis-Recherche):
  freie Tage (Schedule) unterbrechen nie; die laufende Periode bricht nie
  (abgeschlossene Lücke schon) — identisch zur Todo-`StreakEngine`.
- **Verlauf:** 91 Tage / 26 Wochen / 6 Monate, berechnet über
  Tages-Buckets (EIN Durchlauf über alle Sessions je Ziel, danach nur
  Tagessummen) — damit auch mit vielen Zielen flott.
- Quellen: Aktivität · Kategorie · **Bildschirmzeit** (echte Zeit aus
  Digital Balance, M18.138-Regel) · **erledigte Aufgaben** · **manuell**
  (Check-ins). Count-Ziele zählen Sessions bzw. Häkchen.
- `periodKey` (z. B. `2026-10-17`, `W2026-42`, `2026-10`) als Grundlage
  der Einmal-Feier.

### 2.4 UI (komplett neu verdrahtet)

- **Einstellungen → „Ziele & Gewohnheiten" → „Ziele"** — die alte
  Goals-Basis aus M18.138 war KOMPLETT verwaist (keine Navigation, nichts
  verlinkt). Jetzt: Settings → Liste → Editor → Detail; ein
  `GoalArchitectureWiringTest` pinnt die gesamte Kette gegen erneutes
  Verwaissen.
- **Liste:** Hero („X/Y heute geschafft", beste Serie, Anzahl),
  Abschnitt „Heute fällig" zuerst, Karten mit Fortschritts-Ring (bei
  Höchstens-Zielen = Rest-Prozent), 🔥-Serie, Status-Pill
  (Fällig/Erreicht/Frei), **Schimmer-Animation** auf erreichten Zielen,
  Abhaken-Knopf direkt auf manuellen Karten.
- **Detail:** großer Ring, Statistik-Kacheln (aktuelle/beste Serie,
  erfüllte Perioden, aktueller Stand), **Balken-Verlauf** (14 Tage /
  8 Wochen / 6 Monate) mit Ziellinie, **13-Wochen-Heatmap** („honest
  record": Intensität = Anteil am Ziel; freie Tage neutral), Check-in-Liste
  mit Einzel-Löschen (manuell), Bearbeiten/Archivieren/Löschen.
- **Editor:** 5 **Schnellstart-Vorlagen** (genau die Nutzer-Beispiele:
  „Digital unter 2 h/Tag", „Digital unter 10 h/Woche", „Lernen 2 h Mo–Fr",
  „4× Aufgaben Mo–Fr", „Tägliche Gewohnheit" — setzen auch einen Titel,
  solange keiner eingetippt ist), Automatisch/Manuell-Umschaltung mit
  Erklärtext, Quellen-Chips (Aktivität/Kategorie/Bildschirmzeit/Aufgaben)
  mit passenden Picker-Menüs und Hinweistexten, Zeitraum, Zieltyp
  (mindestens/höchstens/Anzahl), Wert+Einheit, Wochentage (Schnellfilter
  „Jeden Tag/Mo–Fr/Wochenende" + 7 tippbare Pills), Farbwahl (8 Akzente +
  Automatisch), Live-Vorschau.
- **Dopamin:** Feder-Bounce am Knopf beim Abhaken; **Konfetti-Feier**
  (Vollbild, 120 Partikel in Ziel-Farbe + Palette, Karte mit Titel, Serie,
  Zeitraum; Tipp oder Auto-Ende nach ~3,3 s) — genau **einmal je Ziel und
  Periode** (`GoalCelebrationStore`, Schlüssel = `periodKey`; „Rückgängig"
  gibt die Feier bewusst wieder frei); Schimmer auf erreichten Karten.
- **Farben:** 8 Akzente (Eis, Feuer, Gold, Indigo, Grün, Orange, Pink,
  Violett) — gespeichert wird nur der Schlüssel; ohne Wahl erbt das Ziel
  die Aktivitätsfarbe (dann Theme-Farbe).

### 2.5 Vergleich mit ähnlichen Apps (Recherche → Design)

`docs/M20-ZIEL-ARCHITEKTUR-RECHERCHE.md` (+ 2 Quellen-Dateien): 12 Apps
(Streaks, Loop Habit Tracker, Habitica, HabitNow, Forest, Fabulous,
Strides, Done, Way of Life, Finch, Apple Fitness, Samsung Health) als
Feature-Matrix. Umgesetzt wurden u. a.: **Auto-Tracking als
Standard-Erfüllungspfad** (klarstes Premium-Signal der Matrix),
**flexible Schedules** (nicht fällige Tage sind safe), **Min/Max/Anzahl
als eigene Zieltypen** (statt alles auf „abhaken" zu mappen),
**Fortschrittsring als Primär-Visual**, **Heatmap als ehrlicher Rückblick**
(„erfüllt = Farbe, frei = neutral; nie Schuld-rot"), **Bestwert geht nie
verloren** (★-Idee von Streaks als „Beste Serie"). Bewusst NICHT in M20:
Skip-/Freeze-/Urlaubs-Mechaniken, Benachrichtigungen für Ziele,
Habit-Score (weicher Verfall) — in der Recherche als Ausbaustufen
dokumentiert. Die Feier ist skippbar (Anti-Beispiel Fabulous).

---

## 3) App-Aufzeichnung — Ursachen + Fix

Nutzer-Report: Start → nach 10 s Duolingo (als „Lernen" zugewiesen) →
„Digital" startete nach ~2 min, „Lernen" erst spät und nur kurz.

Drei strukturelle Ursachen (Code-belegt):

1. **Faltung auf PAKET-Ebene statt Activity-Ebene.** Beim App-Start ist
   kurz „Splash → MainActivity" aktiv; auf Paket-Ebene leerte der Wechsel
   den Vordergrund-Zustand, sodass der frische App-Fokus nicht erkannt
   wurde → nach Ablauf der Vorlaufzeit lief „Digital" an.
   **Fix:** `ForegroundTracker` faltet auf **(Paket + Activity-Klasse)**
   (`className = event.className`); Splash→Main bleibt derselbe
   Vordergrund. Regressionstests: `ForegroundTrackerTest`,
   `AppForegroundResolutionTest`.
2. **Beweislage zu schwach + keine Selbstheilung.** `ForegroundAppProbe`
   hatte nur ein fixes, altes Fenster; ein leerer Zustand blieb leer.
   **Fix:** gestufte Auflösung — frischer Schnappschuss (< 2 min) →
   2-min-Faltung → **weite 12-h-Baseline**; leerer Zustand wird
   re-baselined (Selbstheilung statt Stillstand).
3. **Wiederbelebungs-Pfade verschluckten Fehlschläge; `isAlive` verpasste
   „läuft, pollt aber nicht".** **Fix:** EIN Einstieg
   `ensureAlive(context, reason)` für Watchdog, Screen-Events, App-Start
   und Boot; neues **`isPollFresh(interactive)`**; Startverbote
   (Android-12-Hintergrundstart) werden explizit ins Protokoll
   geschrieben (mit konkretem Hinweis: Akku-Optimierung ausnehmen).

Zusätzlich:

- **Boot:** 60-s-WakeLock + `AppTrackingBootCheckWorker` prüfen direkt
  nach dem Neustart, ob die Aufzeichnung läuft (der Fall „Handy
  eingeschaltet" aus dem Report).
- **Diagnose-Karte** im App-Aufzeichnungs-Screen (2-s-Live): Service
  lebendig?, Alter des letzten Polls, Vordergrund-Schnappschuss, Anzahl
  getrackter Apps, Akku-Ausnahme ja/nein, Protokoll kopieren/leeren.
  Der Nutzer kann damit SELBST sehen, was die Aufzeichnung gerade tut.

**Ehrlicher Status:** Der Fix ist vollständig im Code, kompiliert und ist
durch Wiring-/Logik-Tests abgesichert — aber **auf-Gerät nicht von hier
verifizierbar** (kein Handy angeschlossen). Die Diagnose-Karte existiert
genau deshalb: Verhalten + Protokoll auf dem Gerät zeigen unmittelbar, ob
der Fall „App-Start → Duolingo → Lernen sofort" jetzt sauber greift.

---

## 4) Verifikation (alles real ausgeführt)

```
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin   → BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest                                    → 1230 Tests, 0 Fehler, 1 Skiped(Alt)
  davon neu/erweitert:
    - GoalProgressAnalyticsTest        (5 Alt- + 11 neue M20-Fälle)
    - SchemaV47Test                    (Struktur-Beweis v46 → v47)
    - GoalV47MigrationTest             (echte SQLite-Datei, CASCADE, Hash)
    - GoalArchitectureWiringTest       (Settings→Route→NavHost→Screen)
    - AppTrackingDigitalWiringTest     (M20-Pins, erweitert)
    - ForegroundTrackerTest / AppForegroundResolutionTest
    - PmRemovalMigrationTest           (Kette jetzt bis v47, angepasst)
/root/ai-projects/robust_build.sh … aevum-m20-apk -- assembleDebug  → exit 0
  signing-verify OK: app-debug.apk = kanonischer Key (SHA-1 9c3055c4…)
```

**APK:** `/root/builds/Aevum-1.0.40.apk`
- Größe: 124.195.206 Bytes (≈ 118,4 MB, Debug-Build)
- SHA-256: `042c6aa9375547ac7de63fe60c161ce079cfb48e07c9902c5a3deed4cf0be939`
- Version: 1.0.40 (versionCode 41), targetSdk 36, minSdk 29
- Installierbar als Update über 1.0.39 (gleicher kanonischer Debug-Key).

---

## 5) Neue/geänderte Kerndateien

**Neu (Ziele):** `data/model/GoalCheckin.kt`, `data/db/GoalCheckinDao.kt`,
`data/goals/GoalCelebrationStore.kt`, `ui/screens/goals/GoalComponents.kt`,
`ui/screens/goals/GoalDetailScreen.kt`, `ui/screens/goals/GoalDetailViewModel.kt`,
`domain/analytics/GoalProgressAnalytics.kt` v2, Tests (s. o.), Schema 47.json.

**Neu (App-Aufzeichnung):** `automation/apptracking/AppTrackingLog.kt`,
`AppForegroundResolution.kt`, `ForegroundTracker.kt`, `ForegroundAppProbe.kt`,
`AppTrackingBootCheckWorker.kt`.

**Geändert:** `AppDatabase.kt` (v47 + MIGRATION_46_47), `Goal.kt` (+4 Spalten),
`GoalsViewModel/GoalsScreen/GoalEditorViewModel/GoalEditorScreen.kt`
(vollständiger Umbau), `AppNavHost/AppDestination/SettingsScreen` (Verdrahtung),
`AppTrackingService.kt` (ensureAlive/isPollFresh/Rebaseline/Diagnose),
`ScreenRecordingWorker.kt`, `AppTrackingWatchdogWorker.kt`,
`ScreenEventReceiver.kt`, `BootReceiver.kt`, `AevumApplication.kt`,
`MainActivity.kt`, `AndroidManifest.xml` (WAKE_LOCK), Strings (EN+DE),
`docs/CHANGELOG.md`.

---

## 6) Bewusst nicht in M20 (Ausbaustufen)

- Skip-/Freeze-/Urlaubs-Tage, Streak-Reparatur (Recherche-Idee 3+4).
- Benachrichtigungen/Erinnerungen für Ziele (z. B. „Abend-Nudge offen").
- Habit-Score (weicher Verfall, Loop-Muster) — bewusst bei harter Serie
  belassen, Bestwert bleibt immer sichtbar.
- Ziel-Widget / Notification-Quick-Check (Recherche-Idee 8, größter
  Retention-Hebel — aber eigenes Kapitel).

## 7) Bekannte Einschränkungen

- Bildschirmzeit-Verlauf lädt beim ersten Öffnen der Ziele-Seite einmalig
  ~192 Tage (UsageStats-Abfrage) — kann einen Moment dauern; danach
  60-s-Takt wie überall.
- Monats-Verlauf für Digital-Ziele: Fenster 6 Monate (deckt alle
  dargestellten Perioden).
- UI ist JVM-kompiliert und verdrahtungs-getestet; ein Instrumented-/
  Emulator-Lauf war in dieser Umgebung nicht Teil der Verifikation.

# M21 — Abschlussbericht: Dashboard-Umbau + ehrliche Bildschirmzeit

**Datum:** 2026-10-18
**Version:** Aevum 1.0.41 (versionCode 42)
**DB-Schema:** unverändert v47 (keine Migration nötig)

---

## 1. Anlass

Zwei Aufträge aus einer Nachricht:

**A) Dashboard-Umbau** (wörtlich): „Die Ziele hätte ich gerne richtig fancy im
dashboard abgebildet, also irgendwie mit einer farbigen bar die den fortschritt
anzeigt und schimmert, und bei manueller eingabe sollte die auch auf dem
dashboard möglich sein. Dafür können die zwei Blöcke ‚Größter Block' und
‚Offene Zeit' komplett weg und statt, dass die tagespauschalen ihren eigenen
block unten kriegen sollten die genauso wie die anderen aktivitäten unter ‚wo
deine Zeit hingeht' angezeigt werden, allerdings weiterhin klickbar so wie sie
es jetzt sind, gerne auch mit ‚Pauschale' hinter dem aktivitätsnamen
gekennzeichnet. Wenn ich oben durch die tage skippe kann das Diagramm ‚Dein
Güte Verlauf' kompmlett verschwinden, der soll nur ‚Heute' sichtbar sein."

**B) Digital-Balance-Bug** (wörtlich): „Ich habe gerade erneut Duolingo
geöffnet und es wurde nicht ‚Lernen' aufgezeichnet wie ich es wollte. Ich habe
dann aber festgestellt, dass Duolingo auch unter dem Tab Digital Balance gar
nicht aufgetaucht ist. Generell hat digital balance angezeigt ich hätte heute 5
Minuten bildschirmzeit, aber ich habe ja noch die app digital balance, da habe
ich nachgeschaut und hatte bereits 10 Minuten bildschirmzeit, außerdem wurde da
auch duolingo angezeigt mit 4 Minuten nutzung."

---

## 2. Dashboard (A)

### 2.1 Ziele-Karte („Dopamin-Block", oben im Sheet)

Neue Karte `DashboardGoalsCard` in `DashboardScreen.kt`, gespeist von einem
eigenen `DashboardGoalsViewModel` (bewusst getrennt vom 1.500-Zeilen-
`DashboardViewModel`):

- **Farbige Schimmer-Bar** je Ziel: Gradient in der Akzentfarbe des Ziels
  (Palette `GoalAccents` → Aktivitätsfarbe → Theme), animiertes Einlaufen
  (`animateFloatAsState`, 850 ms), laufender Glanz über `Modifier.shimmering`
  — bei erreichtem Ziel **schneller (2,2 s statt 3,8 s) und stärker**.
- **Direktes Abhaken manueller Ziele:** 28-dp-Knopf „+" → „✓" sobald heute
  abgehakt; darunter „N× heute" mit „↩" zum Rückgängig (löscht den letzten
  Check-in von heute, `GoalCelebrationStore.forget` gibt die Feier frei).
- **Kontext:** Akzent-Punkt, Titel, Streak-Flamme (🔥 ab 2), Fortschrittstext
  (`GoalProgressAnalytics`-Format), Zeile öffnet die Detail-Ansicht,
  Kopfzeile „Alle" den Ziele-Screen, „+N weitere" wenn mehr als 4 Ziele
  relevant sind.
- **Feier-Overlay** (`GoalCelebrationOverlay`) liegt jetzt auch auf dem
  Dashboard — Dedupe je Ziel+Periode über den Store, damit die Feier nicht
  doppelt im Ziele-Screen auftaucht.
- Auswertung identisch zum Ziele-Screen: `evaluateGoal` mit Sessions,
  Typen, Kategorien, Bildschirmzeit (32-Tage-Takt + 192-Tage-Verlauf) und
  Check-ins.

### 2.2 Entfernt: „Größter Block" und „Offene Zeit"

`buildInsights` erzeugt nur noch Reflexions- und Vielfalt-Insight (bzw. den
Ruhe-Hinweis) — maximal 2 Kacheln. Zusätzlich ist der Satz „… sind noch nicht
erzählt" (`dashboard_narrative_open_time`) aus dem Erzähltext entfernt; die
Berechnung der offenen Zeit bleibt nur noch, wo sie fachlich gebraucht wird
(Balance-Score).

### 2.3 Tagespauschalen in „Wo deine Zeit hingeht"

`computeQualityBreakdown` erzeugte Pauschalen schon als Slice — sie waren aber
nicht als solche erkennbar und nicht klickbar. Jetzt:

- `QualitySlice` hat `isAllowance` und `allowanceId`;
- die Zeile zeigt **„<Name> · Pauschale"** (`dashboard_allowance_suffix`),
  gleiche Optik wie Aktivitäten (Icon-Kreis, Prozent, Dauer, Balken), und ist
  **klickbar** → derselbe `AllowanceDayPopup` wie vorher (Tages-Override
  bearbeiten/löschen);
- der eigene Block `DashboardAllowancesRow` wird nicht mehr aufgerufen
  (Funktion bleibt für Kompatibilität stehen).

### 2.4 „Dein Güte-Verlauf" nur am heutigen Tag

`if (isShowingToday) { QualityTrendCard(trend = qualityTrend) }` —
`isShowingToday = state.displayedDate == LocalDate.now()`. Beim Blättern
durch die Tage verschwindet das Diagramm vollständig.

### 2.5 Navigation

`AppNavHost` reicht `onOpenGoals` (→ `AppDestination.Goals`) und
`onOpenGoalDetail` (→ `goal_detail/{id}`) an das Dashboard durch.

---

## 3. Digital Balance (B) — Ursache und Fix

### 3.1 Befund (code-belegt)

Die UsageStats-Events wurden in `AppUsageAggregator` **auf Paket-Ebene**
gefaltet: `foregroundSince[packageName]` beim Foreground-Event,
`foregroundSince.remove(packageName)` bei **jedem** Background-/Pause-/Stop-
Event. Beim App-Start ist die Eventfolge aber:

```
RESUMED(Splash)  t0
RESUMED(Main)    t1   → Startzeit überschrieben (t0..t1 verloren)
PAUSED(Splash)   t2   → Phase des GANZEN Pakets beendet, obwohl Main offen ist
STOPPED(Main)    t4   → kein offenes Fenster mehr → nichts mehr gezählt
```

Ergebnis:

- Duolingo-Sitzung ≈ **0 ms** → unter dem 1-s-Filter in `todayUsageByApp()`
  → **„taucht gar nicht auf"** (genau die Beobachtung).
- Tagessumme (`todayDetail`, `todayForegroundTotalMs`, `dailyTotals`) zu
  klein → **5 min statt 10 min**.
- Derselbe Fehler traf die App-Aufzeichnung indirekt: `ForegroundTracker`
  arbeitete zwar seit M20 auf Activity-Ebene, hatte aber keinen Schutz, wenn
  ein `PAUSED` **ohne** brauchbaren Klassennamen kommt (OEM) — dann fiel er
  auf Paket-Ebene zurück und leerte den Vordergrund mitten in der App
  („Digital" statt „Lernen").

### 3.2 Fix

**Neu: `domain/digital/UsageEventFolding.kt`** (pure Kotlin, JVM-getestet):

- Fenster je **(Paket, Activity)**, Klassenname normalisiert
  (`com.pkg.MainActivity` ≡ `.MainActivity`);
- ein Background-Event schließt **nur** das Fenster derselben Activity;
- passt die Kennung nicht (OEM), wird das **älteste** offene Fenster des
  Pakets geschlossen (Splash zuerst geöffnet — genau richtig);
- am Ende **Vereinigung je Paket** (disjunkt, keine Doppelzählung bei
  überlappenden Activities), offene Fenster laufen bis Fensterende,
  Clipping am Query-Start;
- doppeltes `RESUMED` derselben Activity öffnet kein zweites Fenster.

**`AppUsageAggregator`:** alle Aggregationen laufen jetzt über diese Faltung
(`foregroundPhases`, `todayDetail`, `todayUsageByApp`,
`todayForegroundTotalMs`, `usageTodayFor`, `dailyTotalsForRange`). Zusätzlich
werden Stunden-Buckets **stundenweise** zugeordnet (vorher zählte eine
3-Stunden-Sitzung komplett in die Startstunde) — lokal-zeitzonenkorrekt.

**`ForegroundTracker` v2:** Zustand `ForegroundState` mit **Menge offener
Activities je Paket**:

- `PAUSED`/`STOPPED` der Splash-Activity lässt die offene Haupt-Activity
  stehen;
- `PAUSED` **ohne** Klassennamen ist mehrdeutig → nichts wird abgeräumt,
  solange mehrere Activities offen sind;
- endet die **aktuelle** Activity selbst, ist die App zu — liegengebliebene
  Fenster werden mit entfernt (keine Leichen);
- App-Wechsel verwirft die offenen Activities des alten Pakets.
- Gleiche Semantik für Service-Polling (`AppTrackingService`) und
  `ForegroundAppProbe` (`ScreenRecordingWorker`).

### 3.3 Was der Fix NICHT ist

Kein Eingriff in die System-Berechtigung und kein „mehr Zeit erfinden": Die
Zahlen kommen weiterhin ausschließlich aus `UsageStatsManager.queryEvents`.
Wenn der Systemzugriff fehlt, bleibt es bei 0 — die Diagnose-Karte der
App-Aufzeichnung sagt das aus.

---

## 4. Dateien

**Neu**
- `domain/digital/UsageEventFolding.kt`
- `ui/screens/dashboard/DashboardGoalsViewModel.kt`
- `test/.../domain/digital/UsageEventFoldingTest.kt`
- `test/.../ui/screens/dashboard/DashboardM21WiringTest.kt`
- `docs/M21-ABSCHLUSSBERICHT.md` (dieses Dokument)

**Geändert**
- `domain/digital/AppUsageAggregator.kt` (Faltung, Stunden-Split)
- `automation/apptracking/ForegroundTracker.kt` (ForegroundState-Menge)
- `automation/apptracking/AppTrackingService.kt`, `ForegroundAppProbe.kt`
  (Call-Sites)
- `ui/screens/dashboard/DashboardViewModel.kt` (Insights, Narrativ,
  QualitySlice-Felder, Pauschalen-Kennzeichnung)
- `ui/screens/dashboard/DashboardScreen.kt` (Ziele-Karte, Pauschalen-Zeile,
  Güte-Verlauf-Guard, Feier-Overlay)
- `navigation/AppNavHost.kt` (Ziele-Navigation vom Dashboard)
- `res/values/strings_timeline_dashboard.xml` + `values-de/…`
  (5 neue Strings)
- `app/build.gradle.kts` (versionCode 42 / 1.0.41)
- `test/.../automation/apptracking/ForegroundTrackerTest.kt` (Mengen-Zustand,
  M21-Fälle)

---

## 5. Tests

- `UsageEventFoldingTest` — 9 Fälle: Duolingo-Splash (4 min exakt),
  fehlender `className`, zwei Apps, Überlappung ohne Doppelzählung, offene
  App bis Fensterende, Clipping, Kurz-/Langform, doppeltes RESUMED,
  Event-Zähler.
- `ForegroundTrackerTest` — bestehende M18/M20-Fälle auf `ForegroundState`
  portiert + 3 M21-Fälle (PAUSED ohne Klasse, App-Wechsel, Stop eines nie
  gestarteten Splash).
- `DashboardM21WiringTest` — 6 Pins: Ziele-Karte verdrahtet (inkl.
  Schimmer), eigenes ViewModel mit echter Auswertung, Navigation zu Liste/
  Detail, entfernte Blöcke, Pauschalen-Zeile (klickbar, nur eine
  `DashboardAllowancesRow`-Referenz = Definition), Güte-Verlauf-Guard.
- Volle Suite: **grün** (siehe Abschnitt 6).

---

## 6. Build & Release

- Volle Testsuite: **1248 Tests, 0 fehlgeschlagen, 1 übersprungen**
  (`./gradlew --offline testDebugUnitTest`, BUILD SUCCESSFUL).
- `robust_build.sh premium-android-app aevum-m21-apk -- assembleDebug` →
  `BUILD SUCCESSFUL in 1m 7s`, Runner rc=0, `signing-verify OK` (kanonischer
  Debug-Key, SHA-1 `9c3055c4…`).
- APK: 124.295.698 Bytes (118,5 MB), SHA-256
  `9a23252cfb404248ea29b398769ef83ed62a915163142698de6fc1037911765c`
  → `/srv/aevum-apk/Aevum-1.0.41-Debug.apk` (HTTP 200),
  `http://192.168.178.40:8098/` verlinkt 1.0.41; Kopie
  `/root/builds/Aevum-1.0.41.apk`.
- Commit `1908b37`, gepusht auf `origin/main`.
- Vault: `Aevum-Änderungen.md` (M21-Abschnitt), `Projekt-Aevum.md`
  (`letzte-version` 1.0.41).

## 7. Verifikation am Gerät (Nutzer)

1. **Digital Balance:** Duolingo einige Minuten öffnen → App muss in der
   Liste stehen (mit ~der Zeit, die auch die System-App zeigt) und die
   Tagessumme muss zur System-Digital-Balance passen (± wenige Sekunden).
2. **App-Aufzeichnung:** Duolingo zugewiesen lassen → beim Öffnen muss
   sofort „Lernen" laufen (nicht „Digital"), Dauer ~= tatsächliche Nutzung.
3. **Dashboard:** Ziele-Karte oben mit Schimmer-Bars; manuelles Ziel direkt
   abhaken („+" → „✓", Zähler, „↩"); „Größter Block"/„Offene Zeit" weg;
   Pauschale als Zeile mit Suffix in „Wo deine Zeit hingeht", antippbar;
   einen Tag zurückblättern → „Dein Güte-Verlauf" verschwindet.

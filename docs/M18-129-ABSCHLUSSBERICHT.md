# M18.129 — ABSCHLUSSBERICHT: Kalender-Integration

**Datum:** 2026-09-16
**Branch:** `hermes/m18.129-calendar-integration`
**Version:** versionCode 13 / 1.0.12
**DB:** v40 → **v41**
**Design-Dokument:** `docs/M18-129-CALENDAR-DESIGN.md` (Vorab-Reflexion, siehe unten)

---

## 1. Was gebaut wurde

Ein vollständiges Kalender-Feature mit Regeln, Auto-Aufzeichnung,
7-Tage-Vorausschau, Sync-Steuerung und Berechtigungs-Flow.

### 1.1 Regeln: Bedingung → Aktivität

Das Kernproblem des Auftrags — „die App zeichnet Activity Types auf, der
Kalender hat nur Titel und Beschreibung" — ist mit **expliziten Regeln**
gelöst (nicht mit Fuzzy-Matching, siehe Design-Dokument § 1):

> WENN Titel **oder** Beschreibung „Vorlesung" oder „Übung" enthält
> DANN Activity **Studium** aufzeichnen (Start = Termin-Start, Ende = Termin-Ende)

**7 Regeltypen** (Auftrag verlangte „denke dir weitere aus"):

| Typ | Zweck |
|---|---|
| Wort in Titel oder Beschreibung | Der Standardfall aus dem Beispiel |
| Wort nur im Titel | z. B. `VL 12` |
| Wort nur in der Beschreibung | z. B. `Übungsblatt` |
| Regulärer Ausdruck (Titel) | Muster wie `^VL\s+\d+` |
| Bestimmte Kalender (Mehrfachauswahl) | nur Uni-/Arbeitskalender |
| Teilnehmer enthält | z. B. `@uni-` |
| Nur ganztägige Termine | Urlaub, Feiertage |

**Pro Regel zusätzlich konfigurierbar:**
- UND/ODER über mehrere Wörter (`requireAllWords`) — Wörter dürfen über
  Titel und Beschreibung verteilt sein
- Groß-/Kleinschreibung
- Mindestdauer (z. B. ≥ 10 min, filtert Erinnerungen)
- Zeitfenster (z. B. nur 06:00–22:00; mitternachtsübergreifend korrekt)
- Wochentage (Bitmaske, Mo–So-Chips)
- Eigener Session-Titel (sonst Name der Activity)
- **Priorität** — bei mehreren Treffern gewinnt die höhere
- **Verhalten bei Überschneidung**: laufende Aufzeichnung übernehmen
  (Default) oder nur starten, wenn nichts läuft

### 1.2 Automatisches Starten und Stoppen

Ein selbst-erneuernder WorkManager-Job startet und stoppt Aufzeichnungen an
Termingrenzen. Er plant sich **auf die nächste Termingrenze** (Deckel
15 min), läuft also pünktlich um 10:15 statt „irgendwann zwischen 10:15 und
10:30". `startedAt` wird auf den **echten Terminbeginn** gesetzt — ein
verspäteter Lauf schneidet den Anfang nicht ab.

### 1.3 7-Tage-Vorausschau in der Timeline

Für die nächsten 7 Tage zeigt die Timeline vorab **nur die Termine, die
durch die eigenen Regeln tatsächlich aufgezeichnet würden** — kein
pauschaler Kalender-Dump.

Darstellung wie gefordert:
- **diagonal gestrichelt** (Textur, `TileMode.Repeated`) + gestrichelte Kontur
- **in Aktivitätsfarbe, mit Icon** und Titel-Zeit-Label („⋯ Studium · 10:15–11:45")
- **klar unterscheidbar** von echten Blöcken (Alpha 0.34 vs. 0.62)
- **nicht klickbar** (kein `pointerInput`) — ein Plan ist keine Aufzeichnung
- **nirgends mitgezählt** — eigener Typ `PlannedSessionUi`, nicht
  `TimelineSessionUi`; damit strukturell unmöglich, dass ein Plan in Summen,
  Insights oder Statistiken einfließt
- auch in der **Wochenansicht** (7 Spalten)

### 1.4 Einstellungen: Regeln verwalten

Einstellungen → **Kalender**:
- Regel-Liste mit Aktivieren/Deaktivieren (Switch), **Bearbeiten**, **Löschen**
  (mit Bestätigungsdialog) und **Hinzufügen**
- Jede Zeile zeigt die Bedingung in Klartext plus die Ziel-Activity
- GELÖSCHTE Activity wird ehrlich als „Aktivität fehlt (bitte neu wählen)"
  angezeigt statt still nicht ausgeführt

### 1.5 Sync: manuell, automatisch, akku-schonend

- **Manueller Button** mit Spinner + Ergebnis-Meldung („Synchronisiert: 12 Termine.")
- **Zeitstempel-Textfeld**: „Zuletzt synchronisiert: heute 14:23"
  (auch bei 0 Terminen gesetzt — „erfolgreich leer" statt altem Datum, das
  wie ein Fehler wirkt)
- **Automatisch** alle 1/3/6/12/24 h (Default **6 h**), Constraint
  **`BATTERY_NOT_LOW`** — kein Sync auf dem letzten Prozent
- **Kein exakter Alarm**, keine `SCHEDULE_EXACT_ALARM`-Berechtigung
  (ab Android 14 nicht mehr automatisch erteilt — unnötige Hürde)
- **Kein Dauerbetrieb**: Der Kalender wird nur während des Syncs gelesen;
  der Minutentakt liest ausschließlich den lokalen Cache
- Termin-Zähler („12 Termine zwischengespeichert")

### 1.6 Berechtigung: erteilen, sehen, widerrufen

`READ_CALENDAR` mit **4-State-Modell**:
- **Nicht gefragt** → Erklärkarte „Kalender verbinden" + „Zugriff erlauben" / „Erstmal ohne"
- **Erteilt** → Banner verschwindet, volle Funktionalität
- **Abgelehnt** → „Erneut versuchen"
- **Dauerhaft gesperrt** → nur noch „App-Einstellungen öffnen" (ein weiterer
  Dialog würde stillschweigend nichts tun)

**Widerruf wird erkannt:** Der Status wird bei jedem `ON_RESUME` neu gelesen.
Entzieht der Nutzer die Berechtigung in den System-Einstellungen, zeigt die
App sofort wieder den Banner, es laufen keine neuen Aufzeichnungen — und die
**Regeln bleiben erhalten**.

**Feature-Trennung:** „Kalender lesen" und „Automatisch aufzeichnen" sind
zwei Schalter. Vorschau ohne Auto-Aufzeichnung ist ein legitimer Wunsch.
Der Auto-Schalter ist disabled, solange Lesen aus ist — ehrliche UI statt
stillem Fehlschlag.

---

## 2. Vorab-Reflexion (Auftrag: „hinterfrage zuerst")

Vollständig in `docs/M18-129-CALENDAR-DESIGN.md` (15 KB). Kernpunkte:

**Usability-Entscheidungen:**
- Explizite Regeln statt Fuzzy-Matching (Begründung: „Vorlesung Analysis"
  würde scheitern, Nutzer müsste Kalender-Titel umbenennen)
- Das „oder/und" aus dem Auftrag wurde als **sichtbare Toggles** gelöst
  statt als versteckte Default-Logik
- Transparenz vor Aktion: Der Nutzer sieht die nächsten 7 Tage vorab, was
  passieren wird, und kann die Regel korrigieren, **bevor** falsche Daten
  entstehen
- Anti-Features bewusst ausgeschlossen: kein Schreiben in den Kalender,
  keine Kalender-Benachrichtigungen (dupliziert), kein Auto-Löschen bei
  verschwundenen Terminen, kein exakter Alarm

**6 Risiken mit Gegenmaßnahmen** (im Design-Dokument ausgeführt):
Room-Schema-Mismatch, Auto-Start-Kollision mit anderen Automatiken,
Endlos-Session, Zukunfts-Bug in der Timeline, Timeline-Performance,
Permission-Entzug mitten im Sync.

---

## 3. Verifikation

### 3.1 Build
```
BUILD SUCCESSFUL (testDebugUnitTest assembleDebug)
```
- APK: `app/build/outputs/apk/debug/app-debug.apk`, **118 MB**
- Signatur: **kanonischer Key** `9c3055c4…` durch `apksigner` bestätigt
  (robust_build.sh v4 Guard: Signing-Check vor + nach dem Build)
- `READ_CALENDAR` ist in den APK-Permissions nachgewiesen (aapt dump)

### 3.2 Tests
**574 Tests, 0 Fehler, 0 Exceptions** (52 Testklassen).

Neu für M18.129 — **69 Tests**:
- `CalendarMatchEngineTest` — **34 Tests**: alle 7 Regeltypen, UND/ODER
  (auch über Feldgrenzen verteilt), case-sensitivity, Regex (inkl.
  **kaputtem Regex → kein Treffer statt Crash**), Kalender-Auswahl,
  Mitternachts-Zeitfenster, Wochentags-Bitmaske, Mindestdauer, Prioritäts-
  Auflösung, **gelöschte Activity → Regel inert statt Crash**
- `CalendarAutoRunEngineTest` — **20 Tests**: pünktlicher/verspäteter/zu
  später Lauf, Doppelstart-Schutz, Stop nicht vor dem Terminende,
  Weckzeit = nächste Termingrenze, **niemals 0 (Schleifenschutz)**,
  Kandidaten-Auswahl nach Priorität/Zeit
- `PlannedSessionsTest` — **15 Tests**: Tages-Zuordnung, **kein
  Zukunfts-Leck**, Mitternachts-Clipping, Icon/Farbe/Titel-Vererbung,
  7-Tage-Abdeckung, Sortierung, eindeutige IDs

Geändert: `M12RegressionTest.autoSourcesContainsAllAutomaticTypes` —
erweitert um `CALENDAR_AUTO`. Das ist die beabsichtigte Funktion dieses
Tests (Wächter über `AUTO_SOURCES`), nicht ein Umgehen einer Regression.

### 3.3 Room-Migration — mit echtem SQLite verifiziert
Nicht „sieht gut aus", sondern nachgerechnet: v40-Schema aus dem generierten
JSON in SQLite aufgebaut → Migrations-SQL angewandt → gegen das v41-Schema
gediffed (`PRAGMA table_info` / `index_list` / `foreign_key_list`).

```
== ROOM-SCHEMA-VALIDIERUNG: 37 Tabellen ==
  OK - keine Abweichung (Spalten, Typen, NOT NULL, Defaults, Indices, FKs/onDelete)
```
Zusätzlich geprüft: `INSERT` in beide neuen Tabellen funktioniert, und
`ON DELETE SET NULL` auf `calendar_rule.activity_type_id` greift
(Aktivität gelöscht → Feld wird NULL → Regel wird inert).

**Debugging-Notiz (ehrlich):** Ein erster Validierungslauf meldete 18
„FEHLER" bei FK-Verhalten. Ursache war **mein Prüfskript** — `PRAGMA
foreign_key_list` liefert `on_update` als 6. und `on_delete` als 7. Spalte;
ich las die falsche. Nach Korrektur: alle grün. Kein Code-Fehler.

### 3.4 Was NICHT geräteseitig bestätigt ist (ehrliche Trennung)

**Gebaut + code-reviewed + getestet:**
- Engine-Logik (69 Unit-Tests, inkl. aller Randfälle)
- Migration (SQLite-verifiziert)
- Build, Signatur, Permissions

**Nicht geräteseitig bestätigt** (kein Gerät in dieser Umgebung):
- Ob der **ContentResolver auf dem konkreten Gerät** die Kalender-Spalten
  `CALENDAR_DISPLAY_NAME` / `ATTENDEE_EMAIL` exakt so liefert (Hersteller
  können hier abweichen)
- Ob die **Auto-Start-Pünktlichkeit** auf dem Gerät dem 15-Minuten-Takt
  entspricht — Hersteller-Batterieoptimierung kann WorkManager verzögern
  (das Design verkraftet das: 20-Minuten-Starttoleranz, Terminbeginn wird
  rückdatiert)
- Ob die **gestrichelte Textur** optisch so wirkt wie beabsichtigt

**Geräte-Verifikations-Checkliste:**
```
adb logcat -s CalendarReader:V         # "Kalender gelesen: N Vorkommen im Fenster"
adb logcat -s CalendarSyncWorker:V     # Gates: deaktiviert / keine Permission
adb logcat -s CalendarAutoRunWorker:V  # "Kalender-Auto-Start: 'Titel' → Session <id>"
```
1. Regel „Vorlesung/Übung → Studium" anlegen
2. Timeline öffnen → gestrichelte Blöcke für 7 Tage, **nicht** in den Summen
3. Termin in 2 Minuten anlegen → Aufzeichnung startet um die Uhrzeit
4. Termin-Ende abwarten → Aufzeichnung stoppt
5. Berechtigung in System-Einstellungen entziehen → zurück zur App →
   Banner sofort da, Regeln noch da, keine neuen Aufzeichnungen
6. Manueller Sync → Zeitstempel aktualisiert sich

---

## 4. Dateien

**Neu (17):**
```
data/model/CalendarRule.kt              (Entity + RuleType + OverlapPolicy)
data/model/CalendarEventCache.kt        (Entity, idempotenter Schlüssel)
data/db/CalendarRuleDao.kt
data/db/CalendarEventCacheDao.kt
data/repository/CalendarRepository.kt   (Interface + Impl)
domain/calendar/CalendarMatchEngine.kt  (Regel-Matching, pure JVM)
domain/calendar/CalendarAutoRunEngine.kt(Start/Stop-Entscheidungen, pure JVM)
automation/calendar/CalendarReader.kt   (ContentResolver/Instances)
automation/calendar/CalendarSyncWorker.kt
automation/calendar/CalendarSyncScheduler.kt
automation/calendar/CalendarAutoRunWorker.kt
automation/calendar/CalendarAutoRunScheduler.kt
ui/screens/calendar/CalendarRulesScreen.kt
ui/screens/calendar/CalendarRulesViewModel.kt
ui/screens/calendar/CalendarRuleEditorDialog.kt
ui/screens/calendar/CalendarPermissionState.kt
ui/screens/timeline/PlannedSessions.kt  (PlannedSessionUi + 7-Tage-Builder)
ui/theme/PlannedBlockTexture.kt         (Diagonal-Streifen-Brush)
+ 3 Testklassen (69 Tests)
```
**Geändert (14):** `AppDatabase.kt` (v41 + Migration), `DatabaseModule.kt`,
`RepositoryModule.kt`, `AutomationSettingsDao.kt` (4 gezielte Spalten-UPDATEs
statt read-modify-write), `AutomationSettings.kt` (4 Spalten),
`TimelineViewModels.kt` (`plannedSessions` + `plannedNext7Days`, 7-Tage-Build),
`TimelineScreen.kt` (Zeichnung Tag + Woche, Labels, Quell-Label),
`AppDestination.kt`, `AppNavHost.kt`, `SettingsScreen.kt`,
`AevumApplication.kt`, `BootReceiver.kt`, `AndroidManifest.xml`,
`M12RegressionTest.kt`, Strings DE + EN, `app/build.gradle.kts`.
**Docs:** `docs/M18-129-CALENDAR-DESIGN.md`, `docs/FEATURES.md`,
`docs/DECISIONS.md` (ADR-0031).

---

## 5. Bekannte Grenzen (bewusst, keine Bugs)

1. **Der Sync liest ein Fenster von −1 bis +9 Tagen**, nicht den ganzen
   Kalender. Ältere Termine sind für Vorschau und Auto-Start wertlos.
   Blättert der Nutzer in der Timeline weiter zurück, erscheinen dort keine
   Plan-Blöcke (die Historie ist ja bereits aufgezeichnet).
2. **Ändert sich der Kalender innerhalb des Sync-Takts**, wirkt das erst
   nach dem nächsten Sync (max. 6 h) — außer man tippt „Jetzt synchronisieren".
3. **Ganztägige Termine** werden mit ihrer Kalender-Dauer (00:00–00:00 →
   auf den Tag normalisiert) geplant; die Aufzeichnung deckt dann den
   ganzen Tag ab. Das ist für „Urlaub" gewollt, für einen versehentlich
   ganztägig angelegten Termin ggf. überraschend — daher der Filter
   „Nur ganztägige Termine" als eigener, opt-in Regeltyp.
4. **Wiederkehrende Termine mit Ausnahmen** (z. B. eine abgesagte
   Einzelstunde) werden korrekt behandelt, sofern der Kalender sie als
   `STATUS_CANCELED` markiert — was Standard ist; ein manuell gelöschter
   Einzeltermin verschwindet beim nächsten Sync.
5. **WorkManager-Pünktlichkeit** ist gerätespezifisch (siehe 3.4).

---

## 6. Mandatorische Fragen

**1. Was genau wurde gebaut?**
Kalender-Integration mit 7 Regeltypen, Konfigurations-UI (Anlegen/Bearbeiten/
Löschen/Aktivieren), automatischem Start/Stop an Termingrenzen, 7-Tage-
Vorausschau als gestrichelte Plan-Blöcke (Tag- + Wochenansicht),
manueller + automatischer Sync (akku-schonend), Zeitstempel-Anzeige und
4-State-Permission-Flow. DB v41.

**2. Wie ist die Kalender→Activity-Synchronisierung gelöst?**
Über explizite, nutzer-konfigurierbare Regeln (Bedingung → Activity) statt
Fuzzy-Matching. Die Session startet zum Terminbeginn (rückdatiert) und endet
mit dem Terminende; Titel ist der Aktivitätsname (oder ein eigener Titel).

**3. Funktioniert es?**
Logik: ja — 574 Tests grün, davon 69 neue für dieses Feature, Migration mit
echtem SQLite verifiziert, Build erfolgreich, kanonisch signiert. Am Gerät:
**nicht bestätigt** (kein Gerät verfügbar) — Checkliste in § 3.4.

**4. Welche Fehler wurden gefunden und behoben?**
- **Stop-Grace-Fehler:** Die erste Fassung stoppte Aufzeichnungen eine
  Minute **zu früh** (`now >= endAt - 60s`) und schnitt sie ab. Der Test
  deckte es auf; Korrektur auf `now >= endAt` (zu kurz ist die schlimmere
  Fehlerrichtung als ein paar Sekunden zu lang).
- **`lastStartedEventId` war wirkungslos** (nahm immer die Session-ID, die
  nie mit einer eventId verglichen werden konnte) → durch einen echten
  Doppelstart-Schutz über ActivityType + Startzeit-Nähe ersetzt.
- **Fehlender `AppDatabase`-Import in `RepositoryModule`** → kapt-Kaskade
  über alle Bindings, während der Kotlin-Fehler im kapt-Output unsichtbar
  war. Gefunden durch separaten `kaptGenerateStubsDebugKotlin`-Lauf.
- **`MutableStateFlow(NotAsked)`** inferierte den Objekttyp statt der
  Basisklasse → 3 Compile-Fehler. Expliziter Typ-Parameter.
- **Zwei fehlende Strings** (`WALKING_AUTO` fehlte seit M18.72 im
  Detail-Screen-Quell-Label, obwohl der Test `AUTO_SOURCES` es enthielt) —
  beide nachgezogen.
- **Veraltete kapt-Stubs** (`UP-TO-DATE`) verdeckten den echten Fehler →
  `rm -rf app/build/tmp/kapt3/stubs`.

**5. Was ist NICHT Teil dieses Milestones?**
- Kein Schreiben in den Kalender (nur Lesen)
- Keine Kalender-spezifischen Benachrichtigungen
- Kein exakter Alarm / keine `SCHEDULE_EXACT_ALARM`-Berechtigung
- Keine Wiederholungs-Erkennung als eigenes Feature (kommt vom Kalender)
- Keine Plan-Blöcke in Statistiken/Insights (bewusst: Pläne sind keine Daten)
- Keine Bearbeitung/Bestätigung einzelner Plan-Instanzen vorab
  (Review-Inbox für geplante Blöcke) — bewusst weggelassen, weil der Nutzer
  die Regel korrigiert statt jeden Einzelfall zu bestätigen

**6. Was müsste am Gerät noch geprüft werden?**
Siehe Checkliste § 3.4 — besonders: liefert der ContentResolver die
Kalender-Spalten auf dem Gerät, wie pünktlich feuert WorkManager (Hersteller-
Batterieoptimierung), wirkt die Streifen-Textur optisch wie beabsichtigt.

**7. Aufwand / Umfang?**
17 neue Dateien, 14 geänderte, 69 neue Tests, DB-Migration v40→v41,
3 Docs (Design, Features, ADR-0031).

---

## 7. NACHTRAG — Bugfix Tagesansicht (Devon-Report)

**Meldung:** „Ja für die Wochenansicht in der Timeline klappt das
hervorragend, allerdings noch nicht für die Tagesansicht, dort steht an den
jeweiligen Tagen weiterhin nur ‚Noch keine Aktivitäten'."

### Root Cause (gefunden, nicht vermutet)

Der Empty-State-Guard der Tagesansicht in `TimelineScreen.kt`:

```kotlin
if (state.sessions.isEmpty() && state.triggerEvents.isEmpty()
        && state.durationOnlySessions.isEmpty()) {
    EmptyState("Noch keine Aktivitäten", …)      // ← gewann immer
} else {
    DayCalendarTimeline(…)                        // ← nie erreicht
}
```

Die Bedingung prüfte **drei** Inhaltsquellen und kannte die M18.129
geplanten Kalender-Blöcke (`state.plannedSessions`) **nicht**. Ein Tag ohne
echte Aufzeichnung, aber mit passenden Terminen, fiel deshalb in den
Empty-State — und `DayCalendarTimeline` wurde nie aufgerufen, die
Zeichenlogik lief also gar nicht erst. Die Wochenansicht ist über einen
anderen Pfad an diesen Guard vorbei gerendert, weshalb sie korrekt
funktionierte: **ein Guard, der eine Inhaltsquelle vergisst, schlägt genau
dort zu, wo er greift.**

### Fix (drei Ebenen, nicht nur die Symptomzeile)

1. **Guard korrigiert.** Die Bedingung ist in die reine, testbare Funktion
   `TimelineEmptyState.hasDayContent(…)` extrahiert — sie nimmt jetzt alle
   vier Quellen (Sessions, Nur-Dauer, Trigger, **geplante Blöcke**). Die
   Logik liegt bewusst nicht mehr inline im Composable: dort war sie in
   einem tief verschachtelten `AnimatedContent`-Lambda versteckt und leicht
   zu übersehen.
2. **Listenansicht mitgezogen** (`EventListTimeline`). Dort lauerte
   derselbe Bug: `merged.isEmpty()` kannte die Pläne ebenfalls nicht und
   hätte „Keine Ereignisse" gezeigt. Geplante Blöcke sind jetzt ein eigener
   `TimelineEntry.Planned` (mit Sortierung nach Startzeit und
   Tagesabschnitt-Zuordnung), gerendert als Zeile mit ⋯-Präfix, Label
   „Geplant", Aktivitätsfarbe und Icon — konsistent zur gestrichelten
   Darstellung in Tag- und Wochenansicht, aber ohne Bearbeiten/Löschen
   (ein Plan ist keine Aufzeichnung; geändert wird die Regel).
3. **Hinweis für reine Plan-Tage** (`PlannedOnlyHint`). Zeigt an Tagen
   ohne echte Aufzeichnung einen Satz: „N Termin(e) werden voraussichtlich
   aufgezeichnet. Gestrichelte Blöcke sind Prognosen und zählen nicht in
   die Statistik." Verhindert die Fehlannahme, die Aufzeichnung laufe
   schon oder die Anzeige sei kaputt.

### Verifikation

- **11 neue Regressionstests** (`TimelineEmptyStateTest`), die genau den
  gemeldeten Fall festhalten: „Tag mit NUR geplanten Blöcken hat Inhalt" —
  plus je ein Test pro Inhaltsquelle, damit dieser Bug bei der nächsten
  Erweiterung nicht zurückkommt.
- **585 Tests grün** (vorher 574; +11).
- Systematische Prüfung auf weitere vergessene Pfade: alle Vorkommen von
  `sessions.isEmpty()` / `triggerEvents.isEmpty()` / `merged.isEmpty()`
  gegreppt — nur die zwei gefundenen Stellen existierten, beide gefixt.
- Alle 22 Verwendungsstellen von `plannedSessions` / `plannedNext7Days` /
  `plannedByDay` kartiert: Canvas-Zeichnung (Tag), Labels (Tag),
  Listenansicht, Wochenansicht (Zeichnung + Spalten) — vollständig
  verdrahtet.

**Ehrliche Trennung:** Gebaut, code-reviewed und durch Regressionstests
abgesichert; die optische Bestätigung am Gerät steht weiterhin aus
(kein Gerät in dieser Umgebung).

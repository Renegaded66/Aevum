# M19.1 — Projektmanagement (Spezifikation)

> Status: umgesetzt (siehe `M19-1-ABSCHLUSSBERICHT.md`)
> Anlass: Devon, 17.10.2026 — „Großes Aevum-Update: Projektmanagement"

## 1. Ausgangslage (Anwendungsfall, nicht Feature-Wunsch)

Problem des Nutzers, wörtlich zusammengefasst:

- Eine umfangreiche **Präsentation** muss fertig werden. Das Ziel ist nicht
  „schön", sondern **fertig**.
- Große Aufgaben lähmen („kein Ende, kein Fortschritt, keine Fokussierung").
- Gegenmittel: **jede kleinste Aufgabe schriftlich festhalten** und einzeln
  abhaken. Nicht „Präsentation fertig machen", sondern „erste Hälfte lesen",
  „Entwurf aufschreiben", „Thema 1 recherchieren" …
- Das Abhaken soll **Dopamin** ausschütten: farbig, animiert, spielerisch —
  **ohne** Level, Maskottchen oder Experience Points.
- Struktur: Oberprojekt → Unterprojekt → Kategorie → Aufgabe → Todos.
- Todos sollen **einem Tag** zuweisbar sein, ohne 25 Einzelklicks.
- Zukünftige Todos dürfen **ohne Datum** existieren (Backlog).
- Morgens: „diese 20 Todos will ich heute schaffen."
- Sichtbar: heutige Todos **und** der Fortschritt im **Gefäß** (Glas mit
  Juwelen, Feuerwerk wenn voll).
- Todos aus mehreren Aufgaben an einem Tag → elegante Lösung (Swipe).
- Fortschrittsbalken pro Aufgabe/Projekt, damit **langfristiger** Fortschritt
  sichtbar ist, nicht nur der Tageserfolg.

## 2. Abgrenzung — was NICHT Bestandteil ist

- **Kein** Belohnungssystem mit Levels, XP, Maskottchen, Währungen, Shop.
- **Keine** Zeit-/Dauer-Erfassung in PM-Todos (kein Pomodoro, kein Timer) —
  das leistet das bestehende Aktivitäts-Tracking. *Ausnahme:* die optionale
  **Frist** (`due_date`) ist nur informativ und erzeugt keinen Alarm.
- **Keine** Wiederholung/Serien (Recurrence) in PM-Todos. Ein PM-Todo ist ein
  einmaliger kleiner Schritt. Das bestehende Modul „Todos" (`todo`,
  `todo_completion`, Recurrence, Streaks, Auto-Check per Dauer) bleibt dafür
  unverändert und wird **nicht** mit PM vermischt.
- **Keine** Erinnerungen/Benachrichtigungen, keine Worker, kein Sync,
  kein Cloud-Abgleich.
- **Keine** Zeitschätzung pro Todo (schafft Eingabe-Reibung ohne Nutzen).
- **Keine** Abhängigkeiten/Verknüpfungen zwischen Todos (Gantt, Blocker).
- **Keine** Drag-&-Drop-Sortierung (Reihenfolge über Hoch/Runter im Menü).
- **Keine** gemeinsame Nutzung/Teilen, kein Multi-User.
- **Kein** Umbau des bestehenden Dashboard-Inhalts (nur Einstieg + Zugang).

## 3. Datenmodell (Schema-Entscheidung und Begründung)

### 3.1 Warum EIN selbstreferenzieller Baum statt fünf Tabellen

Die geforderte Struktur ist fünf Ebenen tief, aber der Nutzer beschreibt
zugleich eine *offene* Hierarchie („verschachtelt"). Bewertet wurden:

| Variante | Bewertung |
|---|---|
| Fünf Tabellen (project, subproject, category, task, todo) | Starr: jede weitere Ebene („3. Semester in Studium" o. ä.) erzwingt eine Migration. Fünf Fast-identische DAOs/Repos/Editoren. Verworfen. |
| Materialized Path (`/id/id/id`) | Schnelle Subtree-Query, aber Umzüge werden zu String-Operationen über viele Zeilen; Nutzen erst ab Zehntausenden Knoten. Verworfen. |
| Closure-Table | Zusätzliche Tabelle + Konsistenzpflege bei jedem Schreibvorgang. Nutzen erst bei teuren rekursiven Queries. Verworfen. |
| **Adjacency-List, eine Tabelle, Typ-Feld** | Schreibvorgänge = 1 UPDATE (Umzug), beliebige Tiefe, ein DAO, ein Repository, ein Editor. Rekursion passiert **in Kotlin** über den vollständig geladenen Baum. Datenmenge: hunderte bis wenige tausend Knoten → der ganze Baum wird als `Flow<List<PmNode>>` beobachtet und in < 1 ms indexiert. |✓

**Begründung der Typen:** Der Nutzer nennt Oberprojekt und Unterprojekt getrennt.
Beide sind derselbe semantische Typ `PROJECT` (beliebig tief verschachtelbar),
daneben `CATEGORY` (Gliederung), `TASK` (die abhakbare Einheit mit Balken und
Swipe-Karte) und `TODO` (Blatt, abhakbar, tagzuweisbar). Damit sind die
geforderten fünf Ebenen darstellbar, ohne die Tiefe zu zementieren.

```
Studium            PROJECT
 └ 2. Semester     PROJECT
    └ Projektgruppe CATEGORY
       └ Präsentation  TASK
          ├ erste Hälfte lesen   TODO
          └ Entwurf aufschreiben TODO
```

Regeln (im Code erzwungen, siehe `PmTree` / `PmRepositoryImpl`):
1. Nur `TODO` ist abhakbar und kann ein Datum tragen.
2. `TODO` ist immer Blatt (kein Kind).
3. Container dürfen beliebig ineinander verschachtelt werden.
4. **Umzüge kreislauffrei**: ein Knoten kann nicht in seinen eigenen Teilbaum
   verschoben werden (`PmTree.isDescendantOf`); das Repository lehnt ab
   (Rückgabe `false`) — es gibt keine stille Korruption.
5. Verwaiste Knoten (Eltern fehlt) werden als Wurzeln behandelt statt zu
   crashen.

### 3.2 Tabelle `pm_node` (DB v44)

```
pm_node
  id              TEXT    PRIMARY KEY            (UUID)
  parent_id       TEXT    NULL, FK pm_node(id) ON DELETE CASCADE, INDEX
  type            TEXT    NOT NULL               PROJECT|CATEGORY|TASK|TODO, INDEX
  title           TEXT    NOT NULL
  note            TEXT    NOT NULL DEFAULT ''    (Notizen, Links, Quellen)
  color           TEXT    NULL                   (#RRGGBB, optional)
  sort_order      INTEGER NOT NULL DEFAULT 0, INDEX
  scheduled_date  TEXT    NULL                   ISO yyyy-MM-dd, NULL = Backlog
  due_date        TEXT    NULL                   ISO, nur Anzeige (Countdown)
  done_at         INTEGER NULL                   Zeitstempel der Erledigung
  created_at      INTEGER NOT NULL
  updated_at      INTEGER NOT NULL
```

- **Ein** `done_at` statt Erledigungs-Tabelle: Ein PM-Todo wird genau einmal
  erledigt (kein Serien-Todo). `done_at` liefert zugleich die Historie
  (welcher Tag, welche Uhrzeit) → der 7-Tage-Streifen braucht keine zweite
  Tabelle.
- `ON DELETE CASCADE` auf `parent_id`: Löschen eines Projekts räumt den
  kompletten Teilbaum ab, ohne Nacharbeit im Code (Room aktiviert
  Fremdschlüssel standardmäßig).
- Alle Schreibvorgänge laufen über die DAO — kein Roh-SQL außerhalb der
  Migration. Export/Backup erfassen die Tabelle **automatisch**, weil
  `DataManager` auf der SQLite-Datei arbeitet (alle Tabellen).

### 3.3 Tagesplan-Regeln (rein, testbar)

`PmToday.plan(tree, today, zone)`:

| Todos des Tages | Regel |
|---|---|
| fällig/geplant **heute** (`scheduled_date == today`), offen | heute |
| geplant **vor heute**, offen | heute, markiert „↻ übernommen" (Rollover) |
| **ohne** Datum, offen | **nicht** heute → Backlog |
| `done_at` **heute** (unabhängig von `scheduled_date`) | heute, als erledigt (zählt als Juwel) |
| geplant nach heute, offen | nicht heute |
| früher erledigt | nirgends („der Rest nicht") |

Glas-Kennzahl: `done = #{Erledigungen heute}`, `total = |Tagesplan|`,
`fraction = done/total`. Ein leeres Glas mit **Geisterplätzen** für die noch
offenen Todos erzeugt die Sog-Wirkung („da fehlen noch 8").

**Feier-Gate** (`PmCelebrationGate`): Feuerwerk nur beim Übergang
`done == total > 0`, nicht bei jeder Neukomposition/Navigationsrückkehr.
Persistiert pro Tag (`date`, `celebratedDone`); steigt `done` danach durch
einen zusätzlich eingeplanten Todo weiter an, wird erneut gefeiert (verdient).
Das ist die Gegenmaßnahme zum klassischen Animations-Bug der App-Geschichte:
Zustand in der DB/Session statt nur in der Komposition.

### 3.4 Fortschritt

- `progress(node)` = erledigte/alle Todos **im Teilbaum** — nicht nur heute.
  Damit wächst der Balken über Tage hinweg: „das Projekt schreitet fort".
- `TASK` zeigt den eigenen Balken dick, `PROJECT`/`CATEGORY` denselben Wert
  dünner (Rollup) → Hierarchie bleibt lesbar.

### 3.5 Farben (ohne Maskottchen, ohne XP)

Palette (8 Töne, feste Reihenfolge) aus `PmColors.PALETTE`. Zuordnung:
`node.color` → sonst Farbe des nächsten Vorfahren mit Farbe → sonst
deterministisch aus der Wurzel-ID (`hash % 8`, stabil über Sessions).
**Bedeutung:** die Juwelenfarbe im Glas = Aufgabe/Projekt. Das Glas zeigt damit
nicht nur „wie viel", sondern **woran** der Tag gearbeitet wurde.

## 4. Informationsarchitektur & Interaktionsvertrag

### 4.1 Einstieg (im vorhandenen Dashboard)

- Kleiner Glas-Button **oben rechts** im Dashboard-Kopf
  (`PmEntryButton`): gemaltes Juwel + Zähler-Badge der noch offenen
  heutigen Todos + ruhiger Puls, wenn etwas offen ist.
- Zweitplatzierung: derselbe Button schwebt oben rechts ein, sobald der
  Hero-Kopf weggescrollt ist (Fade + Scale). Es ist immer nur **einer**
  sichtbar → keine Doppelung, immer erreichbar.
- Zusätzlich: Eintrag „Projektmanagement" in den Einstellungen.
- Öffnen: eigener Navigations-Route mit **Slide von unten** (Sheet-Metapher).
  Der Screen hat einen Griff: Herunterziehen am Kopf schließt ihn (Drag nur
  am Kopfbereich → kein Konflikt mit Scrollen), Rest zieht föderal mit.

### 4.2 Aufbau des PM-Screens

```
┌ Griff (drag-to-close) ────────────────────────────────┐
│ Projektmanagement            [Tag planen] [+ / ⋯]      │
│ [ Heute ] [ Projekte ]   ← animierter Tab-Indikator    │
├────────────────────────────────────────────────────────┤
│ TAB „Heute" (Standard)                                 │
│  1. Glas-Hero: Gefäß mit Juwelen, Geisterplätzen,      │
│     Zähler „12 / 20", dünner Balken, 7-Tage-Streifen   │
│  2. Karussell: eine Karte je Aufgabe mit heutigen      │
│     Todos (HorizontalPager = Swipe links/rechts)       │
│     je Karte: Pfad-Brotkrumen, Titel, Frist,           │
│     Fortschrittsbalken der GESAMTEN Aufgabe,           │
│     die heutigen Schritte, „Alle offenen → heute"      │
│  3. „Aus dem Backlog": undatierte offene Todos         │
│     gruppiert je Aufgabe, Schnellchip „+ Heute"        │
│  4. „Heute erledigt": Uhrzeit + Rückgängig             │
│ TAB „Projekte"                                         │
│  Baum, beliebig tief; Chevron klappt inline auf, Tap  │
│  öffnet die Detailseite; Balken je Ebene (Rollup)     │
└────────────────────────────────────────────────────────┘
```

Untergeordnete Seiten: Knoten-Detailseite (Todos, Unterknoten, Aktionen,
Mehrfachauswahl), Editor (Knoten anlegen/bearbeiten, **Sammel-Eingabe** von
Todos) — beide als „Sheet von unten" (Modal-Charakter), Detailseite als
normaler Push (Hierarchie-Gefühl).

### 4.3 Interaktionsvertrag (bewusst konfliktfrei)

| Geste | Wirkung | Warum |
|---|---|---|
| Tipp auf Todo | erledigt / rückgängig (Juwel fällt ins Glas) | Kernhandlung, 1 Tipp |
| Langdruck auf Todo (Detailseite) | Mehrfachauswahl | Massenbearbeitung |
| Horizontal wischen **im Karussell** | Aufgabe wechseln | geforderte Swipe-Navigation |
| Horizontal wischen auf einer Zeile | **keine** Aktion | sonst Konflikt mit dem Karussell — bewusst nicht belegt |
| Vertikal ziehen am Kopf | Sheet schließen | Sheet-Metapher, kein Scroll-Konflikt |
| „＋"-Menü auf der Zeile | Heute / Morgen / Kein Datum / Datum … | Einzelzuweisung |

### 4.4 Massenzuweisung (Kernkomfort)

Drei Wege, damit 25 Todos **nicht** 25-mal geklickt werden müssen:

1. **Sammel-Eingabe** beim Anlegen: mehrzeiliges Feld, **eine Zeile = ein
   Todo** (Copy-&-Paste einer ganzen Schrittliste), Datums-Chips für alle
   Zeilen gemeinsam.
2. **Aufgaben-Knopf** „Alle offenen → heute" (Karussell + Detailseite):
   der komplette Rückstand einer Aufgabe mit einem Tipp auf heute.
3. **Tag-planen-Sheet** (Morgenritual): alle offenen Todos gruppiert,
   Rollover vorausgewählt, Gruppen-Schalter „alle", Datums-Chips
   (Heute/Morgen/Kein Datum/Datum …) → eine Bestätigung.

## 5. Animationen (Dopamin-Inventar)

| Element | Animation | Ort |
|---|---|---|
| Glas-Hero | Juwelen fallen mit Beschleunigung, Aufprall staucht den Haufen (Spring), Glas wackelt, kurzes Aufleuchten | `GemGlass.kt` |
| Geisterplätze | offene Todos als transparente Steine im Glas | `GemGlass.kt` |
| Voll | Feuerwerk (3 Wellen Partikel, Schwerkraft, Funken, Nachleuchten), Banner „Alles geschafft" | `FireworksOverlay.kt` |
| Häkchen | Kreis füllt sich, Haken zeichnet sich (trim), Funkenstoß am Todo | `PmCheckbox.kt` |
| Balken | Gradient füllt mit Spring, Glanz-Sweep wandert, Zahl zählt hoch, ab 100 % Gold + ✓ | `PlayfulProgressBar.kt` |
| Juwel-Farbe | Farbe der Aufgabe/des Projekts | `PmColors` |
| Tabs, Listen, Zahlen | Indikator gleitet, Einträge animiert, Zähler zählen | verteilt |
| Haptik | Spürbares Feedback beim Abhaken und beim Feuerwerk | `PmCheckbox`, `ProjectsScreen` |

## 6. Fehler- und Randfälle (explizit behandelt)

1. **Mitternacht im Vordergrund:** Der Tag wird über einen Ticker (60 s)
   neu bestimmt; der Flow wird neu kombiniert. Sonst zeigt die Seite nach
   Mitternacht weiter gestern (bekannter Bug-Typ in M18.43).
2. **Leerer Tag:** Glas leer + Hinweis + Einstieg „Tag planen" statt leerer
   Fläche.
3. **Alle Todos erledigt, danach neues Todo für heute:** Gate feuert erneut
   (verdient), aber **nicht** bei jedem Rekomponieren.
4. **Löschen eines Projekts** entfernt den Teilbaum inkl. Todos und damit die
   Juwelen (Glas rechnet sich korrekt neu).
5. **Umzug in eigenen Teilbaum** wird abgelehnt.
6. **Verwaiste Knoten** (fehlender Elter) erscheinen als Wurzel — kein Crash.
7. **Uhrzeit-/Zeitzonenwechsel** (Reise, Sommerzeit): Datum kommt aus
   `LocalDate` in der Systemzone, `done_at` ist ein Zeitstempel → beides
   konsistent.
8. **Konfigurationswechsel** (Rotation): Zustand in ViewModels, nicht in der
   Komposition.
9. **Viele Todos:** Karussell und Listen sind `LazyColumn`/Pager ohne
   Voll-Rendering; Glas zeichnet max. 60 Steine (darüber: Zahl statt Steine).
10. **Bestandsdaten:** Migration 43 → 44 rein additiv; Fail-Fast-Öffnen bleibt
    (kein `fallbackToDestructiveMigration`), Schema-Gleichheit der Migration
    wird getestet.

## 7. Testplan

| Ebene | Test | Beweist |
|---|---|---|
| Domain (rein) | `PmTreeTest` | Kinder/Wurzel/Pfad/Teilbaum/Fortschritt/Umzugsverbot/Zyklen/Waisen |
| Domain (rein) | `PmTodayPlanTest` | Tagesplan, Rollover, „Rest nicht", Glas-Kennzahl, 7-Tage-Historie, Mitternacht |
| Domain (rein) | `PmCelebrationGateTest` | kein Doppel-Feuerwerk, verdientes zweites |
| Domain (rein) | `PmSeedTest` | Beispielstruktur gültig (Typen, Eltern, eindeutige IDs, keine Zyklen) |
| Domain (rein) | `PmColorsTest` | deterministisch, Vererbung, Fallback |
| Integration | `PmRepositoryRoomTest` (Robolectric + echte Room-DB) | echte SQL: anlegen, Cascade-Löschung, Sammel-Datum, Subtree-Update, Umzug, Fortschritt |
| Schema | `PmMigrationSchemaTest` | Migrations-SQL == vom Schema-JSON erwartetes SQL (Room akzeptiert die Migration) |
| Bestand | komplette Suite | keine Regression (1114 Tests → +neu) |

## 8. Umsetzungskarte (Dateien)

```
data/model/PmNode.kt                     Entity + Typ-Konstanten
data/db/PmNodeDao.kt                    DAO
data/repository/PmRepository.kt         Schnittstelle
data/repository/PmRepositoryImpl.kt     Umsetzung (Mutex, Baum-Operationen)
data/db/AppDatabase.kt                  Entity, DAO, v44, MIGRATION_43_44
di/DatabaseModule.kt                    DAO-Provider + Migration registrieren
di/RepositoryModule.kt                  Binding

domain/projects/PmTree.kt               Baum, Fortschritt, Umzugsregeln (rein)
domain/projects/PmTodayPlan.kt          Tagesplan, Glas, Historie (rein)
domain/projects/PmCelebrationGate.kt    Feier-Gate (rein)
domain/projects/PmColors.kt             Farbregeln (rein)
domain/projects/PmSeed.kt               Beispielstruktur (rein)

ui/components/PmCheckbox.kt             animiertes Häkchen + Funken
ui/components/PlayfulProgressBar.kt     spielerischer Balken
ui/components/GemGlass.kt               Gefäß + Juwelen + Geisterplätze
ui/components/FireworksOverlay.kt       Feuerwerk
ui/components/PmEntryButton.kt          Dashboard-Einstieg + Badge

ui/screens/projects/ProjectsScreen.kt        Sheet-Wurzel, Tabs, Drag
ui/screens/projects/ProjectsTodayTab.kt      Glas, Karussell, Backlog
ui/screens/projects/ProjectsTreeTab.kt       Baum
ui/screens/projects/PmNodeDetailScreen.kt    Detailseite
ui/screens/projects/PmEditorScreen.kt        Editor + Sammel-Eingabe
ui/screens/projects/PmDayPlannerSheet.kt     Tag-planen-Sheet
ui/screens/projects/ProjectsViewModel.kt     Wurzel-Zustand + Aktionen
ui/screens/projects/PmNodeDetailViewModel.kt
ui/screens/projects/PmEditorViewModel.kt
ui/screens/projects/PmEntryViewModel.kt      Badge-Zähler

navigation/AppDestination.kt           6 Routen
navigation/AppNavHost.kt               Verdrahtung + Übergänge
res/values|values-de/strings_projects.xml  Texte EN/DE
```

## 9. Definition of Done

- [ ] Alle neuen Tests grün, Bestandstests ohne Regression.
- [ ] Debug-APK kanonisch signiert, Release-APK + AAB im Build-Output.
- [ ] Migration 43→44 durch Test gegen das Schema-JSON belegt.
- [ ] Docs, Projekt-Memory, README, Vault aktualisiert.
- [ ] Abschlussbericht mit echten Messwerten.

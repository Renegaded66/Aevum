# M19.1 — Projektmanagement: Abschlussbericht

> **Meilenstein:** M19.1 — Projektmanagement („Projekte")
> **App-Version:** 1.0.31 (versionCode 32)
> **Datenbank:** v44 (`pm_node`, Migration 43 → 44, rein additiv)
> **Arbeitsstand:** 17.10.2026
> **Spezifikation:** `docs/M19-1-PROJEKTMANAGEMENT.md`

---

## 1. Der Auftrag (wörtlich) und was daraus folgt

> „Großes Aevum update. […] ich habe Probleme bei meinem Studium, große Aufgaben
> zu erledigen. Aktuell muss ich eine umfangreiche Präsentation machen. Und als
> Ziel hab ich einfach nur dass die fertig wird. Und ich kann mich überhaupt
> nicht fokussieren. Es scheint kein Ende zu haben und ich scheine keinen
> Fortschritt zu machen. Daher halte ich es für sinnvoll jede kleinste Aufgabe
> schriftlich festzuhalten und abzuarbeiten."

Das ist kein Feature-Wunsch, sondern ein Arbeitsproblem mit vier Ursachen.
Jede gebaute Entscheidung lässt sich auf genau eine davon zurückführen:

| Ursache im Zitat | Antwort im Produkt |
|---|---|
| „große Aufgaben" | Ein Baum, in dem eine Aufgabe aus lauter kleinen Schritten besteht |
| „es scheint kein Ende zu haben" | Fortschrittsbalken über ALLE Schritte einer Aufgabe, nicht nur die von heute |
| „ich scheine keinen Fortschritt zu machen" | Glas, das sich mit jedem abgehakten Schritt sichtbar füllt (Juwel = ein Schritt, Farbe = Aufgabe) |
| „kann mich nicht fokussieren" | Tagesplan mit einem Blick: was heute ansteht, plus Karussell je Aufgabe |

---

## 2. Was gebaut wurde

### 2.1 Struktur (beliebig tief, vier Typen)

```text
Studium                PROJECT   (Oberprojekt)
 └ 2. Semester         PROJECT   (Unterprojekt)
    └ Projektgruppe    CATEGORY  (Kategorie)
       └ Präsentation  TASK      (Aufgabe: Fortschrittsbalken, eigene Swipe-Karte)
          ├ Erste Hälfte der Quelle lesen      TODO
          └ Entwurf aufschreiben               TODO
```

EIN selbstreferenzieller Baum (`pm_node`, DB v44) statt fünf Tabellen. Ein
Schritt (`TODO`) ist immer Blatt, abhakbar, optional tagzuweisbar. Container
lassen sich beliebig ineinander schachteln — die geforderten fünf Ebenen sind
darstellbar, ohne die Tiefe zu zementieren.

### 2.2 Einstieg

Kleiner Knopf **oben rechts im Dashboard**: ein gezeichnetes Juwel mit dem
Zähler der noch offenen Schritte des Tages (atmet, solange etwas offen ist;
goldener Rand, wenn der Tag fertig ist). Sobald der Hero weggescrollt ist,
schwebt derselbe Knopf als zweite Platzierung ein — es ist immer nur einer
sichtbar, aber immer einer erreichbar. Zusätzlich in den Einstellungen unter
„Erweitert".

Öffnen: eigenes Ziel mit **Übergang von unten nach oben** (`slideInVertically`),
abgerundete obere Ecken, Griff am Kopf, Herunterziehen am Kopf schließt wieder
(Sheet-Metapher; bewusst nur am Kopf ziehbar, damit Scrollen nicht schließt).

### 2.3 Tagesplan und Glas

- **Glas:** vektoriell gezeichnetes Gefäß mit Lippe, Glanzkante, Messmarken und
  Schatten. Jeder erledigte Schritt wird ein facettierter Edelstein in der
  Farbe der Aufgabe; die Steine liegen in der Reihenfolge des Abhakens als
  Haufen. Die noch offenen Schritte liegen als **leere Fassungen** darüber —
  die sichtbare Lücke ist der Antrieb. Ein neuer Stein **fällt** mit
  federnder Kurve herab, der Haufen sackt nach, am Aufschlag blitzt es auf.
- **Voll:** goldener Rand, leuchtende Steine, aufsteigende Funken und ein
  **Feuerwerk** aus fünf Abschüssen mit Schwerkraft, Spuren und Banner.
  Es erscheint **genau einmal pro erreichtem Stand** (persistiertes
  Feier-Gate).
- **7-Tage-Streifen:** wie viele Schritte an den letzten Tagen erledigt wurden,
  direkt aus den Erledigungszeitpunkten abgeleitet.
- **Übernahme:** Nicht erledigte Todos von gestern und früher erscheinen
  weiterhin (Chip „übernommen"), erledigte verschwinden am Folgetag.
  Schritte ohne Datum liegen im Backlog.

### 2.4 Karussell (Wischen zwischen Aufgaben)

Eine Karte je Aufgabe mit heutigen Schritten. Wischen nach links/rechts wechselt
die Aufgabe (HorizontalPager mit farbigen Seiten-Punkten). Jede Karte zeigt:
Brotkrumen (Studium › 2. Semester › Projektgruppe), Titel, Frist, den
**Gesamtfortschritt der Aufgabe** und die heutigen Schritte zum Abhaken.

### 2.5 Kein 25-mal-Tippen

Drei Wege, alle mit einem Vorgang:

1. **Sammel-Eingabe:** mehrzeiliges Feld, **eine Zeile = ein Schritt**
   (eingefügte oder getippte Liste), optional alle zusammen auf ein Datum.
2. **„Alle offenen → heute"** je Aufgabe (Karussell, Backlog, Detailseite).
3. **„Tag planen"-Sheet:** alle offenen Schritte gruppiert, bereits geplante
   vorausgewählt, Gruppen-Schalter „Alle", Datum wählbar (Heute/Morgen/frei),
   danach eine Bestätigung.

Zusätzlich: Mehrfachauswahl per Langdruck auf der Detailseite mit
Datums-Aktionsleiste und ein Datumsmenü an jeder Zeile.

### 2.6 Fortschritt, der wächst

Jeder Container trägt einen Balken über alle enthaltenen Schritte (auch die
von morgen): beim Projekt dünn, bei der Aufgabe dick. Ab 100 % wird der Balken
golden, der Glanz hört auf zu wandern, ein Haken poppt auf. Das ist die
Antwort auf „es scheint kein Ende zu haben".

### 2.7 Beispielstruktur

Im leeren Zustand legt EIN Tipp genau die Struktur an, die dem Anwendungsfall
entspricht (Studium → 2. Semester → Projektgruppe → Präsentation mit 15
kleinen Schritten, davon 10 für heute eingeplant; dazu Projektbericht und ein
privates Projekt als Backlog). Feste IDs: zweimal tippen erzeugt keine
Dubletten und überschreibt keinen erreichten Fortschritt.

---

## 3. Entscheidungen — und wo ich sie gegen die naheliegende Lösung getroffen habe

### 3.1 Ein Baum statt fünf Tabellen

**Naheliegend:** fünf Tabellen (Projekt, Unterprojekt, Kategorie, Aufgabe,
Todo) — genau so, wie der Nutzer es beschrieben hat.
**Dagegen:** Der Nutzer beschreibt gleichzeitig eine OFFENE Hierarchie
(„verschachtelt"). Fünf Tabellen hätten jede weitere oder fehlende Ebene zu
einer Migration gemacht. Die Rekursion passiert jetzt in Kotlin über einen
vollständig geladenen, indexierten Baum — bei diesen Datenmengen unter einer
Millisekunde, billiger als jede rekursive SQL-Query.
**Preis, den ich bewusst zahle:** Ein Typ-Feld statt fünf sprechender Tabellen,
und die Fortschrittsberechnung liegt im Speicher (kein SQL-Index darauf). Bei
zehntausenden Knoten wäre das die falsche Wahl — bei einem Menschen mit
Studienprojekten ist es die richtige.

### 3.2 Ein `done_at` statt einer Erledigungs-Tabelle

**Naheliegend:** eine Tabelle „Erledigung" wie beim bestehenden Todos-Modul.
**Dagegen:** Ein PM-Schritt wird genau einmal erledigt (kein Serien-Todo).
Der Zeitstempel liefert die Historie (welcher Tag, welche Uhrzeit) gratis —
der 7-Tage-Verlauf braucht keine zweite Tabelle.

### 3.3 KEINE Vermischung mit dem bestehenden Todos-Modul

**Naheliegend:** die vorhandene `todo`-Tabelle um ein Elternfeld erweitern.
**Dagegen:** Das bestehende Modul ist ein Gewohnheits-System (Serien,
Streaks, Auto-Abhaken über erfasste Dauer). PM-Schritte sind einmalige,
tagzuweisbare Kleinschritte. Eine Vermischung hätte beide Modelle verzerrt
und 1114 Bestandstests gefährdet. Die beiden Dinge heißen ähnlich, sind aber
verschieden — jetzt sind sie es auch im Code.

### 3.4 Das Feuerwerk hängt an einem Übergang, nicht an „100 %"

**Naheliegend:** `if (fortschritt == 100%) feiern()`.
**Warum das falsch ist:** Compose komponiert bei Scrollen, Rotation und
Rückkehr neu — die Bedingung ist dann jedes Mal wahr. Der Nutzer hätte
Dauerfeuer und die Belohnung wäre kaputt. Deshalb: persistierter Feierstand
pro Tag (`PmCelebrationStore`); gefeiert wird nur beim Übergang, und ein
zweites Mal nur, wenn er danach wirklich eine zusätzliche Einplanung abhakt.
Zwei Tests halten genau das fest.

### 3.5 Wisch-Gesten sind vergeben — und zwar nur einmal

**Naheliegend:** Wischen nach rechts = erledigen (wie in vielen Todo-Apps).
**Dagegen:** Der Nutzer hat das Wischen AUSDRÜCKLICH für den Aufgabenwechsel
gefordert. Beides auf derselben Achse wäre ein Konflikt. Deshalb gilt ein
fester Vertrag: horizontal wischen = Aufgabe wechseln, tippen = abhaken,
Langdruck = Mehrfachauswahl, „⌄" = Datum. Auf Zeilen ist Wischen bewusst
**nicht** belegt.

### 3.6 Der Tag wird aktiv nachgeführt

**Naheliegend:** `LocalDate.now()` einmal beim Öffnen lesen.
**Dagegen:** Die App hat genau diese Fehlerklasse schon einmal gehabt (siehe
Kommentar an `TodoDao.getAllCompletions`): ein einmal abonnierter Wert bleibt
über Mitternacht auf gestern stehen. Deshalb tickt der Tagesplan auf einem
Minuten-Fluss und wechselt von selbst.

### 3.7 Zähler im Dashboard, aber in einem eigenen ViewModel

**Naheliegend:** ein Feld mehr im DashboardViewModel (77 KB).
**Dagegen:** Minimaler Eingriff, kein Risiko für die Bestandstests, und die
Ableitung läuft nur, wenn der Knopf sichtbar ist.

### 3.8 Was ich bewusst NICHT gebaut habe

Keine Levels, Erfahrungspunkte, Maskottchen, Währungen, Shops (Vorgabe).
Keine Zeiterfassung in PM-Schritten, keine Wiederholungen/Serien, keine
Erinnerungen, keine Abhängigkeiten zwischen Schritten, keine
Drag-&-Drop-Sortierung (stattdessen „nach oben/unten" im Menü), kein Teilen,
kein Sync. Nicht aus Zeitmangel, sondern weil jede dieser Zusagen Reibung
erzeugt, ohne dass sie das Ziel („fertig werden") näher bringt.

---

## 4. Was die Tests beim Bauen gefunden haben (nicht nachträglich geschönt)

1. **Stiller Umzug an die falsche Stelle (echter Bug, gefunden, behoben).**
   `PmRepositoryImpl.move` normalisierte einen unzulässigen Zielknoten
   („unter ein Todo") stillschweigend zu „Wurzel" — der Knoten wäre ohne
   Fehlermeldung ganz woanders gelandet. `PmRepositoryRoomTest` hat es
   aufgedeckt; die Regel ist jetzt streng (fehlendes/verbotenes Ziel → keine
   Änderung, Rückgabe `false`) und in `PmTree.moveAllowed` dokumentiert.
2. **Verdrahtungsfehler im Zustand (gefunden, behoben).** Im Editor war
   `setOf(SEED_EXPAND)` statt `SEED_EXPAND` — der Beispielbaum wäre nach dem
   Einfügen nicht aufgeklappt worden.
3. **Doppelte Anzeige heutiger Schritte (gefunden, behoben).** Der
   Backlog-Abschnitt und das Tag-planen-Sheet brauchen unterschiedliche
   Mengen: das Sheet ALLE offenen Schritte (sonst wären die heutigen nicht
   auswählbar), der Backlog-Abschnitt nur die, die heute nicht anstehen (sonst
   stünden sie doppelt auf dem Bildschirm). Beides ist jetzt getrennt
   (`plannerGroups` / `backlog`) und im Test festgehalten.
4. **Versionsprüfung im Bestandstest (gefunden, bebehoben).** Ein alter Test
   verdrahtete `version = 43` fest und wäre bei JEDER weiteren Migration
   gebrochen. Er prüft jetzt die Absicht („Version wurde mitgezogen") statt
   der Zahl.

---

## 5. Verifikation

| Prüfung | Ergebnis |
|---|---|
| Neue Tests | **126** in 8 Suiten |
| Gesamte Testsuite | **1240/1240 grün**, 0 Fehler (vorher 1114) |
| Davon echte Room-Datenbank | `PmRepositoryRoomTest` (31): Cascade-Löschung, Sammel-Datum, Umzugsregeln, idempotenter Beispiel-Import |
| Migration 43 → 44 | `PmMigrationSchemaTest` (6): Migrations-SQL == vom Room-Compiler erzeugtes Schema (Zeichen für Zeichen, inkl. `${TABLE_NAME}`-Auflösung) — die App öffnet die DB fail-fast, eine Abweichung wäre ein Startabsturz |
| Kompletter Ablauf | `PmProjectsViewModelTest` (14): Datenbank → Repository → ViewModel → Zustand (Glas, Juwelen, Feier-Gate, Tag-planen, Massenwege, Löschen) |
| Build | `aevum-m191` — `testDebugUnitTest assembleDebug assembleRelease bundleRelease`, **BUILD SUCCESSFUL in 8m 54s** (exit 0) |
| Debug-APK | `app-debug.apk`, 124.501.966 B (124,5 MB), versionCode 32 / 1.0.31-debug, Signatur **kanonisch** `9c3055c4…fbe0e8` (vom Build-Skript unabhängig nachgeprüft) |
| Release-APK | `app-release.apk`, 52.231.271 B (52,2 MB), versionCode 32 / 1.0.31, Upload-Key `c8430d35…61ffe9`, minSdk 29 / targetSdk 36 |
| AAB (Play) | `app-release.aab`, 24.556.502 B (24,6 MB) |

**Ehrliche Einschränkung:** Auf dieser Maschine fehlt `/dev/kvm`, ein Emulator
ist also nicht möglich, und es ist kein Gerät angeschlossen. Die Oberfläche
wurde deshalb **nicht** auf einem laufenden System durchgeklickt; bewiesen ist
sie über Kompilierung, Zustandsableitung, Datenbankintegration und
Schema-Gleichheit. Das entspricht dem Vorgehen der App-Geschichte (siehe
M18.137) — die Sichtprüfung auf dem Handy bleibt der letzte Schritt, den nur
Devon machen kann.

---

## 6. Dateien

**Neu (Daten/Domäne):** `data/model/PmNode.kt`, `data/db/PmNodeDao.kt`,
`data/repository/PmRepository.kt`, `PmRepositoryImpl.kt`, `PmCelebrationStore.kt`,
`domain/projects/PmTree.kt`, `PmTodayPlan.kt`, `PmCelebrationGate.kt`,
`PmColors.kt`, `PmSeed.kt`

**Neu (Oberfläche):** `ui/components/GemGlass.kt`, `FireworksOverlay.kt`,
`PmCheckbox.kt`, `PlayfulProgressBar.kt`, `PmEntryButton.kt`,
`ui/theme/PmPalette.kt`, `ui/screens/projects/ProjectsScreen.kt`,
`ProjectsTodayTab`-Teile, `ProjectsTreeTab`-Teile, `PmRows.kt`,
`PmDateControls.kt`, `PmDayPlannerSheet.kt`, `PmNodeDetailScreen.kt`,
`PmNodeDetailViewModel.kt`, `PmEditorScreen.kt`, `PmEditorViewModel.kt`,
`ProjectsViewModel.kt`, `PmEntryViewModel.kt`

**Neu (Texte):** `res/values/strings_projects.xml`, `res/values-de/strings_projects.xml`

**Neu (Tests):** `PmTreeTest`, `PmTodayPlanTest`, `PmCelebrationGateTest`,
`PmColorsTest`, `PmSeedTest`, `PmRepositoryRoomTest`, `PmMigrationSchemaTest`,
`PmProjectsViewModelTest`

**Geändert:** `AppDatabase.kt` (Entity, DAO, v44, `MIGRATION_43_44` + SQL-Konstanten),
`di/DatabaseModule.kt`, `di/RepositoryModule.kt`, `navigation/AppDestination.kt`,
`navigation/AppNavHost.kt` (3 Routen + Übergänge), `ui/screens/dashboard/DashboardScreen.kt`,
`ui/screens/dashboard/DashboardHero.kt`, `ui/screens/settings/SettingsScreen.kt`,
`app/build.gradle.kts` (1.0.31 / versionCode 32), `README.md`, `docs/FEATURES.md`,
`docs/DATABASE.md`, `docs/NAVIGATION.md`, `docs/ROADMAP.md`, `docs/CHANGELOG.md`,
`.project-memory/*`

**Bewusst unverändert:** `todo`/`todo_completion`, TodosScreen, StreakEngine,
RecurrenceEngine, `DataManager` (Export/Backup erfassen `pm_node` automatisch,
weil sie auf der SQLite-Datei arbeiten).

---

## 7. Offene Punkte und Empfehlungen

1. **Sichtprüfung auf dem Gerät** — der einzige Schritt, der Devon gehört:
   Knopf oben rechts → Sheet fährt hoch → Beispielstruktur einfügen → ersten
   Schritt abhaken → Juwel fällt ins Glas → alle 10 abhaken → Feuerwerk.
2. **Frist-Erinnerung** gibt es nicht (bewusst, siehe §3.8). Wenn gewünscht,
   wäre ein Hinweis am Morgen die passende Ergänzung — dann aber als
   Benachrichtigung, nicht als Spielfigur.
3. **Glas mit mehr als 60 Steinen:** darüber wird nicht weiter gezeichnet
   (die Zahl bleibt korrekt). Bei echten Tagesplänen (> 60 Schritte) wäre eine
   zweite Darstellung sinnvoll — bis dahin ist die Zahl aussagekräftiger als
   unlesbare Krümel.
4. **Karussell-Höhe** ist fest (380 dp) mit scrollbarer Liste je Karte. Bei
   sehr langen Tageslisten ist Scrollen innerhalb der Karte nötig; eine
   adaptive Höhe wäre der nächste Feinschliff.

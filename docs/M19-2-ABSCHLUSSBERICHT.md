# M19.2 — Eisblau/Feuerrot, Projektmanagement-Umbau, Digital-Balance-Revival: Abschlussbericht

> **App-Version:** 1.0.32 (versionCode 33) · **Datenbank:** v45 (reine
> Daten-Migration aus v44) · **Datum:** 17.10.2026
> **Vorgänger:** `docs/M19-1-ABSCHLUSSBERICHT.md` (App 1.0.31)

---

## 0. In einem Satz

Die App trägt jetzt ihre eigene Identität (Eisblau→Feuerrot statt
Standard-Android-Grau, mit Schimmer auf den Hauptknöpfen), das
Projektmanagement kennt nur noch **Projekte und Aufgaben** (To-do-Listen mit
Drag & Drop, Löschen und Inline-Eingabezeile, Dashboard zeigt nur heute, das
Juwelen-Glas sitzt schmal daneben), die Vorlage ist eine generische für die
meisten Menschen — und Digital Balance wird nach Reboot, App-Update oder
Prozess-Kill automatisch wiederbelebt, statt still zu sterben.

---

## 1. Der Auftrag (wörtlich) und was daraus folgt

> „Ich möchte nicht mehr, dass die Theme Farbe der App das Standard Android
> ist, ich möchte einen schönen Kontrast aus eiskalten blau und feuerrot,
> gerne auch mit schimmern bei manchen Buttons und so."

> „Ich möchte keine Unterscheidung zwischen Projekt, Kategorie und Aufgabe.
> Man soll nur Projekte anlegen können, und innerhalb Projekte kann man
> Unterprojekte und Aufgaben anlegen, natürlich zu den Unterprojekten auch
> Aufgaben."

> „Aufgaben sollten nicht einfach in einem großen Textfeld untereinander
> stehen sondern direkt schon wie in einer To-Do Liste erscheinen zum abhaken,
> und per drag and drop verschieben, und auch die Möglichkeit zum löschen."

> „Im PM (Projekt Management) Dashboard sollten nur für heute zugewiesene
> Aufgaben angezeigt werden. Und das Glas das sich mit Juwelen ausfüllt soll
> neben den todos sein, also sehr schmal dass man auch die todos richtig lesen
> kann, und beim abhaken der todos kommt dann eine Animation wie das Glas
> voller wird."

> „Es soll kein Textfeld geben zum neue Aufgaben hinzufügen sondern ganz unten
> soll schon direkt ein Platzhalter sein für das nächste To-Do das man einfach
> nur ausfüllen muss und wenn man enter klickt ist es hinzugefügt und der
> nächste Platzhalter erscheint."

> „Deine Beispielstruktur ist exakt mein Beispiel gewesen. Das war grundsätzlich
> zwar praktisch, aber die App kommt in den Play Store, da möchte ich das nicht
> so haben, denke dir eine Struktur aus die auf die meisten Menschen
> möglicherweise zutrifft."

> „Bei digital Balance […] wird einfach nur digital aufgezeichnet und ignoriert,
> dass ich der App Instagram eine andere Activity zugeordnet habe, die gestartet
> werden sollte wenn ich Instagram öffne."

Daraus folgen sieben Arbeitspakete: **(1)** App-Theme auf eine feste
Eisblau/Feuerrot-Identität umstellen (kein `dynamicColor`), **(2)** die
PM-Hierarchie auf zwei Knotenarten reduzieren, **(3)** die Aufgabenliste als
echte To-do-Liste mit Drag & Drop und Löschen bauen, **(4)** das PM-Dashboard
auf „nur heute" schneiden, **(5)** das Juwelen-Glas als schmale Spalte neu
platzieren, **(6)** die persönliche Beispielstruktur durch eine generische
Play-Store-taugliche Vorlage ersetzen, **(7)** die automatische
App-Aufzeichnung (Digital Balance) dauerhaft überleben lassen.

---

## 2. Was gebaut wurde

### 2.1 Theme: Eisblau → Feuerrot mit Schimmer

`ui/theme/Theme.kt` enthält jetzt für Dark **und** Light je 29 vollständig
festgelegte Farb-Tokens (`internal` statt `private`, damit Tests sie prüfen
können). Die Identität:

| Rolle | Dark | Light |
|---|---|---|
| primary (Eisblau) | `#7DD3FC` | `#0369A1` |
| tertiary (Feuerrot) | `#F87171` | `#B91C1C` |
| Hintergrund | `#0B1220` (Eisnacht) | `#F4F9FF` |

Kein dynamisches Material-You mehr — die App sieht auf jedem Gerät gleich
aus. `ui/components/Shimmer.kt` liefert `Modifier.shimmering(...)`: ein
weicher Lichtband-Zug, der in einer Endlosschleife über die Fläche wandert
(bandbasierte `Brush.linearGradient`, Intensität ~0.22). Er sitzt u. a. auf
dem **Projektmanagement-Knopf** (`PmEntryButton`) zwischen Hintergrund und
Klick-Layer. System-Animationen aus = kein Schimmer (respektiert
`Settings.Global.ANIMATOR_DURATION_SCALE`).

### 2.2 Projektmanagement: nur noch Projekte und Aufgaben

`PmType` kennt nur noch `PROJECT` (Container, beliebig verschachtelbar) und
`TASK` (abhakbares Blatt). Die vier Domänen-Dateien wurden auf die neuen
Regeln umgeschrieben:

- **`PmTree`** — Baumindex, Pfad, Teilbaum, Fortschritt, Umzugsregeln;
  an die Wurzel darf nur ein Projekt; unter eine Aufgabe darf nichts.
- **`PmToday`** — Tagesplan (nur heutige + übernommene offene Aufgaben;
  heute Erledigtes inkl. Juwel), 7-Tage-Historie, Planungsgruppen.
- **`PmSeed`** — neue generische Vorlage (siehe 2.5).
- **`PmColors`** — Auflösungsreihenfolge eigene Farbe → Vorfahre → stabil
  aus Root-ID; Palette beginnt mit `#38BDF8` Eisblau / `#F87171` Feuerrot.

**Datenbank v45 als reine Daten-Migration:** Das Schema bleibt Zeichen für
Zeichen identisch (`PmMigrationSchemaTest` vergleicht `44.json` und `45.json`
Element für Element). Geändert wird nur die BEDEUTUNG des `type`-Feldes:
`CATEGORY→PROJECT`, `TASK mit Kindern→PROJECT` (Kinder bleiben),
`TODO→TASK`, `TASK ohne Kinder` bleibt. `PmMigrationDataTest` fährt diese
Migration auf einer echten SQLite-Datei durch (aus `44.json` aufgebaut,
Altdaten eingefüllt, über Room geöffnet) und prüft Typen, Datum, Erledigung
und Sortierung.

### 2.3 To-do-Listen: Abhaken, Drag & Drop, Löschen, Inline-Eingabe

- Die Heute-Ansicht ist eine Liste abhakbarer Zeilen (`PmTaskRow`), gruppiert
  je Projekt mit Farbpunkt, Fortschrittsbalken und Übernahme-Chip.
- **Drag & Drop** (`PmDragDrop.kt`): Ein langer Zug auf dem Griff hebt die
  Zeile an (schwebend, mit Schatten), an den Rändern scrollt die Liste von
  selbst nach. Die Zielrechnung ist eine reine Funktion
  (`PmReorder.targetIndex`), das Schreiben läuft über
  `PmRepository.reorderTasks` — mit **exakt-Liste-Prüfung** (fehlt eine ID
  oder ist eine fremde dabei, wird nichts geschrieben). Aufgaben bleiben in
  ihrer Projekt-Gruppe.
- **Löschen:** über das Kontextmenü der Zeile (Aufgabe löschen; Projekt
  löscht mit Kaskade den Teilbaum — bestehende Mechanik).
- **Inline-Eingabe:** Am Ende jeder Gruppe steht die Eingabezeile
  „Neue Aufgabe…"; Enter hängt an (an letzter Position), der Platzhalter
  erscheint sofort wieder. Kein Dialog, kein Sammel-Textfeld mehr.

### 2.4 Dashboard „nur heute" und das schmale Glas

- Der Tagesplan enthält **ausschließlich** für heute zugewiesene (oder
  übernommene) Aufgaben; Gruppen mit den meisten offenen Aufgaben stehen
  oben, innerhalb der Gruppe gilt die gezogene Reihenfolge.
- Das **Glas** ist jetzt eine schmale Spalte rechts neben den Listen
  (`GemGlass` mit `maxGems`/`maxColumns`): maximal 3 Juwelen-Spalten, damit
  die Todos voll lesbar bleiben. Jedes Abhaken lässt ein Juwel in der Farbe
  des Projekts animiert einschwingen; offene Aufgaben liegen als leere
  Fassungen darüber. Bei vollem Glas genau einmal Feuerwerk (persistiertes
  Feier-Gate unverändert).

### 2.5 Neue Vorlage: vier Lebensbereiche statt Studien-Struktur

`PmSeed` legt jetzt eine generische Struktur an, die fast jeden betrifft —
lokalisiert über `PmSeedTitles` (Englisch als Standard in `values/`, Deutsch
in `values-de/`):

```text
Haushalt & Alltag      → Wohnung (Einkaufsliste, Aufräumen, Wäsche, Papiere) + Wochentermine
Arbeit & Ausbildung    → Aktuelles Projekt (nächster Schritt, Wochenziele, offene Punkte, Ergebnis)
                         + Weiterbildung (Lernziel, erste Lektion)
Gesundheit             → Sport planen, Spaziergang, Vorsorge-Termin
Finanzen               → Monatsbudget, Rechnungen ablegen, Verträge prüfen
```

7 Projekte, 17 Aufgaben, davon 6 für heute geplant — der Nutzer sieht am
ersten Tag Tagesplan UND Backlog-Regel. Feste IDs (Präfix `pm-seed-`),
zweimal tippen erzeugt keine Dubletten; bereits erreichter Fortschritt wird
beim erneuten Import nie überschrieben.

### 2.6 Digital Balance: Warum es wieder ausfiel — und die Revival-Kette

**Befund (aus dem Code, nicht geraten):** Der Dienst
`AppTrackingService` (Foreground-Service, `specialUse`) hatte genau ZWEI
Startpfade: beim Prozess-Start der App (Self-Heal) und beim Ändern der
Einstellung. Nach **Reboot**, nach einem **App-Update** (APK-Install) oder
einem **OEM-Prozess-Kill** existierte kein Revival — während „Digital"
überlebte, weil es an Manifest-Receivern und WorkManager hängt. Genau das
erklärt „erst funktionierte es, jetzt wieder nicht": Das Verhalten hing
davon ab, ob seit dem letzten Reboot zufällig ein Prozess-Start stattfand.

**Der Fix (drei Netze übereinander):**

1. **Boot/Package-Replaced:** `BootReceiver` (verarbeitet bereits
   `MY_PACKAGE_REPLACED`) plant beim Start zusätzlich
   `AppTrackingWatchdogWorker.schedule(context)`;
   `AevumApplication` tut dasselbe beim normalen Prozessstart.
2. **15-Minuten-Watchdog:** `AppTrackingWatchdogWorker` (CoroutineWorker im
   Muster von `ScreenRecordingWorker`) prüft periodisch (KEEP — idempotent):
   Sind überhaupt getrackte Apps aktiv? Läuft der Dienst (`isAlive()`)?
   Wenn nicht: `AppTrackingService.start()`. Der Worker fasst nichts von der
   Übergabe-Logik an — `AppTrackingTakeoverPolicy`, `ForegroundTracker` und
   die `ScreenRecordingWorker`-Logik blieben unverändert.
3. **Screen-On-Nudge:** `ScreenEventReceiver` stößt beim Einschalten des
   Bildschirms denselben Revival an (mit `isAlive()`-Guard).

Dazu: `AppTrackingService` pflegt `@Volatile alive`/`lastPollAtMs`
(onCreate/onDestroy/first Poll instruction) — die Grundlage für
`isAlive()`; und die `assignActivity`-Persistenz beim Zuweisen einer
Activity zur getrackten App wurde korrigiert, damit die Zuweisung den
Revival übersteht.

---

## 3. Entscheidungen — und wo ich sie gegen die naheliegende Lösung getroffen habe

### 3.1 Zwei Typen statt vier — Migration deutet um statt Schema zu ändern

Naheliegend wäre gewesen, `CATEGORY` stehen zu lassen („schadet ja nicht")
oder die Tabelle neu zu bauen. Beides falsch: Ein ungenutzter vierter Typ
wäre in jeder Query, jedem Test und jeder späteren Oberfläche ein
Sonderfall — und ein Tabellen-Neubau riskiert Datenverlust. Die gewählte
Lösung: Das Schema bleibt identisch, die Migration 44→45 schreibt nur die
`type`-Werte um (drei UPDATE-Anweisungen, als Konstanten dokumentiert).
Damit ist das Update für Bestandsgeräte ein reines, getestetes Daten-Update —
und Room-Validierung kann nicht fehlschlagen, weil sich nichts geändert hat,
was sie prüft.

### 3.2 Die Regeln gehören ins Repository, nicht in die Oberfläche

Alle Baum-Regeln (an die Wurzel nur Projekte, unter Aufgaben nichts,
Geschwister-Nummerierung, exakt-Liste beim Neusortieren) stehen in
`PmTree`/`PmRepositoryImpl` — nicht in den Screens. Warum: Die nächste
Oberfläche (Widget, Import, Automation) erbt dieselben Regeln gratis, statt
sie nachzubauen.

### 3.3 Ein Mutex um alle Baum-Schreibvorgänge

Drag & Drop, Massen-Datum und Umzug sind Lese→Rechne→Schreib-Zyklen. Zwei
gleichzeitige Vorgänge könnten sich sonst Sortiernummern doppelt geben oder
Änderungen verlieren. `PmRepositoryImpl` serialisiert sie deshalb mit einem
`Mutex` — die Oberfläche bleibt davon unberührt.

### 3.4 Die gezogene Reihenfolge schlägt jede Automatik

Die frühere M19.1-Liste sortierte „übernommene zuerst". Das hätte jede per
Drag & Drop gezogene Ordnung beim nächsten Öffnen wieder überschrieben —
der Nutzer sieht seine Anordnung und meint, die App ignoriert ihn. Jetzt
gilt: offene vor erledigten (das ist Zustand, nicht Meinung), sonst exakt
`sort_order`. Der Übernahme-Hinweis bleibt als Chip an der Zeile sichtbar.

### 3.5 Das Glas bekommt ein Spaltenlimit

Die schmale Seitenspalte aus dem Auftrag („sehr schmal dass man auch die
todos richtig lesen kann") brauchte eine Änderung an `GemGlass` selbst:
`maxColumns = 3` begrenzt die Juwelen-Spalten, statt sie bei schmaler Breite
zu winzigen Krümeln schrumpfen zu lassen. Über der Grenze bleibt die Zahl
ehrlicher als unlesbare Punkte.

### 3.6 Schimmer: ein Modifier statt zehn Stellen, und respektiert Systemeinstellungen

Der Schimmer ist EIN wiederverwendbarer Modifier (`Modifier.shimmering`),
kein Copy-Paste in jedem Button. Und: Ist im System „Animationen
deaktivieren" gesetzt, bleibt der Knopf ruhig — ein Bling-Effekt, der bei
Barrierefreiheits-Einstellungen trotzdem flackert, ist kein „schöner
Kontrast", sondern ein Fehler.

### 3.7 Digital Balance: drei Netze, aber keine angefasste Kernlogik

Der Impuls wäre gewesen, „den Dienst halt dauerhaft laufen zu lassen" oder
die Übergabe-Logik umzubauen. Beides riskant: Das M18.151-Verhalten ist
ausgetestet (30 Tests) und darf nicht kaputtgehen. Deshalb nur
Wiederbelebungs-Netze AUSSEN herum (Boot/Update, Watchdog, Screen-On), alle
mit `isAlive()`-Guard gegen Doppelstart — die Kernlogik blieb unangetastet
und ihre Tests unverändert grün.

### 3.8 Was bewusst NICHT gemacht wurde

Kein `dynamicColor` (Theme bleibt fest), keine Animationen ohne
System-Freigabe, keine Änderung an `AppTrackingTakeoverPolicy`/
`ForegroundTracker`/`ScreenRecordingWorker`-Logik, kein Antasten des
Alt-Moduls „Todos" (Serien/Streaks), keine Levels/XP/Maskottchen.

---

## 4. Was die Tests beim Bauen gefunden haben (nicht nachträglich geschönt)

1. **Fehlender Import `scrollBy`** (`PmDragDrop.kt`): Das Auto-Scrollen der
   Liste beim Ziehen an den Rand kompilierte nicht — Kotlin verlangt den
   expliziten Import der Erweiterung `androidx.compose.foundation.gestures.scrollBy`.
2. **Falscher Import in `Shimmer.kt`** (aus dem Subagenten-Paket):
   `androidx.compose.runtime.composed` existiert nicht —
   `Modifier.composed` lebt in `androidx.compose.ui`. Vom Kompilierlauf
   gefunden, bevor irgendetwas lief.
3. **JSON-Schema-Annahmen der Migrationstests:** Die Schema-Dateien lassen
   die Felder `indices` (bei Tabellen ohne Index) und `views` (ganz) WEG —
   `getJSONArray` wirft dann. Beide Tests auf `optJSONArray` umgestellt:
   Der Aufbau der v44-Datenbank und der Element-Vergleich 44↔45 funktionieren
   jetzt auch für Tabellen ohne Indizes.
4. **Rooms Identitäts-Hash enthält die Version NICHT:** `44.json` und
   `45.json` tragen denselben `identityHash` (nachgemessen). Die erste
   Test-Fassung erwartete unterschiedliche Hashes; korrigiert und im Test
   dokumentiert — die Version wird ausschließlich über `PRAGMA user_version`
   unterschieden.
5. **Drei falsche Erwartungen in den ViewModel-Tests — jede lehrte eine
   Regel:** (a) Nach dem Tag-planen tragen SECHS Projekte die 17 Aufgaben,
   nicht fünf; (b) wird die einzige heutige Aufgabe eines Projekts gelöscht,
   verschwindet die Gruppe aus „Heute" (der Fortschritt bleibt am Baum);
   (c) eine heute erledigte Aufgabe ohne Datum bleibt im Tagesplan. Die
   Tests wurden auf die tatsächlichen (und richtigen) Regeln korrigiert, mit
   Begründung im Testkommentar.
6. **Eigene Fehlannahme zur Vorlage:** Die erste Testfassung erwartete die
   heutigen Aufgaben unter den Wurzel-Projekten (`pm-seed-alltag`), tatsächlich
   liegen sie in den Unterprojekten (`pm-seed-wohnung`) — der Test prüft jetzt
   die echte Struktur.

**Ehrliche Test-Lücke (dokumentiert statt verschwiegen):** Der
`assignActivity`-Persistenz-Fix im `AppTrackingViewModel` (legt beim Zuweisen
einer Activity zu einer noch nicht getrackten App jetzt einen Eintrag mit
`enabled = true` an) ist NICHT durch einen eigenen Unit-Test abgedeckt — es
gibt in `app/src/test` keine `AppTrackingViewModel`-Testdatei (Suche nach
`AppTrackingViewModel|assignActivity`: 0 Treffer), und ein Neubau dieser
Verdrahtung (ViewModel + Sync-Service + Repository) stand bewusst außerhalb
dieses Pakets. Belegt ist er durch Kompilierung und den Quellen-Scan in
`AppTrackingDigitalWiringTest`; empfohlen ist eine eigene kleine Suite, sobald
an `AppTrackingViewModel` das nächste Mal gearbeitet wird.

---

## 5. Verifikation

> **Bauausführung:** `robust_build.sh` (detached), Tag `aevum-m192b` —
> `testDebugUnitTest assembleDebug assembleRelease bundleRelease` (die
> bewährte Aufgabenliste aus M19.1), Signing-Guard aktiv (kanonischer
> Debug-Key, exit 99 bei Abweichung).

| Beleg | Ergebnis |
|---|---|
| Unit-Tests (voll) | **1273/1273 grün**, 0 Fehler (vorher 1240; +33 neue Tests) |
| Build | `aevum-m192b` — `testDebugUnitTest assembleDebug assembleRelease bundleRelease`, **BUILD SUCCESSFUL in 7m 47s** (exit 0) |
| Debug-APK | `app-debug.apk`, 124.486.270 B (124,5 MB), versionCode 33 / 1.0.32-debug, Signatur **kanonisch** `9c3055c4…fbe0e8` (vom Build-Skript UND unabhängig nachgeprüft) |
| Release-APK | `app-release.apk`, 52.232.455 B (52,2 MB), versionCode 33 / 1.0.32, Upload-Key `c8430d35…61ffe9`, minSdk 29 / targetSdk 36 |
| AAB (Play) | `app-release.aab`, 24.556.830 B (24,6 MB) |
| Download (LAN) | `http://192.168.178.40:8098/Aevum-1.0.32-Debug.apk` (124.486.270 B, kanonisch signiert) |
| Schemata 44/45 | Element für Element identisch (nur `version` unterscheidet sich) — `PmMigrationSchemaTest` |
| Daten-Migration | Kategorie/Schritte/Todo → neue Typen, Datum+Erledigung+Sortierung erhalten — `PmMigrationDataTest` (6 Tests, echte SQLite) |
| PM-Suiten | `PmTreeTest`, `PmTodayPlanTest`, `PmSeedTest`, `PmColorsTest`, `PmReorderTest`, `PmCelebrationGateTest`, `PmRepositoryRoomTest`, `PmProjectsViewModelTest` — grün |
| Theme/Schimmer | `AevumThemeTest` (2) + `PmShimmerTest` (5) — grün |
| Digital Balance | `AppTrackingDigitalWiringTest` (16) — Boot/Update-Haken, Watchdog-Einplanung, `isAlive()`-Guard, Screen-On-Nudge |

**Nebenbefund (Lint, NICHT Teil dieses Auftrags):** Ein zusätzlicher Lauf mit
`lintDebug` zeigt **5 Fehler in Altbestand-Dateien**, die diese Änderung nicht
angefasst hat: zwei `MissingPermission` (ACTIVITY_RECOGNITION) in
`ActivityContinuousSamples.kt`, zwei in `InitialActivitySnapshotWorker.kt`
und ein `UnspecifiedRegisterReceiverFlag` in `AppBlockService.kt` —
letzteres ein Lint-Fehlalarm auf dem Alt-Pfad: ab Android 13 wird
`RECEIVER_NOT_EXPORTED` gesetzt, der Treffer betrifft den else-Zweig für
ältere Versionen. Empfehlung: als eigenes kleines Paket beheben (explizite
Permission-Checks und `ContextCompat.registerReceiver` für alle Pfade) —
bewusst nicht in diesem Milestone mitgemacht, um den Auftrag nicht mit
fremden Baustellen zu vermischen.

**Ehrliche Einschränkung:** Auf dieser Maschine fehlt `/dev/kvm` (kein
Emulator) und es ist kein Gerät angeschlossen. Die Oberfläche wurde deshalb
**nicht** auf einem laufenden System durchgeklickt; bewiesen ist sie über
Kompilierung, Regel-Tests, echte Datenbank-Integration und
Schema-Gleichheit. Die Sichtprüfung (Drag & Drop mit dem Finger, Schimmer,
Glas neben den Listen, Instagram-Zuweisung) bleibt der letzte Schritt, den
nur Devon machen kann.

---

## 6. Dateien

**Neu:** `automation/apptracking/AppTrackingWatchdogWorker.kt`,
`data/repository/PmSeedTitles.kt`, `ui/components/Shimmer.kt`,
`ui/screens/projects/PmDragDrop.kt`,
`app/schemas/…/45.json`, Tests: `PmReorderTest`, `PmMigrationDataTest`,
`PmShimmerTest`, `AevumThemeTest`

**Umgeschrieben/geändert:** `data/model/PmNode.kt`,
`domain/projects/{PmTree,PmTodayPlan,PmSeed,PmColors}.kt`,
`data/repository/{PmRepository,PmRepositoryImpl}.kt`,
`data/db/AppDatabase.kt` (v45, `MIGRATION_44_45`), `di/{DatabaseModule,RepositoryModule}.kt`,
`ui/screens/projects/{ProjectsScreen,ProjectsViewModel,PmRows,PmNodeDetailScreen,PmNodeDetailViewModel,PmEditorScreen,PmEditorViewModel,PmDayPlannerSheet}.kt`,
`ui/components/{GemGlass,PmEntryButton}.kt`,
`ui/theme/{Theme,PmPalette,DesignTokens}.kt`,
`navigation/{AppNavHost,AppDestination}.kt`,
`automation/apptracking/AppTrackingService.kt`, `automation/geofence/BootReceiver.kt`,
`automation/sleep/ScreenEventReceiver.kt`, `ui/screens/apptracking/AppTrackingViewModel.kt`,
`AevumApplication.kt`, `AndroidManifest.xml`,
`res/values{,-de}/strings_projects.xml`,
`app/build.gradle.kts` (1.0.32 / versionCode 33),
Tests: `PmTreeTest`, `PmTodayPlanTest`, `PmSeedTest`, `PmColorsTest`,
`PmRepositoryRoomTest`, `PmProjectsViewModelTest`, `PmMigrationSchemaTest`,
`AppTrackingDigitalWiringTest`; `docs/{CHANGELOG,FEATURES,DATABASE,NAVIGATION}.md`,
`.project-memory/*`

**Bewusst unverändert:** `AppTrackingTakeoverPolicy.kt`,
`ForegroundTracker.kt`, `ForegroundAppProbe.kt`, `ScreenRecordingWorker.kt`
(Logik), Alt-Modul „Todos".

---

## 7. Offene Punkte und Empfehlungen

1. **Sichtprüfung auf dem Gerät** — der einzige Schritt, der Devon gehört:
   Update installieren → Theme und Schimmer ansehen → Projektmanagement
   öffnen → Aufgabe per Enter hinzufügen → ziehen → abhaken (Glas füllt sich
   daneben) → Instagram öffnen und die zugewiesene Activity prüfen.
2. **Nach dem ersten Reboot bewusst testen:** App komplett schließen
   (aus dem Recents wischen), Gerät neu starten, dann Instagram öffnen — die
   Aufzeichnung sollte sich von selbst wiederbeleben (BootReceiver). Schlägt
   das fehl, liefert der Watchdog spätestens nach 15 Minuten nach.
3. **Glas bei sehr langen Tagesplänen:** über der Kapazität bleibt die Zahl
   der Juwelen maßgeblich (bewusst, siehe M19.1 §7.3).
4. **Farbe der Vorlage:** Die vier Lebensbereiche tragen Eisblau, Feuerrot,
   Grün, Gold — falls eine andere Zuordnung gewünscht ist, ist das eine
   Einzeiler-Änderung in `PmSeed`.

# M19.3 — Abschlussbericht

**Datum:** 2026-10-17
**App-Version:** 1.0.33 (`versionCode 34`)
**Datenbank:** v46 (`MIGRATION_45_46`)
**Auftrag (Original-Wortlaut):**

> „Ich nehme es zurück. Ich will das ganze PM Management doch nicht. Also alles
> davon. Im normalen Dashboard soll auch kein Button mehr sein der dahin führt.
> Außerdem funktioniert die App Aufzeichnung immer noch nicht zuverlässig. Oft
> zeichnet der einfach weiter digital auf obwohl ich etwas bestimmtes geöffnet
> habe. Und manchmal klappt es dann direkt. Aber dann wieder nicht. Also ich
> habe eine Activity Handynutzung angelegt und Instagram zu dieser Activity
> hinzugefügt, dann will ich, dass die Aufzeichnung Handynutzung 5 Sekunden
> nach dem öffnen auch startet, egal ob digital oder eine andere App oder
> nichts aufgezeichnet wird, Hauptsache nichts anderes wird aufgezeichnet.
> Und die rote theme Farbe soll noch etwas röter. So feuerrot."

Drei Pakete also: **(1)** Projektmanagement komplett zurücknehmen (inkl.
Dashboard-Knopf), **(2)** App-Aufzeichnung zuverlässig machen (getrackte Activity
≤5 s nach dem Öffnen, nichts anderes zeichnet parallel), **(3)** Feuerrot
nachschärfen.

---

## 1. Projektmanagement KOMPLETT zurückgenommen

### Was entfernt wurde

„Also alles davon" wurde wörtlich genommen — kein Ausgrauen, kein
Feature-Flag, kein halber Rückbau:

**Einstiege und Navigation**

- `DashboardHero.kt`: PM-Knopf in der Kopfzeile (rechte obere Ecke) samt
  Zähler-Parametern `pmPending`/`pmHasPlan`/`onOpenProjects`.
- `DashboardScreen.kt`: Übergabe der PM-Parameter, der einschwebende
  PM-Knopf beim Wegscrollen (`AnimatedVisibility`-Block), der eigene
  `PmEntryViewModel` samt `pmEntry`-State und Import.
- `SettingsScreen.kt`: Eintrag „Projektmanagement" im Abschnitt „Erweitert"
  samt `onOpenProjects`-Parameter.
- `AppNavHost.kt`: die drei `composable`-Blöcke (`Projects`,
  `ProjectsNode`, `PmEditor` mit Sheet-Übergängen) und beide
  Navigations-Callbacks (Dashboard + Einstellungen).
- `AppDestination.kt`: die Enum-Einträge `Projects`, `ProjectsNode`,
  `PmEditor`.

**UI, Domäne, Daten**

- `ui/screens/projects/` komplett: `ProjectsScreen`, `ProjectsViewModel`,
  `PmNodeDetailScreen`, `PmNodeDetailViewModel`, `PmEditorScreen`,
  `PmEditorViewModel`, `PmEntryViewModel`, `PmDayPlannerSheet`,
  `PmDragDrop`, `PmRows`, `PmDateControls`.
- `domain/projects/` komplett: `PmTree`, `PmTodayPlan`, `PmSeed`,
  `PmColors`, `PmCelebrationGate`.
- `data/`: `PmNode`, `PmNodeDao`, `PmRepository`, `PmRepositoryImpl`,
  `PmSeedTitles`, `PmCelebrationStore`.
- `ui/components/`: `PmEntryButton`, `PmCheckbox`, `PlayfulProgressBar`,
  `GemGlass`, `FireworksOverlay`. `ui/theme/PmPalette.kt`.
- DI: beide Provider in `RepositoryModule.kt` + `PmNodeDao`-Import,
  `pmNodeDao()` in `AppDatabase.kt`, Entity-Eintrag in der `@Database`-Liste.
- Ressourcen: `values/strings_projects.xml` + `values-de/strings_projects.xml`
  (PM-Titel/Editor/Details, DE und EN).
- Tests komplett: `PmTreeTest`, `PmTodayPlanTest`, `PmSeedTest`,
  `PmColorsTest`, `PmCelebrationGateTest`, `PmRepositoryRoomTest`,
  `PmProjectsViewModelTest`, `PmReorderTest`, `PmMigrationDataTest`,
  `PmMigrationSchemaTest`.

**Bewusst NICHT entfernt** (wird von anderen Features genutzt):
`Shimmer.kt` (`shimmering`-Modifier — wandert auf den Dashboard-Plus-Knopf,
siehe §3), `PmEntryButton`-freie Restkomponenten wie alles Übrige, das
keinen PM-Bezug hatte. Ein Rest-Grep über `app/src` findet nach dem Umbau
nur noch Kommentar-Erwähnungen (Historie), keine Code-Referenzen.

### Datenbank: v46 — `MIGRATION_45_46` = DROP

Der Rückbau löscht die Tabelle **samt Inhalt**:

```sql
DROP TABLE IF EXISTS `pm_node`
```

Begründung (auch als KDoc in `AppDatabase.kt`): Eine jahrelang verwaiste
Tabelle läge sonst in jedem JSON-Export und jedem ZIP-Backup mit — ein
sauberer Schnitt ist ehrlicher als Datenmüll. Bestandsgeräte, die direkt von
≤44 kommen, fahren weiterhin die komplette Kette 43→44 (Tabelle anlegen) →
44→45 (Typen umdeuten) → 45→46 (Tabelle löschen); die historischen
SQL-Konstanten und Migrationsobjekte der M19.1/M19.2-Schritte bleiben dafür
erhalten. Der Code des Features selbst bleibt in der Git-Historie
(commits M19.1/M19.2) — ein Zurück wäre ein Revert, kein Rätselraten.

### Neue Absicherungs-Tests (beide neu geschrieben)

- **`SchemaV46Test`** (5 Tests, reine JVM): hält fest, dass `46.json`
  Element für Element `45.json` MINUS genau `pm_node` ist (Spalten, Indizes,
  Fremdschlüssel, Reihenfolge), dass Views und Setup-Abfragen strukturell
  unverändert sind (nur die Identitäts-Hash wechselt, wie sie muss), dass die
  Hash von 45 und 46 sich unterscheiden (Beweis: der Compiler hat die
  Entfernung gesehen) und dass `SQL_PM_45_46_DROP` genau
  ``DROP TABLE IF EXISTS `pm_node` `` lautet.
- **`PmRemovalMigrationTest`** (3 Tests, Robolectric): baut aus `45.json`
  eine ECHTE v45-Datenbank, füllt einen Alt-Baum aus zwei `pm_node`-Knoten
  ein, öffnet sie über Room mit `MIGRATION_45_46` — und prüft: Tabelle weg
  (inkl. Inhalt), alle übrigen **38 Tabellen** unangetastet (direkt gegen die
  Entity-Liste der 45er-Schema-Datei), `PRAGMA user_version` = 46 und
  Identitäts-Hash = die von `46.json`.

---

## 2. App-Aufzeichnung zuverlässig („Digital Balance")

### Befund (ehrlich)

Der Nutzerbericht: „Oft zeichnet der einfach weiter digital auf obwohl ich
etwas bestimmtes geöffnet habe. Und manchmal klappt es dann direkt. Aber dann
wieder nicht." Code-Befund: zwei unabhängige Ursachen in
`AppTrackingService`:

1. **Der Poll-Takt war zu langsam.** `POLL_INTERVAL_MS` stand bei **15 s**
   (Akkusparmaßnahme aus M18.93v11). Der Service bemerkt eine frisch geöffnete
   getrackte App also erst beim nächsten Takt — bis zu **15 s** lief „Digital"
   in der Zwischenzeit weiter. Das ist exakt das gemeldete Muster
   („zeichnet einfach weiter digital auf").
2. **Zwei ereignisgetriebene Lücken.** Die Übergabe hing an einem
   Wechsel-Trigger („Vordergrund-App hat sich geändert"). Ist die getrackte
   App aber schon vor dem Session-Start vorn (Instagram war bereits offen,
   als die Digital-Aufzeichnung startete), gibt es nie einen Wechsel — der
   Same-App-Zweig tat nichts. Und traf die Übergabe ein, während die eigene
   Session gerade ihre Stopp-Phase fuhr, wurde sie still verworfen („dann
   wieder nicht").

### Fix

1. **`POLL_INTERVAL_MS = 2_000L`** (war `15_000L`). Damit: Erkennung im
   Mittel nach ~1 s, Worst Case 2 s — deutlich unter den geforderten 5 s.
   Der Takt läuft nur, während der Bildschirm AN ist UND mindestens eine
   getrackte App definiert ist (der bisherige Guard bleibt) — die
   Akku-Ersparnis der Drosselung bleibt für alle anderen Zustände erhalten.
2. **Nachprüfen auch bei unveränderter App** — neues, reines Regelwerk
   `AppTrackingTakeoverPolicy.onRepeatedForeground(liveSourceType,
   liveIsLive, isOwnSession)`: Ist die Vordergrund-App dieselbe, die Session
   aber NICHT die eigene (z. B. spät angelaufenes „Digital"), wird sie wie
   ein Wechsel übernommen. Eigene Session oder gar keine Session → nichts
   tun (kein Flackern, keine Doppel-Starts).
3. **Einmaliges Nachfassen bei Stopp-Races** —
   `AppTrackingTakeoverPolicy.onStopRace(...)`: Kommt die Übergabe an,
   während die eigene Session bereits im Stoppen ist, wird die Übergabe
   nicht verworfen, sondern genau einmal nachgefasst (Ziel erreicht →
   fertig; eigene Session noch da → Rangfolge prüfen und nichts Fremdes
   anfassen).
4. **Zustands-Hygiene:** Läuft das Beenden der Session in einen Fehler,
   wird `currentTrackedApp` auf `null` zurückgesetzt statt einen toten
   Zustand zu konservieren.

`CALENDAR_AUTO` bleibt bewusst außerhalb der Übernahme-Liste (Kalender
startet nur über den expliziten Screen-Auto-Pfad; unverändert).

### Tests (festgenagelt statt „geht schon")

- `AppTrackingTakeoverPolicyTest` — bestehende Regeln + neue Regeln
  `onRepeatedForeground` (4 Fälle) und `onStopRace` (3 Fälle).
- `AppTrackingDigitalWiringTest` — **Test 11 TEMPO**: `POLL_INTERVAL_MS`
  muss exakt `2_000L` sein, `15_000L` darf nie zurückkehren (dunkler Wert
  verboten, per Datei-Scan gepinnt); **Test 12 NACHPFASSEN**: `onStopRace`
  und `onRepeatedForeground` müssen im Service verdrahtet sein; KDoc der
  Testdatei um beide Punkte ergänzt.

### Erwartetes Verhalten im Nutzer-Szenario

Activity „Handynutzung" angelegt, Instagram dieser Activity zugeordnet:
Instagram öffnen → spätestens nach **2 s** (Takt), unabhängig vom Vorzustand
(Digital läuft / andere App läuft / nichts läuft), startet die Session
„Handynutzung" — und weil digitale Aufzeichnung nur parallel laufen darf,
wenn NICHTS Getracktes vorn ist, beendet die Übergabe sie (bestehende
M18.151-Mechanik `stopIfLive` + neue Nachfass-Regel). „Hauptsache nichts
anderes wird aufgezeichnet."

---

## 3. Feuerrot nachgeschärft

„die rote theme Farbe soll noch etwas röter. So feuerrot."

| Rolle | vorher (M19.2) | **nachher (M19.3)** |
|---|---|---|
| `secondary` dunkel | `#F87171` | **`#FF3B2E`** |
| `secondaryContainer` dunkel | `#5F1714` | **`#6E140B`** |
| `onSecondaryContainer` dunkel | `#FFD9D3` | **`#FFDAD5`** |
| `secondary` hell | `#DC2626` | **`#D32614`** |
| `DesignTokens[1]` (Feuer) | `#F87171` | **`#FF3B2E`** |

- `Theme.kt`: Dark- und Light-Schema angepasst, KDoc um M19.3-Begründung
  ergänzt (Eisblau bleibt unangetastet — nur die Sättigung des Feuers steigt).
- `DesignTokens.kt`: Feuer-Token nachgezogen (das bisherige `PmPalette`-Feuer
  entfällt mit dem PM-Rückbau; der Token bleibt für andere Nutzungen).
- `AevumThemeTest`: Pins auf die neuen Werte umgestellt (dunkel `#FF3B2E`,
  hell `#D32614`), inkl. KDoc.
- **Schimmer umgezogen:** Der wandernde Schimmer saß auf dem entfallenen
  PM-Knopf. Er wandert auf den runden **Plus-Knopf** des Dashboards
  (`FloatingPlusButton`, `.shimmering(intensity = 0.22f)`) — „gerne auch mit
  schimmern bei manchen Buttons und so". Respektiert weiterhin die
  System-Einstellung „Animationen aus" (`Shimmer.kt` unverändert).

---

## 4. Versionssprung & Abgrenzung

- `versionCode = 34`, `versionName = "1.0.33"` (Kommentar in
  `app/build.gradle.kts` mit den drei Paketen).
- **Bewusst NICHT angefasst:** Todos-Modul (unverändert; PM war immer strikt
  getrennt), Kalender-Automatik, übrige Theme-Töne (Eisblau, Gold),
  M18.151-Übergabe-Kernlogik (`ForegroundTracker`, `stopIfLive`).
- Die bekannten **Lint-Altbestände** (2× MissingPermission
  ACTIVITY_RECOGNITION in `ActivityContinuousSamples.kt`/
  `InitialActivitySnapshotWorker.kt`, 1× RegisterReceiver-Flag in
  `AppBlockService.kt`) sind unverändert außerhalb dieses Auftrags — der
  Build läuft wie bei M19.2 ohne `lintDebug`.

---

## 5. Build & Verifikation

### Messwerte (Build `aevum-m193`, robust_build.sh v4)

- **Unit-Tests: 1145/1145 grün — 0 Fehler, 0 Errors** (107 Testklassen; der
  eine „Skipped" ist Altbestand: `@Ignore` in `CyclingVsCarDetectionTest`).
  Die Suite ist kleiner als bei M19.2 (1273), weil **10 PM-Testklassen**
  entfallen und **2 neue** (`SchemaV46Test`, `PmRemovalMigrationTest`)
  hinzukommen.
- **Build** `testDebugUnitTest assembleDebug assembleRelease bundleRelease`:
  **BUILD SUCCESSFUL in 5m**, exit 0.
- **Debug-APK:** 123.438.646 B (123,4 MB), `versionCode 34`,
  `versionName 1.0.33-debug`, minSdk 29 / targetSdk 36 — Signatur SHA-1
  `9c3055c4810b5cc2e548d8de8357344352fbe0e8` (kanonischer Debug-Key,
  per `apksigner verify` bestätigt).
- **Release-APK:** 51.987.807 B (52,0 MB), `versionCode 34`, `1.0.33` —
  Upload-Key SHA-1 `c8430d3589bc579db2e8c7c6af9a1619e361ffe9` (bestätigt).
- **AAB (Play-Console):** 24.228.225 B (24,2 MB), Upload-Key.
- **Ausgeliefert:** `http://192.168.178.40:8098/Aevum-1.0.33-Debug.apk` —
  Datei liegt als `Aevum-1.0.33-Debug.apk` im Download-Ordner, `index.html`
  trägt Button + „Neu in 1.0.33"-Karte.
- Gebaut wie M19.1/M19.2 **ohne `lintDebug`** — die 5 bekannten
  Lint-Altbestandsfehler außerhalb dieses Auftrags bleiben unverändert
  (Empfehlung aus dem M19.2-Bericht: eigenes kleines Paket).

## 6. Ehrliche Lücken

- **Kein Geräte-Test möglich** (wie immer in dieser Umgebung): Die
  ≤5-s-Übergabe ist per Test auf Konstanten/Regeln/Verdrahtung festgenagelt
  (`POLL_INTERVAL_MS = 2_000L` + beide Nachfass-Regeln verdrahtet), der
  Beweis am echten Gerät (Instagram öffnen → „Handynutzung" läuft nach ≤5 s)
  steht beim Nutzer aus. Der Worst Case ist rechnerisch 2 s Takt + 1
  Poll-Zyklus Verarbeitung; die App-Session-Startzeit selbst war schon in
  M18.150/M18.151 optimiert (Scan außerhalb des Main-Threads).
- **`PmShimmerTest`** testet jetzt nur noch den `shimmering`-Modifier ohne
  den PM-Knopf — der Test bleibt grün und sinnvoll (Modifier-Verhalten), die
  frühere Verankerung „PmEntryButton nutzt shimmering" ist entfallen.
- Die PM-Historie in Doku (`docs/M19-1-*.md`, `docs/M19-2-*.md`,
  CHANGELOG-Einträge) bleibt absichtlich stehen — als Archiv, ausdrücklich
  als HISTORIE markiert (FEATURES/NAVIGATION/Projekt-Memory).

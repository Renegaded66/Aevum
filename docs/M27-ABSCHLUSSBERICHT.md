# M27 — Sucht-Detektor, Backup-Audit und verzögerte Ziel-Wertung

> App **1.0.47** (versionCode 48) · DB **v50** · Stand: 21.10.2026
>
> Auftrag von Devon (drei Teile):
> 1. **Handy-Sucht-Detektor** als neuer Punkt im Balance-Tab: Apps hinzufügen,
>    Öffnungen pro Tag, Sitzungslängen, Vergleich über Tage/Wochen/Monate.
> 2. **Backup-System prüfen** — landen die vielen neuen Features überhaupt
>    im Backup für den Gerätewechsel?
> 3. **Ziel-Wertung um einen Tag verzögern** — das Morgen-Popup für
>    „Bildschirmzeit unter 3 h" darf nicht mehr am selben Morgen feuern,
>    sondern erst nach einem abgeschlossenen Tag (bzw. ab 2 Tagen in Folge).

---

## 1. Sucht-Detektor im Balance-Tab

### 1.1 Einstieg und Aufbau

Der Balance-Tab hat jetzt **drei** Seiten im bestehenden HorizontalPager:

```
Balance  |  Detektor  |  Pomodoro
```

Zusätzlich gibt es auf der Balance-Seite eine kompakte **Einstiegskarte**
(„Sucht-Detektor", heutige Zeit, Öffnungen, Nutzungsdruck). Ein Tipp darauf
scrollt auf die Detektor-Seite. Damit ist das Feature sichtbar, ohne die
bereits lange App-Liste weiter aufzublähen.

Die Detektor-Seite selbst ist eine eigene, scrollbare Seite:

- **Heute-Karte** (Gradient): große Zeit seit Mitternacht, Öffnungen, längste
  Sitzung, Ø Sitzung und ein **Nutzungsdruck-Ring** (0–100).
- **Entwicklung**: Umschalter **7 Tage / 4 Wochen / 6 Monate** und
  **Zeit / Öffnungen**; Balken je vollständiger Periode, darunter Ø pro Tag
  und Gesamtwert mit Veränderung zur Vorperiode.
- **Heute nach Uhrzeit**: 24 Stundenbalken (Nachtstunden 22–6 farblich rot).
- **Apps im Vergleich**: pro App Zeit, Öffnungen, Anteil, Trend und
  aufklappbare Detailansicht mit Ø/Tag, Ø Sitzung, längster Sitzung und
  **allen Sitzungen von heute** (Startzeit + Dauer).
- **App-Auswahl**: Bottom-Sheet mit Suche, Mehrfachauswahl, bereits
  beobachteten Apps und Schnellvorschlägen (Instagram, TikTok, YouTube,
  Snapchat, Facebook, X, Reddit, Netflix — nur wenn installiert).
- **Info-Dialog** erklärt Datenquelle, Öffnungs-Definition, abgeschlossene
  Perioden, Score-Formel und dass alles lokal bleibt.

### 1.2 Datenmodell (DB v50)

Neu, rein additiv (`MIGRATION_49_50`):

| Tabelle | Inhalt |
|---|---|
| `addiction_watch_app` | ausgewählte Apps (`package_name` PK, Label, `added_at`, `sort_order`) |
| `addiction_session` | Vordergrund-Sitzung (`id` PK = `paket@start`, Paket, Start, Ende, Dauer), Indizes auf Paket und Start |

Keine Fremdschlüssel: Das Entfernen einer beobachteten App darf ihre bereits
erhobene Historie nicht mitlöschen. Wird die App später erneut hinzugefügt,
ist der Verlauf sofort wieder da.

### 1.3 Datenerhebung

Quelle ist derselbe Android-**Nutzungszugriff** wie Digital Balance
(`UsageStatsManager.queryEvents`). Kein neuer Permission-Dialog, keine neue
Berechtigung, keine Cloud.

- **Faltung** über den bestehenden `UsageEventFolding.Folder` (Activity-Ebene,
  Paket-Vereinigung). Dadurch zählt ein Splash-Screen nicht als zusätzliche
  Öffnung.
- **Screen-Off schließt offene Sitzungen** (`SCREEN_NON_INTERACTIVE` /
  `DEVICE_SHUTDOWN`). Ohne diesen Pfad hätte eine beim Sperren noch offene
  App die ganze Nacht weitergezählt — für einen Sucht-Detektor ein fataler
  Datenfehler.
- **Rauschfilter**: Phasen unter **3 Sekunden** werden verworfen.
- **Idempotenz**: ID = `paket@start`. Ein erneuter Scan aktualisiert dieselbe
  Zeile (eine noch offene Sitzung wächst bis zum Scan-Zeitpunkt), statt
  Duplikate anzulegen.
- **Aufbewahrung**: 400 Tage; ältere Sitzungen werden beim Sync gelöscht.
- **Erst-Backfill**: beim Hinzufügen einer App bis zu **45 Tage** rückwirkend
  (Android hält Events je nach Hersteller oft nur wenige Tage vor — was da
  ist, wird mitgenommen; der Rest wächst ab jetzt mit).
- **Live-Takt**: Die Detektor-Seite synct beim Öffnen und danach jede Minute,
  solange sie sichtbar ist. Außerhalb übernimmt ein **30-Minuten-Worker**
  (`AddictionSyncWorker`, WorkManager, `KEEP`), damit Events nicht aus dem
  System-Ringpuffer fallen.
- **Sync-Fenster**: ab letztem Sync minus 6 h Puffer, maximal 45 Tage. Der
  Puffer deckt verspätete Events und offene Sitzungen ab; mehrfach gelesene
  Events sind wegen der stabilen ID harmlos.

### 1.4 Auswertung — welche Daten ergeben sinnvolle Vergleiche?

Die zentrale Design-Entscheidung: **„Heute" ist live, jeder Vergleich nutzt
nur abgeschlossene Perioden.**

- 7 Tage = die letzten **7 vollen Tage** (gestern rückwärts),
- 4 Wochen = die letzten **4 vollen Kalenderwochen**,
- 6 Monate = die letzten **6 vollen Kalendermonate**.

Dadurch sind zwei Vergleichszeiträume immer gleich lang, und ein laufender
Morgen kann keinen „besseren" Wert vortäuschen. Die Heute-Karte steht separat
daneben.

Weitere Semantik:

- **Öffnung** = Vordergrund-Phase ab 3 s. Eine Sitzung über Mitternacht zählt
  als Öffnung an ihrem **Starttag**; ihre Dauer wird für Tages-Summen an
  Mitternacht geteilt. So entstehen weder Doppel-Öffnungen um 0 Uhr noch
  falsche Tageszeiten.
- **Ø Sitzung** = Gesamtzeit / Öffnungen (nicht „Median"), weil das die
  tatsächlich erlebte durchschnittliche Sitzungslänge ist.
- **Trend pro App** = Veränderung zur gleich langen Vorperiode. Fehlte die
  App in der Vorperiode komplett, zeigt die UI **„neu"** statt einer
  sinnlosen Riesenzahl.
- **Nutzungsdruck (0–100)** aus den letzten 7 vollen Tagen, transparent:
  - 40 % Tageszeit (Referenz 4 h),
  - 25 % Öffnungen (Referenz 70/Tag),
  - 20 % längste Sitzung (Referenz 60 min),
  - 15 % Nachtanteil 22–6 (Referenz 20 %).
  Labels: **ruhig** (≤ 39), **erhöht** (40–69), **intensiv** (≥ 70). Das ist
  eine Orientierung, keine Diagnose — steht so auch im Info-Dialog.

### 1.5 Bewusste Abgrenzungen

- Keine Benachrichtigungs-Blockade, keine Erziehung, kein „Sucht-Score" mit
  medizinischem Anspruch.
- Keine Auswertung von Inhalten, Kontakten oder Netzwerkdaten — nur
  Vordergrund-Zeit und Öffnungen der vom Nutzer ausgewählten Apps.
- Keine Cloud, kein Upload, keine neue Berechtigung.
- Kein Hintergrund-Foreground-Service; der 30-Minuten-Worker ist ein normaler
  CoroutineWorker.

---

## 2. Backup-Audit

### 2.1 Befund: Das Backup war seit v41 kaputt

`DataManager.createBackup()` sichert **die komplette SQLite-Datei** plus
**alle** SharedPreferences- und DataStore-Dateien. Das ist die richtige
Architektur: Neue Tabellen (Ziele, Todos, Kalender, Garmin, App-Tracking,
Digital Balance, jetzt Sucht-Detektor) landen automatisch im ZIP, ohne dass
jemand eine Tabellenliste pflegen muss. Auch neue SharedPreferences werden
durch den Verzeichnis-Scan automatisch erfasst.

Der **Versionscheck** war trotzdem ein Totalblocker:

```kotlin
// alt:
private const val CURRENT_SCHEMA_VERSION = 40
if (backupVersion != currentVersion) { ... Fehler ... }
```

Die Datenbank stand bereits bei **v49**, die Konstante noch bei **40**. Seit
v41 schlug damit **jeder** Restore mit „Backup-Version passt nicht" fehl.
Zusätzlich war „exakte Gleichheit" falsch: Ein Backup von einem älteren
App-Stand ist beim Gerätewechsel normal und muss durch die vorhandene
Room-Migrationskette hochgezogen werden.

### 2.2 Fix

- **Eine Quelle für die Version:** top-level `APP_DATABASE_VERSION = 50`,
  `@Database(version = APP_DATABASE_VERSION)` und
  `AppDatabase.SCHEMA_VERSION` für den Backup-Check. Die zweite, einfrierbare
  Zahl ist entfernt.
- **`BackupVersionPolicy`** (rein, getestet):
  - älter → erlaubt, Room migriert beim nächsten Start,
  - gleich → erlaubt,
  - neuer → abgelehnt (ältere App kennt neue Tabellen nicht),
  - 0/negativ → keine gültige Room-DB.
- **DB-Handle wird geschlossen**, nachdem die Backup-Version gelesen wurde
  (vorher Leak).
- **ZIP-Pfad-Ausbruch** (`../`) wird ignoriert.
- **UI-Texte** erklären jetzt: komplette Datenbank + alle Einstellungen,
  neue Datenbereiche automatisch, ältere Backups werden migriert.
- **Eigener Audit-Test** (`BackupCoverageWiringTest`) hält fest: DB-Datei,
  alle SharedPreferences, DataStore, `SCHEMA_VERSION`, Migrations-Policy.

### 2.3 Was ist im Backup?

| Bereich | Gesichert? |
|---|---|
| Alle Room-Tabellen inkl. neuer Detektor-Tabellen | ✅ als komplette DB-Datei |
| Ziele, Check-ins, Todos, Kalender, Garmin, App-Tracking | ✅ (DB) |
| Digital-Balance-Limits, Profile, App-Auswahl | ✅ (DB) |
| Sucht-Detektor: beobachtete Apps + Sitzungen | ✅ (DB v50) |
| Sprache, Theme, Insights-Periode, Timeline-Zoom, LifeView | ✅ SharedPreferences |
| Garmin-/PC-Tracker-Zugang, Limit-Warnungen, Celebration-Merker | ✅ SharedPreferences |
| DataStore (`aevum_preferences`) | ✅ Datei |
| Crash-Logs / App-Tracking-Diagnoselog | ❌ bewusst (nur Diagnose, keine Nutzerdaten) |

---

## 3. Verzögerte Ziel-Wertung

### 3.1 Problem

Ein AT_MOST-Tagesziel („Bildschirmzeit unter 3 h") ist morgens um 8 Uhr
natürlich erfüllt. Die Feier (`GoalCelebrationOverlay`) feuerte deshalb jeden
Morgen — obwohl der Tag abends noch kippen konnte. Die Serie zählte den
laufenden Tag ebenfalls mit.

### 3.2 Regel

Verzögert werden **automatisch gemessene AT_MOST-Tagesziele** — genau der
Fall „am Tag unter einem Wert bleiben".

- `isMet` bleibt der **Live**-Wert (Fortschrittsbalken, Warnung bei
  Überschreitung).
- `isPendingFinal` = true, solange der Tag läuft.
- `isCurrentAchieved` = false während des laufenden Tages → die Karte zeigt
  **„Wertung folgt"** statt „Erreicht".
- `lastCompletedStart/Met/PeriodKey` beschreiben den **letzten vollen Tag**.
- `streakCurrent` zählt nur abgeschlossene Tage. Die Karte zeigt bei einem
  erfolgreichen gestrigen Tag zusätzlich **„Zuletzt geschafft"**.
- Die Feier nutzt `celebrationPeriodKey` = **gestern**, nicht heute, und
  erscheint erst ab **2 Tagen in Folge** (`MIN_DELAYED_CELEBRATION_STREAK`).
  Ein einzelner erfolgreicher Tag bleibt sichtbar, löst aber noch kein
  Vollbild-Popup aus.
- **AT_LEAST** („2 h lernen") bleibt sofort final: Ist der Wert erreicht,
  kann er nicht mehr „kippen".
- **Manuelle Ziele** bleiben mit dem Check-in sofort final.
- **Wochen-/Monatsziele** behalten ihr bisheriges Live-Verhalten.

### 3.3 Zusatzfund: 91-Tage-Serie bei frisch angelegten AT_MOST-Zielen

Die Verlaufsauswertung baute 91 Tage Historie auf — auch vor der
Ziel-Erstellung. Bei AT_MOST erfüllt „keine Daten = 0" das Limit automatisch.
Ein neu angelegtes „unter 3 h"-Ziel hätte damit sofort eine 91-Tage-Serie
gehabt. M27 filtert Perioden vor `goal.startAt` aus Serie und Feier.

### 3.4 UI-Anpassungen

- Ziel-Karte: neuer Status **„Wertung folgt"** (statt „Erreicht") für
  laufende AT_MOST-Tage.
- Zusatzzeile **„Zuletzt geschafft"**, wenn der letzte volle Tag erfüllt war.
- Dashboard-/Ziele-Zähler und Sortierung nutzen `isCurrentAchieved` statt des
  Live-Werts — laufende Ziele werden nicht mehr als „erledigt" einsortiert.
- Schimmer auf der Ziel-Karte nur noch bei finaler Wertung.

---

## 4. Tests

Neu (jeweils JVM/Robolectric, keine echten Geräte):

| Datei | Inhalt |
|---|---|
| `domain/addiction/AddictionAnalyticsTest.kt` | 8 Tests: 7-Tage-Fenster ohne heute, Mitternachtssplit, 4-Wochen-/6-Monats-Fenster, 3-s-Filter, Druckindex, Nachtanteil, App-Trend, Leerzustand |
| `domain/addiction/AddictionSessionMapperTest.kt` | 3 Tests: Paket-/Kurzphasen-Filter, stabile ID, Screen-Off schließt offene Sitzung |
| `data/db/SchemaV50Test.kt` | 4 Tests: v50 = v49 + zwei Tabellen, Spalten/Indizes, keine FKs, Konstante = 50 |
| `data/db/AddictionMigrationTest.kt` | 4 Tests auf echter SQLite: 49→50, Bestandsdaten, DAO-Schreiben/-Lesen, user_version |
| `data/manager/BackupVersionPolicyTest.kt` | 4 Tests: älter/gleich/neuer/ungültig |
| `data/manager/BackupCoverageWiringTest.kt` | 4 Tests: DB-Datei, alle Prefs, DataStore, Schema-Konstante/Migrations-Policy, neue Tabellen |
| `GoalDelayedEvaluationTest.kt` | 6 Tests: Morgen-/Abendfall, 2-Tage-Popup, Einzeltag ohne Popup, frisches AT_MOST-Ziel ohne 91er-Serie, manuelles Ziel sofort, AT_LEAST sofort |
| `ui/screens/addiction/AddictionDetectorRenderTest.kt` | 2 Robolectric-Render-Tests (gefüllt/leer) → PNG-Sichtprüfung |

Angepasst: `GoalArchitectureWiringTest`, `StepWalkStopWiringRegressionTest`,
`CallDetectionMigrationTest`, `GoalV47MigrationTest`, `PmRemovalMigrationTest`
(Migrationskette bis v50, Identitäts-Hash aus 50.json).

**Gesamt: 1352+ Tests, 0 Fehler** (genaue Zahl/Zeit siehe Build-Log).

Sichtprüfung der gerenderten Detektor-Seite: Der Erklärungstext des leeren
Zustands war zu lang und wurde nach 4 Zeilen abgeschnitten (auf 10 erhöht und
Text gekürzt); die Stundenachse brach „18" um (`maxLines=1`, `softWrap=false`);
„1 opens" und „7× opens" wurden zu „1×"/„7× total"; „new" ist jetzt ein
sichtbarer Primärfarben-Hinweis.

---

## 5. Build und Artefakte

- **Tests:** `:app:testDebugUnitTest` — **1359 Tests, 0 Fehler, 0 Errors,
  1 skipped** (56,1 s Testzeit). Neu: 35 Tests in acht Dateien.
- **Voll-Build:** `:app:testDebugUnitTest :app:assembleDebug` —
  **BUILD SUCCESSFUL** (4m 58s im Voll-Lauf, 1m 30s im finalen inkrementellen
  Lauf).
- **Debug-APK:** `app/build/outputs/apk/debug/app-debug.apk`,
  **126.514.602 Bytes** (120,7 MiB), versionCode **48**, versionName
  **1.0.47-debug**.
- **SHA-256:** `c300469021efaf8fbfd229d3de2b164482429ed8e16b2457e6ee30b9b3456505`
- **Signatur:** kanonischer Debug-Key, SHA-1
  `9C:30:55:C4:81:0B:5C:C2:E5:48:D8:DE:83:57:34:43:52:FB:E0:E8`
  (`signing-verify OK`).
- **Commit:** `442ecef` — „M27: Sucht-Detektor im Balance-Tab,
  Backup-Restore repariert, Ziele einen Tag verzoegert - Aevum 1.0.47".
- **Hinweis Lint:** `lintDebug` ist im Projekt kein Build-Gate. Der Lauf
  meldet weiterhin bestehende Altbefunde (`MissingPermission` in
  `ActivityContinuousSamples`/`InitialActivitySnapshotWorker`,
  `UnspecifiedRegisterReceiverFlag` in `AppBlockService`). Der einzige neue
  M27-Befund (`ExtraTranslation` durch eine nur deutsche Zusatzstring) wurde
  behoben; die AAPT-Warnung zu `%`-Zeichen im Info-Text ebenfalls
  (`formatted="false"`).

Sichtprüfung der gerenderten Detektor-Seite: Der Erklärungstext des leeren
Zustands war zu lang und wurde nach 4 Zeilen abgeschnitten (auf 10 erhöht und
Text gekürzt); die Stundenachse brach „18" um (`maxLines=1`, `softWrap=false`);
„1 opens" und „7× opens" wurden zu „1×"/„7× total"; „Share 72 %" ist jetzt
lokalisiert korrekt „Share 72%" bzw. „Anteil 72 %".

---

## 6. Bekannte Grenzen

- **Android-Event-Retention** ist herstellerabhängig (oft nur wenige Tage).
  Der 45-Tage-Backfill beim Hinzufügen nimmt mit, was das Gerät liefert;
  ältere Monate wachsen erst ab Installation/Beobachtung.
- **Kein Geräte-/Emulator-Test** in dieser Umgebung. Die UsageStats-Pipeline
  ist über die bestehende Digital-Balance-Logik und reine Faltungs-Tests
  abgesichert; ein echter Gerätetest (Instagram öffnen/schließen, Bildschirm
  sperren, Detektor prüfen) steht noch aus.
- **Nutzungsdruck** ist eine transparente Heuristik, keine medizinische
  Diagnose.
- **Backup**: Ältere Backups werden beim nächsten App-Start per Room
  migriert. Sehr alte Backups vor dem ersten Migrationspfad (v1) bleiben
  außen vor — der praktisch relevante Bereich ist ab v40 abgedeckt.
- **App-Icons** werden aus dem installierten App-Katalog geladen; ist eine
  beobachtete App deinstalliert, zeigt die Karte das neutrale 📱-Fallback.

---

## 7. Dateien (Auszug)

**Neu:**
`data/model/AddictionWatchApp.kt`, `data/model/AddictionSession.kt`,
`data/db/AddictionWatchAppDao.kt`, `data/db/AddictionSessionDao.kt`,
`data/repository/AddictionWatchRepository.kt`,
`data/repository/AddictionSessionRepository.kt`,
`domain/addiction/AddictionAnalytics.kt`,
`domain/addiction/AddictionSessionMapper.kt`,
`domain/addiction/AddictionSessionCollector.kt`,
`automation/addiction/AddictionSyncWorker.kt`,
`data/manager/BackupVersionPolicy.kt`,
`ui/screens/addiction/AddictionDetectorViewModel.kt`,
`ui/screens/addiction/AddictionDetectorPage.kt`,
`ui/screens/goals/GoalCelebrationPolicy.kt`,
`app/schemas/.../50.json`,
`values/strings_addiction.xml`, `values-de/strings_addiction.xml`,
acht neue Testdateien.

**Geändert:**
`AppDatabase` (v50 + Migration), `DatabaseModule`, `AevumApplication`
(Worker-Scheduling), `DataManager` (Versionscheck + ZIP-Härtung),
`GoalProgressAnalytics`, `GoalsViewModel`, `DashboardGoalsViewModel`,
`GoalDetailViewModel`, `GoalsScreen`, `DashboardScreen`,
`DigitalBalanceScreen` (dritter Pager + Einstiegskarte),
`UsageEventFolding` (Screen-Off), `EmptyState` (maxLines-Parameter),
Strings (Backup/Badges), Build-Version.

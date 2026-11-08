# M36 — Abschlussbericht: Habit-Tracker & PC-Duplikat-Fix

**Datum:** 8. November 2026
**App:** Aevum 1.0.57 (versionCode 58, Debug `1.0.57-debug`)
**Datenbank:** v53
**PC-Tracker:** 1.7.2 (win-x64)
**Commit:** `2166c6b`

---

## 1. Auftrag (Devon, wörtlich)

> „Beim PC Tracker ist es heute irgendwie passiert, dass eine Aufzeichnung
> zwei Mal zu unterschiedlichen Zeitpunkten übertragen wurde ans Handy,
> einmal von 20 bis 21 Uhr und dann nochmal von 20 bis 22 Uhr, und dann hab
> ich jetzt zwei Aufzeichnungen mit unterschiedlicher Dauer parallel, das ist
> natürlich Quatsch. Und bei den Zielen will ich bei einer Zeiteingabe eine
> vernünftige … drei Drehregler nebeneinander für Stunden, Minuten und
> Sekunden. Und nichts mehr mit optional. Es gibt Ziele, wo man nur angibt
> erledigt, und es gibt Ziele, wo man eine Anzahl festlegen kann, und es gibt
> Ziele, wo man eine Zeit eintragen kann. Fertig. Und sobald man eine Zeit
> eingetragen hat, dann hat man das Ziel für den jeweiligen Tag ja auch
> offensichtlich erreicht. Außerdem soll man auch durch die Tage bei den
> Zielen swipen können und die anklicken können, damit ich bspw. wenn ich die
> letzten 2 Tage vergessen habe, da immer noch abhaken und Zeit eintragen
> kann. Das Ziel-Feature soll ein vollständiger Habit-Tracker mit süchtig
> machender Dopamin-UI sein."

Daraus wurden zwei Arbeitsstränge: **PC-Duplikate** und **Ziele 2.0**.

---

## 2. PC-Tracker: Ursache und Fix

### 2.1 Ursache (belegt)

Der Import war auf dem Papier idempotent (`external_id = "pc_<uuid>"`), aber
nicht atomar:

1. Der periodische WorkManager-Lauf, der gedrosselte App-Öffnen-Kick und der
   manuelle „Jetzt synchronisieren"-Knopf konnten **gleichzeitig**
   `syncNow()` ausführen.
2. Beide lasen denselben PC-Stand (`GET /api/sessions?since=…`), fanden über
   `getByExternalIdsOnce` **keine** vorhandene Zeile und legten jeweils eine
   neue mit **eigener zufälliger UUID** an.
3. Traf Lauf A den PC noch bei `endAt = 21:00` und Lauf B wenig später bei
   `endAt = 22:00`, entstanden exakt die beiden gemeldeten Zeilen
   **20–21** und **20–22**.

Zusätzlich konnte auf dem PC ein Timer-Reentry (Tick länger als 2 s) zwei
Engine-Ticks parallel laufen lassen; ein verspäteter Heartbeat mit
`end_at = NULL` konnte eine bereits abgeschlossene Session wieder „öffnen".

### 2.2 Fixes auf dem Handy

| Fix | Datei | Wirkung |
|---|---|---|
| `Mutex` um `syncNow()` | `PcTrackerRepository.kt` | nur ein Sync gleichzeitig; kein paralleles Lookup+Insert mehr |
| Deterministische ID `id = externalId` | `PcSessionImporter.kt` | zwei Schreiber landen auf derselben PK-Zeile (`REPLACE`) |
| `UNIQUE` auf `activity_session.external_id` | `ActivitySession.kt`, DB v53 | die Datenbank selbst verhindert Duplikate |
| Migrations-Aufräumen | `AppDatabase.MIGRATION_52_53` | Bestandsduplikate: je externalId bleibt die neueste, nicht gelöschte Zeile |
| `PcSessionDeduper` | `data/pc/PcSessionDeduper.kt` | führt eingebettete PC-Aufzeichnungen zusammen (20–21 ⊂ 20–22 → nur 20–22) |
| Aufräumen nach jedem Import | `PcSessionImporter.dedupeStoredSessions()` | idempotent, heilt auch alte Bestände |
| Soft-Delete-Schutz | `ActivitySessionDao`, `PcSessionImporter` | eine bewusst gelöschte PC-Zeile bleibt gelöscht und kollidiert nicht mit dem UNIQUE-Index |

Der Deduper schützt ausdrücklich: nutzerbearbeitete Sessions, offene Sessions,
andere Quellen und echte aufeinanderfolgende Sessions (20–21 / 21–22 bleiben
beide). Nur vollständig enthaltene PC-Zeilen derselben Aktivität (oder mit
nahezu identischem Start) werden gelöscht.

### 2.3 Fixes auf dem PC (1.7.2)

- `TrackerService.TickSafe()`: `Interlocked.Exchange`-Guard — ein laufender
  Tick blockiert den nächsten.
- `SessionStore.Apply()`: `end_at = COALESCE(excluded.end_at, sessions.end_at)`
  — ein verspäteter Heartbeat kann eine abgeschlossene Session nicht mehr auf
  `NULL` zurückdrehen.
- `SessionStore.MergeOverlappingSessions()`: räumt beim Start eingebettete
  Doppel-Sessions derselben Aktivität auf (längere bleibt, Toleranz 2 min).

---

## 3. Ziele 2.0: Habit-Tracker

### 3.1 Genau drei Arten

| Art | Speicherung | Tages-Erfüllung |
|---|---|---|
| **DONE** | `goal.kind='DONE'`, Check-in `count=1` | mindestens ein Check-in |
| **COUNT** | `goal.kind='COUNT'`, `target_value=N` | Summe der Check-ins ≥ N |
| **TIME** | `goal.kind='TIME'`, Check-in `duration_ms>0` | **jede** eingetragene Zeit > 0 |

`GOAL_KIND_LEGACY` bleibt für Alt-Ziele aus M20/M35. Die Auswertung neuer
Habits läuft in `GoalProgressAnalytics.evaluateHabit()` / `habitHistory()`
ausschließlich über `goal_checkin` — keine Sessions, keine Bildschirmzeit,
keine Aufgaben.

### 3.2 Editor

Der Editor enthält nur noch:

- Titel + Emoji (20 Vorschläge, Default je Art 🎯/🔢/⏱️),
- Art (drei große Auswahlkarten),
- bei COUNT: Zielanzahl mit −/＋-Stepper,
- Wochentage (Jeden Tag / Mo–Fr / Wochenende / frei),
- Akzentfarbe,
- Live-Vorschau.

Entfernt: Automatisch/Manuell, Quellen (Aktivität/Kategorie/Bildschirmzeit/
Aufgaben), Zeitraum (täglich/wöchentlich/monatlich), Zieltyp (mindestens/
maximal), Einheiten-Dropdown, optionale Ziel-Aktivität, optionale
Zeitgutschrift. Alt-Ziele zeigen einen ehrlichen Hinweis und werden beim
Speichern in die gewählte Habit-Art überführt.

### 3.3 Zeit-Eingabe: drei Drehregler

`HabitTimePickerDialog` (Compose):

- drei schnappende `LazyColumn`-Räder nebeneinander: **Stunden 0–23,
  Minuten 0–59, Sekunden 0–59**,
- mittiges Auswahlband, große Uhrzeit-Vorschau (Monospace),
- Haptik-Tick beim Einrasten,
- Schnellwahl 5/10/15/30 min und 1 h,
- Speichern erst ab > 0; „Zeit entfernen" bei bestehendem Eintrag.

Jede bestätigte Zeit > 0 erfüllt den Tag. Es gibt **keinen** Zielwert für
TIME — das war die ausdrückliche Vorgabe.

### 3.4 Tage-Swipe und Nachträge

- `HorizontalPager` über **30 Tage** (ältester → heute), Start auf heute.
- Tagesleiste mit sieben Tagen; jeder Tag zeigt einen Punkt (frei / teilweise /
  perfekt), Antippen animiert zum Tag.
- „Heute"-Knopf auf vergangenen Tagen.
- Alle Aktionen bekommen ein explizites `LocalDate`: Abhaken, Zählen,
  Zeiteintragen und Rückgängig funktionieren für jeden vergangenen Tag —
  angezeigt wird ein Habit aber erst ab seinem Erstellungsdatum.
- Retro-Check-ins werden mit dem aktuellen Uhrzeitanteil des gewählten Tages
  gespeichert (`dateKey` = gewählter Tag), erscheinen also in der richtigen
  Tagesliste.

### 3.5 Dopamin-UI

- Tages-Hero mit Fortschrittsring („3 von 5 erledigt"), Serien-Flamme und
  „Perfekte Tage in Folge".
- **„Perfekter Tag"**: sind alle fälligen Habits erledigt, erscheint der
  bestehende Konfetti-Overlay mit eigenem Titel und Serien-Badge — genau
  einmal pro Tag (Store-gated).
- Schimmer auf erledigten Karten, animierte Fortschrittsbalken, Zähl-Punkte
  (2–12), Haptik bei jedem Tap, Emoji-Icons, Akzentfarben.
- Detail: Hero, Streaks, 91-Tage-Verlauf, Heatmap, Check-in-Liste.

### 3.6 Dashboard

- DONE/COUNT: Quick-Check hakt direkt ab.
- TIME: Quick-Check öffnet den Drei-Räder-Dialog.
- Das Häkchen erscheint erst bei tatsächlich erreichtem Ziel (nicht schon beim
  ersten von fünf Zähl-Taps).
- Alt-Ziele werden weiterhin mit ihrer alten Auswertung angezeigt.

---

## 4. Datenbank v53

```sql
ALTER TABLE goal ADD COLUMN kind TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE goal ADD COLUMN icon TEXT;

-- Erst zaehlen, dann Zeit, dann abhaken (M35-Zaehlziel mit Zeitgutschrift
-- bleibt COUNT, nicht TIME).
UPDATE goal SET kind='COUNT'
WHERE kind='LEGACY' AND tracking_mode='MANUAL' AND period='DAILY'
  AND (target_unit='COUNT' OR type='COUNT') AND target_value>1;

UPDATE goal SET kind='TIME'
WHERE kind='LEGACY' AND tracking_mode='MANUAL' AND period='DAILY'
  AND type <> 'AT_MOST'
  AND (target_unit IN ('MINUTES','HOURS') OR type='DURATION'
       OR EXISTS (SELECT 1 FROM goal_checkin c
                  WHERE c.goal_id=goal.id AND c.duration_ms>0));

UPDATE goal SET kind='DONE'
WHERE kind='LEGACY' AND tracking_mode='MANUAL' AND period='DAILY';

-- external_id-Duplikate auf die beste Zeile reduzieren, dann:
DROP INDEX IF EXISTS index_activity_session_external_id;
CREATE UNIQUE INDEX index_activity_session_external_id
  ON activity_session (external_id);
```

Wichtig: **Wochen-/Monats-, AUTO- und manuelle AT_MOST-Ziele bleiben
`LEGACY`**. Nur manuelle Tagesziele werden automatisch zu DONE/COUNT/TIME. Das verhindert, dass ein
„Digital unter 2 h" still zu einem manuellen Zeit-Habit wird.

`activity_session` hat jetzt einen UNIQUE-Index auf `external_id`; Sessions
ohne externe Identität (`NULL`) bleiben erlaubt. Die Migration räumt vorher
Bestandsduplikate auf.

---

## 5. Eigene Design-Entscheidungen — hinterfragt

### 5.1 Nur tägliche Habits (kein Wochen-/Monatsrhythmus)

**Naheliegende Alternative:** Perioden-Auswahl wie bisher behalten.
**Verworfen, weil:** Der Nutzer ausdrücklich durch Tage wischt und „Ziel für
den jeweiligen Tag" sagt. Jede zusätzliche Achse macht den Editor wieder zum
Formular, das er gerade loswerden wollte.
**Bewusst gezahlter Preis:** Wochen-/Monats- und AUTO-Ziele leben als
`LEGACY` weiter und müssen im Editor umgewandelt werden. Es gibt keine neue
„3× pro Woche"-Habit-Art.

### 5.2 TIME ohne Zielwert

**Naheliegende Alternative:** „Mindestens 30 Minuten" als Zielwert.
**Verworfen, weil:** Devon sagt: „Sobald man eine Zeit eingetragen hat, hat
man das Ziel für den jeweiligen Tag ja auch offensichtlich erreicht." Ein
Zielwert würde diese Aussage wieder aufheben.
**Bewusst gezahlter Preis:** Ein TIME-Habit kann nicht „zu wenig" sein; die
Dauer ist Information, keine Bedingung.

### 5.3 Drei Räder statt Minuten-Textfeld

**Naheliegende Alternative:** Das M35-Textfeld für Minuten behalten.
**Verworfen, weil:** Ausdrücklich „drei Drehregler nebeneinander für Stunden,
Minuten und Sekunden" gefordert war.
**Bewusst gezahlter Preis:** Mehr UI-Code (eigener Wheel-Picker, Snap,
Haptik) und ein höherer Dialog; dafür keine Tippfehler und Sekundenpräzision.

### 5.4 Legacy-Ziele sichtbar lassen statt still umzudeuten

**Naheliegende Alternative:** Alle Altziele automatisch auf DONE/COUNT/TIME
mappen.
**Verworfen, weil:** Ein automatisches „Digital unter 2 h" → TIME-Habit würde
die Bedeutung des Ziels still verändern; der Nutzer würde das erst merken,
wenn die Zahlen nicht mehr stimmen.
**Bewusst gezahlter Preis:** Zwei Listen (Habits + „Weitere Ziele"), bis der
Nutzer ein Alt-Ziel im Editor umwandelt. Der Editor sagt das ausdrücklich.

### 5.5 PC-Duplikate: Serialisieren + deterministische ID + UNIQUE-Index

**Naheliegende Alternative:** Nur nach dem Import aufräumen.
**Verworfen, weil:** Das Rennen würde weiterhin zwei Zeilen schreiben und
kurzzeitig doppelte Zahlen in der Timeline zeigen; der Aufräumer liefe erst
danach.
**Bewusst gezahlter Preis:** `REPLACE` auf `external_id` kann bei einem echten
Konflikt eine alte Zeile ersetzen. Da `externalId` stabile Identität bedeutet,
ist das gewollt. Zusätzlich bleiben echte Folgesessions unangetastet, weil der
Deduper nur vollständig enthaltene Intervalle löscht.

---

## 6. Nicht Bestandteil dieses Auftrags

- **Keine Erinnerungen/Benachrichtigungen** für Habits (kein Reminder-System,
  keine Notification-Kanäle).
- **Kein Habit-Widget** auf dem Homescreen.
- **Keine sozialen Features** (Teilen, Freunde, Challenges).
- **Keine XP-/Level-/Punktesimulation** — Dopamin kommt über Streaks,
  Perfekte Tage, Konfetti, Schimmer und Haptik, nicht über erfundene Punkte.
- **Keine Wochen-/Monats-Habits** im neuen Editor; Alt-Ziele bleiben lesbar.
- **Keine Aktivitäts-/Kategorie-Verknüpfung** für Zeit-Habits — das war der
  „optionale" Ballast, der ausdrücklich weg sollte.
- **Kein Rückkanal vom Handy zum PC**, der bereits importierte Duplikate auf
  dem PC löscht; der PC heilt seine eigene Datenbank beim nächsten Start.
- **Kein automatisches PC-Update**; die neue Windows-ZIP muss manuell
  installiert werden (kein Update-Kanal in diesem Auftrag).
- **Keine Emulator-/Geräte-Durchklicks** (siehe §8).

---

## 7. Verifikation

### 7.1 Android

- **Kompilierung:** `:app:compileDebugKotlin` erfolgreich.
- **Unit-Tests:** `:app:testDebugUnitTest` — **1514 Tests, 0 Fehler,
  1 skipped** (der bestehende, bewusst übersprungene Test).
- **Finaler Build:** `:app:testDebugUnitTest :app:assembleDebug`,
  **BUILD SUCCESSFUL in 2m 35s**, Exit 0.
- **Signatur:** `apksigner verify` OK; SHA-1
  `9c3055c4810b5cc2e548d8de8357344352fbe0e8` (kanonischer Debug-Key).
- **APK:** `app-debug.apk`, **128.081.230 Bytes (122,1 MiB)**,
  SHA-256 `c01233afd20de8e0021464f88d61ccb19782745d465e00933c23673519dc5755`.
- **versionCode/Name:** 58 / `1.0.57-debug`.

### 7.2 Desktop PC-Tracker

- `dotnet test AevumPcTracker.sln`: **165 Tests, 0 Fehler**.
- `dotnet publish -c Release -r win-x64 --self-contained true`: erfolgreich.
- ZIP: `AevumPcTracker-1.7.2-win-x64.zip`, **57.510.980 Bytes (54,85 MiB)**,
  SHA-256 `4efad93ef8e04623ad6a76dda36a8a8de9f9b1f0640bf1ac65d124afdedf2dc1`.
- Ausgeliefert unter `desktop/dist/` und
  `http://192.168.178.40:8098/AevumPcTracker-1.7.2-win-x64.zip`.

### 7.3 Tests, die die neue Logik beweisen

- `GoalHabitTrackerTest` (9): DONE/COUNT/TIME, Streaks, freie Tage,
  91-Tage-Verlauf, Uhrformat, Fälligkeit erst ab Erstellungsdatum.
- `PcSessionDeduperTest` (6): gleiche externalId, eingebettete Intervalle,
  Folgesessions, gleicher Start/andere Aktivität, Nutzerbearbeitung, offene
  Sessions.
- `PcSessionImporterTest` (8): deterministische ID, Zusammenführen zweier
  paralleler Importe, soft-gelöschte Session bleibt gelöscht.
- `HabitTrackerMigrationTest` (3): Arten-Backfill, Duplikat-Aufräumen,
  UNIQUE-Index blockiert neue Duplikate.
- `SchemaV53Test` (4): Schema 53 = 52 + kind/icon, UNIQUE-Index.
- `GoalArchitectureWiringTest` (6): Editor bietet genau die drei Arten,
  keine optionale Aktivität; Verdrahtung bleibt.

---

## 8. Ehrliche Einschränkung

**Es gab keinen Emulator und kein angeschlossenes Gerät.** Der UI-Fluss
(Drei-Räder-Dialog, Pager-Gesten, Konfetti, Dashboard-Quick-Check) wurde
deshalb **nicht** interaktiv durchgeklickt. Verifiziert sind:

- Kompilierung und Ressourcen (11 Sprachen),
- 1514 JVM-/Robolectric-Unit-Tests inkl. echter Room-Migration auf SQLite,
- Quelltext-Verdrahtung (Wiring-Pins),
- APK-Signatur und Manifest-Werte.

Das ist bewusst kein „auf dem Gerät getestet"-Bericht. Ein visueller Test auf
Devons Handy steht noch aus; besonders prüfenswert sind die Wheel-Gesten und
die Pager-/Tagesleisten-Interaktion.

---

## 9. Von den Tests gefundene Fehler

1. **Fehlende gemeinsame Präsentationsmodelle:** Beim Umbau von
   `GoalsViewModel` verschwanden `GoalWithProgress`/`toGoalWithProgress`, die
   Dashboard, Detail und `GoalCelebrationPolicy` weiterhin brauchten. Der
   Compiler zeigte die komplette Kette; die Modelle wurden am Ende von
   `GoalsViewModel.kt` wieder bereitgestellt.
2. **Alte Wiring-Tests:** `GoalArchitectureWiringTest` prüfte noch
   `goal_presets`, `setTrackingMode` und `undoLastCheckin` — alles bewusst
   entfernt. Die Tests wurden auf die drei Habit-Arten und `fun undo(`/
   `maybeEmitPerfectDay` umgestellt.
3. **Falsche Erwartung im Streak-Test:** Der Test legte Check-ins am Anker-
   Montag und am Montag davor an, bewertete aber den Sonntag davor — der
   zukünftige Montag lag außerhalb des Verlaufs. Die Erwartung war falsch,
   nicht der Code; der Test nutzt jetzt die beiden vergangenen Montage.
4. **SchemaV53-Vergleich:** Der Test verglich zunächst das komplette
   `activity_session`-Entity-JSON und schlug wegen des geänderten Index fehl;
   jetzt werden Felder und Indizes getrennt geprüft.
5. **Veraltete Versions-Asserts:** Acht Migrations-/Schema-Tests erwarteten
   noch DB-Version 52; sie wurden auf 53 gezogen (die historischen Schemas
   51/52 bleiben unverändert).
6. **Apostroph-Escaping:** Die italienischen/französischen Ressourcen hatten
   `'` unescaped (`l'abitudine`); AAPT brach mit „Invalid unicode escape
   sequence" ab. Die M36-Blöcke escapen jetzt `\'`.
7. **Wheel-Picker-Offset:** Die erste Fassung las den mittigen Wert aus
   `firstVisibleItemIndex`. Mit Content-Padding liegt der aber zwei Items vor
   der Mitte — das Rad hätte den falschen Wert geliefert. Jetzt ein
   `VerticalPager` mit `PageSize.Fixed` und Content-Padding; `currentPage`
   ist per Konstruktion die mittige Seite.
8. **Soft-gelöschte PC-Session + UNIQUE-Index:** `getByExternalIdsOnce`
   filterte `deleted_at IS NULL`. Nach dem Löschen einer importierten Session
   hätte der nächste Sync sie nicht gesehen, neu eingefügt und wäre am neuen
   UNIQUE-Index gescheitert. Die Query liest jetzt inklusive gelöschter
   Zeilen; der Importer lässt eine bewusste Löschung bestehen (Test
   `soft geloeschte PC-Session wird nicht wiederbelebt`).
9. **Perfekter Tag vs. Alt-Ziele:** Der Hero zählte Alt-Ziele mit, die
   Perfekt-Tag-Feier aber nicht — dadurch konnte „5 von 5" stehen, ohne dass
   die Feier kam (oder umgekehrt). Der Hero zählt jetzt nur die neuen Habits,
   genau wie die Feier.
10. **Habits vor ihrem Erstellungsdatum:** Der 30-Tage-Pager zeigte ein neu
    angelegtes Habit rückwirkend als „verpasst"/„nicht fällig" und erlaubte
    Check-ins davor. `isHabitScheduledOn`/`isVisibleOn` schneiden jetzt am
    Erstellungsdatum ab.
11. **Migrations-Reihenfolge:** Die erste Fassung machte jedes manuelle
    Tagesziel mit Zeitgutschrift zu TIME — auch ein „5× meditieren"-COUNT-Ziel
    aus M35. Jetzt gilt COUNT vor TIME; AT_MOST bleibt LEGACY, weil das neue
    TIME kein Maximum kennt. Der Migrationstest deckt den Fall ab.

---

## 10. Artefakte & Auslieferung

- **APK:**
  `http://192.168.178.40:8098/Aevum-1.0.57-M36-Debug.apk`
  (128.081.230 Bytes, SHA-256 `c01233af…19dc5755`).
- **PC-Tracker:**
  `http://192.168.178.40:8098/AevumPcTracker-1.7.2-win-x64.zip`
  (57.510.980 Bytes, SHA-256 `4efad93e…dedf2dc1`).
- **Quellcode:** Commit `2166c6b` (gepusht).

---

## 11. Nach dem Build

Der finale Build ist der oben genannte `aevum-m36-final5`-Lauf. Nach diesem
Lauf wurden **nur Dokumentation und Vault** aktualisiert, kein Code und keine
Ressource. Die APK-/ZIP-Größen und Hashes oben stammen aus genau diesem
finalen Lauf; die Artefakte wurden anschließend nach `/srv/aevum-apk/`
kopiert und dort erneut gehasht.

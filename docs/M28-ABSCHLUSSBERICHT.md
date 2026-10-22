# M28 — Fahrt-Vorrang, Berechtigungsstatus, Aufzeichnungs-Aktivität, Timeline-Details

> App **1.0.48** (versionCode 49) · DB **v51** · Stand: 22.10.2026
>
> Auftrag von Devon (sechs Punkte aus dem Praxis-Test):
>
> 1. **Automatische Fahrtaufzeichnung darf keine laufenden Aufzeichnungen
>    unterbrechen.** Sie soll sich merken, dass gerade gefahren wird, und die
>    Aufzeichnung starten, sobald die laufende Aktivität stoppt — aber nicht
>    unendlich lange. Hört die Fahrt vorher auf, darf danach keine Fahrt mehr
>    starten.
> 2. **Berechtigungs-Bug**: Auf „Trigger & Erkennung" steht „Nutzungszugriff"
>    beim ersten Öffnen auf „nicht erlaubt", obwohl er erteilt ist — erst nach
>    Öffnen und Verlassen der System-Settings steht „zugelassen".
> 3. **Bildschirm-Aufzeichnung**: unter dem Schieberegler eine zugehörige
>    Aktivität wählbar machen, damit der Block nicht zwingend „Digital" ist.
> 4. **Status-Details** zeigt denselben falschen Nutzungszugriff-Status.
> 5. **Timeline-Listenansicht**: Elemente sind sehr klein; Antippen soll
>    Details zum Element zeigen.
> 6. **Kalender-Blöcke**: gestrichelt nur, solange der Termin in der Zukunft
>    liegt. Laufende und vergangene Termine sollen gar nicht mehr erscheinen —
>    nur die Aufzeichnungen.
> 7. **Sucht-Detektor**: die Diagramme sollen die aktuellen Daten (Tage,
>    Wochen …) zeigen und bei jedem Öffnen mit dem aktuellen Tag neu laden.

---

## 1. Auto-Fahrt unterbricht keine laufende Aufzeichnung

### 1.1 Root-Cause

`LiveActivityManager.start()` beendet bzw. kürzt eine bestehende Live-Session
(`trimOverlappingForNewSession`), damit nie zwei Blöcke parallel laufen. Der
automatische Fahrt-Start rief das ohne Rücksicht auf eine bereits laufende
Aufzeichnung auf. Weil der Fahrt-Start zusätzlich rückdatiert wird (Cluster-
Anker), wurde der laufende Block nicht nur beendet, sondern **rückwirkend
zerschneiden** — der gemeldete Effekt.

### 1.2 Neue Regel

`PendingDrivePolicy` (rein, ohne Android/Zeitquelle):

- `shouldDefer(liveIsLive, liveActivityTypeId, liveSourceType)` → true, wenn
  irgendeine andere Session live ist (RUNNING oder PAUSED). Eine bereits
  laufende **Fahrt** blockiert nicht — sie ist ein Duplikat und wird vom
  bestehenden Herzschlag-Zweig behandelt.
- `shouldStartAfterBlock(drivingEvidenceNow, lastEvidenceAtMs,
  pendingStartedAtMs, nowMs)` → true nur, wenn ein Vormerk existiert, er
  höchstens 4 h alt ist und es **jetzt** Fahrt-Evidenz gibt: frische
  GPS-Klassifikation ODER eine Bestätigung, die höchstens **6 Minuten** alt
  ist.
- `startAtAfterBlock(blockingEndAtMs, nowMs)` → Start-Anker ist das Ende des
  blockierenden Blocks, nie der ursprüngliche Fahrt-Anker (sonst würde die
  Fahrt wieder rückwärts in den alten Block schneiden).

Der Vormerk liegt in `ActivityRecognitionBridge`
(`markDrivePending` / `refreshDrivePendingEvidence` / `isDrivePending` /
`clearDrivePending`), getrennt von `driveActive` — die Selbstheilung
`healIfOrphaned` darf ihn nicht löschen.

`DriveStartWorker` fragt die Policy an genau der Stelle, an der vorher
`live.start()` stand, und merkt die Fahrt nur vor.

### 1.3 Auflösung des Vormerks

`PendingDriveStarter` (Singleton, in `AevumApplication.onCreate` gestartet)
beobachtet `LiveActivityManager.liveSession`. Endet die blockierende Session,
startet er die Fahrt:

- 2 s Karenz gegen „Stop → Start" im selben Coroutine-Lauf
  (Aktivitätswechsel).
- Ist inzwischen wieder eine Session live, bleibt der Vormerk bestehen und
  wird am Ende DIESER Session erneut geprüft.
- Sonst: Evidenz prüfen, dann `live.start(...)` mit
  `startedAt = blocking.endAt`, `LiveActivityService`, TRACK-Restore,
  Watchdog, `DRIVING_STARTED`-Trigger — derselbe Nachlauf wie ein normaler
  Start.
- Ohne frische Evidenz: `clearDrivePending()` und **kein** Start (der
  User-Fall „Fahrt vorbei, danach erst gestoppt").

Evidenz frisch halten: `DriveProbeWorker` (2-Min-Takt) und
`DriveDetectionService` rufen `refreshDrivePendingEvidence(now)` bei jedem
Fix mit ≥ 2 m/s. Im Stand schweigt der Fix — der Vormerk verfällt nach 6 min.
Das ist nötig, weil der Probe-Puffer nach jeder Bestätigung gedraint wird und
bis zur nächsten vollen Klassifikation mehrere Minuten vergehen können.

---

## 2. Berechtigungsstatus (Nutzungszugriff)

`UsageStatsCollector.hasPermission()` fragte `queryUsageStats` in einem
**1-Sekunden-Fenster** ab. Ohne Usage-Event in genau dieser Sekunde kam eine
leere Liste zurück → „nicht erteilt", obwohl der AppOps-Schalter an ist. Beim
Zurückkehren aus den System-Settings gab es ein frisches Event (die eigene App
war im Vordergrund) → plötzlich „zugelassen". Genau der gemeldete Ablauf.

Fix: `hasPermission()` delegiert an `UsageStatsPermission.isGranted(context)`
(AppOps, `OPSTR_GET_USAGE_STATS`) — der Schalter spiegelt exakt die
System-Einstellung, unabhängig von Events. Gilt für die Trigger-Seite und für
Status-Details (beide nutzen dieselbe Methode).

Zusätzlich startet `TriggerSettingsViewModel` seinen Berechtigungs-Snapshot
jetzt **synchron im Konstruktor** (`readPermissions()`), und der UI-Erststand
kommt aus diesem Snapshot. Vorher war der Startwert „alles false", bis der
Room-Flow den ersten Settings-Stand lieferte — ein kurzes Aufblitzen roter
Zeilen.

---

## 3. Bildschirm-Aufzeichnung: zugehörige Aktivität

- Neue Spalte `automation_settings.screen_recording_activity_type_id`
  (`TEXT NOT NULL DEFAULT 'digital'`), Migration 50 → 51, rein additiv.
- Unter dem Vorlauf-Slider eine Aktivitäts-Auswahl (gleicher Picker wie die
  Anruf-Erkennung, Default „Digital").
- `ScreenRecordingWorker` liest die Auswahl, löst sie über
  `ScreenRecordingEngine.recordingActivityTypeId` auf und startet die Session
  mit Typ, Name, Icon und Farbe dieser Aktivität. Gelöschte Typen fallen auf
  `digital`, dann auf `other` zurück.
- `sourceType` bleibt `SCREEN_AUTO`: Screen-Off-Stop, App-Takeover und die
  Statistik-Policy (M18.138) hängen daran und bleiben unverändert.

---

## 4. Timeline-Listenansicht

- Zeilen vergrößert: Titel 14 → **15 sp**, Detail 11 → **12 sp**, Zeit-Chip
  11 → **12 sp**, Icon 40 → **44 dp**, Akzentbalken 44 → **48 dp**,
  vertikales Padding 10 → **13 dp**. Damit ist jede Zeile ein sauberes
  Touch-Ziel.
- **Trigger-Signale** und **geplante Kalender-Blöcke** öffnen beim Antippen
  einen Detail-Dialog (Titel; Aktivität, Kategorie, Zeitraum, Dauer, Güte,
  Quelle bzw. Uhrzeit, Konfidenz, Quelle/Regel, Zeitraum, Dauer) mit einem
  erklärenden Hinweis, dass ein Signal/eine Prognose keine Aufzeichnung ist.
  Vorher war `onClick = {}` — es passierte nichts.
- Aufzeichnungen öffnen weiterhin die volle Detailseite (unverändert).
- Ein „›" markiert Zeilen, die Details öffnen.

---

## 5. Kalender-Blöcke nur in der Zukunft

`filterPlannedToFuture` / `filterPlannedWeekToFuture` (rein, getestet):
Gestrichelte Blöcke bleiben nur, solange `TagStart + startMinute > now`.
Ab Terminbeginn (laufend oder vergangen) verschwindet der Block; sichtbar
bleiben nur echte Aufzeichnungen. Angewandt auf die Tagesansicht, die
7-Tage-Karte der Wochenansicht und die Listenansicht.

Damit ein Block auch **während** die Timeline offen ist pünktlich verschwindet,
hat der `TimelineViewModel` einen Minuten-Takt, der die Kalender-Auswertung neu
berechnet.

---

## 6. Sucht-Detektor: aktuelle Daten

`AddictionAnalytics.currentWindow` ersetzt die „nur abgeschlossene Perioden"-
Fenster:

- **7 Tage**: heute + 6 Tage davor.
- **4 Wochen**: aktuelle Kalenderwoche (ab Montag) + 3 Wochen davor.
- **6 Monate**: aktueller Monat + 5 Monate davor.
- Vorperiode = dieselbe Anzahl Tage direkt davor; der Druckindex nutzt
  weiterhin die letzten 7 vollen Tage.

Die Detektor-Seite bekommt `isActive` aus dem Pager und refresht bei jedem
sichtbaren Öffnen (und danach im Minutentakt). Die Info-Texte und die
Untertitel („nur abgeschlossene Zeiträume") wurden auf die neue Semantik
korrigiert.

---

## 7. Eigene Design-Entscheidungen — hinterfragt

**Vormerk-Beobachter statt Stop-Hooks.** Naheliegende Alternative: in jedem
Stop-Pfad (Dashboard, Notification, Widget, Geofence, Kalender, Screen-Off,
App-Wechsel) einen Aufruf ergänzen. Verworfen — die Liste ist lang und wächst;
ein vergessener Pfad blockiert den Vormerk dauerhaft (M18.75/M18.76-Lehre).
**Preis:** ein dauerhaft lebender StateFlow-Collector im App-Prozess und ein
Vormerk, der nur im Speicher lebt (Prozess-Tod verwirft ihn; dann erkennt das
System neu). Das ist bewusst: ein persistierter Vormerk über einen Neustart
hinweg wäre riskanter als der Verlust.

**6-Minuten-Evidenz.** Naheliegende Alternative: nur die aktuelle
GPS-Klassifikation. Verworfen, weil der Block Sekunden nach dem letzten Probe
enden kann — die Fahrt verfiele fälschlich. Zweite Alternative: Evidenz gar
nicht prüfen. Verworfen, weil dann genau der User-Fall „Fahrt vorbei, danach
gestoppt" wieder eine Fahrt startet. **Preis:** 6 Minuten Nachlauf; ein sehr
langsames Ausparken nach längerem Halt kann als neue Fahrt erscheinen
(bestehendes Verhalten der Erkennung).

**Statistik bleibt bei Digital-Balance-Zeit.** Ein als „Lesen" zugewiesener
Aufzeichnungsblock erscheint in der Timeline, zählt aber nicht in der
Statistik. Alternative: für nicht-digitale Typen die Aufzeichnung zählen.
Verworfen, weil M18.138 ausdrücklich festhält, dass Kennzahlen nur die echte
Bildschirmzeit nutzen (die Aufzeichnung ist ungenau). **Preis:** Die
Zuweisung wirkt nur auf die Timeline; der Hinweistext in der Einstellungen-
Karte sagt das ausdrücklich.

**Sucht-Detektor inkl. laufendem Tag.** Alternative: weiter nur volle
Perioden. Verworfen (User-Report). **Preis:** Der laufende Tag/die Woche ist
unvollständig; der Prozentvergleich zur Vorperiode fällt morgens niedriger
aus. Bewusst akzeptiert — die Alternative war der gemeldete Fehler.

**Migrationsliste als eine Quelle.** `AppDatabase.ALL_MIGRATIONS` wird von DI
und allen Migrationstests genutzt. Alternative: wie bisher jede Testdatei
ihre Kette selbst pflegen. Verworfen, weil bei jedem Versionssprung vier
Dateien nachgezogen werden mussten und der Fehler („A migration from X to Y
was required") jedes Mal erneut auftrat. **Preis:** Die Tests prüfen nicht
mehr ihre eigene, minimale Kette, sondern immer die Produktionskette — dafür
ist diese garantiert identisch mit dem, was auf dem Gerät läuft.

---

## 8. Nicht Bestandteil (bewusst nicht gebaut)

- **Kein Eingriff in andere Auto-Quellen.** Die Rangfolge „Geofence/Fahrt/
  Sport/manuell/Kalender nie angetastet" bleibt; nur die Fahrt wartet jetzt.
  Wandern/Rad/Anruf unterbrechen weiterhin nach ihren eigenen Regeln.
- **Keine Statistik-Zählung für zugewiesene Aufzeichnungs-Typen** (siehe
  Design-Entscheidung).
- **Kein persistierter Vormerk** über Prozess-Tod/Neustart.
- **Keine Benachrichtigung „Fahrt vorgemerkt"** — der Zustand ist im Log
  sichtbar, aber nicht als Notification aufgedrängt.
- **Keine Änderung an der Permission-Erklärung** (Play-Auflage M18.139/141
  bleibt unverändert).
- **Kein neuer Detail-Screen** für Trigger/Termine — nur ein Dialog.
- **Kein Geräte-/Emulator-Durchklick** in dieser Umgebung (siehe §10).

---

## 9. Gefundene Fehler während der Arbeit

- **Migrationstests brachen durch den Versionssprung.** Vier Tests öffneten
  eine alte DB mit einer eigenen Migrationsliste bis v50; Room erwartet v51 →
  „A migration from X to Y was required". Ursache war nicht der Code, sondern
  die Erwartung/Registrierung in den Tests. Behoben durch
  `ALL_MIGRATIONS` (eine Quelle) und Anpassung der Identitäts-Hash-Prüfungen
  auf `51.json`.
- **Evidenz-Fenster wäre in der Praxis zu knapp gewesen.** Der erste Entwurf
  verließ sich auf `markDrivePending` aus dem `DriveStartWorker`; da der
  Probe-Puffer nach jeder Bestätigung gedraint wird, hätten zwischen zwei
  vollen Klassifikationen >6 Minuten liegen können. Behoben durch
  `refreshDrivePendingEvidence` bei jedem Fahrzeug-Fix in
  `DriveProbeWorker` und `DriveDetectionService`.
- **Veraltete UI-Texte im Sucht-Detektor**: Info-Dialog und Untertitel
  behaupteten weiter „nur abgeschlossene Zeiträume". Korrigiert (DE/EN).

---

## 10. Ehrliche Einschränkung — was NICHT verifiziert werden konnte

- **Kein Lauf auf einem echten Gerät/Emulator.** Die Fahrt-Vormerkung ist
  über reine Logik-Tests und Verdrahtungs-Guards abgesichert, aber das
  Zusammenspiel mit Google Activity Recognition, Doze und WorkManager-Latenz
  wurde nicht auf Hardware beobachtet. Insbesondere gilt: endet die
  blockierende Session, während der Prozess tot ist, geht der Vormerk
  verloren.
- **Nutzungszugriff**: Der AppOps-Check ist der dokumentierte, zuverlässige
  Weg; auf OEM-ROMs mit abweichendem AppOps-Verhalten (vereinzelt Xiaomi/
  Huawei) wurde er nicht gegengeprüft.
- **Timeline-Details**: Die Darstellung wurde nicht visuell auf einem Gerät
  geprüft (nur Compose-Compile + Logik). Ein Render-Test existiert für den
  Sucht-Detektor, nicht für den neuen Dialog.
- **Kalender-Prognose**: Der Minuten-Takt ist implementiert, aber das
  Verschwinden eines Blocks exakt zur Terminminute wurde nicht auf Hardware
  gestoppt.

---

## 11. Build und Artefakte (gemessen)

- **Tests:** `:app:testDebugUnitTest` — **1399 Tests, 0 Fehler, 0 Errors,
  1 skipped** (143 Suites). Neu in M28: **40 Tests** in sechs Dateien
  (`PendingDrivePolicyTest` 14, `PlannedSessionsFutureFilterTest` 7,
  `SchemaV51Test` 3, `ScreenRecordingActivityMigrationTest` 4,
  `M28WiringTest` 10, `ScreenRecordingEngineTest` +2); `AddictionAnalyticsTest`
  wurde auf die neue Zeitraum-Semantik umgestellt (8 Tests, unverändert in
  der Anzahl).
- **Voll-Build:** `:app:testDebugUnitTest :app:assembleDebug` —
  **BUILD SUCCESSFUL**, Exit **0**, Dauer **115 s** (inkrementell; der
  vorherige Voll-Lauf der Suite dauerte 1 m 55 s). Log:
  `/root/builds/aevum-m28.log`.
- **Debug-APK:** `app/build/outputs/apk/debug/app-debug.apk`,
  **125.153.256 Bytes** (119,4 MiB), versionCode **49**, versionName
  **1.0.48-debug** (`aapt dump badging`).
- **SHA-256:** `c274625359521c99c19f64fc45570876cbe8adfaa53890bbb913da2ea914f79c`
- **Signatur:** kanonischer Debug-Key, SHA-1
  `9C:30:55:C4:81:0B:5C:C2:E5:48:D8:DE:83:57:34:43:52:FB:E0:E8`
  (`signing-verify OK`, `apksigner verify --print-certs`).
- **DB-Version:** 51 (`SchemaV51Test` + echte SQLite-Migration 50→51).
- **Commit:** `7308bf9` — „M28: Fahrt unterbricht keine Aufzeichnung,
  AppOps-Status, Aufzeichnungs-Aktivitaet, Timeline-Details - Aevum 1.0.48".

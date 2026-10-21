# Architecture

Projekt: **Aevum** (`com.d_drostes_apps.aevum`)

Aevum ist ein offline-first Life-Management- und Life-Analytics-Assistent. Keine Cloud, kein Login, kein Backend.

M7 ist abgeschlossen: Automation Experience v1 mit erweiterter Trigger-Pair-Engine, Candidate-Merge-Engine, Candidate-Timeline-UX, Multi-Select-Review-Workflow und Dashboard-Automatisierungskarte.

Wichtiges Architekturprinzip: Android API Signale werden als `RawDetectionEvent` gespeichert und erst durch eine Classification Pipeline zu bearbeitbaren `ActivitySession` Kandidaten. Manuell bearbeitete Sessions haben höchste Priorität.

Siehe `docs/MASTERPLAN.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE.md`, `docs/AUTOMATION_SYSTEM.md`, `docs/PROJECT_STATE.md`.

PC-Tracker (Windows-Begleiter): Der Ordner `desktop/` enthält eine eigenständige Windows-App (Avalonia, .NET 8), die die Fensteraktivität des PCs nach Regeln aufzeichnet (Aufnahmefenster + Pause-Erkennung) und fertige Sessions im Heimnetz bereitstellt. Die Aevum-App koppelt sich per Kurzcode (UDP-Discovery + HTTP-API mit Token, Port 47825) und zieht per Pull-Sync. Idempotenter Import über `external_id` (`pc_<uuid>`), Auto-Mapping auf ActivityTypes über den Namen, DataSource `type=pc_tracker`. Vertrag: `docs/PC-TRACKER-PROTOCOL.md`.

---

## M27 — Sucht-Detektor, Backup-Fix, verzögerte Ziel-Wertung (21.10.2026)

App **1.0.47** (versionCode 48), DB **v50**.

**Sucht-Detektor im Balance-Tab:** dritte Pager-Seite „Detektor" + Einstiegskarte
auf der Balance-Seite. Ausgewählte Apps (`addiction_watch_app`) und
Vordergrund-Sitzungen (`addiction_session`, ID = `paket@start`) in DB v50.
Erhebung über `UsageStatsManager.queryEvents` + bestehende
`UsageEventFolding` (Activity-Ebene, Paket-Vereinigung), **Screen-Off schließt
offene Sitzungen** (`SCREEN_NON_INTERACTIVE` / `DEVICE_SHUTDOWN`), 3-s-Filter,
45-Tage-Backfill beim Hinzufügen, 400-Tage-Retention, 30-min-Worker
(`AddictionSyncWorker`, KEEP) + 60-s-Sync nur solange die Detektor-Seite
sichtbar ist. Auswertung (`AddictionAnalytics`, rein/JVM): „Heute" live,
alle Vergleiche nur **abgeschlossene** 7 Tage / 4 Kalenderwochen / 6
Kalendermonate; Öffnung zählt am Starttag, Dauer wird an Mitternacht
geteilt; Nutzungsdruck 0–100 aus Zeit (40 %), Öffnungen (25 %), längster
Sitzung (20 %), Nachtanteil 22–6 (15 %) der letzten 7 vollen Tage. UI:
Heute-Karte mit Ring, Verlauf (Zeit/Öffnungen), Stundenbalken, App-Karten
mit aufklappbaren heutigen Sitzungen, App-Auswahl-Sheet, Info-Dialog.

**Backup-Audit:** Backup ist dateibasiert (DB + alle SharedPreferences/
DataStore) und erfasst neue Tabellen/Preference-Dateien automatisch. Der
Versionscheck war seit v41 kaputt: `DataManager` hatte eine eingefrorene
`CURRENT_SCHEMA_VERSION = 40` und verlangte exakte Gleichheit. Jetzt EINE
Quelle (`APP_DATABASE_VERSION` top-level, `AppDatabase.SCHEMA_VERSION`),
`BackupVersionPolicy` erlaubt ältere Backups (Room migriert beim Start),
lehnt neuere ab, schließt das DB-Handle und ignoriert ZIP-Pfad-Ausbrüche.
Tests: `BackupVersionPolicyTest`, `BackupCoverageWiringTest`.

**Ziel-Wertung:** Automatische **AT_MOST-Tagesziele** („unter 3 h bleiben")
sind erst nach Tagesende final. `isMet` bleibt live; neu
`isPendingFinal`, `isCurrentAchieved`, `lastCompleted*`,
`celebrationPeriodKey`/`-Label`/`-Streak`. Serie zählt nur volle Tage;
Karte zeigt „Wertung folgt" + „Zuletzt geschafft"; Popup nutzt die letzte
abgeschlossene Periode und erscheint erst ab **2 Tagen in Folge**
(`MIN_DELAYED_CELEBRATION_STREAK`). Perioden vor `goal.startAt` werden aus
Serie/Feier gefiltert (verhindert 91-Tage-Serie neuer AT_MOST-Ziele).
AT_LEAST, manuelle Ziele und Wochen-/Monatsziele bleiben sofort final.
Gemeinsame Regel: `ui/screens/goals/GoalCelebrationPolicy.kt`.

---

## M26 — Anruf-Erkennung (20.10.2026)

App **1.0.46** (versionCode 47), DB **v49**.

**Neu:** Während ein Gespräch läuft, zeichnet Aevum die zugeordnete Aktivität
automatisch auf (Vorgabe `social` = „Soziales"). Unterbrochen wird dabei
ausschließlich Digital-Nutzung; manuell Gestartetes, Geofences, Kalender,
Fahrten, Sport und alles Unbekannte bleiben unberührt.

- **Domain (rein, JVM-getestet):** `domain/call/CallDetectionPolicy.kt` —
  `onCallActive(enabled, liveSourceType, liveIsLive)` →
  `START_CALL | TAKE_OVER_DIGITAL | ALREADY_TRACKING | KEEP_FOREIGN | DISABLED`,
  `onCallEnded(...)` → `STOP_OWN | NOTHING`, `DIGITAL_SOURCES` =
  {SCREEN_AUTO, APP_TRACKING, PC_TRACKER, PC_REMOTE} als **Allowlist**
  (künftige Quellen sind automatisch tabu — M18.144/M18.151-Lehre).
  `mayRecord(action)` ist das Gate; `start()` läuft NUR danach, weil
  `LiveActivityManager.start()` sonst JEDE Live-Session abschneiden würde.
- **Android:** `automation/call/CallDetectionService.kt` — FGS `specialUse`,
  Subtyp `call_state_detection`; `registerTelephonyCallback` (API 31+) /
  `PhoneStateListener` (29/30); `OFFHOOK` startet, `IDLE` beendet (nur eigene
  Session via `stopIfLive`), `RINGING` startet bewusst nicht. Startet
  zusätzlich `LiveActivityService` (Banner/Notification). Kein
  PHONE_STATE-Broadcast-Receiver (Implicit-Broadcast-Verbot ab Android 8).
  `automation/call/CallDetectionWatchdogWorker.kt` (15 min, KEEP) + Starts aus
  `AevumApplication` und `BootReceiver` + `START_STICKY`.
- **Berechtigung:** `READ_PHONE_STATE` (dangerous). `PermissionDisclosureKind.CALLS`
  + Texte DE/EN; Zeile „Anruf-Erkennung (Telefonstatus)" in der
  Berechtigungs-Karte der Trigger-Einstellungen; Grant schaltet den Trigger
  scharf (`pendingTrigger == "call_detection"`). Keine READ_CALL_LOG /
  READ_PHONE_NUMBERS / CALL_PHONE / ANSWER_PHONE_CALLS.
- **Daten:** `automation_settings` + `call_detection_enabled` (Default 0) +
  `call_activity_type_id` (Default `'social'`); `MIGRATION_48_49` rein additiv.
  UI: Karte „Anruf" (Schalter + Aktivitäten-Picker + Hinweistext).
- **Timeline:** `CALL_AUTO` in `AUTO_SOURCES`, Detail-Label „📞 Anruf (Auto)".
- **Abgrenzung:** keine VoIP-Erkennung (nur klassische Telefonie), keine
  Rufnummern-/Kontaktauswertung, keine eigene Diagnose-Seite.
- **Offen für Play:** Der neue `specialUse`-Subtyp muss in der Play Console
  im Formular für Vordergrunddienste ergänzt werden (Formular, kein Code).

---

## M19.3 — Projektmanagement zurückgenommen, Aufzeichnung zuverlässig (17.10.2026)

**Projektmanagement ist KOMPLETT entfernt** (Nutzerentscheidung: „Ich nehme
es zurück. Ich will das ganze PM Management doch nicht. Also alles davon."):
Dashboard-Knopf + schwebender Nachfolger, Settings-Eintrag, die drei Routen,
alle Screens/ViewModels/Domäne/Repository/DAO/Entity, alle PM-Komponenten und
Strings — die folgenden M19.1/M19.2-Abschnitte sind deshalb HISTORIE, keine
Beschreibung des aktuellen Stands. Datenbank: **v46**, `MIGRATION_45_46` =
`DROP TABLE pm_node` (Kette 43→44→45→46 bleibt für Bestandsgeräte intakt);
Schema-Guard `SchemaV46Test` (46 = 45 minus pm_node, Element für Element),
Daten-Beweis `PmRemovalMigrationTest` (echte SQLite aus 45.json → Room).

**App-Aufzeichnung (Digital-Übergabe) zuverlässig:** `POLL_INTERVAL_MS`
15 s → **2 s** (Ziel ≤5 s nach App-Öffnen, Worst Case 2 s; Takt läuft nur bei
Screen AN und mindestens einer getrackten App). Dazu zwei neue reine Regeln
in `AppTrackingTakeoverPolicy`: `onRepeatedForeground` (auch bei UNVERÄNDERTER
Vordergrund-App nachprüfen — später angelaufenes „Digital" wird übernommen)
und `onStopRace` (einmaliges Nachfassen, wenn die eigene Session gerade in
der Stopp-Phase steckt). Gepinnt in `AppTrackingDigitalWiringTest`
(Test 11 TEMPO, Test 12 NACHPFASSEN) + `AppTrackingTakeoverPolicyTest`.

**Theme:** Feuerrot nachgeschärft — dunkel `#FF3B2E` (war `#F87171`), hell
`#D32614` (war `#DC2626`), Container/onContainer angepasst; `DesignTokens[1]`
= `#FF3B2E`; Pins in `AevumThemeTest`. Der Schimmer (`shimmering`, respektiert
„Animationen aus") sitzt jetzt auf dem Dashboard-Plus-Knopf.

---

## M19.1 — Projektmanagement (17.10.2026) — HISTORIE, in M19.3 wieder ENTFERNT

Neues Teilsystem neben der Aktivitäts-Erfassung: **Projektmanagement**.

- **Tabelle:** `pm_node` (DB v44) — EIN selbstreferenzieller Baum
  (Adjacency-List) mit `type` ∈ {PROJECT, CATEGORY, TASK, TODO}, `parent_id`
  (FK auf sich selbst, ON DELETE CASCADE), `scheduled_date`, `done_at`,
  `due_date`, `color`, `sort_order`. Migration 43→44 rein additiv.
- **Domain (rein, ohne Android):** `PmTree` (Index, Pfad, Teilbaum,
  Fortschritt, Umzugsregeln, zyklensicher), `PmToday` (Tagesplan, Rollover,
  Glas-Kennzahl, 7-Tage-Historie, Planungsgruppen), `PmCelebrationGate`,
  `PmColors`, `PmSeed` (Beispielstruktur mit festen IDs).
- **Daten:** `PmRepository`/`PmRepositoryImpl` (Mutex je Baum-Operation,
  Massenzuweisung, Umzugsvalidierung, idempotenter Beispiel-Import).
- **UI:** `ui/screens/projects/` (Wurzel-Sheet mit Drag-to-close, Tages-Tab mit
  Glas + Karussell + Backlog, Baum-Tab, Detailseite, Editor mit
  Sammel-Eingabe, Tag-planen-Sheet) und `ui/components/` (GemGlass,
  FireworksOverlay, PmCheckbox/SparkBurst, PlayfulProgressBar, PmEntryButton).
- **Einstieg:** Dashboard-Knopf oben rechts (`PmEntryButton` +
  `PmEntryViewModel`, eigener ViewModel, um das DashboardViewModel nicht
  anzufassen), zusätzlich in den Einstellungen.
- **Feier-Gate:** `PmCelebrationStore` (SharedPreferences) hält Tag +
  gefeierten Stand — das Feuerwerk erscheint nur beim Übergang auf „voll".
- **Abgrenzung:** Das bestehende Todos-Modul (Serien, Streaks,
  Dauer-Auto-Check) bleibt unverändert; PM-Schritte sind einmalig.

---

## M19.2 — Eisblau/Feuerrot, PM-Umbau auf zwei Knotenarten, Digital-Balance-Revival (17.10.2026)

App 1.0.32 (versionCode 33), DB **v45**.

- **Theme:** Dark- und Light-Schema vollständig auf Eisblau→Feuerrot
  umgestellt (`ui/theme/Theme.kt`, Token-Werte fest, kein „dynamic color");
  Hauptknöpfe mit wanderndem Schimmer (`ui/components/Shimmer.kt`,
  `Modifier.shimmering`; Intensität ~0.22, respektiert System-Animationen).
  `PmPalette`-Fallback = Eisblau `#38BDF8`, `DesignTokens` [0]=Eis, [1]=Feuer.
- **PM-Umbau:** Nur noch `PROJECT`/`TASK` in `pm_node`. Regeln unverändert im
  Repository (Mutex, Massenzuweisung, `reorderTasks` mit exakt-Liste-Prüfung);
  Migration 44→45 als reine Daten-Migration. UI: Heute-Tab = nur heutige
  Aufgaben in Projekt-Gruppen (offene vor erledigten, dann `sort_order`),
  Drag & Drop (`PmDragDrop.kt`, `PmReorder` reine Mathematik, Controller mit
  Auto-Scroll), Inline-Eingabezeile statt Sammel-Eingabe, Löschen je Zeile,
  Glas als schmale Spalte (`GemGlass` mit `maxGems`/`maxColumns`).
  Vorlage = vier Lebensbereiche (Haushalt & Alltag, Arbeit & Ausbildung,
  Gesundheit, Finanzen), Lokalisierung über `PmSeedTitles` (EN in `values/`,
  DE in `values-de/`).
- **Digital Balance:** Revival-Kette komplett — `BootReceiver` +
  `MY_PACKAGE_REPLACED` → `AppTrackingWatchdogWorker.schedule` (15-min
  Periodik, KEEP, gated auf aktive getrackte Apps), Screen-On-Nudge im
  `ScreenEventReceiver`, `AppTrackingService.isAlive()`/`lastPollAtMs` als
  `@Volatile`-Status, `AevumApplication` plant Watchdog beim Prozessstart.
  `assignActivity`-Persistenz beim Zuweisen korrigiert.
- **Tests:** neue Suiten `PmReorderTest`, `PmMigrationDataTest` (echte
  SQLite-Migration aus 44.json), `PmShimmerTest`, `AevumThemeTest`;
  erweitert: `PmTreeTest`, `PmTodayPlanTest`, `PmSeedTest`, `PmColorsTest`,
  `PmRepositoryRoomTest`, `PmProjectsViewModelTest`,
  `AppTrackingDigitalWiringTest` (16 Tests).
- **Abgrenzung:** Alt-Modul „Todos" (Serien/Streaks) unberührt; Digital-
  Balance-Kern (`AppTrackingTakeoverPolicy`, `ForegroundTracker`,
  `ScreenRecordingWorker`-Logik) unangetastet.
- **PC-Tracker 1.4.0 / App 1.0.34 — Live-Kanal:** Desktop
  `PhoneLiveRegistry` (TTL stale 15 s/gone 45 s, Befehl bis Bestätigung,
  Resume-Split) + `POST /api/live` im `ApiServer`; App-Relay im
  `LiveActivityService` (7 s/15 s, ohne Session kein HTTP; Stop =
  `performStopFromOutside` inkl. driveActive-Reset). Dashboard-Karte
  „LIVE VON DEINEM HANDY" über der PC-Karte; `s.plive_*` DE/EN.
- **App-Updater (seit 1.0.35 wieder ENTFERNT):** Der In-App-Updater der
  App 1.0.34 (Karte, Checker/Installer, FileProvider) wurde auf Nutzerwunsch
  komplett zurückgebaut — Update-Knopf gibt es NUR im PC-Tracker, die
  Handy-App aktualisiert sich über den Play Store. Sync-Gate:
  `MIN_GAP_MS` 2 min, `FRESH_SYNC_MS` 60 s, Sofort-Sync in `MainActivity`
  (In-Process) — WorkManager nur Zusatz.
- **PC-Tracker-Update-Pfad (ab 1.4.1, hart):** `App.ShutdownForUpdate()`
  (AllowClose=true → `ShutdownHost()` → `Environment.Exit(0)`);
  `UpdateInstaller.BuildSwapScriptLines()` wartet per tasklist-Poll auf die
  Starter-PID, `taskkill /F`-Fallback nach ~45 s, dann `robocopy /MIR`
  (ersetzt alte Versionen vollständig), erst dann Start. Nutzerdaten liegen
  immer in `%APPDATA%\AevumPcTracker`.
- **M23 (App 1.0.43):** Fahrt-Startanker in `DriveDetectionEngine.toVehicleCluster`
  (Fahrzeug-Tempo >=3,0 m/s, zwei Probes/90 s); App-Tracking-`KEEP_FOREIGN`
  merkt sich die App nicht mehr; DB v48 `activity_session.source_app_package`
  (AppTrackingService → LiveActivityManager → Timeline-Detail mit
  PackageManager-Icon). Neues `widget/`-Paket: `AevumWidgetProvider`
  (Broadcast-Stopp), `AevumWidgetUpdater` (RemoteViews/Timer/Maskottchen),
  `WidgetStartActivity` (Duolingo-Picker), `WidgetMascotRenderer` (Canvas).
  Update-Takt aus `LiveActivityService` (1 s/10 s); Layout `aevum_widget.xml`,
  Metadaten `aevum_widget_info.xml` (2×1).
- **M24 (App 1.0.44):** `AppUsageAggregator.detailForDay(date)` (gemeinsame
  `detailForRange`-Basis) + `DigitalBalanceViewModel` wählt bei gewähltem Tag
  diesen Pfad; `DigitalBalanceUiState.totalMs` ist der angezeigte Tageswert.
  Orts-Timeline: `VisitDetailSheet` (intern) + `ModalBottomSheet`,
  `onCreateGeofence(lat,lon,name)` → `geofenceCreateRoute(...)` →
  `geofence/new?lat&lon&name`; `GeofenceEditorViewModel` liest die Args und
  `geofencePrefillFromArgs` validiert (inkl. 0/0-Ablehnung).
- **M25 (App 1.0.45):** `PlaceTimelineEngine.geofenceForPoint` matcht
  unbenannte Unknown-Places (Haversine <= radiusMeters, nicht gelöschte
  Geofences, nächster Mittelpunkt) und emittiert sie mit Geofence-Name,
  Icon, Farbe und `geofenceId`; sonst `UNNAMED_PLACE`. Kein DB-Backfill.

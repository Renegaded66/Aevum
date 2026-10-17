# Architecture

Projekt: **Aevum** (`com.d_drostes_apps.aevum`)

Aevum ist ein offline-first Life-Management- und Life-Analytics-Assistent. Keine Cloud, kein Login, kein Backend.

M7 ist abgeschlossen: Automation Experience v1 mit erweiterter Trigger-Pair-Engine, Candidate-Merge-Engine, Candidate-Timeline-UX, Multi-Select-Review-Workflow und Dashboard-Automatisierungskarte.

Wichtiges Architekturprinzip: Android API Signale werden als `RawDetectionEvent` gespeichert und erst durch eine Classification Pipeline zu bearbeitbaren `ActivitySession` Kandidaten. Manuell bearbeitete Sessions haben höchste Priorität.

Siehe `docs/MASTERPLAN.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE.md`, `docs/AUTOMATION_SYSTEM.md`, `docs/PROJECT_STATE.md`.

PC-Tracker (Windows-Begleiter): Der Ordner `desktop/` enthält eine eigenständige Windows-App (Avalonia, .NET 8), die die Fensteraktivität des PCs nach Regeln aufzeichnet (Aufnahmefenster + Pause-Erkennung) und fertige Sessions im Heimnetz bereitstellt. Die Aevum-App koppelt sich per Kurzcode (UDP-Discovery + HTTP-API mit Token, Port 47825) und zieht per Pull-Sync. Idempotenter Import über `external_id` (`pc_<uuid>`), Auto-Mapping auf ActivityTypes über den Namen, DataSource `type=pc_tracker`. Vertrag: `docs/PC-TRACKER-PROTOCOL.md`.

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

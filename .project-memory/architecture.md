# Architecture

Projekt: **Aevum** (`com.d_drostes_apps.aevum`)

Aevum ist ein offline-first Life-Management- und Life-Analytics-Assistent. Keine Cloud, kein Login, kein Backend.

M7 ist abgeschlossen: Automation Experience v1 mit erweiterter Trigger-Pair-Engine, Candidate-Merge-Engine, Candidate-Timeline-UX, Multi-Select-Review-Workflow und Dashboard-Automatisierungskarte.

Wichtiges Architekturprinzip: Android API Signale werden als `RawDetectionEvent` gespeichert und erst durch eine Classification Pipeline zu bearbeitbaren `ActivitySession` Kandidaten. Manuell bearbeitete Sessions haben höchste Priorität.

Siehe `docs/MASTERPLAN.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE.md`, `docs/AUTOMATION_SYSTEM.md`, `docs/PROJECT_STATE.md`.

PC-Tracker (Windows-Begleiter): Der Ordner `desktop/` enthält eine eigenständige Windows-App (Avalonia, .NET 8), die die Fensteraktivität des PCs nach Regeln aufzeichnet (Aufnahmefenster + Pause-Erkennung) und fertige Sessions im Heimnetz bereitstellt. Die Aevum-App koppelt sich per Kurzcode (UDP-Discovery + HTTP-API mit Token, Port 47825) und zieht per Pull-Sync. Idempotenter Import über `external_id` (`pc_<uuid>`), Auto-Mapping auf ActivityTypes über den Namen, DataSource `type=pc_tracker`. Vertrag: `docs/PC-TRACKER-PROTOCOL.md`.

---

## M19.1 — Projektmanagement (17.10.2026)

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

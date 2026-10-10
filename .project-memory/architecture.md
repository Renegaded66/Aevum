# Architecture

Projekt: **Aevum** (`com.d_drostes_apps.aevum`)

Aevum ist ein offline-first Life-Management- und Life-Analytics-Assistent. Keine Cloud, kein Login, kein Backend.

M7 ist abgeschlossen: Automation Experience v1 mit erweiterter Trigger-Pair-Engine, Candidate-Merge-Engine, Candidate-Timeline-UX, Multi-Select-Review-Workflow und Dashboard-Automatisierungskarte.

Wichtiges Architekturprinzip: Android API Signale werden als `RawDetectionEvent` gespeichert und erst durch eine Classification Pipeline zu bearbeitbaren `ActivitySession` Kandidaten. Manuell bearbeitete Sessions haben höchste Priorität.

Siehe `docs/MASTERPLAN.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE.md`, `docs/AUTOMATION_SYSTEM.md`, `docs/PROJECT_STATE.md`.

PC-Tracker (Windows-Begleiter): Der Ordner `desktop/` enthält eine eigenständige Windows-App (Avalonia, .NET 8), die die Fensteraktivität des PCs nach Regeln aufzeichnet (Aufnahmefenster + Pause-Erkennung) und fertige Sessions im Heimnetz bereitstellt. Die Aevum-App koppelt sich per Kurzcode (UDP-Discovery + HTTP-API mit Token, Port 47825) und zieht per Pull-Sync. Idempotenter Import über `external_id` (`pc_<uuid>`), Auto-Mapping auf ActivityTypes über den Namen, DataSource `type=pc_tracker`. Vertrag: `docs/PC-TRACKER-PROTOCOL.md`.

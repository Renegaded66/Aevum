# KNOWN_ISSUES — Aevum

## Aktuelle bekannte Punkte

| ID | Thema | Schwere | Status | Lösung |
|---|---|---|---|---|
| KI-001 | Android-Projekt noch nicht erstellt | Hoch | Offen | M2 durchführen |
| KI-002 | Permissions können Nutzer abschrecken | Hoch | Offen | Permission Education und optionale Aktivierung |
| KI-003 | Automatische Erkennung kann falsch liegen | Hoch | Offen | Raw Events, Confidence, Review/Edit UI |
| KI-004 | Background Location ist sensibel | Hoch | Offen | Nur nach klarem Nutzen; Geofences optional |
| KI-005 | UsageStatsManager braucht Sonderzugriff | Mittel | Offen | Expliziter Settings-Flow, App funktioniert auch ohne |
| KI-006 | Schlafdaten sind ohne Health Connect ggf. unvollständig | Mittel | Offen | Health Connect primär, manuelle Sessions fallback |
| KI-007 | Komplexe Visualisierungen können Performance kosten | Mittel | Offen | Canvas optimieren, Aggregationen vorberechnen |
| KI-008 | Lokale Daten sind sensibel | Hoch | Offen | Kein Netzwerk, Backup bewusst steuern, Export später verschlüsseln |
| KI-009 | Kandidaten und bestätigte Sessions dürfen fachlich nicht vermischt werden | Hoch | In M4 adressieren | Separate `activity_candidate`, `activity_session` und `session_evidence` Tabellen |
| KI-010 | Mehrjährige Statistiken können ohne Aggregat-Caches langsam werden | Mittel | In M4/M8 adressieren | `activity_aggregate_day` und ableitbare `stat_cache` Tabellen |
| KI-011 | Spätere Quellen wie Wear OS/Kalender dürfen kein Schema-Refactoring erzwingen | Hoch | In M4 adressieren | `data_source`, `raw_source_event`, `detection_event` Pipeline |
| KI-012 | Nutzerkorrekturen könnten ursprüngliche Auto-Vorschläge überschreiben | Hoch | In M4 adressieren | `activity_session_change`, `source_candidate_id`, `created_by`, `updated_by`, optional `supersedes_session_id` |

## Technische Risiken

### Sensor-Konflikte

Geofence, Activity Recognition, Schlaf und Usage Stats können überlappen. Lösung: Prioritäts-/Konfliktregeln und manuelle Bestätigung.

### Akkuverbrauch

Zu häufige Standort-/Activity-Abfragen vermeiden. Lösung: Transition APIs, Geofencing, WorkManager statt dauerhafter Services.

### Datenschutz

Lebensdaten sind hochsensibel. Lösung: offline-only, minimale Permissions, transparente Erklärung, optional `allowBackup=false`.

### Dashboard-Komplexität

Zu viele Karten können überfordern. Lösung: klare Informationshierarchie und konfigurierbares Dashboard.

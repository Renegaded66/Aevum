# AUTOMATION_SYSTEM — Aevum

## Ziel

Aevum soll Lebenszeit möglichst automatisch erfassen, ohne Nutzerkontrolle zu verlieren.

## Architektur

```text
SignalSource
  -> RawDetectionEvent
  -> Classification Pipeline
  -> ActivitySession Candidate
  -> Review/Edit/Confirm
  -> Dashboard/Goals/Habits
```

## Signalquellen

| Quelle | Erkennt | Zuverlässigkeit | Bemerkung |
|---|---|---:|---|
| Geofencing | Ortbasierte Aktivitäten | Hoch bei festen Orten | Arbeit/Gym ideal |
| Activity Recognition | Bewegung/Autofahren | Mittel-Hoch | Transition API bevorzugt |
| Health Connect | Schlaf | Hoch wenn Daten vorhanden | Primärquelle für Schlaf |
| UsageStats | Smartphone-Nutzung | Hoch für App-Nutzung | Sonderberechtigung |
| Manuell | alles | Hoch | Nutzerwahrheit |

## Classification Pipeline

1. Raw Event speichern
2. Quelle normalisieren
3. Regeln anwenden
4. Confidence berechnen
5. Kandidaten erstellen/aktualisieren
6. Konflikte markieren
7. Reconciliation Worker korrigiert Tagesende/fehlende Exits

## Confidence Modell

| Confidence | Bedeutung | UX |
|---:|---|---|
| 0.9–1.0 | sehr wahrscheinlich | kann automatisch bestätigt werden, wenn Nutzer es erlaubt |
| 0.6–0.89 | wahrscheinlich | als Vorschlag anzeigen |
| 0.3–0.59 | unsicher | nur in Review/Inbox |
| <0.3 | ignorieren oder debug-only |

## Konfliktregeln

- Manuell bearbeitete Sessions gewinnen immer.
- Schlaf blockiert normale Aktivitäten, außer Nutzer korrigiert.
- Smartphone-Nutzung ist Overlay, nicht automatisch Hauptaktivität.
- Autofahren kann Arbeitsweg/Reise sein; Kategorie zunächst „Autofahren“.
- Geofence Enter ohne Exit wird durch Reconciliation begrenzt.

## WorkManager Jobs

- `DailyReconciliationWorker`
- `GoalEvaluationWorker`
- `HabitEvaluationWorker`
- `HealthConnectImportWorker`
- `UsageStatsImportWorker`

## MVP-Automatisierung

M6 startet mit Geofencing + Activity Recognition. Health Connect und UsageStats folgen in M7.

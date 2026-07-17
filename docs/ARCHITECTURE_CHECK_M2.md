# ARCHITECTURE CHECK M2 — Aevum

> Stand: 2026-07-17  
> Zweck: letzter Daten-/Architekturcheck vor M2 Android-Projektgrundlage.

## Ergebnis

Die geplante Architektur unterstützt die langfristigen Ziele grundsätzlich. Vor M2 wurden jedoch folgende Verbesserungen festgelegt:

1. **Explizite Performance-Indizes** für jahrelange Zeitreihendaten.
2. **Aggregationsstrategie** für Dashboard/Jahresanalysen, damit komplexe Visualisierungen nicht dauerhaft Rohdaten scannen.
3. **Strikte Trennung** zwischen Rohdaten, erkannten Kandidaten, bestätigten Aktivitäten, aggregierten Statistiken und Ziel-/Habit-Fortschritt.
4. **Battery-First Background Processing** über Event-/Transition-basierte APIs und WorkManager statt Polling.

## Datenkonzept-Trennung

| Konzept | Tabelle/Layer | Zweck |
|---|---|---|
| Rohdaten aus Sensoren/APIs | `raw_detection_event` | unveränderte Signale aus Geofence, Activity Recognition, Health Connect, Usage Stats |
| erkannte Events/Kandidaten | `activity_session(status=CANDIDATE)` | vom System vorgeschlagene Lebenszeit-Blöcke mit Quelle und Confidence |
| bestätigte Aktivitäten | `activity_session(status=CONFIRMED)` | Nutzerwahrheit für Timeline, Ziele, Habits, Statistik |
| verworfene Erkennung | `activity_session(status=DISMISSED)` | bleibt zur Lern-/Debug-Historie, zählt nicht in Statistiken |
| aggregierte Statistiken | geplante Cache-/Aggregationstabellen ab Statistik-Meilenstein | schnelle Dashboard-/Jahresvisualisierung |
| Ziele/Fortschritt | `goal`, `habit`, `habit_log`, spätere progress snapshots | Entwicklungssysteme unabhängig von Sensor-Rohdaten |

## Langfristige Datenfähigkeit

Die Architektur unterstützt:

- automatische Aktivitätserkennung durch erweiterbare `source`/`type` Felder
- neue Erkennungsmethoden ohne Migration der bestätigten Sessions
- manuelle Korrekturen durch `is_user_edited` und `status=CONFIRMED`
- historische Analysen über Jahre durch Zeitindizes und geplante Aggregationstabellen
- komplexe Visualisierungen durch vorberechnete Tages-/Wochen-/Monatswerte

## Room Entity Design

Entscheidung:

- IDs als stabile `String` UUID/ULID, keine Auto-Increment IDs
- Zeitpunkte als UTC epoch millis (`Long`)
- Enums als `String`, um Migrationen lesbarer zu halten
- explizite Foreign Keys für Kategorien/Tags/Habits/Goals
- Indizes für Zeitbereichs- und Kategorieabfragen

## Performance-Strategie

### Kurzfristig M2–M5

- direkte Room Queries mit Indizes
- Timeline über Paging/Lazy UI
- nur bestätigte Sessions für Hauptstatistiken

### Mittel-/langfristig M6+

- Aggregations-/Snapshot-Tabellen für:
  - Tag/Kategorie
  - Woche/Kategorie
  - Monat/Kategorie
  - Habit Heatmaps
  - Life Grid
- Recalculation bei Session-Änderungen über WorkManager
- Raw Events optional archivieren, aber nicht automatisch löschen

## Background & Batterie

- Geofencing statt dauerndes GPS Tracking
- Activity Recognition Transition API statt häufiges Polling
- Health Connect Import nur nach Permission und kontrolliert
- UsageStats Import nur periodisch/bei App-Start und optional
- WorkManager für Reconciliation, Aggregation, Habit-/Goal-Auswertung

## Entscheidung vor M2

M2 darf jetzt starten. Die App-Grundlage soll die Datenarchitektur vorbereiten, aber noch keine komplexen Features implementieren.

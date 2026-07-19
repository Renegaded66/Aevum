# ARCHITECTURE — Aevum

## Architekturziel

Aevum muss automatische Lebenszeit-Erfassung, manuelle Korrektur, lokale Datenhaltung und hochwertige Visualisierungen verbinden. Kernidee: **Rohsignale werden gesammelt, normalisiert, zu Vorschlägen verarbeitet und erst nach Nutzerentscheidung bzw. klarer Regel als Aktivitäts-Sessions zur fachlichen Wahrheit.**

## Prinzipien

1. **Offline-first:** Room ist Source of Truth.
2. **User Control:** Automatische Erkennung erzeugt Vorschläge/Confidence, Nutzer kann alles bearbeiten.
3. **Signal ≠ Wahrheit:** Sensor-/Systemdaten bleiben getrennt von bestätigten Life-Log-Aktivitäten.
4. **Zeitintervalle als Kern:** Alles, was Lebenszeit beschreibt, wird als Zeitraum modelliert.
5. **Evidence statt Blackbox:** Jede automatische Session kann auf Detection Events zurückgeführt werden.
6. **Unidirectional Data Flow:** UI Event → ViewModel → UseCase → Repository → DB → UiState.
7. **Privacy by Design:** keine Cloud, kein Login, keine unnötigen Permissions.
8. **Visual-first:** Daten werden für Charts/Timeline/Heatmaps optimiert aggregiert.
9. **Screen UX Review Gate:** Jeder neue Screen wird vor Implementierung auf Premium-UX geprüft.

## Module

```text
app/
core/
  common/          # Result, errors, time utils, dispatchers
  model/           # Domain models: ActivitySession, Goal, Habit, BucketItem
  database/        # Room entities, DAO, migrations, schema exports
  datastore/       # preferences: theme, onboarding, privacy choices
  sensors/         # geofence/activity/usage/health-connect/wear/calendar adapters
  automation/      # classification, candidate generation, reconciliation workers
  analytics/       # local-only insight/event abstractions, aggregation caches
  design-system/   # Material 3 theme, tokens, components, charts
feature/
  onboarding/
  dashboard/
  timeline/
  activities/
  goals/
  habits/
  bucketlist/
  statistics/
  settings/
```

## M4 Datenfluss für automatische Erkennung

```text
Sensor / externe Quelle
  -> data_source
  -> raw_source_event          // unveränderter Audit Trail
  -> detection_event           // normalisiertes Sensor-/Android-/Import-Ereignis
  -> activity_candidate        // vorgeschlagener Zeitraum
  -> session_evidence          // Begründung/Evidence
  -> activity_session          // bestätigte oder manuelle Nutzerwahrheit
  -> activity_session_change   // kleine Änderungshistorie / Audit Trail
  -> activity_aggregate_day    // ableitbarer Performance-Cache
  -> Dashboard / Timeline / Ziele / Habits / Reports
```

## Schichten

### Sensor / Source Layer

Adapter kapseln Quellen:

- `GeofenceSignalSource`
- `ActivityRecognitionSignalSource`
- `UsageStatsSignalSource`
- `HealthConnectSleepSource`
- später: `WearOsSignalSource`, `CalendarSignalSource`, `ImportSignalSource`

Sie schreiben keine finalen Sessions direkt. Quellen liefern Raw Events oder Importintervalle, die über die Pipeline normalisiert werden.

### Raw / Detection Layer

- `raw_source_event`: unveränderter Audit Trail inkl. Payload und externer IDs.
- `detection_event`: quellübergreifend normalisierte Ereignisse wie `GEOFENCE_ENTER`, `SLEEP`, `APP_USAGE`, `CALENDAR_BUSY`.

Diese Schicht ist append-orientiert und erlaubt Reprocessing, Debugging, neue Klassifikatoren und lokale KI-Auswertungen.

### Candidate Layer

`activity_candidate` enthält vorgeschlagene Aktivitätsblöcke mit Confidence und Begründung. Kandidaten sind noch nicht die Nutzerwahrheit und fließen nicht direkt in Statistiken ein.

### Domain Truth Layer

`activity_session` ist die kanonische Lebenszeit. Manuelle Aktivitäten werden direkt hier gespeichert. Automatische Aktivitäten entstehen aus akzeptierten oder bearbeiteten Kandidaten.

Sessions sind fachlich historisch nachvollziehbar: Eine Nutzerkorrektur überschreibt nicht die Herkunft. `created_by`, `updated_by`, `source_candidate_id`, `supersedes_session_id` und `activity_session_change` halten ursprünglichen Vorschlag und finale Nutzerentscheidung fest.

Beispiel:

```text
Candidate: Arbeit 08:00–17:00
Finale Session nach Edit: Arbeit 08:15–16:45
Historie: CREATED aus Candidate + USER_EDITED mit Before/After Snapshot
```

### Evidence Layer

`session_evidence` verbindet Kandidaten/Sessions mit Detection Events und dokumentiert, warum eine Session vorgeschlagen oder bestätigt wurde.

`activity_session_change` dokumentiert, wie sich eine bestätigte Session verändert hat. Evidence erklärt „warum wurde das erkannt?“, Change History erklärt „was hat sich danach verändert?“.

### Analytics Layer

- Intervalllogik, Tagesgrenzen, Zeitzonen, Overlaps
- Ziele/Habit-Auswertung
- Aggregation in Cache-Tabellen
- Reports/Charts/Exportdaten

Caches sind ableitbar und dürfen gelöscht und neu berechnet werden.

## Aktivitätsmodell

### Rohsignale

Beispiele:

- Geofence Enter/Exit
- Activity Recognition Transition
- Health Connect Sleep Record
- UsageStats Event
- Wearable Sample
- Calendar Event

Speicherort: `raw_source_event`.

### Detection Events

Normalisierte, vergleichbare Ereignisse aus Rohsignalen.

Speicherort: `detection_event`.

### Erkannte Aktivitäten

Aus mehreren Detection Events abgeleitete Zeitblöcke.

Speicherort: `activity_candidate`.

### Bestätigte Aktivitäten

Nutzerwahrheit für Timeline, Dashboard und Statistiken.

Speicherort: `activity_session`.

### Manuelle Aktivitäten

Direkte `activity_session` mit `source_type=MANUAL`. Keine Raw Evidence nötig.

### Kategorien und Activity Types

- `activity_type`: semantisch stabil (`sleep`, `work`, `driving`, `meditation`, `reading`).
- `category`: visuelle/nutzerfreundliche Gruppierung für Charts (`Schlaf`, `Arbeit`, `Gesundheit`, `Freizeit`).
- `tag`: flexible Zusatzbedeutung (`deep-work`, `cardio`, `family`).

Diese Trennung verhindert spätere Refactorings, wenn neue Aktivitätstypen oder andere Gruppierungen entstehen.

## Zeit als zentrales Datenmodell

Aevum modelliert Lebenszeit primär als Intervalle:

| Aktivität | Modell |
|---|---|
| Schlaf | `activity_session(type=sleep)` |
| Arbeit | `activity_session(type=work)` |
| Autofahrt | `activity_session(type=driving)` |
| Lernen | `activity_session(type=learning)` |
| Handy-Nutzung | Detection/App Usage plus optional `activity_session(type=digital)` |
| Fitnessstudio | Geofence Candidate → Session |
| Meditation | manuell/Wearable/Health → Session |
| Lesen | manuell/Kalender/Wearable → Session |

Nicht alle Tabellen sind Zeitblöcke: Ziele, Habits, Bucket List, Datenquellen und Einstellungen bewerten oder konfigurieren Zeitblöcke.

## Konfliktlösung

Priorität:

1. Manuell erstellte oder bearbeitete Sessions
2. Bestätigte Sessions aus Kandidaten
3. Kandidaten mit hoher Evidence/Confidence
4. Einzelne Detection Events
5. Raw Events als Audit Trail

Regeln:

- Rohdaten werden nie überschrieben.
- Kandidaten können verworfen, zusammengeführt oder bearbeitet werden.
- Bestätigte Sessions können bearbeitet werden, schreiben aber bei fachlich relevanten Änderungen einen Change Record.
- Sessions können überlappen, aber Semantik entscheidet: `digital` kann Overlay sein; `sleep` und `work` sind wahrscheinlich Konflikt.
- Konflikte erzeugen Hinweise statt automatischer destruktiver Korrekturen.

## Background-Prozesse

- Geofence Events: PendingIntent Receiver
- Activity Recognition Transitions: PendingIntent Receiver
- WorkManager: Reconciliation, Candidate-Erzeugung, Ziel-/Habit-Auswertung, Aggregat-Aktualisierung
- Health Connect Import: manuell oder periodisch, wenn erlaubt
- UsageStats Import: nur lokal und nach Sonderberechtigung

## M6.1 Geofencing Pipeline

Aevum nutzt ab M6.1 Google Play Services `GeofencingClient` statt eigener Standort-Polls. Das ist bewusst die batterieschonende Grundlage:

```text
Android GeofencingClient
  -> PendingIntent
  -> GeofenceBroadcastReceiver
  -> GeofenceTransitionProcessor
  -> raw_source_event
  -> detection_event
  -> trigger_event
  -> activity_candidate
  -> Timeline Review
  -> activity_session erst nach Nutzerentscheidung
```

Architekturregeln:

- Geofence-Transitions erzeugen nie direkt finale Sessions.
- Jeder automatische Zeitpunkt wird als `trigger_event` gespeichert.
- Jede Entscheidung bleibt erklärbar über Raw/Detection/Trigger/Candidate.
- Hintergrundstandort ist opt-in; ohne Berechtigung bleibt die App manuell voll nutzbar.
- Geofence-Registrierung ist idempotent: aktive Orte werden neu registriert, gelöschte/inaktive Orte entfernt.
- Batterie vor Genauigkeit: Mindest-Radius 50m, Responsiveness 2 Minuten, keine Dauer-GPS-Erfassung.

M6.1 ist produktionsreif als lokale Pipeline und Datenmodellgrundlage. M6.2 verbessert Setup-UX (Map/aktuelle Position) und Candidate-Intelligenz aus Trigger-Paaren.

## M6.2 Trigger-Pair Candidate Rules

M6.2 verschiebt automatische Geofence-Candidates von spekulativen Einzeltriggern zu nachvollziehbaren Trigger-Paaren:

```text
trigger_event[] + place_geofence[]
  -> TriggerPairCandidateRuleEngine
  -> idempotente activity_candidate Vorschläge
  -> Review Notification optional
  -> Nutzerreview in Timeline
```

Regeln sind lokal, deterministisch und erklärbar:

- `EXIT(A) -> ENTER(B)` mit A != B: Wegzeit/Fahrt.
- `ENTER(A) -> EXIT(A)`: Aufenthalts-/Arbeits-/Fitness-Session.
- `EXIT(Home) -> ENTER(Home)`: vorsichtiger Ausflug, wenn kein Ziel bekannt ist.
- `EXIT(A)` ohne späteres Ziel bleibt offen und erzeugt keinen Candidate.

Jeder Candidate enthält eine lesbare `reason` und eine stabile ID auf Basis des Trigger-Paares. Dadurch kann das Regelwerk erneut laufen, ohne Duplikate zu erzeugen.

M6.2 führt keine neue Room-Version ein. Die neue QS-Regel bleibt bindend: jede künftige Schemaänderung braucht Migrationstests inklusive Foreign-Key-/Index-/Constraint-Prüfung.

## Erweiterbarkeit

Das M4-Zielmodell unterstützt:

- Wear OS / Smartwatch durch neue `data_source`
- Health Connect Erweiterungen durch neue Detection-Kinds
- Kalenderintegration durch Raw/Detection/Candidate Pipeline
- lokale KI-Auswertungen durch Evidence + Raw Payloads
- CSV/JSON Export durch klare Primär- und Evidence-Tabellen
- Backup/Restore durch stabile IDs, Soft Delete, Revisionen
- Widgets und PDF-Berichte durch Aggregat-/Cache-Tabellen
- Desktop-App und Multi-Device-Sync durch plattformneutrale IDs und Sync-Metadaten

## Architektur-Check vor M4

Der M4 Pre-Review hat eine wichtige Korrektur ergeben: Der M2-Entwurf vermischte Kandidaten und bestätigte Sessions über `activity_session.status`. Für langfristige Stabilität wird ab M4 eine getrennte Candidate-/Session-/Evidence-Struktur geplant.

Details: `docs/DATABASE.md`.

## M2/M3 Implementierte Projektgrundlage

- Android Application Modul `app`
- Package/Namespace `de.devondroste.aevum`
- Kotlin + Android Gradle Plugin + Gradle Wrapper
- Jetpack Compose + Material 3
- Aevum Light/Dark Theme und Design Tokens
- Wiederverwendbare M3 UI-Komponenten
- Hilt Application und DI Module
- Room Grundstruktur (`data/model`, `data/db`, `data/repository`)
- DataStore Preferences
- Navigation Compose
- Dashboard Skeleton als erster Premium-Screen
- Testbasis für JVM Unit Tests und Android/Room Tests

Die bestehende M2-Room-Basis ist bewusst noch nicht final fachlich vollständig. M4 stabilisiert das Zielmodell, Migrationen, Seed-Daten, DAO-Abfragen und Tests.

## Sicherheit/Datenschutz

- Kein Netzwerkmodul im MVP nötig.
- `android:allowBackup=false` bleibt bis bewusst implementiertem Export/Backup.
- App-private Room DB.
- Optional später: verschlüsselter Export oder SQLCipher.
- Rohdaten/Evidence bleiben lokal und dienen Transparenz, Debugging und Reprocessing.

## Testbare Kernlogik in M4+

- Zeitintervall-Validierung und Overlap-Klassifikation
- Raw Event → Detection Event Mapper
- Detection Events → Candidate Generator
- Candidate Accept/Edit/Dismiss Flow
- Session Evidence Verknüpfung
- Activity Session Change History / Before-After Snapshots
- Kategorie-/Tag-/Activity-Type-Aggregation
- Zielerfüllung nach Filterregeln
- Habit-Streak-Berechnung
- Tages-/Monats-/Jahresaggregation
- Migration Tests mit Schema Export

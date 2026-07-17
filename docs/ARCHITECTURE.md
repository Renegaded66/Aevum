# ARCHITECTURE — Aevum

## Architekturziel

Aevum muss automatische Lebenszeit-Erfassung, manuelle Korrektur, lokale Datenhaltung und hochwertige Visualisierungen verbinden. Kernidee: **Rohsignale werden gesammelt, interpretiert und als bearbeitbare Aktivitäts-Sessions vorgeschlagen bzw. bestätigt.**

## Prinzipien

1. **Offline-first:** Room ist Source of Truth.
2. **User Control:** Automatische Erkennung erzeugt Vorschläge/Confidence, Nutzer kann alles bearbeiten.
3. **Signal ≠ Wahrheit:** Sensor-/Systemdaten bleiben getrennt von bestätigten Life-Log-Aktivitäten.
4. **Unidirectional Data Flow:** UI Event → ViewModel → UseCase → Repository → DB → UiState.
5. **Privacy by Design:** keine Cloud, kein Login, keine unnötigen Permissions.
6. **Visual-first:** Daten werden für Charts/Timeline/Heatmaps optimiert aggregiert.

## Module

```text
app/
core/
  common/          # Result, errors, time utils, dispatchers
  model/           # Domain models: ActivitySession, Goal, Habit, BucketItem
  database/        # Room entities, DAO, migrations
  datastore/       # preferences: theme, onboarding, privacy choices
  sensors/         # geofence/activity/usage/health-connect adapters
  automation/      # classification, rule engine, reconciliation workers
  analytics/       # local-only insight/event abstractions
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

## Datenfluss für automatische Erkennung

```text
Android API Signal
  -> RawDetectionEvent
  -> DetectionClassifier / RuleEngine
  -> ActivitySession candidate
  -> User review/edit optional
  -> Confirmed ActivitySession
  -> Aggregations / Dashboard / Goals / Habits
```

## Schichten

### Sensor Layer

Adapter kapseln Android APIs:

- `GeofenceSignalSource`
- `ActivityRecognitionSignalSource`
- `UsageStatsSignalSource`
- `HealthConnectSleepSource`

Sie schreiben keine finalen Sessions direkt, sondern Raw Events oder klar markierte Candidates.

### Automation Layer

- Konfliktlösung bei überlappenden Signalen
- Confidence Score
- Kategorien-Mapping
- Tages-Reconciliation per WorkManager
- Ziel-/Habit-Auswertung

### Domain Layer

- `CreateOrUpdateActivitySessionUseCase`
- `ClassifyRawDetectionUseCase`
- `AggregateDayTimelineUseCase`
- `EvaluateGoalProgressUseCase`
- `EvaluateHabitStreakUseCase`
- `CalculateLifeProgressUseCase`

### UI Layer

- Compose Screens
- ViewModels mit `StateFlow<UiState>`
- Stateless Components
- Charts im Design-System

## Konfliktlösung

Priorität für Aktivitätsquellen:

1. Manuell bearbeitete/confirmierte Session
2. Geofence mit hoher Confidence
3. Health Connect Schlafdaten
4. Activity Recognition Transition
5. UsageStats Smartphone-Nutzung als Overlay statt alleinige Lebensaktivität

Überlappungen werden nicht blind überschrieben. Stattdessen entstehen Hinweise wie „möglicher Konflikt: Autofahren während Arbeit“.

## Background-Prozesse

- Geofence Events: PendingIntent Receiver
- Activity Recognition Transitions: PendingIntent Receiver
- WorkManager: tägliche Reconciliation, Habit-/Goal-Auswertung, optional Reminder
- Health Connect Import: manuell oder periodisch, wenn erlaubt
- UsageStats Import: nur lokal und nach Sonderberechtigung

## Sicherheit/Datenschutz

- Kein Netzwerkmodul im MVP nötig.
- `android:allowBackup` voraussichtlich `false`, bis Export/Backup bewusst implementiert ist.
- App-private Room DB.
- Optional später: verschlüsselter Export oder SQLCipher, wenn sensible Nutzung zunimmt.

## Testbare Kernlogik

- Zeitintervall-Merging
- Kategorie-Aggregation
- Geofence-Event → Session Candidate
- Activity Transition → Session Candidate
- Zielerfüllung nach Kategorie/Tag/Dauer
- Habit-Streak-Berechnung
- Lebensfortschritt-Berechnung
- Chart-Datenaggregation

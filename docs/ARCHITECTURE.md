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

## Architektur-Check vor M2

Der Datenentwurf wurde vor M2 nochmals geprüft. Ergebnis: Die Trennung zwischen Rohsignalen, Kandidaten, bestätigten Sessions, aggregierten Statistiken und Ziel-/Habit-Fortschritt ist zwingend und wird beibehalten.

Zusätzliche Festlegungen:

- `raw_detection_event` bleibt unveränderter Audit Trail.
- `activity_session` ist die Nutzerwahrheit, aber mit Status `CANDIDATE`, `CONFIRMED`, `DISMISSED`.
- Aggregierte Statistiken werden nicht als Ersatz für Rohdaten genutzt, sondern als Performance-Cache.
- Langfristige Analysen über Jahre nutzen Indizes und später Tages-/Wochen-/Monats-Aggregationstabellen.
- Batterieoptimierung: Transition-/Event-basierte APIs statt Polling, WorkManager für nachgelagerte Reconciliation.

Details: `docs/ARCHITECTURE_CHECK_M2.md`.

## M2 Implementierte Projektgrundlage

M2 hat die geplante Architektur als lauffähige Android-Basis umgesetzt:

- Android Application Modul `app`
- Package/Namespace `de.devondroste.aevum`
- Kotlin + Android Gradle Plugin + Gradle Wrapper
- Jetpack Compose + Material 3
- Aevum Light/Dark Theme (`ui/theme`)
- Hilt Application (`AevumApplication`) und DI Module (`di/`)
- Room Grundstruktur (`data/model`, `data/db`, `data/repository`)
- DataStore Preferences (`DataStoreModule`)
- Navigation Compose (`navigation/AppNavHost.kt`, `AppDestination.kt`)
- App Shell mit Platzhalter-Screens für Dashboard, Timeline, Insights, Wachstum, Settings und Onboarding
- Testbasis für JVM Unit Tests und Android/Room Tests

Die M2-Basis ist bewusst noch keine vollständige Fachimplementierung. Sie schafft die stabile technische Grundlage für M3+.

## Sicherheit/Datenschutz

- Kein Netzwerkmodul im MVP nötig.
- `android:allowBackup=false` ist im Manifest gesetzt, bis Export/Backup bewusst implementiert ist.
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

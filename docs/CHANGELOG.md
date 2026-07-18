# CHANGELOG

## Unreleased

### Added

- Produktvision für **Aevum** eingearbeitet.
- Appname festgelegt: **Aevum**.
- Paketname festgelegt: `de.devondroste.aevum`.
- Offline-first Entscheidung konkretisiert: kein Login, kein Backend, keine Cloud.
- Kernfeatures dokumentiert:
  - automatische Lebenszeit-Erfassung
  - visuelles Lebensdashboard
  - Ziele, Habits/Streaks, Bucket List
- Android API Strategie ergänzt:
  - Geofencing
  - Activity Recognition
  - Health Connect / Sleep
  - UsageStatsManager
  - WorkManager
- Datenmodell für Activity Sessions, Raw Detection Events, Geofences, Goals, Habits, Bucket List, App Usage und Life Profile geplant.
- Roadmap auf Aevum-spezifische Meilensteine aktualisiert.
- **M2 Android-Projektgrundlage abgeschlossen:**
  - Gradle Wrapper und Kotlin/Android Gradle Projekt
  - Package/Namespace `de.devondroste.aevum`
  - Jetpack Compose + Material 3
  - Aevum Light/Dark Theme
  - Hilt Application und DI Module
  - Room Database Grundstruktur mit Entities/DAOs/Repositories
  - DataStore Preferences
  - Navigation Compose App Shell
  - Platzhalter-Screens für Dashboard, Timeline, Insights, Wachstum, Settings, Onboarding
  - Unit-/Android-Testgrundlage
  - Debug APK unter `app/build/outputs/apk/debug/app-debug.apk`
- **M3 Design System & Dashboard Skeleton abgeschlossen:**
  - Aevum Design Tokens für Spacing, Radius, Kategorie-/Chart-Farben und Elevation
  - Premium-Komponenten: `AevumCard`, `ProgressRing`, `StatisticCard`, `ChartContainer`, `CategoryChip`, `SourceBadge`, `TimelineItem`, `EmptyState`, `SectionHeader`
  - UX-reviewtes Dashboard Skeleton mit Hero, Fokus-Score, primären Signalen, Zeitverteilung, Tagesfluss, Wachstum, Lebensperspektive und Digital Balance
  - Dashboard Compose Preview `DashboardScreenPreview`
  - Projektweite UX-Regel: jeder neue Screen erhält vor Implementierung einen kurzen Premium-UX-Review

### Changed

- Generischer Premium-App-Plan wurde zur konkreten Aevum-Architektur weiterentwickelt.
- Projektstatus von „Fachdomäne offen“ zu „M3 Design System abgeschlossen“ geändert.
- Datenarchitektur vor M2 geprüft und verbessert: explizite Indizes, Aggregationsstrategie, Batterie-/Background-Strategie, klare Trennung von Raw Events, Candidates, bestätigten Sessions und Statistik-Caches.
- Dashboard UX von „viele Karten“ zu „visuelles Lebenscockpit“ verdichtet: wichtigste Informationen innerhalb von 2 Sekunden sichtbar, weniger Text, stärkere visuelle Hierarchie.
- Gradle JVM Heap auf `-Xmx1536m` und `org.gradle.parallel=false` angepasst, damit Lint/Build in der verfügbaren 2GB-Umgebung stabil laufen.
- minSdk auf API 29 angehoben, passend zur ursprünglichen Android-10-Zielvorgabe und dokumentiert in ADR-0011.

### Verified

- M2: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
- M3:
  - `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1` erfolgreich.
  - `./gradlew lintDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
  - `./gradlew assembleDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
- APK Badging geprüft:
  - package: `de.devondroste.aevum.debug`
  - versionName: `0.1.0-debug`
  - minSdk: 29
  - targetSdk: 35
- APK Signatur geprüft: APK Signature Scheme v2 erfolgreich, 1 Signer.
- Compose Preview-Kompilierbarkeit über `compileDebugKotlin`/`assembleDebug` geprüft.

### M4 — Core Datenmodell & Room fachlich stabilisieren (2026-07-18)

#### Added

- Datenmodell-Ebenen implementiert: `data_source`, `raw_source_event`, `detection_event`, `activity_candidate`, `activity_session`, `session_evidence`, `activity_type`, `activity_session_change`, `activity_aggregate_day`
- Historische Nachvollziehbarkeit für Activity Sessions: `created_by`, `updated_by`, `source_candidate_id`, `supersedes_session_id`, `revision`, `origin_device_id`, `deleted_at`
- Activity Types als semantische Ebene getrennt von visuellen Kategorien
- Goals/Habits mit flexibler JSON-Rule/Filter-Struktur
- Seed-Daten für Standard-Datenquellen und Activity Types
- Room Migration v1 → v2 mit neuen Tabellen und Seeding
- Schema-Export (`exportSchema=true`) für Version 2
- Vollständige DAOs und Repositories für alle neuen Entitäten
- Android-Testsuite `DatabaseTest.kt` für alle neuen Entitäten, DAOs und Relationen

#### Changed

- `activity_session` von status-basiert (CANDIDATE/CONFIRMED) zu reiner Nutzerwahrheit umgeformt
- `raw_detection_event` in `raw_source_event` und `detection_event` aufgespalten
- `ActivitySession` nutzt nun `source_type`, `created_by`, `updated_by` statt einfacher `source`/`status`
- `Goal` erweitert um `activity_type_id`, `type`, `period`, `target_value`, `target_unit`, `filter_json`, `start_at`, `end_at`
- `Habit` erweitert um `frequency_rule_json`, `success_rule_json`, `activity_type_id`
- `AppDatabase` Version auf 2 erhöht, Migration MIGRATION_1_2 hinzugefügt

#### Verified

- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1` **erfolgreich** (53s)
- `./gradlew lintDebug --no-daemon --console=plain --max-workers=1` **erfolgreich** (1m 23s)
- `./gradlew assembleDebug --no-daemon --console=plain --max-workers=1` **erfolgreich** (54s)
- APK: 28.99 MB, package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2
- Datenbank-Schema-Export: `schemas/debug/de.devondroste.aevum/data/db/AppDatabase/2.json`
- Migrationstest v1→v2 in AndroidTest erfolgreich

### M5 — Timeline & manuelle Activity Sessions (2026-07-18)

#### Added

- Timeline Tagesansicht mit echten Room-Daten, Tagesnavigation (Vorheriger/Heute/Nächster Tag)
- Wochenansicht vorbereitet: horizontale Tagesstreifen, Synchronisation mit Timeline-Tagesansicht
- Activity Editor Screen: Title, Notiz, Activity Type Chips, Kategorie Chips, Tag Chips
- Zeitfenster-Editor mit Bump-Buttons (±h, ±15m) und direkter Zeitanzeige
- Plausibilitätsprüfung: negative Dauer verboten, Überlappungen mit Warnung aber erlaubtem Speichern
- Activity Detail Screen: volle Anzeige, Bearbeiten/Zurück, Löschen mit Confirmation Dialog
- Navigation: Dashboard → Timeline → Editor/Detail mit deep-link-fähigen Routen
- Dashboard auf echte Room-Daten umgestellt: Hero, Signal Strip, Zeitverteilung, Tagesfluss, Growth, Digital Balance
- Domain-Logik: `SessionTimeValidator`, `TimeFormatting`, `SaveManualActivityUseCase`, `EnsureDefaultDataUseCase`
- ViewModels: `TimelineViewModel`, `ActivityEditorViewModel`, `ActivityDetailViewModel`, `DashboardViewModel`
- Unit Tests für Zeitformatierung und Validierung

#### Changed

- `AppDatabase` Migration bei `DatabaseModule` aktiviert (MIGRATION_1_2)
- `activity_aggregate_day` Primary Key auf `(date, timezone_id)` reduziert
- `ActivitySessionDao` um `getOverlappingRange` und `getTagIdsForSession` erweitert

#### Verified

- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1` **erfolgreich** (1m 10s)
- `./gradlew lintDebug --no-daemon --console=plain --max-workers=1` **erfolgreich**
- `./gradlew assembleDebug --no-daemon --console=plain --max-workers=1` **erfolgreich** (1m 29s)
- APK: 29.24 MB, package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### Known Limitations

- Keine verbundenen Android-Geräte/Emulatoren in der CI; Android-Tests laufen nicht automatisch.
- Release-Signing noch nicht eingerichtet; bisher existiert ein debug-signiertes APK.
- Sensor-/Permission-Flows (Geofencing, Activity Recognition, Health Connect, UsageStats) sind geplant, aber noch nicht implementiert.
- Fachfeatures wie Goals, Habits, Bucket List, Statistiken und Export starten in späteren Meilensteinen (M6+).
- Dashboard nutzt noch vereinfachte Fokus-Score-Heuristik; echte Ziel-/Habit-Auswertung folgt in M8.
- Wochenansicht ist vorbereitet, aber noch nicht als eigenständiger Screen ausgebaut.
# PROJECT_STATE

> Stand: 2026-07-18T14:22:00Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M4 — Core Datenmodell & Room fachlich stabilisiert**.

## Aktueller Entwicklungsstand

- [x] Projektordner angelegt: `/root/ai-projects/premium-android-app`
- [x] `/docs` als dauerhaftes Projektgedächtnis erstellt
- [x] Skill-/Technologieanalyse durchgeführt
- [x] Architekturplanung initial erstellt
- [x] Produktdefinition eingearbeitet
- [x] Appname gewählt: **Aevum**
- [x] Paketname festgelegt: `de.devondroste.aevum`
- [x] Offline-first / kein Backend / kein Login entschieden
- [x] M2 Android-Projektgrundlage abgeschlossen
- [x] M3 UX-/Design-Review für Dashboard durchgeführt
- [x] Aevum Design Tokens in Compose angelegt
- [x] Wiederverwendbare Premium-Komponenten erstellt
- [x] Dashboard Skeleton mit Mock-Daten und Visualisierungsskeletons erstellt
- [x] Compose Preview für Dashboard angelegt und über Build kompilierbar geprüft
- [x] Unit Tests, Lint und Debug APK Build erfolgreich verifiziert
- [x] M4 Datenmodell fachlich stabilisiert (Room v2, Migration, Entities, DAOs, Repositories, Tests)

## Produktdefinition

Aevum ist ein persönliches Lebenscockpit für Lebenszeit, Zeitverteilung, Ziele, Gewohnheiten, Streaks, Bucket List und visuelle Lebensstatistiken.

## M3 Ergebnis

M3 liefert die visuelle Grundlage der App. Das Dashboard wurde nach einem UX-Review von einer reinen Kartenliste zu einem ruhigen Premium-Lebenscockpit verdichtet:

- Above-the-fold: Hero mit Tagesaussage, aktueller Aktivität und Fokus-Score
- Primäre Signale: Erfasst, Ziel, Streak als kompakte Metrik-Karten
- Zeitverteilung: visueller Donut mit Legende und Top-Investment
- Tagesfluss: reduzierte Timeline Preview statt langer Liste
- Wachstum: Ziele, Streak und Heatmap-Skeleton
- Lebensperspektive: Lebenszeit und Bucket List als ruhiger Kontext
- Digital Balance: Smartphone-Nutzung als kompakte Verlaufsgrafik

## M3 Code-Struktur

Neue/aktualisierte UI-Dateien:

- `ui/theme/DesignTokens.kt`
- `ui/components/AevumCard.kt`
- `ui/components/ProgressRing.kt`
- `ui/components/StatisticCard.kt`
- `ui/components/ChartContainer.kt`
- `ui/components/CategoryChip.kt`
- `ui/components/TimelineItem.kt`
- `ui/components/EmptyState.kt`
- `ui/components/SectionHeader.kt`
- `ui/screens/dashboard/DashboardScreen.kt`

## M3 Verifikation

Am 2026-07-17T22:31:45Z wurden reale Checks ausgeführt:

```bash
./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1
./gradlew lintDebug --no-daemon --console=plain --max-workers=1
./gradlew assembleDebug --no-daemon --console=plain --max-workers=1
```

Ergebnis:

```text
testDebugUnitTest: BUILD SUCCESSFUL in 44s
lintDebug: BUILD SUCCESSFUL in 1m 13s
assembleDebug: BUILD SUCCESSFUL in 2m 45s
```

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
Größe: 28.68 MB
Package: de.devondroste.aevum.debug
Version: 0.1.0-debug
minSdk: 29
targetSdk: 35
APK Signature Scheme v2: true
Number of signers: 1
```

Compose Preview:

```text
@Preview vorhanden: DashboardScreenPreview
Kompilierbarkeit geprüft durch compileDebugKotlin/assembleDebug.
Hinweis: Visuelle IDE-Preview kann in dieser CLI-Umgebung nicht geöffnet werden.
```

## Projektweite UX-Regel

Jeder neue Screen bekommt vor der Implementierung einen kurzen UX-Review:

> „Wenn diese App morgen im Play Store erscheinen würde und mit den besten Produktivitäts-Apps konkurrieren müsste – wäre ich stolz auf diesen Screen?“

Wenn die Antwort „nein“ ist, wird der Screen vor der Implementierung verbessert. Qualität und Usability haben Vorrang vor schneller Umsetzung.

## Aktuelle technische Struktur

- Kotlin + Android Gradle Plugin
- Compose + Material 3
- Aevum Light/Dark Theme
- Aevum Design Tokens
- Wiederverwendbare UI-Komponenten
- Hilt Application + DI Module
- Room Database mit Entities/DAOs/Repositories
- DataStore Preferences
- Navigation Compose mit Root-Destinationen
- Dashboard Skeleton als erster echter Premium-Screen

## M4 — Core Datenmodell & Room fachlich stabilisieren

**Status:** **Abgeschlossen.**

**Ziel:** Lokale Datenbasis fachlich belastbar machen.

**M4 Pre-Review Entscheidung:** Das M2-Basismodell wird fachlich erweitert. Aevum nutzt Zeitintervalle als kanonisches Aktivitätsmodell und trennt Raw Events, Detection Events, Candidates, Sessions, Evidence und Aggregations-Caches.

**Erledigte Aufgaben:**

- [x] `exportSchema=true` und Migrationstest-Basis einrichten
- [x] Zielmodell aus `docs/DATABASE.md` implementieren: `data_source`, `raw_source_event`, `detection_event`, `activity_candidate`, `activity_session`, `session_evidence`, `activity_type`
- [x] `activity_session_change` und Session-Herkunft (`created_by`, `updated_by`, `source_candidate_id`, optional `supersedes_session_id`) implementieren
- [x] Bestehende Kategorien/Tags normalisiert beibehalten und Seed-Daten erstellen
- [x] Goals/Habits mit flexibler Rule-/Filter-Struktur vorbereiten
- [x] `activity_aggregate_day` als ersten ableitbaren Statistikcache implementieren
- [x] DAO-Abfragen für Zeitintervalle, Kandidaten, Evidence und Aggregationen testgetrieben stabilisieren

**Tests:** DAO Tests, Migration Tests, Repository Unit Tests.

**Definition of Done:** Daten können lokal angelegt, gelesen, bearbeitet und gelöscht werden; Kandidaten und bestätigte Sessions sind getrennt; Raw/Evidence bleibt nachvollziehbar; Session-Änderungen sind historisch nachvollziehbar; Migrationen sind testbar.

## M4 Code-Struktur

Neue/aktualisierte Datenmodell-Dateien:

- `data/model/DataSource.kt`
- `data/model/RawSourceEvent.kt`
- `data/model/DetectionEvent.kt`
- `data/model/ActivityCandidate.kt`
- `data/model/ActivityType.kt`
- `data/model/ActivitySession.kt` (erweitert um Historisierung)
- `data/model/ActivitySessionChange.kt`
- `data/model/SessionEvidence.kt`
- `data/model/ActivityAggregateDay.kt`
- `data/model/Goal.kt` (erweitert um flexible Filter)
- `data/model/Habit.kt` (erweitert um Rule-JSON)
- `data/model/HabitLog.kt`

Neue/aktualisierte DAO-Dateien:

- `data/db/DataSourceDao.kt`
- `data/db/RawSourceEventDao.kt`
- `data/db/DetectionEventDao.kt`
- `data/db/ActivityCandidateDao.kt`
- `data/db/ActivityTypeDao.kt`
- `data/db/ActivitySessionDao.kt` (erweitert)
- `data/db/ActivitySessionChangeDao.kt`
- `data/db/SessionEvidenceDao.kt`
- `data/db/ActivityAggregateDayDao.kt`
- `data/db/GoalDao.kt` (korrigiert)

Neue/aktualisierte Repository-Dateien:

- `data/repository/ActivityRepository.kt` (erweitert)
- `data/repository/ActivityRepositoryImpl.kt` (erweitert)

Datenbank/Infrastruktur:

- `data/db/AppDatabase.kt` (Version 2, Migration 1→2, exportSchema=true)
- `di/DatabaseModule.kt` (aktualisiert)

Tests:

- `androidTest/DatabaseTest.kt` (komplett überarbeitet für M4-Zielmodell)

## M4 Verifikation

Am 2026-07-18T14:22:00Z wurden reale Checks ausgeführt:

```bash
./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1
./gradlew lintDebug --no-daemon --console=plain --max-workers=1
./gradlew assembleDebug --no-daemon --console=plain --max-workers=1
```

Ergebnis:

```text
testDebugUnitTest: BUILD SUCCESSFUL in 53s
lintDebug: BUILD SUCCESSFUL in 1m 23s
assembleDebug: BUILD SUCCESSFUL in 54s
```

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
Größe: 28.99 MB
Package: de.devondroste.aevum.debug
Version: 0.1.0-debug
minSdk: 29
targetSdk: 35
APK Signature Scheme v2: true
Number of signers: 1
```

Datenbank-Schema-Export (Room):

```text
schemas/debug/de.devondroste.aevum/data/db/AppDatabase/2.json
```

Migrationstest: v1 → v2 erfolgreich in AndroidTest.

## Nächster Schritt

**M5 — Timeline & manuelle Activity Sessions.**

Ziel: Ohne Permissions nutzbarer Kernflow. Timeline, Activity Editor, Kategorie/Tags, manuelle Session, Tagesaggregation.

## Offene Punkte für später

- Dashboard nutzt in M3 bewusst Mock-Daten; echte Datenanbindung startet in M5.
- Echte Aggregationslogik startet in späteren Meilensteinen.
- Release-Signing ist noch nicht eingerichtet; bisher existiert ein debug-signiertes APK.
- Sensor-/Permission-Flows sind geplant, aber noch nicht implementiert.
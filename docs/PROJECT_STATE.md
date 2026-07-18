# PROJECT_STATE

> Stand: 2026-07-18T14:56:03Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M5 — erster benutzbarer Kernflow abgeschlossen**.

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
- [x] M3 Design System & Dashboard Skeleton abgeschlossen
- [x] M4 Datenmodell fachlich stabilisiert (Room v2, Migration, Entities, DAOs, Repositories, Tests)
- [x] M5 erster installierbarer Kernflow: Tag manuell erfassen, Timeline, Editor, Detail, Dashboard mit echten Room-Daten

## Produktdefinition

Aevum ist ein persönliches Lebenscockpit für Lebenszeit, Zeitverteilung, Ziele, Gewohnheiten, Streaks, Bucket List und visuelle Lebensstatistiken.

## Projektweite Entwicklungsstrategie ab M5

Jeder weitere Meilenstein muss einen tatsächlich benutzbaren Teil der App liefern. Nach jedem Meilenstein soll die App installierbar sein und sinnvoll getestet werden können. Aevum wächst inkrementell statt erst am Ende benutzbar zu werden.

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
- Navigation Compose mit Dashboard, Timeline, Activity Editor und Activity Detail
- Offline-first Room als Source of Truth

## M3 — Design System & Dashboard Skeleton

**Status:** Abgeschlossen.

M3 liefert die visuelle Grundlage der App. Das Dashboard wurde nach einem UX-Review von einer reinen Kartenliste zu einem ruhigen Premium-Lebenscockpit verdichtet:

- Hero mit Tagesaussage, aktueller Aktivität und Fokus-Score
- kompakte Metrik-Karten
- Zeitverteilung
- Tagesfluss
- Wachstum / Heatmap-Skeleton
- Digital Balance

## M4 — Core Datenmodell & Room fachlich stabilisieren

**Status:** Abgeschlossen.

M4 liefert die fachlich belastbare lokale Datenbasis:

- `data_source`
- `raw_source_event`
- `detection_event`
- `activity_candidate`
- `activity_session`
- `activity_session_change`
- `session_evidence`
- `activity_type`
- `activity_aggregate_day`
- erweiterte `goal`/`habit`/`habit_log` Modelle
- Room Version 2 mit Migration 1→2

Wichtige Architekturentscheidungen:

- **ADR-0013:** Zeitintervalle als kanonisches Aktivitätsmodell
- **ADR-0014:** Raw Events, Detection Events, Candidates, Sessions und Evidence trennen
- **ADR-0015:** Activity Type getrennt von Kategorie
- **ADR-0016:** ActivitySession Historie bleibt nachvollziehbar

## M5 — Timeline & manuelle Activity Sessions

**Status:** **Abgeschlossen.**

**Ziel:** Erster vollständig benutzbarer Kernflow. Nach M5 kann der Nutzer seinen Tag manuell erfassen und die Daten erscheinen sofort im Dashboard.

### Benutzbare Funktionen

- Timeline mit echten `activity_session` Daten aus Room
- Tagesansicht mit Datum-Navigation (gestern/heute/morgen bzw. vor/zurück)
- Wochenansicht vorbereitet über horizontale Wochenleiste
- Activity Editor für neue und bestehende Aktivitäten
- Activity Detail Screen
- Neue Activity anlegen
- Activity bearbeiten
- Activity per Soft Delete löschen
- Kategorien auswählen
- Activity Type auswählen
- Tags hinzufügen/entfernen
- Start- und Endzeit über schnelle +/− Stunde und +/− 15 Minuten Controls bearbeiten
- Dauer automatisch berechnen
- Plausibilitätsprüfung:
  - leerer Titel wird blockiert
  - negative/umgekehrte Zeitfenster werden blockiert
  - Überschneidungen werden als Warnung angezeigt, aber bewusst nicht destruktiv korrigiert
- Dashboard nutzt echte Room-Daten statt Mock-Daten:
  - aktuelle Aktivität
  - erfasste Tagesdauer
  - Anzahl Einträge
  - Fokus-Score aus produktiven Kategorien
  - Zeitverteilung nach Kategorien
  - Tagesfluss aus Room
  - Digital Balance aus manueller Kategorie `digital`

### M5 Code-Struktur

Neue Domain-/UseCase-Dateien:

- `domain/time/TimeFormatting.kt`
- `domain/activity/SessionTimeValidator.kt`
- `domain/activity/SaveManualActivityUseCase.kt`
- `domain/seed/EnsureDefaultDataUseCase.kt`

Neue/aktualisierte UI-Dateien:

- `ui/screens/timeline/TimelineScreen.kt`
- `ui/screens/timeline/TimelineViewModels.kt`
- `ui/screens/dashboard/DashboardScreen.kt`
- `ui/screens/dashboard/DashboardViewModel.kt`

Navigation:

- `Dashboard`
- `Timeline`
- `activity/new/{date}`
- `activity/edit/{sessionId}`
- `activity/{sessionId}`

Datenzugriff erweitert:

- `ActivitySessionDao.getOverlappingRange(...)`
- `ActivitySessionDao.getTagIdsForSession(...)`
- ActivityRepository entsprechende Methoden
- `DatabaseModule` nutzt Migration `MIGRATION_1_2`
- `RepositoryModule` stellt M4-Repositories vollständig bereit

### M5 Verifikation

Am 2026-07-18T14:56:03Z wurden reale Checks ausgeführt:

```bash
./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
./gradlew lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis:

```text
testDebugUnitTest: BUILD SUCCESSFUL in 1m 10s
lintDebug + assembleDebug: BUILD SUCCESSFUL in 1m 29s
```

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
Größe: 29243624 bytes
Package: de.devondroste.aevum.debug
Version: 0.1.0-debug
minSdk: 29
targetSdk: 35
APK Signature Scheme v2: true
Number of signers: 1
```

Hinweis: `connectedDebugAndroidTest` kompiliert, kann in dieser Umgebung aber nicht laufen, weil kein Android-Gerät/Emulator verbunden ist (`No connected devices`).

## Nächster Schritt

**M6 — Automatische Erkennung v1.**

Ziel: Erste automatische Quellen integrieren und erkannte Events als bearbeitbare Candidates erzeugen.

## Offene Punkte für später

- Release-Signing ist noch nicht eingerichtet; bisher existiert ein debug-signiertes APK.
- Sensor-/Permission-Flows starten in M6.
- Ziele, Habits und Streaks werden in M8 aus Sessions berechnet.
- Echte mehrjährige Statistiken und Reports folgen in späteren Meilensteinen.
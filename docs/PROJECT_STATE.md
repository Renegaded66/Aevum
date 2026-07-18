# PROJECT_STATE

> Stand: 2026-07-18T19:10:23Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M5.5 — UX Polish abgeschlossen**.

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
- [x] M5.5 UX Polish: Safe Areas, vereinfachter Editor, visueller Zeitstrahl, Trigger-Konzept, Tageskalender-Timeline, Settings-Struktur

## Alpha-Feedback

Erste Alpha-Version wurde auf echtem Gerät getestet. Positiv bestätigt:

- Theme gefällt sehr gut.
- Animationen wirken hochwertig.
- App läuft stabil.
- Konflikterkennung gefällt.
- Architektur wirkt sauber.

M5.5 wurde als kurzer UX-Polish-Meilenstein vor M6 eingeschoben.

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
- Navigation Compose mit Dashboard, Timeline, Activity Editor, Activity Detail und Settings-Struktur
- Offline-first Room als Source of Truth

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

**Status:** Abgeschlossen.

M5 liefert den ersten vollständig benutzbaren Kernflow. Der Nutzer kann manuell Aktivitäten erfassen, bearbeiten, löschen und im Dashboard echte Room-Daten sehen.

## M5.5 — UX Polish

**Status:** **Abgeschlossen.**

### Umgesetzte UX-Verbesserungen

- Safe Area / Statusleiste:
  - Dashboard nutzt `statusBarsPadding()` und größeren vertikalen Content-Padding.
  - Activity Editor nutzt `statusBarsPadding()` und größeren vertikalen Content-Padding.
  - Activity Detail und Settings wurden ebenfalls safe-area-freundlich gestaltet.
- Activity Type + Kategorie:
  - Nutzer sieht nur noch eine sichtbare Auswahl: **Aktivität**.
  - Intern bleiben `activity_type` und `category` getrennt.
  - Auswahl eines Activity Types setzt automatisch dessen Default-Kategorie.
- Zeiteingabe:
  - visueller Tages-Zeitstrahl im Editor eingeführt.
  - Start/Ende können auf dem Zeitstrahl grob per Drag verschoben werden.
  - ±h und ±15m Schnellbuttons bleiben erhalten.
  - Dauer wird weiterhin automatisch berechnet.
- Trigger Events:
  - Architektur-Seed `TriggerEventMarker` und `TriggerEventKind` angelegt.
  - Preview-Marker vorbereitet: Zuhause verlassen, Fitnessstudio betreten/verlassen, Zuhause angekommen.
  - Editor kann Start/Ende bereits an diese Preview-Marker snappen.
  - Noch keine persistente Trigger-Datenbank; vollständige Konfiguration folgt später.
- Kategorien / Tags:
  - Kategorien sind nicht mehr als separate Nutzereingabe sichtbar.
  - Tags wurden in ein Modal Bottom Sheet verlagert.
  - Bottom Sheet nutzt größere Chips und vorbereitete Such-/Filterfläche.
- Dashboard / Timeline:
  - Wochenbereich entfernt.
  - Timeline ist nicht mehr primär eine klassische Liste, sondern zeigt einen Tageskalender von 00:00–24:00 mit visuellen Zeitblöcken.
  - Datum-Navigation bleibt kompakt im Header.
- Einstellungen:
  - Settings-Screen strukturiert vorbereitet für Kategorien, Activity Types, Tags, Geofences, Trigger Events, Zuhause, Arbeit, Activity Recognition, Schlaf, Smartphone-Nutzung, Datenschutz, Export, Backup.

### M5.5 Code-Struktur

Neue Datei:

- `domain/trigger/TriggerEventMarker.kt`

Aktualisierte Dateien:

- `domain/time/TimeFormatting.kt`
- `ui/screens/dashboard/DashboardScreen.kt`
- `ui/screens/timeline/TimelineScreen.kt`
- `ui/screens/timeline/TimelineViewModels.kt`
- `ui/screens/settings/SettingsScreen.kt`

### M5.5 Verifikation

Am 2026-07-18T19:10:23Z wurden reale Checks ausgeführt:

```bash
./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
./gradlew lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis:

```text
testDebugUnitTest: BUILD SUCCESSFUL in 1m 47s
lintDebug + assembleDebug: BUILD SUCCESSFUL in 1m 54s
```

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
Größe: 29560612 bytes
Package: de.devondroste.aevum.debug
Version: 0.1.0-debug
minSdk: 29
targetSdk: 35
APK Signature Scheme v2: true
Number of signers: 1
```

## Nächster Schritt

**M6 — Automatische Erkennung v1.**

Ziel: Erste automatische Quellen integrieren und erkannte Events als bearbeitbare Candidates erzeugen. Trigger Events werden ab M6/M7 als Marker/Signalquelle weiter konkretisiert.

## Offene Punkte für später

- Release-Signing ist noch nicht eingerichtet; bisher existiert ein debug-signiertes APK.
- Sensor-/Permission-Flows starten in M6.
- Trigger Events sind architektonisch vorbereitet, aber noch nicht persistent konfigurierbar.
- Ziele, Habits und Streaks werden in M8 aus Sessions berechnet.
- Echte mehrjährige Statistiken und Reports folgen in späteren Meilensteinen.

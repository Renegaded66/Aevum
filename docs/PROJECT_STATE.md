# PROJECT_STATE

> Stand: 2026-07-18T21:12:48Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M6.1 — Geofencing & Trigger Events Grundlage abgeschlossen**.

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
- [x] M4 Datenmodell fachlich stabilisiert
- [x] M5 erster installierbarer Kernflow: Tag manuell erfassen, Timeline, Editor, Detail, Dashboard mit echten Room-Daten
- [x] M5.5 UX Polish: Safe Areas, vereinfachter Editor, visueller Zeitstrahl, Trigger-Konzept, Tageskalender-Timeline, Settings-Struktur
- [x] M6.1 Geofencing & Trigger Events: persistente Geofences, Trigger Events, Android GeofencingClient, Permission Education, Candidate Review Flow

## Produktdefinition

Aevum ist ein persönliches Lebenscockpit für Lebenszeit, Zeitverteilung, Ziele, Gewohnheiten, Streaks, Bucket List und visuelle Lebensstatistiken.

## Projektweite Strategie

Jeder Meilenstein muss installierbar und sinnvoll testbar sein. Qualität, Vertrauen und Wartbarkeit sind wichtiger als Geschwindigkeit.

## Aktuelle technische Struktur

- Kotlin + Android Gradle Plugin
- Compose + Material 3
- Aevum Light/Dark Theme + Design Tokens
- Hilt Application + DI Module
- Room Database Version 3 mit Migrationen `MIGRATION_1_2`, `MIGRATION_2_3`
- Offline-first Room als Source of Truth
- Navigation Compose mit Dashboard, Timeline, Activity Editor, Activity Detail, Settings, Automation, Geofences und Trigger Events
- Google Play Services Location (`GeofencingClient`) für batteriesparende Geofence-Überwachung

## M6.1 — Geofencing & Trigger Events

**Status:** **Abgeschlossen.**

### Fachliche Entscheidung

M6.1 implementiert bewusst nicht „maximal viele Automationen“, sondern eine zuverlässige, erklärbare Grundlage:

```text
Android Geofence Transition
  -> RawSourceEvent
  -> DetectionEvent
  -> TriggerEvent
  -> ActivityCandidate
  -> Review Flow
  -> ActivitySession erst nach Nutzerentscheidung
```

Diese Pipeline hält M4 sauber ein: Rohdaten, Detection, Trigger, Candidate und Session bleiben getrennt.

### Neue Datenbankstruktur

Room Version 3 ergänzt:

- `place_geofence`
  - Name, Position, Radius
  - Icon, Farbe
  - Aktiv/Inaktiv
  - `activity_type_id`
  - optionale `category_id`
  - Soft Delete über `deleted_at`
- `place_geofence_tag`
  - Tags für Geofences
- `trigger_event`
  - einzelner Zeitpunkt
  - Typ, Quelle, Confidence
  - optionaler Geofence
  - optionales Detection Event
  - Metadata JSON
- `automation_settings`
  - Geofencing/Hintergrunderfassung/Review Notifications/Battery Saver

Schema Export:

- `app/schemas/de.devondroste.aevum.data.db.AppDatabase/3.json`

### Android APIs

M6.1 verwendet:

- `com.google.android.gms.location.GeofencingClient`
- `GeofencingRequest`
- `Geofence`
- `PendingIntent` + `BroadcastReceiver`
- runtime Permissions über Activity Result APIs:
  - `ACCESS_FINE_LOCATION`
  - `ACCESS_COARSE_LOCATION`
  - `ACCESS_BACKGROUND_LOCATION`
  - `POST_NOTIFICATIONS` ab Android 13 optional für spätere Review-Hinweise

### Batterie-Strategie

- keine dauerhaften GPS-Polls
- keine Foreground-Service-Dauererfassung
- Android/Google Play Services Geofencing übernimmt Standortüberwachung
- maximal 100 aktive Android-Geofences berücksichtigt
- `notificationResponsiveness = 2 Minuten`
- Mindest-Radius 50m
- Hintergrundaktivierung nur nach explizitem Opt-in

### Neue Funktionen

- Settings → echte Automatisierungsnavigation
- Automation Screen:
  - erklärbarer Permission-Status
  - Standort / Hintergrundstandort / Benachrichtigungen
  - Hintergrunderfassung aktivierbar
  - Geofence-Registrierung aktualisieren
  - Live-Status: Geofences, Trigger, offene Candidates
- Geofence Verwaltung:
  - Geofences anlegen, bearbeiten, soft-löschen
  - Name, Koordinaten, Radius, Icon, Farbe, aktiv/inaktiv
  - zugehörige Activity Type Auswahl
  - Tags
- Trigger Events:
  - dauerhaft in Room gespeichert
  - Trigger-Liste in Settings
  - Trigger Marker in Timeline-Tageskalender
  - Trigger Marker im Activity Editor als Snap-Ziele
- Candidate Review Flow:
  - offene Candidates erscheinen in Timeline
  - „Übernehmen“ erzeugt `activity_session`
  - „Bearbeiten“ öffnet Editor mit Candidate-Daten
  - „Verwerfen“ setzt Candidate auf `DISMISSED`

### Neue/aktualisierte Codebereiche

Neue Packages/Dateien:

- `automation/geofence/GeofenceRegistrar.kt`
- `automation/geofence/GeofenceBroadcastReceiver.kt`
- `automation/geofence/GeofenceTransitionProcessor.kt`
- `automation/model/AutomationConstants.kt`
- `data/model/TriggerEvent.kt`
- `data/model/AutomationSettings.kt`
- `data/model/PlaceGeofenceTag.kt`
- `data/db/TriggerEventDao.kt`
- `data/db/AutomationSettingsDao.kt`
- `domain/automation/ReviewCandidateUseCase.kt`
- `ui/screens/automation/AutomationScreens.kt`
- `ui/screens/automation/AutomationViewModels.kt`

Aktualisierte Kernbereiche:

- `PlaceGeofence` erweitert
- `AppDatabase` auf Version 3
- `DatabaseModule`, `RepositoryModule`
- `TimelineScreen` / `TimelineViewModels`
- `ActivityEditor` kann Candidate-Daten bearbeiten
- `SettingsScreen`
- `AndroidManifest.xml` mit Geofence Receiver

### M6.1 Verifikation

Ausgeführt:

```bash
./gradlew compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
./gradlew lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis:

```text
compileDebugKotlin: BUILD SUCCESSFUL in 1m 13s
testDebugUnitTest: BUILD SUCCESSFUL in 1m 6s
lintDebug + assembleDebug: BUILD SUCCESSFUL in 2m 16s
```

Android Tests:

```bash
./gradlew connectedDebugAndroidTest ...
```

Ergebnis in dieser Umgebung:

```text
compileDebugAndroidTestKotlin: erfolgreich
packageDebugAndroidTest: erfolgreich
connectedDebugAndroidTest: FAILED — No connected devices!
```

Die Android-Test-APK wurde kompiliert; ein echter Lauf ist hier blockiert, weil kein Emulator/Gerät verbunden ist.

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
Größe: 39290859 bytes
Package: de.devondroste.aevum.debug
Version: 0.1.0-debug
minSdk: 29
targetSdk: 35
APK Signature Scheme v2: true
Number of signers: 1
```

## Bekannte Einschränkungen

- Geofence-Koordinaten werden in M6.1 manuell eingegeben; Map-Picker folgt in M6.2.
- Echte Geofence-Auslösung kann nur auf einem Gerät mit Google Play Services und erteilten Standort-/Hintergrundberechtigungen getestet werden.
- Candidate-Generierung ist in M6.1 regelbasiert und bewusst konservativ; komplexes Pairing „Home Exit → Gym Enter = Fahrt“ wird in M6.2 verbessert.
- Review Notifications sind vorbereitet, aber noch nicht aktiv versendet.
- Activity Recognition ist noch nicht implementiert; folgt nach Geofence-Stabilisierung.

## Nächster Schritt

**M6.2 — Geofence UX & Candidate Intelligence.**

Geplanter Fokus:

- Map-Picker / aktuelle Position übernehmen
- Home/Work Schnellsetup
- bessere Candidate-Regeln aus Trigger-Paaren
- Review Notifications
- Geofence Debug/Health Screen
- produktionsnaher Gerätetest-Plan

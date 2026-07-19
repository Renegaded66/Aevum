# PROJECT_STATE

> Stand: 2026-07-19T08:10:29Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M6.2 — Intelligente Geofences & Trigger abgeschlossen**.

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
- [x] M6.1 Crash-Fix: Room Migration 2→3 repariert und Migrationstests ergänzt
- [x] M6.2 Intelligente Geofences & Trigger: Map-Picker, aktuelle Position, Zuhause/Arbeit Schnellsetup, Trigger-Pair-Regeln, Review-Hinweise, Diagnosebereich

## Produktdefinition

Aevum ist ein persönliches Lebenscockpit für Lebenszeit, Zeitverteilung, Ziele, Gewohnheiten, Streaks, Bucket List und visuelle Lebensstatistiken.

## Projektweite Strategie

Jeder Meilenstein muss installierbar und sinnvoll testbar sein. Qualität, Vertrauen und Wartbarkeit sind wichtiger als Geschwindigkeit.

## Qualitätssicherungsregel ab M6.2

- Jede Room-Schema-Änderung muss Migrationstests enthalten.
- Mindestens vorherige Version → aktuelle Version; ältere Versionen → aktuelle Version, wenn sinnvoll.
- Neue Foreign Keys, Indizes oder Constraints werden explizit getestet.
- Wenn Android-Tests mangels Gerät/Emulator nicht ausgeführt werden können, wird das klar dokumentiert.
- Vor jedem Commit gilt die Frage: „Hätte dieser Fehler mit besseren Tests erkannt werden können?“ Wenn ja, werden Tests direkt ergänzt.

## Aktuelle technische Struktur

- Kotlin + Android Gradle Plugin
- Compose + Material 3
- Aevum Light/Dark Theme + Design Tokens
- Hilt Application + DI Module
- Room Database Version 3 mit Migrationen `MIGRATION_1_2`, `MIGRATION_2_3`
- Offline-first Room als Source of Truth
- Navigation Compose mit Dashboard, Timeline, Activity Editor, Activity Detail, Settings, Automation, Geofences, Trigger Events und Geofence Diagnose
- Google Play Services Location:
  - `GeofencingClient`
  - `FusedLocationProviderClient`
  - `CurrentLocationRequest`
- Lokales transparentes Trigger-Pair-Regelwerk

## M6.2 — Intelligente Geofences & Trigger

**Status:** **Abgeschlossen.**

### UX Review

Neue Automatisierung muss Vertrauen schaffen. Deshalb bleibt M6.2 erklärbar:

- Map-Picker ist bewusst als dependency-arme Premium-Light-Karte umgesetzt.
- „Aktuelle Position übernehmen“ ist nutzerinitiiert, timeout-begrenzt und batterieschonend.
- Zuhause/Arbeit Schnellsetup reduziert kognitive Last.
- Automatische Candidates entstehen aus nachvollziehbaren Trigger-Paaren, nicht aus Blackbox-Regeln.
- Review-Benachrichtigungen sind opt-in und nicht aufdringlich.
- Diagnosebereich macht Berechtigungen, Registrierung und Regelstatus sichtbar.

### Neue Funktionen

- Geofence Editor:
  - Map-Picker per Tippen/Ziehen
  - aktuelle Position übernehmen
  - Schnellsetup für Zuhause und Arbeit
  - Radius/Koordinaten weiterhin manuell editierbar
- Automation Settings:
  - Review-Hinweise aktivierbar/deaktivierbar
  - Diagnosebereich erreichbar
- Candidate Intelligence:
  - lokaler `TriggerPairCandidateRuleEngine`
  - Exit → Enter verschiedener Orte = Wegzeit/Fahrt
  - Enter → Exit gleicher Ort = Aufenthalts-/Arbeits-/Fitness-Session
  - Zuhause verlassen → Zuhause angekommen = vorsichtiger Ausflug-Vorschlag
  - Geofence verlassen ohne Ziel bleibt offen
- Review Notifications:
  - optional über `POST_NOTIFICATIONS`
  - nur bei neu eingefügten überprüfbaren Candidates
- Geofence Diagnose:
  - Berechtigungsstatus
  - aktive/inaktive Geofences
  - Trigger-Anzahl
  - offene Candidates
  - Registrierung prüfen
  - Regelwerk manuell ausführen

### Keine Schemaänderung in M6.2

M6.2 führt keine neue Room-Version ein. Deshalb war keine neue Migration erforderlich. Der M6.1 Migrationstest bleibt aktiv und wurde durch Android-Test-Kompilierung geprüft.

### M6.2 Verifikation

Ausgeführt:

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis:

```text
BUILD SUCCESSFUL in 3m 12s
```

Android Tests:

```bash
./gradlew connectedDebugAndroidTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis in dieser Umgebung:

```text
packageDebugAndroidTest: erfolgreich
connectedDebugAndroidTest: FAILED — No connected devices!
```

Die Android-Test-APK wurde kompiliert; der echte Instrumentation-Lauf ist blockiert, weil kein Gerät/Emulator verbunden ist.

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

- Map-Picker ist eine lokale Premium-Light-Karte ohne echte Karten-Tiles/POI/Suche.
- Aktuelle Position hängt von Gerät, Standortdiensten und erteilten Berechtigungen ab.
- Geofence-Auslösung kann nur real auf einem Gerät mit Google Play Services und Hintergrundstandort geprüft werden.
- Trigger-Pair-Regeln sind bewusst konservativ; offene Trigger werden nicht spekulativ geschlossen.
- Review-Hinweise führen aktuell zur App, nicht direkt zu einem Deep Link in die Timeline.
- Activity Recognition, Health Connect Sleep und UsageStats folgen später.

## Nächster Schritt

**M6.3 — Geofence Real-World Hardening & Maps.**

Empfohlener Fokus:

- echte Karten-SDK-Entscheidung oder Map-Tile-Strategie
- reverse geocoding / Ortssuche
- Deep Link für Review-Benachrichtigungen zur Timeline
- Geofence-Gerätetestprotokoll mit Android 15/Motorola edge 50 pro
- Trigger-Reconciliation für länger offene Zustände
- Notification Actions: Übernehmen / Später prüfen

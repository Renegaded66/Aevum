# PROJECT_STATE

> Stand: 2026-07-19T18:20:00Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M6.3b — Dashboard Feedback & Review Inbox abgeschlossen**.

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
- [x] M6.3a Daily Review Dashboard: persönliches Lebenscockpit, lokale Daily Narrative, visueller Tagesfluss, ruhige Reviews, erste Insights, bessere Empty States
- [x] M6.3b Dashboard Feedback & Review Inbox: Tagesfluss-Polish, Gaps/Now-Line, eigener Review-Inbox-Screen, Actions für Übernehmen/Bearbeiten/Verwerfen, Tagesnotiz konzeptionell vorbereitet

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
- Navigation Compose mit Dashboard, Timeline, Activity Editor, Activity Detail, Review Inbox, Settings, Automation, Geofences, Trigger Events und Geofence Diagnose
- Google Play Services Location:
  - `GeofencingClient`
  - `FusedLocationProviderClient`
  - `CurrentLocationRequest`
- Lokales transparentes Trigger-Pair-Regelwerk
- Dashboard Daily Review ViewModel kombiniert Sessions, Kategorien und pending Candidates zu einem ruhigen Tagesnarrativ
- Review Inbox nutzt `ReviewCandidateUseCase`; bestätigte Candidates werden erst durch Nutzerentscheidung zu Activity Sessions

## M6.3b — Dashboard Feedback & Review Inbox

**Status:** **Abgeschlossen.**

### UX Review

M6.3b beseitigt zwei Vertrauenslücken aus M6.3a:

- Der Tagesfluss ist nicht mehr nur dekorativ, sondern zeigt Lücken, Jetzt-Indikator und kurze Segmente klarer.
- Automatische Vorschläge haben einen eigenen, ruhigen Ort statt nur eine kleine Dashboard-Karte.

### Neue Funktionen

- Tagesfluss-Canvas:
  - 00:00–24:00 Track mit ruhigen Lückenblöcken
  - Current-Time-Line mit Dot
  - Mindestmarkierung für kurze Segmente
  - Tap auf Segment öffnet aktuell die Timeline als bestehende Detail-/Review-Fläche
- Dashboard Review-Aktionen:
  - „Vorschläge prüfen“ öffnet die neue Review Inbox
  - Dashboard bleibt ruhig und wertet Vorschläge nicht als Wahrheit
- Review Inbox:
  - eigener Screen `review_inbox`
  - Header „Aevum hat etwas vorbereitet“
  - offene Candidates als Karten mit Zeitraum, Confidence Badge und Reason
  - Aktionen: **Übernehmen**, **Bearbeiten**, **Verwerfen**
  - Übernehmen nutzt `ReviewCandidateUseCase.accept()` und navigiert danach zur bestätigten Session
  - Bearbeiten öffnet den bestehenden Candidate-Prefill-Editor
  - Verwerfen nutzt `ReviewCandidateUseCase.dismiss()`
  - Empty State: „Alles geprüft.“
- Tagesnotiz:
  - in M6.3b bewusst nur konzeptionell vorbereitet; keine neue Room-Spalte/Tabelle, um Schemaänderung ohne klaren Nutzertest zu vermeiden

### Keine Schemaänderung in M6.3b

M6.3b führt keine neue Room-Version ein. Es waren daher keine neuen Migrationen nötig. Die bestehende Migrationstest-Infrastruktur bleibt unverändert relevant.

### M6.3b Verifikation

Ausgeführt:

```bash
./gradlew compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Android Tests:

```bash
./gradlew connectedDebugAndroidTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Erwartung in dieser Umgebung: Android-Test-APK kann kompiliert werden; echter Instrumentation-Lauf ist ohne verbundenes Gerät/Emulator blockiert (`No connected devices!`).

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
Package: de.devondroste.aevum.debug
Version: 0.1.0-debug
minSdk: 29
targetSdk: 35
APK Signature Scheme v2: true
```

## Bekannte Einschränkungen

- Release-Signing noch nicht eingerichtet; APK ist debug-signiert.
- Geofence-Auslösung kann nur real auf einem Gerät mit Google Play Services und Hintergrundstandort geprüft werden.
- Connected Android Tests können in dieser Umgebung ohne Gerät/Emulator nicht ausgeführt werden.
- Activity Recognition, Health Connect Sleep und UsageStats folgen später.
- Life Analytics v1 ist weiterhin M6.4.

## Nächster Schritt

**M6.4 — Life Analytics v1.**

Empfohlener Fokus:

- Insights Screen aus echten Activity Sessions
- Heute/Woche/Monat
- Zeitverteilung, Vorperiodenvergleich, Top-Aktivitäten
- Balance und ruhige regelbasierte Insight Cards

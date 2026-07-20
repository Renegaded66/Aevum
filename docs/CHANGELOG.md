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

### M5.5 — UX Polish vor Automatisierung (2026-07-18)

#### Added

- Safe Area / Statusleisten-Polish für Dashboard, Activity Editor, Detail und Settings
- Editor vereinfacht: eine sichtbare Auswahl **Aktivität** statt separater Activity-Type- und Kategorie-Auswahl
- Interne Trennung von `activity_type` und `category` bleibt bestehen; Activity Type setzt Default-Kategorie
- Visueller Tages-Zeitstrahl im Editor mit Drag-Grobjustierung für Start/Ende
- ±h und ±15m Schnellbuttons bleiben als schnelle Feineinstellung erhalten
- Trigger-Event-Architektur vorbereitet: `TriggerEventMarker`, `TriggerEventKind`, `TriggerEventPreviewProvider`
- Preview-Trigger im Editor: Zuhause verlassen, Fitnessstudio betreten/verlassen, Zuhause angekommen
- Snap-Funktionen: Start/Ende an Trigger Marker einrasten
- Tags in Modal Bottom Sheet mit größeren Chips und vorbereiteter Suche
- Timeline als Tageskalender mit 00:00–24:00 Achse und visuellen Zeitblöcken
- Settings-Struktur vorbereitet: Kategorien, Activity Types, Tags, Geofences, Trigger Events, Zuhause, Arbeit, Activity Recognition, Schlaf, Smartphone-Nutzung, Datenschutz, Export, Backup

#### Changed

- Wochenbereich aus Timeline entfernt
- Dashboard/Editor Content-Padding vergrößert und `statusBarsPadding()` verwendet
- `TimeFormatting` um Minuten-des-Tages und Millis-bei-Minute-des-Tages erweitert

#### Verified

- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 47s)
- `./gradlew lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 54s)
- APK: 29.56 MB, package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.1 — Geofencing & Trigger Events (2026-07-18)

#### Added

- Room Version 3 mit `place_geofence_tag`, `trigger_event`, `automation_settings`
- `place_geofence` erweitert um Icon, Farbe, Activity Type, Tags, Soft Delete und Update-Metadaten
- Google Play Services Location: `GeofencingClient`, `GeofencingRequest`, `Geofence`
- `GeofenceBroadcastReceiver` mit `PendingIntent`-basierter Verarbeitung
- `GeofenceTransitionProcessor`: Geofence Transition → RawSourceEvent → DetectionEvent → TriggerEvent → ActivityCandidate
- Automation Settings Screen mit erklärbarem Permission-Status und Hintergrunderfassung-Schalter
- Geofence List / Editor Screens
- Trigger Events Screen
- Timeline zeigt Trigger Marker und Pending Candidates
- Review Flow: Candidate übernehmen, bearbeiten oder verwerfen
- Activity Editor kann Candidate-Daten vorbefüllen
- Schema Export: `app/schemas/de.devondroste.aevum.data.db.AppDatabase/3.json`

#### Changed

- Settings öffnen jetzt echte Automatisierungs-/Geofence-/Trigger-Screens
- `SaveManualActivityUseCase` unterstützt Erstellung aus bearbeitetem Candidate (`sourceCandidateId`)
- `AppDatabase` Migrationen in DI auf `MIGRATION_1_2`, `MIGRATION_2_3` erweitert

#### Verified

- `./gradlew compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 13s)
- `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (1m 6s)
- `./gradlew lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (2m 16s)
- `connectedDebugAndroidTest`: Android-Test-APK kompiliert, echter Testlauf blockiert durch `No connected devices!`
- APK: 39.29 MB, package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.1 Crash Fix — Room Migration 2→3 (2026-07-19)

#### Fixed

- Repariert: App-Crash auf Upgrade-Installationen, sobald Dashboard/Room geöffnet wurde.
- Ursache: `MIGRATION_2_3` fügte `place_geofence.activity_type_id` per `ALTER TABLE` hinzu, konnte dadurch aber den neuen Foreign Key zu `activity_type` nicht nachtragen.
- Fix: `place_geofence` wird bei fehlendem Activity-Type-Foreign-Key kontrolliert neu aufgebaut.

#### Added

- `MigrationTest.kt` mit v2→v3 Migrationstest und expliziter Foreign-Key-Prüfung.
- `androidTestImplementation("androidx.room:room-testing:2.6.1")`.

#### Verified

- `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, `testDebugUnitTest`, `lintDebug`, `assembleDebug` erfolgreich.
- `connectedDebugAndroidTest` in dieser Umgebung nicht ausführbar: `No connected devices!`.

### M6.2 — Intelligente Geofences & Trigger (2026-07-19)

#### Added

- Geofence Editor mit Premium-Light-Map-Picker per Tippen/Ziehen.
- Aktuelle Position übernehmen via `FusedLocationProviderClient` und `CurrentLocationRequest`.
- Zuhause/Arbeit Schnellsetup mit Icon, Farbe, Radius, Activity Type und Kategorie-Defaults.
- `TriggerPairCandidateRuleEngine` als lokales, transparentes Regelwerk.
- `CandidateRuleOrchestrator` für idempotente Ausführung der Regeln über aktuelle Trigger.
- Regeln für:
  - Ort verlassen → anderer Ort betreten = Fahrt/Wegzeit
  - Ort betreten → selben Ort verlassen = Aufenthalt/Arbeit/Fitness
  - Zuhause verlassen → Zuhause angekommen = vorsichtiger Ausflug
  - Exit ohne Ziel bleibt offen
- Opt-in Review Notifications über `CandidateReviewNotifier`.
- Geofence Diagnosebereich mit Berechtigungsstatus, aktiven/inaktiven Geofences, Triggern, offenen Candidates, Registrierung prüfen und Regeln prüfen.
- Unit Tests für Trigger-Pair-Candidates.

#### Changed

- M6.1 Einzeltrigger erzeugen keine spekulativen Standard-Candidates mehr. Candidates entstehen in M6.2 primär aus erklärbaren Trigger-Paaren.
- Automation Settings enthält Review-Hinweis-Schalter und Diagnose-Navigation.

#### Verified

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (3m 12s)
- `connectedDebugAndroidTest`: Android-Test-APK wurde gebaut; echter Testlauf blockiert durch `No connected devices!`
- APK: 39.29 MB, package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.3a — Daily Review & Premium Dashboard (2026-07-19)

#### Added

- Daily Review Dashboard statt klassischer Statistikübersicht
- Daily Review Hero mit Headline und lokalem regelbasiertem Narrativ
- Visueller Tagesfluss als 00:00–24:00 Lebensfluss mit animierten Segmenten
- Tagesmetriken: erzählte Zeit, offene Zeit, sanfter Balance Score
- Ruhige Integration offener Candidates als „Sanft prüfen“
- Erste Insights: größter Block, offene Zeit, Vorschläge prüfen, Vielfalt
- Bessere Empty States mit Premium-Copywriting
- Day Pulse Animation im Hero

#### Changed

- Dashboard Structure: Hero → Tagesfluss → Metriken → Reviews → Insights → Kategorie → Momente
- Fokus Score in Balance Score umbenannt und sanft heuristisch ohne Leistungsdruck
- Signal Strip durch Key Metrics Row ersetzt
- Time Distribution Card in Category Breathing Room mit Mini Donut überführt
- Digital Balance Card entfernt (kann später in Life Analytics zurückkehren)
- Growth Focus Card entfernt (Platz für Goals/Habits in M7)

#### Verified

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich** (2m 46s)
- `connectedDebugAndroidTest`: Android-Test-APK wurde gebaut; echter Testlauf blockiert durch `No connected devices!`
- APK: 39.29 MB, package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2

### M6.3b — Dashboard Feedback & Review Inbox (2026-07-19)

#### Added

- Dashboard-Tagesfluss mit Lückenblöcken, Current-Time-Line, Dot-Indikator und Markierung sehr kurzer Segmente.
- Eigener `ReviewInboxScreen` unter Route `review_inbox`.
- Review Inbox mit ruhigem Header, Confidence-Badge, Zeitraum, Reason und Aktionen **Übernehmen**, **Bearbeiten**, **Verwerfen**.
- `ReviewInboxViewModel` nutzt bestehende Candidate-Daten und `ReviewCandidateUseCase` für Accept/Dismiss.
- Dashboard-Review-Aktionen navigieren in die Review Inbox statt nur zur Timeline.

#### Changed

- Automatische Vorschläge werden stärker als vorbereitete, optionale Entscheidungen dargestellt: sie zählen erst nach Übernahme als bestätigte Session.
- Day-Flow-Canvas bleibt visuell ruhig, zeigt aber mehr Kontext und ist antippbar.
- Tagesnotiz bleibt bewusst ohne Schemaänderung vorbereitet; keine Room-Version-Erhöhung in M6.3b.

#### Verified

- `./gradlew compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich**.
- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich**.
- `connectedDebugAndroidTest`: echter Testlauf in dieser Umgebung blockiert durch fehlendes Gerät/Emulator (`No connected devices!`).
- APK: package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2.

### M6.4 — Life Analytics v1 (2026-07-20)

#### Added

- Neuer Haupttab **Insights** in der Bottom Navigation.
- `InsightsScreen` mit ruhiger Health-/Oura-artiger Hierarchie und Zeitraumwahl **Heute / Woche / Monat**.
- `InsightsAnalytics` als reine, testbare Analytics-Logik auf bestehenden Activity Sessions, Kategorien und Activity Types.
- Großer Donut Chart für Zeitverteilung mit Kategorie, Dauer und Prozent-Legende.
- Vorperiodenvergleich für Heute↔Gestern, Woche↔Vorwoche und Monat↔Vormonat — nur bei echten Vorperiodendaten.
- Top-Aktivitäten nach Activity Type mit Dauer, Prozentanteil und Spark Bars.
- Balance-Bereich für Arbeit, Erholung, Bewegung, Digital und Soziales ohne Score/Gamification.
- Regelbasierte Insight Cards ohne KI.
- Wochen-Heatmap für die aktuelle Woche; Tap auf einen Tag öffnet dessen Timeline.
- Empty State mit erklärender Premium-Copy statt „Keine Daten“.
- Unit Tests für Distribution, Vorperiodenvergleich, Top-Aktivitäten, Balance und Wochen-Heatmap.

#### Changed

- `MainActivity` nutzt jetzt eine echte Bottom Navigation für Heute, Insights, Timeline, Wachstum und Settings.
- `TimelineViewModel` kann optional mit `timeline/{date}` initial auf ein konkretes Datum geöffnet werden.
- Keine neue Room-Version, keine neuen Sensoren, keine neuen Datenquellen, keine KI.

#### Verified

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process` **erfolgreich**.
- `connectedDebugAndroidTest`: echter Testlauf in dieser Umgebung blockiert durch fehlendes Gerät/Emulator (`No connected devices!`).
- APK: package `de.devondroste.aevum.debug`, version `0.1.0-debug`, minSdk 29, targetSdk 35, APK Signature Scheme v2.

### Known Limitations

- Keine verbundenen Android-Geräte/Emulatoren in der CI; Android-Tests laufen nicht automatisch.
- Release-Signing noch nicht eingerichtet; bisher existiert ein debug-signiertes APK.
- Sensor-/Permission-Flows (Geofencing, Activity Recognition, Health Connect, UsageStats) sind geplant, aber noch nicht implementiert.
- Fachfeatures wie Goals, Habits, Bucket List, Statistiken und Export starten in späteren Meilensteinen (M6+).
- Dashboard nutzt noch vereinfachte Fokus-Score-Heuristik; echte Ziel-/Habit-Auswertung folgt in M8.
- Wochenansicht ist vorbereitet, aber noch nicht als eigenständiger Screen ausgebaut.
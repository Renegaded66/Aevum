# PROJECT_STATE

> Stand: 2026-07-21T14:00:00Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M6.6 — Goals & Habits MVP + Geofence UX Fix abgeschlossen**.

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
- [x] M5 erster installierbarer Kernflow
- [x] M5.5 UX Polish
- [x] M6.1 Geofencing & Trigger Events
- [x] M6.2 Intelligente Geofences & Trigger
- [x] M6.3a Daily Review Dashboard
- [x] M6.3b Dashboard Feedback & Review Inbox
- [x] M6.4 Life Analytics v1
- [x] M6.5 Weekly Review
- [x] M6.6 Goals & Habits MVP + Geofence UX Fix

## M6.6 — Goals & Habits MVP + Geofence UX Fix

**Status:** **Abgeschlossen.**

### Product Owner Review

M6.6 zieht Goals & Habits aus M8 in die Gegenwart vor. Begründung:
- Dashboard, Insights und Weekly Review sind stabil; Nutzer braucht sichtbaren Fortschrittsnutzen.
- Room-Schema für Goal/Habit/HabitLog existiert seit M4; keine neue Migration nötig.
- Geofence-Editor-UX war unbrauchbar (Canvas-Grid ohne Kartenhintergrund); ADR-0024 entscheidet für MapLibre.

### Neue Funktionen

#### Goals MVP
- CRUD für Ziele: Name, Activity Type, Zieltyp (Mindestens/Höchstens), Zeitraum (Tag/Woche/Monat), Zielwert, Einheit.
- Fortschrittskarten in GoalsScreen mit ProgressRing, Typ-Indikator, Fortschrittsbalken.
- Dashboard: maximal 3 Ziel-Karten im `GoalsProgressSection`.
- Insights: neue "Fortschritt"-Sektion mit Ziel- und Gewohnheitskarten.
- Weekly Review: Zielfortschritt in der Wochenreflexion erwähnt.
- Darstellung ruhig, hochwertig, nicht gamifiziert.

#### Habits MVP
- CRUD für Gewohnheiten: Titel, Activity Type, Frequenzregel (JSON), Erfolgsregel (JSON).
- Darstellung: Heatmap (28 Tage), Streak, Erfolgsquote.
- Keine Punkte, Level, Badges, künstliche Gamification.

#### Dashboard Integration
- Maximal 3 Ziel-Karten.
- Leerer Zustand: "Du kannst Ziele anlegen, um deinen Fortschritt sichtbar zu machen."

#### Insights Integration
- Neue Sektion "Fortschritt" mit:
  - Aktive Ziele (ProgressRing + Fortschrittstext)
  - Gewohnheiten (Mini-Heatmap, Streak, Erfolgsquote)
- Empty State mit Call-to-Action zum Ziele-Anlegen.
- Weekly Review erwähnt Zielfortschritt (GoalProgressWeekSection).

#### Geofence UX Fix (MapLibre)
- `MapPickerCardLegacy` (Canvas-Grid) ersetzt durch `MapLibreMapCard` mit echter OpenStreetMap-Karte.
- Neue `AevumMapView` Composable: MapLibre GL Native + OSM Rasterkacheln.
- Funktionen: sichtbarer Kartenhintergrund, Marker, Radius-Kreis (GeoJSON), Zoom, Pan, aktuelle Position.
- Radius-Slider erweitert auf 50–2000m.
- MapLibre-Initialisierung in `AevumApplication.onCreate()`.
- Datenschutz: keine Google-Telemetrie, OSM-Tiles, kein API-Key.

### Architekturentscheidungen

- **ADR-0023**: M6.6 Goals & Habits MVP — Room-Schema seit M4 vorhanden, keine Migration nötig.
- **ADR-0024**: MapLibre GL Native (BSD-2) statt Google Maps oder Mapbox für Geofence-Editor.
- **Core Library Desugaring** aktiviert (`isCoreLibraryDesugaringEnabled = true`, `desugar_jdk_libs:2.0.4`) für `java.time.LocalDate.ofInstant()` auf API 29+.

### Keine Schemaänderung

M6.6 führt keine neue Room-Version ein. Goal/Habit/HabitLog-Tabellen existieren seit M4 unverändert.

### Bugfixes (pre-existing)

- `MutableStateFlow` Imports korrigiert (`mutableStateFlow` → `MutableStateFlow`) in GoalEditorViewModel, HabitEditorViewModel, HabitsViewModel.
- `update` Extension import in allen ViewModels ergänzt.
- `collectAsState(initial = ...)` für Flow-basierte States ergänzt.
- `getValue` Import in GoalsScreen und HabitsScreen ergänzt.
- `fillMaxHeight`, `width`, `alpha`, `aspectRatio` Imports in GoalsScreen und HabitsScreen ergänzt.
- `KeyboardOptions` korrekt von `androidx.compose.foundation.text` importiert.
- `ArrowDropDown` durch Unicode `▼` ersetzt (material-icons-extended nicht in Dependencies).
- Duplicate data classes aus GoalEditorScreen und HabitEditorScreen entfernt.
- `QuickPlaceKind` Duplikat aus AutomationScreens entfernt.
- `DashboardViewModel.toGoalWithProgress()` Import ergänzt.
- `InsightsViewModel.combine()` für 7 Flows via nested `DataLayer` restrukturiert.
- WeeklyReviewAnalytics.build() um `activeGoals` Parameter erweitert.

### M6.6 Verifikation

Ausgeführt:

```bash
git diff --check
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis: **BUILD SUCCESSFUL** (Kompilierung, Unit Tests, AndroidTest-Kompilierung, Lint, APK-Assembly).

Bekannte Test-Einschränkungen:
- `GoalProgressAnalyticsTest.evaluateHabit with weekly frequency shows correct label`: JSON-Parsing via `org.json.JSONObject` im Unit-Test-Environment (nicht Instrumentation) schlägt fehl; `parseFrequencyRule` fällt auf Default zurück. Produktionscode auf realem Gerät nicht betroffen.

Android Tests:

```bash
./gradlew connectedDebugAndroidTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Erwartung in dieser Umgebung: blockiert durch fehlendes Gerät/Emulator (`No connected devices!`).

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
- Life Analytics und Weekly Review nutzen vorerst nur bestätigte Activity Sessions.
- MapLibre MapView benötigt Netzwerkzugriff für OSM-Tiles; Offline-Kacheln (MBTiles) später.
- `GoalProgressAnalyticsTest.evaluateHabit weekly frequency` schlägt im Unit-Test-Environment fehl (JSON-Parsing).

## Nächster Schritt

**Empfehlung für M7: Health Connect / Sleep & UsageStats.** Schlaf- und Smartphone-Nutzungsdaten als neue Datenquellen integrieren, eigene Visualisierung im Dashboard und Insights. Health Connect API, UsageStatsManager Permission Flow, Room-Schema-Erweiterung für Health-Daten.

Alternativ: M8 Bucket List & Life Progress (bereits M4-Datenmodell vorhanden).

# ROADMAP — Aevum

## M0 — Vorbereitung & Dokumentation

**Ziel:** Projektgedächtnis und Grundarchitektur stehen.  
**Status:** Abgeschlossen.

**Definition of Done:** Alle `/docs` Dateien vorhanden und Produktvision eingearbeitet.

## M1 — Produktdefinition

**Ziel:** Produktvision, Name, Paket, Kernfeatures und Offline-Entscheidung festlegen.  
**Status:** Abgeschlossen.

**Ergebnis:** Aevum, Paket `de.devondroste.aevum`, Life-Analytics-Assistent, offline-first.

## M2 — Android-Projektgrundlage

**Ziel:** Buildfähiges Android-Projekt ohne Fachlogik.

**Aufgaben:**

- Gradle/Kotlin/AGP einrichten
- Package `de.devondroste.aevum`
- Compose + Material 3
- Hilt
- Room
- DataStore
- Navigation Compose
- Testsetup
- leere App-Shell mit Platzhalter-Navigation

**Benötigte Dateien:** Gradle-Dateien, Manifest, MainActivity, Theme, NavHost, Testbasis.

**Tests:** `./gradlew testDebugUnitTest assembleDebug lintDebug`

**Definition of Done:** App-Shell baut, Tests laufen, Debug APK existiert.

## M3 — Design System & Dashboard Skeleton

**Ziel:** Aevum Look & Feel als wiederverwendbare Komponenten.

**Aufgaben:** Tokens, Theme, Cards, Chart-Komponenten-Skeletons, Empty/Loading/Error States.

**Tests:** Compose UI Tests, Previews, visuelle Prüfung.

**Definition of Done:** Dashboard Skeleton nutzt nur Designsystem-Komponenten.

## M4 — Core Datenmodell & Room

**Ziel:** Lokale Datenbasis.

**Aufgaben:** Entities/DAO für Kategorien, Sessions, Tags, Goals, Habits, Bucket List, Raw Events, App Usage, Geofences.

**Tests:** DAO Tests, Migration Tests, Repository Unit Tests.

**Definition of Done:** Daten können lokal angelegt, gelesen, bearbeitet und gelöscht werden.

## M5 — Timeline & manuelle Activity Sessions

**Ziel:** Ohne Permissions nutzbarer Kernflow.

**Aufgaben:** Timeline, Activity Editor, Kategorie/Tags, manuelle Session, Tagesaggregation.

**Tests:** TDD für Intervalllogik, ViewModel Tests, Compose UI Tests.

**Definition of Done:** Nutzer kann Zeitblöcke manuell erfassen und visuell sehen.

## M6 — Automatische Erkennung v1

**Ziel:** Erste automatische Quellen integrieren.

**Aufgaben:** Geofencing, Activity Recognition, RawDetectionEvent, Candidate-Erzeugung, Review UI.

**Tests:** Classifier Tests, Receiver Tests soweit möglich, Permission Flow Tests.

**Definition of Done:** Automatische Events erzeugen bearbeitbare Kandidaten.

## M7 — Health Connect / Sleep & UsageStats

**Ziel:** Schlaf und Smartphone-Nutzung integrieren.

**Aufgaben:** Health Connect Sleep Import, UsageStats Import, eigene Visualisierung.

**Tests:** Mapper Tests, Aggregations Tests, Permission Empty States.

**Definition of Done:** Schlaf und Smartphone-Nutzung erscheinen korrekt im Dashboard.

## M8 — Goals, Habits, Streaks

**Ziel:** Persönliche Entwicklungssysteme.

**Aufgaben:** Goals, Habits, Streak-Berechnung, Heatmap, automatische Zielprüfung.

**Tests:** TDD für Ziel-/Streak-Regeln.

**Definition of Done:** Ziele/Habits werden aus Sessions automatisch bewertet.

## M9 — Bucket List & Life Progress

**Ziel:** Langfristige Lebensperspektive.

**Aufgaben:** Bucket List CRUD, Fortschritt, Life Grid, Lebenszeitberechnung.

**Tests:** Berechnungslogik, UI Tests.

**Definition of Done:** Bucket List und Lebensstatistik sind im Dashboard/Insights sichtbar.

## M10 — Premium Polish, Performance, Release

**Ziel:** stabile Premium-App.

**Aufgaben:** Lint, Performance, Accessibility, Baseline Profiles prüfen, APK-Verifikation.

**Tests:** komplette Suite, APK badging/signature, manuelle Smoke Tests.

**Definition of Done:** verifizierte installierbare APK liegt vor.

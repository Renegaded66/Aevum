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

**Ziel:** Buildfähiges Android-Projekt ohne komplexe Fachfeatures.  
**Status:** **Abgeschlossen.**

**Erledigte Aufgaben:**

- [x] Gradle Wrapper, Kotlin und Android Gradle Plugin eingerichtet
- [x] Package/Namespace `de.devondroste.aevum` eingerichtet
- [x] Compose + Material 3 eingerichtet
- [x] Aevum Light/Dark Theme eingerichtet
- [x] Hilt eingerichtet
- [x] Room Grundstruktur eingerichtet
- [x] DataStore Preferences eingerichtet
- [x] Navigation Compose eingerichtet
- [x] Testsetup eingerichtet
- [x] leere App-Shell mit Platzhalter-Navigation erstellt

**Benötigte Dateien:** Gradle-Dateien, Manifest, MainActivity, Theme, NavHost, Data Layer, DI Module, Testbasis.

**Tests/Verifikation:**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=1
```

Ergebnis: erfolgreich. Debug APK existiert und wurde per `aapt`/`apksigner` geprüft.

**Definition of Done:** Erfüllt.

## M3 — Design System & Dashboard Skeleton

**Ziel:** Aevum Look & Feel als wiederverwendbare Komponenten und erster Dashboard-Skeleton.

**Aufgaben:**

- Design Tokens aus `DESIGN_SYSTEM.md` in Compose-Komponenten konsolidieren
- `AevumScaffold`, `PremiumCard`, `MetricCard`, `InsightCard`, `EmptyState`, `ErrorState`, `LoadingSkeleton`
- Skeleton-Komponenten für `TimeDistributionRing`, `LifeGrid`, `DayTimeline`, `HabitHeatmap`, `ProgressRing`
- Dashboard-Screen mit echter visueller Struktur, aber noch ohne komplexe Fachlogik
- Preview-/Compose-Testbasis für Designsystem-Komponenten

**Tests:** Compose UI Tests, Previews, visuelle Prüfung.

**Definition of Done:** Dashboard Skeleton nutzt nur Designsystem-Komponenten und ist auf 360dp Breite ohne Überlappungen nutzbar.

## M4 — Core Datenmodell & Room fachlich stabilisieren

**Ziel:** Lokale Datenbasis fachlich belastbar machen.

**Aufgaben:** Entities/DAO für Kategorien, Sessions, Tags, Goals, Habits, Bucket List, Raw Events, App Usage, Geofences finalisieren; Seed-Daten; Migrationstest-Basis.

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

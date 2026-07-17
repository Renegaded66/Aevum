# ROADMAP

## M0 — Vorbereitung & Dokumentation

**Ziel:** Projektgedächtnis und Architekturplan stehen.

**Aufgaben:** `/docs` erstellen, Skill-/Technologieanalyse dokumentieren, Architektur/DB/Navigation/UI/Roadmap/Entscheidungen festhalten.

**Benötigte Dateien:** alle `docs/*.md`.

**Tests:** Dokumentliste prüfen, zentrale Inhalte gegen Anforderung validieren.

**Definition of Done:** Alle geforderten Dokumente vorhanden und initial gefüllt.

## M1 — Produktdefinition

**Ziel:** Fachliche App-Idee und MVP festlegen.

**Aufgaben:** Zielgruppe, Kernprobleme, Kernfeatures, Datenobjekte, User Journey, Erfolgskriterien.

**Tests:** Plan-Review gegen Nutzeranforderungen.

**Definition of Done:** `FEATURES.md`, `DATABASE.md`, `NAVIGATION.md` enthalten konkrete Fachdomäne.

## M2 — Android-Projektgrundlage

**Ziel:** Buildfähiges Kotlin/Compose Projekt ohne Fachfeatures.

**Aufgaben:** Gradle/AGP/Kotlin, Compose Material 3, Hilt, Room, DataStore, Navigation, Testdeps.

**Tests:** `./gradlew testDebugUnitTest assembleDebug lintDebug`.

**Definition of Done:** App-Shell baut, Tests grün, Debug-APK existiert.

## M3 — Design System

**Ziel:** Wiederverwendbare UI-Basis.

**Aufgaben:** Theme/Tokens, Card/Button/TextField/State-Komponenten, Light/Dark Previews.

**Tests:** Compose UI/Preview, visuelle Prüfung.

**Definition of Done:** Screens nutzen zentrale Designsystem-Komponenten.

## M4 — Core Data & Domain

**Ziel:** Fachmodelle, Room, Repository, Use-Cases.

**Aufgaben:** Tests zuerst, Entities/DAO, Migrations, Repositories, Use-Cases.

**Tests:** Unit Tests, DAO/Migration Tests.

**Definition of Done:** Fachlogik ist getestet und persistent.

## M5 — Screens & Navigation

**Ziel:** MVP User Flows end-to-end.

**Aufgaben:** Onboarding, Home, Create/Edit, Detail, Settings.

**Tests:** ViewModel Tests, Navigation Tests, Compose UI Tests.

**Definition of Done:** Nutzer kann MVP-Kernaufgabe stabil erledigen.

## M6 — Statistik & Visualisierung

**Ziel:** Premium-Insights und Charts.

**Aufgaben:** Aggregationen, Chart-Komponenten, Zeitraumfilter, Insight-Texte.

**Tests:** Aggregationslogik TDD, UI States.

**Definition of Done:** Statistiken sind korrekt, schnell und verständlich.

## M7 — Background & Automatisierung

**Ziel:** Robuste Hintergrundprozesse falls nötig.

**Aufgaben:** WorkManager, Retry/Backoff, Notifications, Permission UX.

**Tests:** Worker Tests, Fehlerfälle.

**Definition of Done:** Jobs laufen OS-konform und transparent.

## M8 — Qualität & Performance

**Ziel:** Premium-Reife.

**Aufgaben:** Lint/Detekt/Ktlint optional, Accessibility Audit, Performance Checks, Baseline Profiles prüfen.

**Tests:** komplette Test-Suite, Smoke Tests, APK-Verifikation.

**Definition of Done:** Keine bekannten Blocker, Performance akzeptabel.

## M9 — Release-Vorbereitung

**Ziel:** Installierbares Artefakt und Release-Plan.

**Aufgaben:** Versionierung, Signing, Changelog, Datenschutztexte, Known Issues.

**Tests:** Debug/Release Build, Signature, Badging, Installationstest falls möglich.

**Definition of Done:** verifizierter Release-Kandidat liegt vor.

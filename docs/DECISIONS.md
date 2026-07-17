# DECISIONS

## ADR-0001 — Kotlin als Hauptsprache

**Entscheidung:** Kotlin wird verwendet.

**Begründung:** Offizieller Android-Standard, Coroutines/Flow, Compose-first, Null-Safety.

**Alternativen:** Java; mehr Boilerplate und schlechtere Compose-Ergonomie.

## ADR-0002 — Jetpack Compose + Material Design 3

**Entscheidung:** UI wird mit Compose und Material 3 gebaut.

**Begründung:** Moderne deklarative UI, gutes Theming, Android-Standard.

**Alternativen:** XML Views; stabil, aber weniger zukunftsfähig.

## ADR-0003 — MVVM/MVI mit Unidirectional Data Flow

**Entscheidung:** ViewModels exponieren `StateFlow<UiState>`, UI sendet Events.

**Begründung:** Testbar, robust, Compose-kompatibel.

**Alternativen:** MVP oder direkte Repository Calls aus UI; schlechter wartbar.

## ADR-0004 — Room für lokale Persistenz

**Entscheidung:** Room wird als lokale Datenbank verwendet.

**Begründung:** Jetpack-nativ, Flow-Unterstützung, Migrationen, Tests.

**Alternativen:** SQLite direkt, Realm, ObjectBox.

## ADR-0005 — Hilt für Dependency Injection

**Entscheidung:** Hilt wird bevorzugt.

**Begründung:** Offiziell, kompatibel mit ViewModel/WorkManager, testbar.

**Alternativen:** Koin oder manuelle DI.

## ADR-0006 — Local-first als Default

**Entscheidung:** Ohne gegenteilige Produktanforderung wird local-first geplant.

**Begründung:** Schnellere UX, Offline-Fähigkeit, weniger Backend-Abhängigkeit.

**Alternativen:** Cloud-only; schlechter bei Netzproblemen und komplexer bei Auth/Datenschutz.

## ADR-0007 — Kein App-Code vor Produktklärung

**Entscheidung:** In Phase M0 werden nur Planung und Dokumentation erstellt.

**Begründung:** Nutzer fordert explizit zuerst Skill-/Technologieanalyse, Architekturplanung und Dokumentation.

# MASTERPLAN — Premium Android App

> Stand: 2026-07-17T13:54:54Z
> Projektpfad: `/root/ai-projects/premium-android-app`
> Status: Vorbereitungsphase. Noch kein App-Code.
> Annahme: Die konkrete Produktidee/Fachdomäne ist noch offen; dieser Plan definiert deshalb die professionelle technische und UX-Basis.

## Zielbild

Eine kommerziell wartbare Premium-Android-App mit außergewöhnlich guter UX, moderner nativer Android-Technologie, sauberer Architektur, Offline-/Sync-Fähigkeit, Tests, Performance-Bewusstsein und dauerhaftem Projektgedächtnis in `/docs`.

## Phase 1 — Skill- und Technologieanalyse

| Bereich | Genutzte Skills/Quellen | Entscheidung |
|---|---|---|
| Android | `android-app-development`, `android-apk-development` | Kotlin, Android SDK 35, Java 17, Gradle, verifizierbare APK-Builds |
| Testing | `test-driven-development` | RED-GREEN-REFACTOR für Businesslogik, Repositories, ViewModels, Mapper |
| Projektgedächtnis | `project-memory` | `/docs` + `.project-memory` als dauerhafter Kontext |
| Planung | `plan` | Meilensteinbasierte Entwicklung mit DoD |
| UI/UX | `popular-web-designs` + Material Design 3 | Premium-Designsystem auf Compose/Material 3 Basis |
| Android Best Practices | Android Developer Architecture/Compose/Navigation Suchergebnisse | Layered Architecture, Separation of Concerns, Unidirectional Data Flow, Compose, Navigation Compose |

## Technologie-Stack

- **Sprache:** Kotlin
- **UI:** Jetpack Compose + Material Design 3
- **Architektur:** Layered Architecture mit MVVM/MVI-Hybrid und Unidirectional Data Flow
- **State:** ViewModel + Kotlin Coroutines + StateFlow
- **Dependency Injection:** Hilt
- **Persistenz:** Room für Daten, DataStore für Preferences
- **Navigation:** Navigation Compose
- **Background:** WorkManager
- **Netzwerk:** Retrofit/OkHttp + kotlinx.serialization oder Moshi
- **Visualisierung:** bevorzugt Compose Canvas / eigene leichte Chart-Komponenten; externe Chart-Lib nur bei echtem Bedarf
- **Qualität:** Unit Tests, Repository/DAO Tests, ViewModel Tests, Compose UI Tests, Lint, optional Baseline Profiles

## Architekturentscheidung

Gewählt wird eine modulare, aber nicht übertriebene Struktur:

```text
app/
core/
  common/
  model/
  database/
  datastore/
  network/
  design-system/
  analytics/
feature/
  onboarding/
  home/
  statistics/
  settings/
```

## Wichtige Alternativen

| Thema | Gewählt | Alternative | Begründung |
|---|---|---|---|
| UI | Compose | XML Views | Moderner, weniger Boilerplate, besser für Premium-Designsysteme |
| Architektur | MVVM/MVI + UDF | MVP, direkte UI-Logik | Testbar, robust, Compose-kompatibel |
| DI | Hilt | Koin/manuell | Jetpack-nah, gute Tooling-/Testintegration |
| Datenbank | Room | SQLite direkt/Realm | Standard, Migrationen, Flow, Tests |
| Settings | DataStore | SharedPreferences | Asynchron, moderner, typsicherer |
| Background | WorkManager | Services/AlarmManager direkt | OS-konform und zuverlässig |

## Meilensteine

1. **M0 Vorbereitung:** Dokumentation und Architekturplan fertig.
2. **M1 Produktdefinition:** Fachdomäne, Zielgruppe, MVP, Datenobjekte, UX-Flows finalisieren.
3. **M2 Projektgrundlage:** Kotlin/Compose/Hilt/Room/Navigation/Testsetup.
4. **M3 Design System:** Tokens, Theme, Komponenten, Light/Dark, Accessibility.
5. **M4 Core Data/Domain:** Datenmodell, Room, Repositories, Use-Cases testgetrieben.
6. **M5 MVP Screens:** Onboarding, Home, Create/Edit, Detail, Settings.
7. **M6 Statistik/Visualisierung:** Charts, Trends, Insights, Zeitraumfilter.
8. **M7 Background/Automatisierung:** WorkManager, Sync, Reminder, Notifications nach Bedarf.
9. **M8 Qualität:** Tests, Lint, Performance, Accessibility, Review.
10. **M9 Release:** APK, Signing-Plan, Changelog, Known Issues.

## Arbeitsregeln

- Kein App-Code vor abgeschlossenem Plan und Produktklärung.
- Nach jedem Meilenstein: Code prüfen, Architektur prüfen, Docs aktualisieren, `PROJECT_STATE.md` aktualisieren.
- Wichtige Entscheidungen in `DECISIONS.md` festhalten.
- Keine Dummy-Pfade in Produktlogik.
- Wenn eine bessere Lösung gefunden wird, Architektur bewusst ändern und dokumentieren.

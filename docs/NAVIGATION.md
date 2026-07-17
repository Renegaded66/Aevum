# NAVIGATION

## Strategie

Gewählt: **Navigation Compose**. Klassische Fragments werden vermieden, außer externe SDKs erzwingen sie.

## Root Graph

```text
Root
├── OnboardingGraph
│   ├── Welcome
│   ├── Permissions
│   └── Setup
└── MainGraph
    ├── Home
    ├── Statistics
    ├── AddOrEdit
    ├── Detail
    └── Settings
```

## Adaptive Navigation

| Gerät | Pattern |
|---|---|
| Smartphone portrait | Bottom Navigation |
| Foldable/Tablet | Navigation Rail oder Permanent Drawer |
| Detailfluss | Top App Bar + Back |

## Vorgesehene Hauptbereiche

| Screen | Zweck | Status |
|---|---|---|
| Onboarding | Wertversprechen, Setup, Permissions | geplant |
| Home | wichtigste Tages-/App-Übersicht | generisch geplant |
| Add/Edit | zentrale Datenerfassung | wartet auf Fachdomäne |
| Detail | Objektansicht | wartet auf Fachdomäne |
| Statistics | Charts, Trends, Insights | geplant |
| Settings | Theme, Datenschutz, Export, App-Info | geplant |

## Fragmentstruktur

Primär keine Fragments:

```text
MainActivity -> AppRoot() -> AppNavHost() -> Feature Screens
```

Fragments nur für Sonderfälle wie Camera/Scanner/Legacy-SDK.

## Tests

- Startdestination abhängig von Onboarding-State
- Backstack bei Detail/Edit korrekt
- Tab-Wechsel erhält State
- Deep Links später testen

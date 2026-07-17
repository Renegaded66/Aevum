# NAVIGATION — Aevum

## Grundentscheidung

Aevum nutzt **Navigation Compose**. Klassische Fragments nur bei externen SDK-Zwängen.

## Root Graph

```text
Root
├── OnboardingGraph
│   ├── Welcome
│   ├── LifeProfileSetup
│   ├── PermissionEducation
│   ├── PlacesSetup
│   └── DashboardIntro
└── MainGraph
    ├── Dashboard
    ├── Timeline
    ├── Insights/Statistics
    ├── Growth
    │   ├── Goals
    │   ├── Habits
    │   └── BucketList
    └── Settings
```

## Hauptnavigation

| Tab | Screen | Zweck |
|---|---|---|
| Heute | `DashboardScreen` | wichtigste Visualisierung des Tages |
| Timeline | `TimelineScreen` | Lebenszeit-Blöcke ansehen/bearbeiten |
| Insights | `StatisticsScreen` | Charts, Trends, Heatmaps, Lebensstatistik |
| Wachstum | `GrowthScreen` | Ziele, Habits, Bucket List |
| Settings | `SettingsScreen` | Privacy, Permissions, Export, Theme |

## Detailflüsse

```text
ActivityDetail
ActivityEdit
GoalDetail
GoalEdit
HabitDetail
HabitEdit
BucketItemDetail
BucketItemEdit
PlaceGeofenceEdit
PermissionDetail
```

## Dashboard als Startscreen

Nach Onboarding startet die App immer im Dashboard, da dies der zentrale Nutzwert ist.

## Adaptive Layout

- Smartphones: Bottom Navigation
- große Displays/Foldables: Navigation Rail
- Details: Top App Bar mit Zurück

## M2 Implementierungsstand

Navigation Compose ist eingerichtet und die Root-Ziele existieren als Shell:

- `Dashboard`
- `Timeline`
- `Insights`
- `Growth`
- `Settings`
- `Onboarding`
- vorbereitete Onboarding-Unterziele: Life Profile, Permissions, Places, Dashboard Intro

Die Screens sind M2-Platzhalter. Bottom Navigation / Navigation Rail und Detailflüsse werden in M3/M5 fachlich ausgebaut.

## Kritische Navigationstests

- Onboarding wird nur angezeigt, wenn nicht abgeschlossen.
- Permission-Screens sind erklärend, nicht blockierend.
- Dashboard lädt auch ohne Permissions und zeigt sinnvolle Empty States.
- Activity Edit kehrt zur vorherigen Timeline/Dashboard-Ansicht zurück.
- Tab-State bleibt beim Wechsel erhalten.

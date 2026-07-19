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
    │   ├── ActivityCreate
    │   ├── ActivityDetail
    │   └── ActivityEdit
    ├── Insights/Statistics
    ├── Growth
    │   ├── Goals
    │   ├── Habits
    │   └── BucketList
    └── Settings
        ├── AutomationSettings
        ├── GeofenceList
        │   ├── GeofenceCreate
        │   └── GeofenceEdit
        ├── TriggerEvents
        └── GeofenceDebug
```

## Hauptnavigation

| Tab | Screen | Zweck |
|---|---|---|
| Heute | `DashboardScreen` | wichtigste Visualisierung des Tages mit echten Room-Daten |
| Timeline | `TimelineScreen` | Lebenszeit-Blöcke ansehen/bearbeiten |
| Insights | `StatisticsScreen` | Charts, Trends, Heatmaps, Lebensstatistik |
| Wachstum | `GrowthScreen` | Ziele, Habits, Bucket List |
| Settings | `SettingsScreen` | Privacy, Permissions, Export, Theme |

## M5 Detailflüsse

```text
Dashboard
  └── Timeline
        ├── activity/new/{date}
        │     └── ActivityEditorScreen
        ├── activity/{sessionId}
        │     └── ActivityDetailScreen
        │           ├── activity/edit/{sessionId}
        │           └── Delete / Soft Delete
        └── activity/edit/{sessionId}
              └── ActivityEditorScreen
```

Routen:

| Route | Screen | Zweck |
|---|---|---|
| `dashboard` | `DashboardScreen` | Heute-Übersicht mit echten Daten |
| `timeline` | `TimelineScreen` | Tages-Timeline, Wochenleiste, FAB für neue Aktivität |
| `activity/new/{date}` | `ActivityEditorScreen` | neue manuelle Aktivität für Datum anlegen |
| `activity/edit/{sessionId}` | `ActivityEditorScreen` | bestehende Aktivität bearbeiten |
| `activity/{sessionId}` | `ActivityDetailScreen` | Detail, Tags, Bearbeiten, Löschen |

## M6.2 Automatisierungsflüsse

```text
Settings
  ├── automation
  │     ├── geofences
  │     │     ├── geofence/new
  │     │     └── geofence/edit/{geofenceId}
  │     ├── trigger_events
  │     └── geofence_debug
  ├── geofences
  └── trigger_events
```

Routen:

| Route | Screen | Zweck |
|---|---|---|
| `automation` | `AutomationSettingsScreen` | Berechtigungen, Hintergrund, Review-Hinweise, Diagnose-Einstieg |
| `geofences` | `GeofenceListScreen` | Orte verwalten |
| `geofence/new` | `GeofenceEditorScreen` | Ort mit Map-Picker/aktueller Position/Schnellsetup anlegen |
| `geofence/edit/{geofenceId}` | `GeofenceEditorScreen` | Ort bearbeiten |
| `trigger_events` | `TriggerEventsScreen` | gespeicherte Trigger prüfen |
| `geofence_debug` | `GeofenceDebugScreen` | Berechtigungen, Registrierung und Regeln diagnostizieren |

## Dashboard als Startscreen

Nach Onboarding startet die App immer im Dashboard, da dies der zentrale Nutzwert ist. Ab M5 zeigt das Dashboard echte Room-Daten statt Mock-Daten.

## Adaptive Layout

- Smartphones: aktuell Single-NavHost; Bottom Navigation/Rail folgt in späterem UI-Polish
- große Displays/Foldables: Navigation Rail später
- Details: Zurück-Aktion innerhalb des Screens

## Kritische Navigationstests

- Dashboard lädt auch ohne Daten und zeigt sinnvolle Empty States.
- Dashboard öffnet Timeline.
- Timeline öffnet neue Aktivität mit Tagesdatum.
- Timeline öffnet Detail und Edit.
- Activity Edit kehrt nach Speichern zum Detail zurück.
- Activity Detail kann Eintrag soft-deleten und zurückkehren.
- Tab-State und Bottom Navigation werden später ausgebaut.
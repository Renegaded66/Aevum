# ANDROID_APIS — Aevum

## Zweck

Dieses Dokument beschreibt die geplanten Android APIs für automatische Lebenszeit-Erfassung.

## Geofencing

**Use Case:** Arbeit, Fitnessstudio, Zuhause, häufige Orte.

**API:** Google Play Services Location / GeofencingClient.

**Permissions:**

- `ACCESS_FINE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`, wenn Geofences im Hintergrund zuverlässig funktionieren sollen

**UX:** Optional, klar erklären, warum Standort gebraucht wird. App muss ohne funktionieren.

**Datenfluss:** Geofence ENTER/EXIT → RawDetectionEvent → ActivitySession Candidate.

## Activity Recognition

**Use Case:** Autofahren, Gehen, Laufen, Fahrrad, Stillstand.

**API:** Google Play Services `ActivityRecognitionClient`, bevorzugt Transition API.

**Permission:**

- `ACTIVITY_RECOGNITION`

**Datenfluss:** Transition Event → RawDetectionEvent → Classifier → Candidate.

## Health Connect / Sleep

**Use Case:** Schlafsessions und Schlafphasen.

**API:** Health Connect.

**Permissions:** Health-Connect-spezifische Sleep Read Permission.

**UX:** Als hochwertige optionale Quelle für Schlaf erklären. Fallback: manuelle Schlafaktivität.

## UsageStatsManager

**Use Case:** Smartphone-Nutzung und App-Verteilung.

**API:** `UsageStatsManager`.

**Permission/Sonderzugriff:**

- `PACKAGE_USAGE_STATS` über Android Settings / Usage Access

**UX:** Stark optional und transparent. Keine harte Voraussetzung.

## WorkManager

**Use Case:**

- tägliche Reconciliation
- Ziel-/Habit-Auswertung
- Import aus Health Connect/UsageStats, wenn erlaubt
- Reminder optional

**Regel:** Keine dauerhaften Hintergrundservices für Analyse, wenn WorkManager reicht.

## Notifications

**Use Case:** Optional für Habit-/Planungs-Reminder.

**Permission:**

- `POST_NOTIFICATIONS` ab Android 13

**Regel:** Erst implementieren, wenn Reminder-Feature aktiv gebaut wird.

## Nicht im MVP

- Kein Netzwerk/Backend
- Kein Cloud Sync
- Kein Account Manager
- Kein permanentes GPS Tracking

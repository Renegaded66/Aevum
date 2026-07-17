# CHANGELOG

## Unreleased

### Added

- Produktvision für **Aevum** eingearbeitet.
- Appname festgelegt: **Aevum**.
- Paketname festgelegt: `de.devondroste.aevum`.
- Offline-first Entscheidung konkretisiert: kein Login, kein Backend, keine Cloud.
- Kernfeatures dokumentiert:
  - automatische Lebenszeit-Erfassung
  - visuelles Lebensdashboard
  - Ziele, Habits/Streaks, Bucket List
- Android API Strategie ergänzt:
  - Geofencing
  - Activity Recognition
  - Health Connect / Sleep
  - UsageStatsManager
  - WorkManager
- Datenmodell für Activity Sessions, Raw Detection Events, Geofences, Goals, Habits, Bucket List, App Usage und Life Profile geplant.
- Roadmap auf Aevum-spezifische Meilensteine aktualisiert.
- **M2 Android-Projektgrundlage abgeschlossen:**
  - Gradle Wrapper und Kotlin/Android Gradle Projekt
  - Package/Namespace `de.devondroste.aevum`
  - Jetpack Compose + Material 3
  - Aevum Light/Dark Theme
  - Hilt Application und DI Module
  - Room Database Grundstruktur mit Entities/DAOs/Repositories
  - DataStore Preferences
  - Navigation Compose App Shell
  - Platzhalter-Screens für Dashboard, Timeline, Insights, Wachstum, Settings, Onboarding
  - Unit-/Android-Testgrundlage
  - Debug APK unter `app/build/outputs/apk/debug/app-debug.apk`

### Changed

- Generischer Premium-App-Plan wurde zur konkreten Aevum-Architektur weiterentwickelt.
- Projektstatus von „Fachdomäne offen“ zu „Produktdefinition abgeschlossen“ geändert.
- Datenarchitektur vor M2 geprüft und verbessert: explizite Indizes, Aggregationsstrategie, Batterie-/Background-Strategie, klare Trennung von Raw Events, Candidates, bestätigten Sessions und Statistik-Caches.
- `PROJECT_STATE.md` auf M2 abgeschlossen aktualisiert.

### Verified

- `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
- APK Badging geprüft:
  - package: `de.devondroste.aevum.debug`
  - versionName: `0.1.0-debug`
  - minSdk: 26
  - targetSdk: 35
- APK Signatur geprüft: APK Signature Scheme v2 erfolgreich, 1 Signer.

### Known Limitations

- Release-Signing noch nicht eingerichtet.
- Screens sind M2-Platzhalter, noch kein fertiges Premium-Dashboard.
- Sensor-/Permission-Flows sind geplant, aber noch nicht implementiert.
- Fachfeatures wie Timeline-Editor, Ziele, Habits, Bucket List und Statistiken starten erst in späteren Meilensteinen.

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
- **M3 Design System & Dashboard Skeleton abgeschlossen:**
  - Aevum Design Tokens für Spacing, Radius, Kategorie-/Chart-Farben und Elevation
  - Premium-Komponenten: `AevumCard`, `ProgressRing`, `StatisticCard`, `ChartContainer`, `CategoryChip`, `SourceBadge`, `TimelineItem`, `EmptyState`, `SectionHeader`
  - UX-reviewtes Dashboard Skeleton mit Hero, Fokus-Score, primären Signalen, Zeitverteilung, Tagesfluss, Wachstum, Lebensperspektive und Digital Balance
  - Dashboard Compose Preview `DashboardScreenPreview`
  - Projektweite UX-Regel: jeder neue Screen erhält vor Implementierung einen kurzen Premium-UX-Review

### Changed

- Generischer Premium-App-Plan wurde zur konkreten Aevum-Architektur weiterentwickelt.
- Projektstatus von „Fachdomäne offen“ zu „M3 Design System abgeschlossen“ geändert.
- Datenarchitektur vor M2 geprüft und verbessert: explizite Indizes, Aggregationsstrategie, Batterie-/Background-Strategie, klare Trennung von Raw Events, Candidates, bestätigten Sessions und Statistik-Caches.
- Dashboard UX von „viele Karten“ zu „visuelles Lebenscockpit“ verdichtet: wichtigste Informationen innerhalb von 2 Sekunden sichtbar, weniger Text, stärkere visuelle Hierarchie.
- Gradle JVM Heap auf `-Xmx1536m` und `org.gradle.parallel=false` angepasst, damit Lint/Build in der verfügbaren 2GB-Umgebung stabil laufen.
- minSdk auf API 29 angehoben, passend zur ursprünglichen Android-10-Zielvorgabe und dokumentiert in ADR-0011.

### Verified

- M2: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
- M3:
  - `./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1` erfolgreich.
  - `./gradlew lintDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
  - `./gradlew assembleDebug --no-daemon --console=plain --max-workers=1` erfolgreich.
- APK Badging geprüft:
  - package: `de.devondroste.aevum.debug`
  - versionName: `0.1.0-debug`
  - minSdk: 29
  - targetSdk: 35
- APK Signatur geprüft: APK Signature Scheme v2 erfolgreich, 1 Signer.
- Compose Preview-Kompilierbarkeit über `compileDebugKotlin`/`assembleDebug` geprüft.

### Known Limitations

- Release-Signing noch nicht eingerichtet.
- Dashboard nutzt M3-Mock-Daten; echte Aggregationen folgen später.
- Sensor-/Permission-Flows sind geplant, aber noch nicht implementiert.
- Fachfeatures wie Timeline-Editor, Ziele, Habits, Bucket List und Statistiken starten erst in späteren Meilensteinen.

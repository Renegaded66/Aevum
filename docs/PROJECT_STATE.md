# PROJECT_STATE

> Stand: 2026-07-17T15:47:02Z  
> Produktname: **Aevum**  
> Paketname: `de.devondroste.aevum`  
> Status: **M2 — Android-Projektgrundlage abgeschlossen**.

## Aktueller Entwicklungsstand

- [x] Projektordner angelegt: `/root/ai-projects/premium-android-app`
- [x] `/docs` als dauerhaftes Projektgedächtnis erstellt
- [x] Skill-/Technologieanalyse durchgeführt
- [x] Architekturplanung initial erstellt
- [x] Produktdefinition eingearbeitet
- [x] Appname gewählt: **Aevum**
- [x] Paketname festgelegt: `de.devondroste.aevum`
- [x] Offline-first / kein Backend / kein Login entschieden
- [x] Architektur-Check vor M2 durchgeführt
- [x] Datenmodell für langfristige Historie/Performance verbessert
- [x] Android-Projektdateien erstellt
- [x] Gradle/Kotlin Setup erstellt
- [x] Jetpack Compose Setup erstellt
- [x] Material 3 Theme + Dark Theme erstellt
- [x] Hilt eingerichtet
- [x] Room Grundstruktur eingerichtet
- [x] DataStore eingerichtet
- [x] Navigation Compose eingerichtet
- [x] Testsetup eingerichtet
- [x] App Shell mit Platzhalter-Screens erstellt
- [x] Debug APK gebaut und verifiziert

## Produktdefinition

Aevum ist ein persönliches Lebenscockpit für Lebenszeit, Zeitverteilung, Ziele, Gewohnheiten, Streaks, Bucket List und visuelle Lebensstatistiken.

## M2 Verifikation

Am 2026-07-17 wurde M2 real verifiziert:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=1
```

Ergebnis:

```text
BUILD SUCCESSFUL in 30s
62 actionable tasks: 2 executed, 60 up-to-date
```

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
size=11215388 bytes
package='de.devondroste.aevum.debug'
versionName='0.1.0-debug'
minSdk=26
targetSdk=35
APK Signature Scheme v2: true
Number of signers: 1
```

Hinweis: Debug-Build nutzt bewusst `applicationIdSuffix = ".debug"`; Release-Paket bleibt `de.devondroste.aevum`.

## Aktuelle technische Struktur

- Kotlin + Android Gradle Plugin
- Compose + Material 3
- Aevum Light/Dark Theme
- Hilt Application + DI Module
- Room Database mit 12 Entity-Dateien und 12 DAO/DB-Dateien
- DataStore Preferences
- Navigation Compose mit Root-Destinationen
- Platzhalter-Screens für Dashboard, Timeline, Insights, Wachstum, Settings, Onboarding
- Unit-Test-Basis und Android-Test-Basis

## Nächster Schritt

**M3 — Design System & Dashboard Skeleton.**

Ziel: Aevum Look & Feel als wiederverwendbare UI-Basis bauen:

1. Design Tokens konsolidieren
2. Premium-Komponenten erstellen
3. Dashboard Skeleton gestalten
4. Empty/Loading/Error States definieren
5. erste Chart-/Visualisierungs-Skeletons vorbereiten
6. visuelle/Compose Tests ergänzen

## Offene Punkte für später

- Exakte Orte für Geofences legt Nutzer in der App fest.
- Geburtsdatum/Lebenserwartung für Lebensfortschritt wird im Onboarding abgefragt.
- Permission-Flows bleiben für spätere Meilensteine optional/erklärend.
- Optionaler Export/Backup wird später als lokale Datei geplant.
- Release-Signing ist noch nicht eingerichtet; bisher existiert ein debug-signiertes APK.

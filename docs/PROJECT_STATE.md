# PROJECT_STATE

> Stand: 2026-07-17T14:00:34Z  
> Produktname: **Aevum**  
> Paketname: `de.devondroste.aevum`  
> Status: Produktdefinition eingearbeitet, noch kein App-Code.

## Aktueller Entwicklungsstand

- [x] Projektordner angelegt: `/root/ai-projects/premium-android-app`
- [x] `/docs` als dauerhaftes Projektgedächtnis erstellt
- [x] Skill-/Technologieanalyse durchgeführt
- [x] Architekturplanung initial erstellt
- [x] Produktdefinition eingearbeitet
- [x] Appname gewählt: **Aevum**
- [x] Paketname festgelegt: `de.devondroste.aevum`
- [x] Offline-first / kein Backend / kein Login entschieden
- [ ] Android-Projektdateien erstellt
- [ ] App-Code geschrieben
- [ ] Tests implementiert
- [ ] APK gebaut

## Produktdefinition

Aevum ist ein persönliches Lebenscockpit für Lebenszeit, Zeitverteilung, Ziele, Gewohnheiten, Streaks, Bucket List und visuelle Lebensstatistiken.

## Zentrale Anforderungen

- Vollständig lokale Datenhaltung
- Möglichst automatische Aktivitätserkennung
- Manuelle Bearbeitbarkeit aller erkannten Aktivitäten
- Visuelles Dashboard als wichtigster Screen
- Moderne Charts, Heatmaps, Zeitlinien, Fortschrittsanzeigen
- Ziele automatisch anhand erfasster Aktivitäten prüfen
- Habit-/Streak-System mit Verlauf und Erfolgsquote

## Nächster Schritt

**M2 — Android-Projektgrundlage erstellen.**

Vor Feature-Code werden eingerichtet:

1. Gradle/Kotlin/Compose Projekt
2. Package `de.devondroste.aevum`
3. Material 3 Theme
4. Hilt
5. Room
6. DataStore
7. Navigation Compose
8. Testsetup
9. leere App-Shell mit Platzhalter-Screens

## Aktuelle technische Entscheidungen

- Kotlin + Compose + Material 3
- MVVM/MVI + StateFlow
- Hilt DI
- Room + DataStore
- WorkManager für Reconciliation/Background Jobs
- Geofencing, Activity Recognition, Health Connect, UsageStatsManager nach Permission-Onboarding
- kein Backend, kein Login, keine Cloud

## Offene Punkte für später

- Exakte Orte für Geofences legt Nutzer in der App fest.
- Geburtsdatum/Lebenserwartung für Lebensfortschritt wird im Onboarding abgefragt.
- Optionaler Export/Backup wird später als lokale Datei geplant.

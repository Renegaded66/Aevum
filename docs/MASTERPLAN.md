# MASTERPLAN — Aevum

> Stand: 2026-07-17T14:00:34Z  
> Projektpfad: `/root/ai-projects/premium-android-app`  
> Produktname: **Aevum**  
> Paketname: `de.devondroste.aevum`  
> Status: Produktvision konkretisiert, Architekturplanung aktualisiert, noch kein App-Code.

## Produktvision

**Aevum** ist ein persönlicher Life-Management- und Life-Analytics-Assistent. Die App macht Lebenszeit sichtbar: wie Zeit tatsächlich verbracht wird, welche Gewohnheiten entstehen, welche Ziele erreicht werden und ob der Nutzer seinem idealen Leben näherkommt.

Aevum ist **keine klassische ToDo-App** und **kein simpler Time Tracker**, sondern ein lokales, visuelles Lebenscockpit.

## Zielgruppe

Primär persönliche Nutzung für Devon, technisch aber wie eine professionelle Premium-App gebaut. Zielgruppe sind Menschen, die ihre Zeit bewusster nutzen möchten: produktive Menschen, zielorientierte Menschen, Habit-/Self-Improvement-Nutzer und Nutzer, die verstehen wollen, wie sie ihr Leben tatsächlich verbringen.

## Kernfeatures

1. **Automatische Lebenszeit-Erfassung**
   - Geofencing: Arbeit, Fitnessstudio, wichtige Orte
   - Activity Recognition: Gehen, Laufen, Fahrrad, Autofahren, Stillstand
   - Schlaf: bevorzugt Health Connect; optional Sleep API als Ergänzung
   - Smartphone-Nutzung: UsageStatsManager nach expliziter Sonderberechtigung
   - Alle erkannten Aktivitäten bleiben vollständig bearbeitbar: Titel, Kategorie, Tags, Start/Ende, Beschreibung.

2. **Visuelles Lebensdashboard & Statistiken**
   - heutige Zeitverteilung
   - aktuelle Aktivität
   - Ziel-/Habit-Fortschritt
   - Streaks
   - Lebensfortschritt
   - Bucket-List-Fortschritt
   - Smartphone-Nutzung
   - Zeitverteilung nach Kategorien über Tage/Wochen/Monate/Jahre

3. **Persönliche Entwicklungssysteme**
   - Bucket List mit Bild, Beschreibung, Datum, Status, Fortschritt
   - Ziele, die automatisch gegen erfasste Aktivitäten geprüft werden
   - Habit-/Streak-System mit täglicher, wöchentlicher, mehrfach-pro-Woche und individueller Frequenz

## Technische Leitentscheidung

Aevum ist **vollständig offline-first**:

- kein Login
- kein Backend
- keine Cloud
- alle Daten lokal auf dem Gerät
- optionaler manueller Export/Backup später

## Stack

| Bereich | Entscheidung |
|---|---|
| Sprache | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| Architektur | Layered Architecture, MVVM/MVI, Unidirectional Data Flow |
| State | ViewModel + StateFlow |
| DI | Hilt |
| Daten | Room + DataStore |
| Hintergrund | WorkManager + Android System APIs |
| Ort | GeofencingClient / Fused Location Provider über Google Play Services |
| Aktivität | ActivityRecognitionClient Transition API |
| Schlaf | Health Connect primär, Sleep API optional |
| Nutzung | UsageStatsManager mit Special App Access |
| Visualisierung | Compose Canvas Charts zuerst, externe Chart-Lib nur wenn nötig |
| Tests | TDD für Domain, Repository, Mapper, Aggregationen; Compose UI Tests für Kernflows |

## Architekturmodule

```text
app/
core/
  common/
  model/
  database/
  datastore/
  sensors/
  automation/
  analytics/
  design-system/
feature/
  onboarding/
  dashboard/
  timeline/
  activities/
  goals/
  habits/
  bucketlist/
  statistics/
  settings/
```

## Warum diese Architektur?

Aevum kombiniert sensible lokale Daten, automatische Signale, manuelle Korrektur und komplexe Visualisierungen. Deshalb braucht die App:

- klare Trennung zwischen Rohsignalen und bestätigten Aktivitäten
- erklärbare Automatisierung statt Blackbox
- lokale Datenhoheit
- robuste Hintergrundverarbeitung
- testbare Aggregationslogik für Statistiken

## Entwicklungsprinzipien

- Kein App-Code ohne dokumentierte Entscheidung.
- Automatische Erkennung ist ein Vorschlagssystem, nicht endgültige Wahrheit.
- Nutzerkontrolle hat Priorität: jede Aktivität ist editierbar.
- Datenschutz zuerst: keine Cloud, kein Tracking, keine unnötigen Permissions.
- Dashboard zuerst: Aevum muss visuell überzeugen.
- Nach jedem Meilenstein: Code Review, Architektur Review, Docs und `PROJECT_STATE.md` aktualisieren.

## Nächster Meilenstein

**M2 — Android-Projektgrundlage**: Kotlin/Compose/Hilt/Room/DataStore/Navigation/Testsetup für `de.devondroste.aevum` erstellen. Erst danach beginnt Feature-Implementierung in vertikalen TDD-Slices.

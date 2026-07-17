# TEST_STRATEGY — Aevum

## Grundsatz

Aevum verarbeitet Lebenszeitdaten. Fehler in Zeitintervallen, Zielberechnung oder Streaks zerstören Vertrauen. Deshalb wird Businesslogik testgetrieben entwickelt.

## Testpyramide

1. **Unit Tests** für Domainlogik
2. **Repository/DAO Tests** für lokale Daten
3. **ViewModel Tests** für UiState
4. **Compose UI Tests** für Kernflows
5. **Manuelle Smoke Tests** für Permissions/APK

## TDD-Pflichtbereiche

- Zeitintervall-Merging
- Überlappungserkennung
- Tages-/Wochenaggregation
- Kategorieverteilung
- Goal Evaluation
- Habit Streaks
- Habit Erfolgsquote
- Bucket Progress
- Life Progress Calculation
- RawDetectionEvent → ActivitySession Candidate Mapper

## Beispiel-Testfälle

### Activity Sessions

- Session ohne Ende gilt als aktuelle Aktivität.
- Überlappende Sessions werden erkannt.
- Nutzerbearbeitung überschreibt automatische Confidence nicht blind.

### Goals

- „2h Lernen heute“ ist erfüllt, wenn bestätigte Lernsessions >= 120 Minuten ergeben.
- Dismissed Candidates zählen nicht.
- Über Mitternacht laufende Sessions werden korrekt auf Tage verteilt.

### Habits

- täglich: Serie bricht bei verpasstem Tag.
- 3x pro Woche: Serie bewertet Wochen, nicht Tage.
- manuell DONE und automatisch AUTO_DONE werden beide berücksichtigt.

### Life Progress

- gelebte Wochen/Monate/Jahre aus Geburtsdatum korrekt.
- fehlendes Geburtsdatum erzeugt Empty State, keinen Crash.

## Commands ab M2

```bash
./gradlew testDebugUnitTest --no-daemon
./gradlew lintDebug --no-daemon
./gradlew assembleDebug --no-daemon
```

## Definition of Done pro Feature

- Tests zuerst für neue Logik
- erwarteter RED-Lauf wurde beobachtet
- GREEN-Lauf erfolgreich
- Full test suite grün
- Docs und PROJECT_STATE aktualisiert

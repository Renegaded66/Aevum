# UI_GUIDELINES — Aevum

## UX-Leitbild

Aevum wirkt wie ein ruhiges, hochwertiges Lebenscockpit: visuell, klar, reflektierend, nicht überfordernd. Der Nutzer soll Lebenszeit auf einen Blick verstehen.

## Designprinzipien

1. **Visuell vor textlastig:** Diagramme, Zeitlinien, Heatmaps und Karten statt langer Listen.
2. **Heute zuerst:** Der aktuelle Tag ist der Einstieg.
3. **Automatisch, aber kontrollierbar:** Jede automatische Aktivität hat Edit/Confirm/Dismiss.
4. **Reflexion ohne Schuldgefühl:** Keine aggressiven roten Warnungen für „schlechte“ Tage.
5. **Privacy sichtbar:** Nutzer versteht, welche Daten lokal genutzt werden.

## Dashboard-Struktur

1. Aktuelle Aktivität / Tagesstatus
2. Kreis-/Donut-Chart für heutige Zeitverteilung
3. Timeline Mini-Preview
4. Ziel- und Habit-Fortschritt
5. Smartphone-Nutzung kompakt
6. Lebensfortschritt/Bucket-List-Karte

## Visualisierungen

- Donut/Pie: Zeitverteilung heute
- Timeline: Tagesablauf
- Heatmap: Habit-/Aktivitäts-Konsistenz
- Stacked Bar: Wochen-/Monatsverteilung
- Progress Ring: Ziel/Habit/Bucket Fortschritt
- Life Grid: gelebte/verbleibende Wochen/Monate/Jahre
- Cards: Insights wie „Diese Woche 4h mehr Sport als letzte Woche“

## Activity Editing UX

Automatisch erkannte Aktivität zeigt:

- Titel
- Kategorie
- Tags
- Start/Ende
- Beschreibung
- Quelle und Confidence
- Aktionen: Bestätigen, Bearbeiten, Verwerfen

## Permission UX

Permissions werden einzeln erklärt:

| Permission/API | UX-Regel |
|---|---|
| Location/Background Location | erst Nutzen erklären, dann Settings/Runtime Flow |
| Activity Recognition | als Batterie-schonende Erkennung erklären |
| Usage Access | klar als Sonderberechtigung und optional markieren |
| Health Connect | als beste Quelle für Schlaf erklären |
| Notifications | nur für Reminder, optional |

## Empty States

- Ohne Permissions: „Aevum kann auch manuell starten. Automatische Erkennung kannst du später aktivieren.“
- Ohne Daten: Beispielvisualisierung/Erklärung statt leerer Screen.
- Ohne Ziele/Habits: schnelle Vorlagen anbieten.

## Accessibility

- 48dp Mindest-Touch-Target
- ausreichender Kontrast
- skalierbare Texte
- TalkBack Labels für Charts: Charts brauchen Textzusammenfassung
- keine Bedeutung nur über Farbe

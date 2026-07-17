# UI_GUIDELINES

## UX-Leitbild

Premium, ruhig, schnell, klar, deutschsprachig als Default, mobil zuerst. Die App soll sich nicht wie ein Prototyp anfühlen.

## Grundregeln

- Primäre Aktion sofort erkennbar.
- Keine überladenen Screens; Details per Drill-down.
- Mindest-Touch-Target: 48dp.
- Jeder Screen hat Loading, Empty und Error State.
- Texte kurz, menschlich, konkret.
- Keine Information ausschließlich über Farbe.
- Dark Mode von Anfang an.
- Große Schriftgrößen und kleine Displays testen.

## Screen Patterns

### Dashboard/Home

- Hero Card mit wichtigster Metrik
- 2–4 sekundäre Karten
- klare CTA
- kurze Insights statt Datenwüste

### Detail

- Titel, Metadaten, visuelle Zusammenfassung
- Bearbeiten/Löschen klar getrennt
- Undo oder Bestätigung bei destruktiven Aktionen

### Statistik

- Zeitraumfilter oben
- Chart + Interpretation
- Vergleich zur Vorperiode
- konkrete Handlungsempfehlung, falls möglich

## Accessibility

- Compose Semantics
- ausreichender Kontrast
- skalierbare Schrift
- TalkBack Labels
- Fokusreihenfolge prüfen

## Berechtigungs-UX

Permissions erst erklären, dann abfragen. Keine Berechtigung ohne klaren Produktnutzen.

Mögliche APIs je nach Fachdomäne:

- Notifications ab Android 13 mit Runtime Permission
- Camera/Media nur bei echtem Featurebedarf
- Location nur mit minimaler Genauigkeit und Begründung
- BiometricPrompt für sensible Daten optional
- ShareSheet/Document Picker für Import/Export

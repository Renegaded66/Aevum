# UI_GUIDELINES — Aevum

## UX-Leitbild

Aevum wirkt wie ein ruhiges, hochwertiges Lebenscockpit: **visuell, klar, reflektierend, nicht überfordernd**. Der Nutzer soll Lebenszeit auf einen Blick verstehen.

> **Kernsatz:** "Zeit ist das einzige, was man nicht kaufen kann. Aevum zeigt, wie man sie investiert."

---

## Designprinzipien

1. **Visuell vor textlastig** — Diagramme, Zeitlinien, Heatmaps und Karten statt langer Listen
2. **Heute zuerst** — Der aktuelle Tag ist der Einstieg
3. **Automatisch, aber kontrollierbar** — Jede automatische Aktivität hat Edit/Confirm/Dismiss
4. **Reflexion ohne Schuldgefühl** — Keine aggressiven roten Warnungen für "schlechte" Tage
5. **Privacy sichtbar** — Nutzer versteht, welche Daten lokal genutzt werden
6. **Dark Mode First** — Light Mode ist vollständig, aber Dark ist der Premium-Modus
7. **Monospace für Zahlen** — Alle Metriken, Timer, Statistiken in JetBrains Mono

---

## Screen Patterns

### Dashboard (Startscreen nach Onboarding)

**Above the fold (wichtigster Bereich):**
- Hero: Begrüßung + Tagesstatus + aktuelle Aktivität
- 2-3 Primary Metric Cards (Zeitverteilung, Ziel, Streak)
- Alles auf einen Blick, kein Scroll nötig

**Below the fold (Scroll):**
- Timeline Mini-Preview (3 Items + Link)
- Ziele (Horizontal Scroll)
- Habits/Streaks (Heatmap Card)
- Lebensfortschritt (Life Grid)
- Bucket List Fortschritt
- Smartphone-Nutzung (Mini Chart)

### Timeline Screen

- **Header:** Datum + Zeitraum-Wahl (Heute/Woche/Monat)
- **Primärdarstellung:** visueller Tageskalender von 00:00–24:00 mit Zeitblöcken
- **Liste:** nur noch sekundär/ergänzend für kompakte Fälle, nicht als langfristiges Leitbild
- **Jedes Item:** Zeit + Connector + Titel + Kategorie + Status + Tags
- **Actions:** Tap → Detail/Edit, Long Press → Mehr-Actions
- **Empty State:** "Noch keine Aktivitäten. Tippe + um zu starten."

### Activity Editor ab M5.5

- Nutzer sieht nur eine sichtbare Hauptauswahl: **Aktivität**.
- Intern bleiben `activity_type` und `category` getrennt, damit Statistiken, Visualisierung und spätere Automatisierung sauber bleiben.
- Activity Type setzt eine Default-Kategorie; Kategorie-Verwaltung bleibt in Settings.
- Zeiteingabe kombiniert:
  - visuellen Tageszeitstrahl
  - Drag-Grobjustierung
  - ±h und ±15m Feineinstellung
  - spätere Trigger-Event-Snap-Marker

### Trigger Events

Trigger Events sind einzelne Zeitpunkte, keine Sessions. Beispiele:

- Zuhause verlassen / angekommen
- Arbeit betreten / verlassen
- Motorrad gestartet / beendet
- Fitnessstudio betreten / verlassen

Sie erscheinen später als Marker auf Zeitstrahlen und können beim Anlegen/Bearbeiten von Activities magnetisch als Start-/Endpunkt genutzt werden. M5.5 bereitet dieses Konzept architektonisch vor; vollständige Konfiguration folgt in späteren Meilensteinen.

### Insights/Statistics Screen

- **Tabs:** Heute / Woche / Monat / Jahr / Leben
- **Charts:** Zeitverteilung (Donut), Trends (Line), Heatmap, Life Grid
- **Interpretation:** Kurze Texte zu Trends ("Diese Woche 2h mehr Sport")
- **Vergleich:** Immer vs. Vorperiode

### Growth Screen (Ziele, Habits, Bucket List)

- **Tabs:** Ziele / Habits / Bucket List
- **Jeder Tab:** Übersicht + Fortschritt + "Neu hinzufügen"
- **Ziele:** ProgressRing + Deadline + Auto-Prüfung Status
- **Habits:** Heatmap + Streak Counter + Erfolgsquote
- **Bucket List:** Cards mit Bild, Progress, Status

### Settings Screen

- **Bereiche:** Struktur, Automatisierung, Datenquellen, Datenschutz & Daten
- **Struktur:** Kategorien, Activity Types, Tags
- **Automatisierung:** Geofences, Trigger Events, Zuhause, Arbeit, Activity Recognition
- **Datenquellen:** Schlaf, Smartphone-Nutzung
- **Datenschutz & Daten:** Datenschutz, Export, Backup
- **Dark/Light/System** Toggle prominent
- **Export/Backup** als Actions
- **Permission Status** übersichtlich

---

## Screen UX Review Gate

Vor der Implementierung jedes neuen Screens wird ein kurzer UX-Review durchgeführt. Leitfrage:

> „Wenn diese App morgen im Play Store erscheinen würde und mit den besten Produktivitäts-Apps konkurrieren müsste – wäre ich stolz auf diesen Screen?“

Wenn die Antwort nicht klar „ja“ ist, wird der Screen zuerst verbessert. Diese Prüfung umfasst:

- wichtigste Information innerhalb von 2 Sekunden sichtbar?
- unnötige Informationen entfernt?
- visuelle Darstellung statt Text, wo sinnvoll?
- Kartenanzahl und Scrolltiefe reduziert?
- Interaktionen intuitiv und eindeutig?
- sinnvolle Mikroanimationen statt dekorativer Bewegung?
- wirkt der Screen wie ein Premiumprodukt statt Standard-Material-App?

Qualität und Usability haben Vorrang vor schneller Implementierung.

---

## Activity Editing UX

Automatisch erkannte Aktivität (Candidate) zeigt:

```
┌─────────────────────────────────────┐
│ 🏃 Sport                    🟡 0.82 │
│ Gestern 18:30 – 19:45  (1h 15m)     │
│ Kategorie: Sport  🏷️ cardio         │
│ ─────────────────────────────────── │
│ [Bestätigen] [Bearbeiten] [Verwerfen]│
└─────────────────────────────────────┘
```

- **Farbe:** Kategorie-Farbe + Confidence Indikator
- **Bestätigen:** → CONFIRMED, zählt in Statistiken
- **Bearbeiten:** → Voller Editor (Titel, Zeit, Kategorie, Tags, Notiz)
- **Verwerfen:** → DISMISSED, bleibt in Raw Events, nicht in Statistiken

---

## Permission UX

Permissions werden **einzeln, kontextualisiert, optional** erklärt:

| Permission | Trigger | Erklärung |
|------------|---------|-----------|
| Location (Fine) | Places Setup | "Für Geofencing: Arbeit, Gym automatisch erkennen" |
| Location (Background) | Places Setup | "Damit Geofences auch im Hintergrund funktionieren" |
| Activity Recognition | Onboarding | "Für Autofahren, Gehen, Sport erkennen (batterieschonend)" |
| Usage Access | Settings > Smartphone | "Für App-Nutzungs-Statistiken (Sonderberechtigung)" |
| Health Connect | Settings > Schlaf | "Für präzise Schlafdaten (beste Quelle)" |
| Notifications | Goals/Habits | "Für Erinnerungen an Ziele & Gewohnheiten" |

**Regeln:**
- Niemals Permission-Dialog ohne vorherige Erklärung
- App ist **ohne jede Permission nutzbar** (manuelle Eingabe)
- Permission kann später in Settings nachgeholt werden
- Keine "Allow all oder nichts" — granular

---

## Empty States

### Prinzip: "Leer ≂ Kaputt"

Jeder Empty State enthält:
1. **Illustration/Icon** (96dp, dezent)
2. **Titel** — was fehlt
3. **Kurze Erklärung** — warum es leer ist
3. **Primary Action** — wie man es füllt
4. **Optional: Secondary** — Alternative

### Beispiele

| Screen | Empty State |
|--------|-------------|
| Timeline | "Noch keine Aktivitäten heute. Tippe + um deine Zeit zu erfassen." |
| Ziele | "Keine Ziele gesetzt. Ein Ziel hilft dir, fokussiert zu bleiben." |
| Habits | "Noch keine Gewohnheiten. Kleine tägliche Routinen machen den Unterschied." |
| Bucket List | "Deine Bucket List ist leer. Was möchtest du in diesem Leben erleben?" |
| Insights | "Nicht genug Daten für Statistiken. Erfasse Aktivitäten für erste Einblicke." |
| Schlaf | "Keine Schlafdaten. Verbinde Health Connect oder erfasse manuell." |

---

## Fehler & Loading

### Loading States

- **Skeleton Loading** für Cards/Listen/Charts (nicht Spinner)
- **Shimmer** 1200ms ease-in-out infinite
- **ProgressRing** für bekannte Dauer
- **Kein Fullscreen-Spinner** außer bei App-Start

### Fehler

- **Inline** (in Card/Banner) für partielle Fehler
- **Fullscreen** nur bei kritischem Fehler (DB corrupt, Permission permanent denied)
- **Immer:** Nutzerfreundlicher Text + Retry Action + "Später erinnern"

---

## Accessibility

| Anforderung | Umsetzung |
|-------------|-----------|
| Touch Targets | min 48dp × 48dp |
| Kontrast Text | WCAG AA (4.5:1) Dark/Light |
| Kontrast UI | WCAG AA (3:1) |
| Schrift-Skalierung | Unterstützt bis 200% (sp Einheiten) |
| TalkBack | semantics auf allen Custom Components |
| Farbenblindheit | Keine Info nur über Farbe (Icon + Label + Pattern) |
| Reduzierte Animation | `prefers-reduced-motion` respektiert |
| Screen Rotation | State erhalten, Layout anpasst |

---

## Responsive / Adaptive

| Breakpoint | Layout |
|------------|--------|
| < 360dp (Small Phone) | Single Column, kompakte Cards, Bottom Nav |
| 360–600dp (Phone) | Single Column, Standard Cards, Bottom Nav |
| 600–840dp (Large Phone/Foldable) | Two Column (Dashboard), Nav Rail optional |
| > 840dp (Tablet) | Two/Three Column, Nav Rail permanent, Sidebar |

### Navigation Patterns

| Gerät | Primary | Secondary |
|-------|---------|-----------|
| Phone | Bottom Navigation Bar | Modal Bottom Sheets |
| Foldable/Tablet | Navigation Rail | Permanent Sidebar |

---

## Micro-Interactions

| Trigger | Feedback |
|---------|----------|
| Button Press | Ripple (Material) + Haptic (light) |
| Card Tap | Subtle scale (0.98) + Ripple |
| Swipe Action | Spring animation, Haptic (medium) |
| Goal Complete | ProgressRing animate + Haptic (success) + Confetti (optional) |
| Streak Milestone | AnimatedCounter + Haptic (heavy) |
| Pull-to-Refresh | Spinner + Haptic (light) |
| Error Inline | Shake + Red accent + Haptic (error) |

---

## Content Guidelines

### Sprache

- **Deutsch** als Default
- **Du-Form** (persönlich, nicht förmlich)
- **Kurz, präzise, menschlich**
- **Keine Fachbegriffe** ohne Erklärung

### Texte in UI

| Element | Stil |
|---------|------|
| Screen Titles | Kurz, Nomen ("Dashboard", nicht "Dein Dashboard") |
| Button Labels | Verb + Objekt ("Ziel hinzufügen", nicht "Hinzufügen") |
| Empty States | Ermutigend, nicht beschuldigend |
| Errors | "Etwas ging schief. Versuche es erneut." |
| Tooltips | Max 2 Zeilen, verschwinden bei Tap |

### Zahlen & Datumsformate

- **Zeit:** 24h (18:30), Dauer: 1h 15m
- **Datum:** TT.MM.YYYY (17.07.2026)
- **Relative:** "Heute", "Gestern", "Vor 3 Tagen", "KW 29"
- **Metriken:** Monospace, 1 Dezimalstelle max (1.5h, nicht 1.50h)

---

## Design Token Usage (Compose)

```kotlin
// Farben
MaterialTheme.colorScheme.primary
MaterialTheme.colorScheme.onSurfaceVariant
AevumCategoryColors.work

// Typografie
MaterialTheme.typography.headlineMedium
MaterialTheme.typography.bodyLarge
AevumTypography.numbersMedium // Monospace

// Spacing
AevumSpacing.md // 16.dp
AevumSpacing.lg // 24.dp

// Radius
AevumRadius.md // 12.dp
AevumRadius.full // 9999.dp

// Shadows/Elevation
AevumElevation.card // Card elevation
AevumElevation.modal // Modal elevation
```

---

## Qualitätscheckliste pro Screen

Vor jedem Merge:

- [ ] Dark Mode vollständig getestet
- [ ] Light Mode vollständig getestet
- [ ] 360dp Breite ohne Überlappung/Clipping
- [ ] Touch Targets ≥ 48dp
- [ ] Keine Information **nur** über Farbe
- [ ] Alle Zahlen in Monospace (JetBrains Mono + tnum)
- [ ] Skeleton Loading für async Content
- [ ] Empty State definiert & getestet
- [ ] Error State definiert & getestet
- [ ] Content Description für TalkBack
- [ ] Prefers-reduced-motion respektiert
- [ ] Schrift-Skalierung 150% getestet
- [ ] Compose Preview für Light/Dark/FontScale

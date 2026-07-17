# DESIGN_SYSTEM — Aevum

## M3 Implementierungsstand

Das Design System ist in M3 als Compose-Basis implementiert. Zentrale Tokens liegen in `ui/theme/DesignTokens.kt`; wiederverwendbare Komponenten liegen unter `ui/components/`. Dashboard-Komponenten nutzen bewusst ruhige Flächen, subtile Borders, visuelle Metriken, Donut-/Ring-/Heatmap-Skeletons und reduzierte Texte.


## Design Philosophy

**Aevum** steht für Zeit, Leben, Bewusstsein und Entwicklung. Der Look soll **modern, ruhig, hochwertig und datenorientiert** wirken — eine Premium-App, die sich nicht wie eine Standard-Material-App anfühlt, sondern wie ein persönliches Lebenscockpit.

### Visuelle Identität

- **Futuristisch, aber ruhig** — keine Spielereien, keine überladenen Animationen
- **Daten-first** — Information wird visuell codiert, nicht textlastig erklärt
- **Dark Mode First** — der Hauptmodus ist dunkel, Light Mode ist vollständig, aber sekundär
- **Präzision** — enge Typografie, feine Borders, subtile Elevation
- **Ehrlichkeit** — keine künstlichen Gamification-Elemente, echte Datenvisualisierung

### Design-Prinzipien

1. **Visuell vor textlastig** — Diagramme, Zeitlinien, Heatmaps statt Listen
2. **Heute zuerst** — Der aktuelle Tag ist der Einstieg
3. **Automatisch, aber kontrollierbar** — Jede automatische Aktivität hat Edit/Confirm/Dismiss
4. **Reflexion ohne Schuldgefühl** — Keine aggressiven roten Warnungen
5. **Privacy sichtbar** — Nutzer versteht, welche Daten lokal genutzt werden

---

## Farbpalette

### Semantische Rollen

| Rolle | Dark | Light | Zweck |
|-------|------|-------|-------|
| **Primary** | `#8B7CFF` | `#6D5DF6` | Zeit, Fokus, Haupt-CTAs |
| **Secondary** | `#2DD4BF` | `#14B8A6` | Wachstum, Bewusstsein, positive Metriken |
| **Tertiary** | `#FBBF24` | `#F59E0B` | Fortschritt, Bucket List, Warnungen |
| **Background** | `#080A10` | `#F7F7FB` | App-Hintergrund |
| **Surface** | `#121521` | `#FFFFFF` | Karten, Sheets, Dialoge |
| **SurfaceVariant** | `#1B2030` | `#E0E2EC` | Sekundäre Flächen, Inputs |
| **OnBackground** | `#E6E6EB` | `#1A1C1E` | Primärer Text |
| **OnSurface** | `#E6E6EB` | `#1A1C1E` | Text auf Karten |
| **OnSurfaceVariant** | `#C4C6D6` | `#44474F` | Sekundärer Text, Labels |
| **Outline** | `#8E90A1` | `#74777F` | Borders, Divider |
| **OutlineVariant** | `#4A4C5D` | `#C4C7CF` | Subtile Borders |
| **Success** | `#4ADE80` | `#16A34A` | Erreicht, positiv |
| **Warning** | `#FBBF24` | `#F59E0B` | Hinweise, partielle Erfüllung |
| **Error** | `#F87171` | `#DC2626` | Fehler, kritisch |
| **Scrim** | `#000000` | `#000000` | Modal Overlays |

### Kategorie-Farben (fixe Zuordnung für Konsistenz)

| Kategorie | Dark | Light | Icon |
|-----------|------|-------|------|
| Arbeit | `#818CF8` | `#6366F1` | 💼 |
| Schlaf | `#475569` | `#334155` | 🌙 |
| Sport | `#4ADE80` | `#22C55E` | 🏃 |
| Lernen | `#38BDF8` | `#0EA5E9` | 📚 |
| Freizeit | `#FB923C` | `#F97316` | 🎮 |
| Beziehungen | `#F472B6` | `#EC4899` | ❤️ |
| Haushalt | `#C084FC` | `#A855F7` | 🏠 |
| Smartphone | `#94A3B8` | `#64748B` | 📱 |
| Autofahren | `#FBBF24` | `#F59E0B` | 🚗 |
| Mobilität | `#60A5FA` | `#3B82F6` | 🚌 |
| Essen | `#F87171` | `#EF4444` | 🍽️ |
| Gesundheit | `#34D399` | `#10B981` | 🏥 |

> **Hinweis:** Kategorie-Farben sind fix, nicht theming-abhängig. Sie sorgen für sofortige Wiedererkennung über alle Screens hinweg.

---

## Typografie

### Font Stack

- **Primary:** `Inter Variable` (via Google Fonts) — system-ui Fallback
- **Monospace:** `JetBrains Mono Variable` — für Zahlen, Timer, Code
- **System:** Android System Font für Body Text (Performance, Lesbarkeit)

> **Begründung:** Inter Variable bietet optisches Sizing, variable Weights (100-900) und ist auf Android via Downloadable Fonts verfügbar. JetBrains Mono für tabellarische Zahlen (tnum) und technische Anzeigen.

### Skala (Material 3 basiert, aber angepasst)

| Rolle | Size | Weight | Line Height | Letter Spacing | Verwendung |
|-------|------|--------|-------------|----------------|------------|
| **Display Large** | 57 sp | 400 | 1.06 | -0.25 | Hero, Life Grid Label |
| **Display Medium** | 45 sp | 400 | 1.10 | 0 | Section Hero |
| **Display Small** | 36 sp | 400 | 1.14 | 0 | Card Titles |
| **Headline Large** | 32 sp | 400 | 1.25 | 0 | Screen Titles |
| **Headline Medium** | 28 sp | 400 | 1.29 | 0 | Card Headers |
| **Headline Small** | 24 sp | 400 | 1.33 | 0 | Section Headers |
| **Title Large** | 22 sp | 500 | 1.27 | 0 | List Items |
| **Title Medium** | 16 sp | 500 | 1.50 | +0.15 | Button Text, Labels |
| **Title Small** | 14 sp | 500 | 1.43 | +0.1 | Chip Labels |
| **Body Large** | 16 sp | 400 | 1.50 | +0.5 | Primary Reading |
| **Body Medium** | 14 sp | 400 | 1.43 | +0.25 | Secondary Text |
| **Body Small** | 12 sp | 400 | 1.33 | +0.4 | Captions, Metadata |
| **Label Large** | 14 sp | 500 | 1.43 | +0.1 | Button, Input Labels |
| **Label Medium** | 12 sp | 500 | 1.33 | +0.5 | Small Labels |
| **Label Small** | 11 sp | 500 | 1.45 | +0.5 | Overline, Tags |
| **Numbers Large** | 48 sp | 600 | 1.00 | 0 | Statistic Hero (Monospace) |
| **Numbers Medium** | 32 sp | 500 | 1.10 | 0 | Card Metrics (Monospace) |
| **Numbers Small** | 20 sp | 500 | 1.20 | 0 | Inline Metrics (Monospace) |

### Typografische Prinzipien

- **Zahlen sind Monospace** — `fontFamily = "JetBrains Mono", fontFeatureSettings = "tnum"` für alle Metriken
- **Negative Tracking bei Display** — komprimierte Headlines für Autorität
- **Keine Gewichte über 600** — 600 ist Maximum, 400/500 sind Arbeitstiere
- **Body Text = System Font** — 16sp Minimum, besser lesbar auf kleinen Screens

---

## Spacing & Layout

### Base Unit: 4dp

| Token | Wert | Verwendung |
|-------|------|------------|
| `space-xxs` | 2dp | Micro-gaps, Inline |
| `space-xs` | 4dp | Icon-Text gaps |
| `space-sm` | 8dp | Inner Padding, kleine Gaps |
| `space-md` | 16dp | Standard Padding, Card Inset |
| `space-lg` | 24dp | Section Padding, Card Gaps |
| `space-xl` | 32dp | Large Section Padding |
| `space-xxl` | 48dp | Hero Sections, Screen Margins |
| `space-xxxl` | 64dp | Page-level Sections |

### Grid & Container

- **Max Content Width:** 1200dp (Tablet/Desktop)
- **Screen Horizontal Padding:** `space-md` (16dp) Mobile, `space-lg` (24dp) Tablet+
- **Card Internal Padding:** `space-md` (16dp)
- **Component Gap:** `space-sm` (8dp) eng, `space-md` (16dp) normal

---

## Border Radius

| Token | Wert | Verwendung |
|-------|------|------------|
| `radius-xs` | 4dp | Chips, Tags, kleine Buttons |
| `radius-sm` | 8dp | Buttons, Inputs, Chips |
| `radius-md` | 12dp | Cards, Dialoge, Sheets |
| `radius-lg` | 16dp | Feature Cards, Hero Cards |
| `radius-xl` | 24dp | Large Panels, Modal Sheets |
| `radius-full` | 9999dp | Pills, Avatar, FAB |

> **Philosophie:** Nur 6 Radius-Werte im gesamten System. Keine micro-rounding (2dp), keine willkürlichen Werte.

---

## Elevation & Shadows

### Dark Mode (Luminance Stacking)

Auf dunklen Oberflächen funktionieren klassische Shadows schlecht. Aevum nutzt **Luminance Stacking** — jede Erhöhung erhöht die Weiß-Opazität des Hintergrunds leicht.

| Level | Surface Color | Border | Verwendung |
|-------|---------------|--------|------------|
| **Level 0 (Base)** | `#080A10` | — | Screen Background |
| **Level 1 (Surface)** | `#121521` | `1px solid #4A4C5D` | Cards, Dialoge |
| **Level 2 (Elevated)** | `#1B2030` | `1px solid #8E90A1` | Floating Sheets, Modals |
| **Level 3 (Overlay)** | `#121521` + Scrim | — | Modal Overlays, Bottom Sheets |

### Light Mode (Traditional Shadows)

| Level | Shadow | Verwendung |
|-------|--------|------------|
| **Level 0** | — | Screen Background |
| **Level 1** | `0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06)` | Cards |
| **Level 2** | `0 4px 12px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06)` | Floating Sheets |
| **Level 3** | `0 12px 28px rgba(0,0,0,0.12), 0 4px 8px rgba(0,0,0,0.08)` | Modals, Dialogs |

---

## Komponenten-Spezifikationen

### AevumCard

```
Container:
  background: surface (Level 1)
  border: 1px solid outlineVariant
  borderRadius: radius-md (12dp)
  padding: space-md (16dp)

Variants:
  - elevated: Level 2 surface, outline border
  - filled: surfaceVariant background, no border
  - outlined: transparent bg, outline border
  - gradient: primary→secondary gradient border (Premium)
```

### StatisticCard (MetricCard)

```
Layout:
  - Icon (24dp) + Label (Label Small) + Value (Numbers Medium, Monospace)
  - Optional: Trend Indicator (Icon + Percent, Success/Warning)
  - Optional: Subtitle (Body Small, onSurfaceVariant)

Spacing:
  - Icon-Label gap: space-xs
  - Label-Value gap: space-xs
  - Value-Trend gap: space-sm
```

### ProgressRing

```
SVG/CircularProgressIndicator:
  - strokeWidth: 6dp (Medium), 8dp (Large), 4dp (Small)
  - trackColor: surfaceVariant
  - progressColor: primary / secondary / tertiary / category
  - gap: 2dp (für segmentierte Ringe)
  - animation: 600ms ease-out on mount
  - center: optional Label/Value (Numbers Small, Monospace)
```

### TimelineItem

```
Structure:
  - Time Column (48dp fixed): Time (Label Medium, Monospace) + Duration (Body Small)
  - Connector Line: 2dp, category color, dashed für Gaps
  - Content Column:
      * Title (Title Medium, category color icon)
      * Category Badge (Chip, radius-full, category bg)
      * Optional: Description (Body Medium)
      * Optional: Tags (Label Small chips)
  - Status Indicator:
      * CONFIRMED: solid dot
      * CANDIDATE: ring outline
      * DISMISSED: strikethrough, muted
```

### EmptyState

```
Layout (Centered):
  - Illustration/Icon (96dp, onSurfaceVariant 0.3)
  - Title (Headline Small, onSurface)
  - Message (Body Medium, onSurfaceVariant, max 2 lines)
  - Optional: Primary Action Button
  - Optional: Secondary Action (Text Button)
```

### LoadingState / Skeleton

```
Shimmer Animation:
  - Base: surfaceVariant
  - Highlight: surface (10% opacity white overlay)
  - Duration: 1200ms ease-in-out infinite
  - BorderRadius: matches target component

Variants:
  - Card Skeleton
  - List Item Skeleton
  - Chart Skeleton
  - Text Line Skeleton (3 lines varying width)
```

### ChartContainer

```
Wrapper für alle Visualisierungen:
  - AevumCard als Basis
  - Title (Title Medium) + Optional Subtitle + Optional TimeRange Selector
  - Content Area: Aspect Ratio 16:9 oder 4:3, min-height 200dp
  - Legend: Bottom oder Right, Label Small, onSurfaceVariant
  - Tooltip: surface Level 2, radius-sm, onSurface text
  - Empty: EmptyState mit "Keine Daten für Zeitraum"
```

---

## Animationen & Transitions

### Timing

| Typ | Dauer | Easing | Verwendung |
|-----|-------|--------|------------|
| `fast` | 120ms | ease-out | Button press, Chip select |
| `normal` | 200ms | ease-out | Screen transitions, Card expand |
| `slow` | 350ms | ease-in-out | Modal enter, Bottom sheet |
| `chart` | 600ms | ease-out-cubic | Chart mount, ProgressRing animate |
| `shimmer` | 1200ms | ease-in-out infinite | Skeleton loading |

### Prinzipien

- **Keine dekorativen Daueranimationen** — nur funktional
- **Respektiere `prefers-reduced-motion`** — alle Animationen deaktivierbar
- **Staggered Entrance** — Listenitems: 50ms Versatz pro Item, max 300ms Gesamt
- **Shared Element Transitions** — bei Detail-Navigation (später)

---

## Icon-Stil

- **Style:** Outlined (Material Symbols Outlined) — konsistent, nicht filled
- **Größen:** 16dp (inline), 20dp (UI), 24dp (Standard), 32dp (Hero), 48dp (Empty State)
- **Gewicht:** 400 (normal), 500 (emphasis)
- **Farbe:** `onSurface` / `onSurfaceVariant` / Kategorie-Farbe
- **Keine dekorativen Icons** — jedes Icon hat semantische Bedeutung

---

## Diagramm- / Visualisierungs-Stil

### Farb-Palette für Charts

Kategorien nutzen ihre fixen Farben. Für Sequenzen ohne Kategorien:

| Index | Farbe | Verwendung |
|-------|-------|------------|
| 0 | Primary | Hauptmetrik |
| 1 | Secondary | Wachstum |
| 2 | Tertiary | Fortschritt |
| 3 | `#6366F1` | Arbeit |
| 4 | `#22C55E` | Sport |
| 5 | `#F97316` | Freizeit |
| 6 | `#EC4899` | Beziehungen |
| 7 | `#A855F7` | Haushalt |

### Chart-Prinzipien

- **Keine 3D-Effekte, keine Schatten auf Datenelementen**
- **Grid Lines:** `outlineVariant` bei 20% opacity, nur horizontale
- **Axis Labels:** `Label Small`, `onSurfaceVariant`
- **Tooltips:** Dark Surface, `radius-sm`, Monospace Zahlen
- **Legende:** Interaktiv (Toggle Series), `Label Small`
- **Leere States:** Inline im Chart-Container, nicht separater Screen
- **Responsive:** Unter 360dp → Legende unten, horizontale Scroll bei Zeitachsen

---

## Visuelle Hierarchie (Screen Level)

### Dashboard (M3 Ziel)

```
1. Hero Section (Top)
   - Begrüßung + Tagesstatus (Display Small)
   - Aktuelle Aktivität (ProgressRing + Label)

2. Primary Metrics Row (2-3 Cards)
   - Heutige Zeitverteilung (Donut Chart Card)
   - Ziel-Fortschritt (ProgressRing Card)
   - Streak/Bucket Highlight (StatisticCard)

3. Secondary Section (Scroll)
   - Timeline Mini-Preview (3 Items + "Mehr")
   - Ziele (Horizontal Scroll Cards)
   - Habits/Streaks (Heatmap Card)

4. Tertiary (Bottom)
   - Lebensfortschritt (Life Grid Card)
   - Bucket List Fortschritt (ProgressRing)
   - Smartphone-Nutzung (Mini Chart)
```

### Wichtigkeit: **Information Density ≠ Clutter**

- Above-the-fold: max 3 Cards
- Touch Targets: min 48dp
- Text Contraste: WCAG AA mindestens (4.5:1)

---

## Compose Implementation Notes

### Theme Structure

```kotlin
// ui/theme/
Color.kt          // AevumDarkColorScheme, AevumLightColorScheme
Typography.kt     // AevumTypography
Shape.kt          // AevumShapes (RoundedCornerShape)
Theme.kt          // AevumTheme Composable
```

### Component Library

```
ui/components/
  AevumCard.kt
  StatisticCard.kt
  ProgressRing.kt
  TimelineItem.kt
  GoalCard.kt
  HabitCard.kt
  EmptyState.kt
  LoadingSkeleton.kt
  ChartContainer.kt
  CategoryChip.kt
  AnimatedCounter.kt
  TimeRangeSelector.kt
```

### Design Tokens als Kotlin Objects

```kotlin
object AevumSpacing { val xs = 4.dp, sm = 8.dp, md = 16.dp, ... }
object AevumRadius { val xs = 4.dp, sm = 8.dp, md = 12.dp, ... }
object AevumCategoryColors { val work = Color(0xFF6366F1), ... }
object AevumChartColors { val palette = listOf(Color(...), ...) }
```

---

## Accessibility

- **Touch Targets:** min 48dp × 48dp
- **Kontrast:** WCAG AA (4.5:1) für Text, 3:1 für UI Elements
- **Skalierbarkeit:** Unterstützt `fontScale` bis 200%
- **TalkBack:** Semantics auf allen Custom Components
- **Farbenblindheit:** Keine Info nur über Farbe, immer Icon/Label/Pattern
- **Reduced Motion:** `LocalContentAlpha` / `AnimatedVisibility` respektieren System-Setting

---

## Qualitätssicherung

- **Preview Compose** für jede Komponente (Light/Dark, verschiedene Größen)
- **Screenshot Tests** (später) für Regression
- **Design Review Checklist** pro Screen:
  - [ ] Dark/Light vollständig
  - [ ] 360dp Breite ohne Überlappung
  - [ ] Touch Targets ≥ 48dp
  - [ ] Kein Text-only-Farbe
  - [ ] Monospace Zahlen
  - [ ] Skeleton Loading für async Content
  - [ ] Empty/Error States definiert

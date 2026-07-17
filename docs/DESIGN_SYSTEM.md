# DESIGN_SYSTEM — Aevum

## Markenrichtung

**Aevum** steht für Zeit, Leben, Bewusstsein und Entwicklung. Der Look soll modern, ruhig, hochwertig und datenorientiert sein.

## Visuelle Metaphern

- Zeitringe
- Lebensraster
- ruhige Gradients
- Glas-/Surface-Karten sparsam
- warme Akzente für Leben/Fortschritt
- dunkler Premium-Modus als First-Class Citizen

## Farbkonzept

| Token | Hell | Dunkel | Zweck |
|---|---:|---:|---|
| `primary` | `#6D5DF6` | `#8B7CFF` | Zeit/Fokus |
| `secondary` | `#14B8A6` | `#2DD4BF` | Wachstum/Bewusstsein |
| `tertiary` | `#F59E0B` | `#FBBF24` | Fortschritt/Bucket |
| `background` | `#F7F7FB` | `#080A10` | App-Hintergrund |
| `surface` | `#FFFFFF` | `#121521` | Karten |
| `surfaceVariant` | `#EEF0F7` | `#1B2030` | Sekundärflächen |
| `success` | `#16A34A` | `#4ADE80` | Erreicht |
| `warning` | `#F59E0B` | `#FBBF24` | Hinweis |
| `error` | `#DC2626` | `#F87171` | Fehler |

## Kategorie-Farben

| Kategorie | Farbe |
|---|---:|
| Arbeit | `#6366F1` |
| Schlaf | `#334155` |
| Sport | `#22C55E` |
| Lernen | `#0EA5E9` |
| Freizeit | `#F97316` |
| Beziehungen | `#EC4899` |
| Haushalt | `#A855F7` |
| Smartphone | `#64748B` |
| Autofahren | `#F59E0B` |

## Typografie

- Material 3 Typography als Basis
- Zahlen/Statistiken groß und ruhig
- Headline: emotional, kurz
- Labels: präzise, nicht technisch

## Spacing

| Token | Wert |
|---|---:|
| xs | 4dp |
| sm | 8dp |
| md | 16dp |
| lg | 24dp |
| xl | 32dp |
| xxl | 48dp |

## Komponenten

- `AevumScaffold`
- `TimeDistributionRing`
- `LifeGrid`
- `DayTimeline`
- `HabitHeatmap`
- `ProgressRing`
- `InsightCard`
- `ActivitySessionCard`
- `EditableDetectionCard`
- `PermissionEducationCard`
- `BucketListCard`
- `GoalProgressCard`

## Motion

- sanfte Chart-Animationen beim Laden
- Timeline-Übergänge 150–250ms
- keine gamifizierte Überanimation
- Haptics bei Zielabschluss/Streak-Meilenstein optional

## Qualitätskriterien

- 360dp Breite ohne Überlappung
- Light/Dark vollständig
- Chart-Semantics für Screenreader
- Preview für jede Kernkomponente

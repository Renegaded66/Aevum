# DESIGN_SYSTEM

## Designrichtung

Basis: **Material Design 3**, erweitert um eine eigene Premium-Identität. Referenzästhetik aus geladenen Designsystemen:

- Linear/Superhuman: präzise, ruhige Dashboard-Ästhetik
- Apple/BMW: hochwertiger Weißraum, reduzierte Premium-Sprache
- Stripe/Revolut: hochwertige Gradients, klare Datenkarten

## Farb-Tokens

| Token | Hell | Dunkel | Zweck |
|---|---:|---:|---|
| `primary` | TBD | TBD | Hauptaktionen |
| `secondary` | TBD | TBD | Nebenakzente |
| `background` | `#FAFAFC` | `#0B0D12` | App-Hintergrund |
| `surface` | `#FFFFFF` | `#131722` | Karten/Sheets |
| `success` | `#16A34A` | `#4ADE80` | Erfolg |
| `warning` | `#F59E0B` | `#FBBF24` | Warnung |
| `error` | `#DC2626` | `#F87171` | Fehler |

## Typografie

- Android System Font / Material Typography als Basis
- klare Hierarchie: Display, Headline, Title, Body, Label
- keine Mini-Texte unter 12sp
- Zahlen/Statistiken mit stabiler Breite prüfen

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

- `AppScaffold`
- `PremiumCard`
- `MetricCard`
- `InsightCard`
- `PrimaryButton`
- `SecondaryButton`
- `AppTextField`
- `EmptyState`
- `ErrorState`
- `LoadingSkeleton`
- `ChartCard`
- `SettingsRow`

## Motion

- 150–300ms Transitions
- keine dauernd ablenkenden Animationen
- Skeleton Loading statt Spinner, wo sinnvoll
- Haptics nur für wichtige Bestätigung

## Qualitätskriterien

- kein überlappender Text bei 360dp Breite
- Light/Dark vollständig
- Preview Composables für Kernkomponenten
- zentrale Tokens statt roher Farben/Abstände

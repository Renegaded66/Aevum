# M31 — Interaktive Diagramme in den Insights

**Status:** Abgeschlossen
**App-Version:** 1.0.51 (versionCode 52)
**Datum:** 2026-10-25
**Commit:** `1603ab551bfae54f603f5597b8f8b3cf88860467`

## Auftrag

> „Bei Aevum bei Insights sind ja unten die drei Blöcke mit größter Block und
> Änderungen und so, die können alle weg. Stattdessen möchte ich noch weitere
> Diagramme haben die abhängig von der oberen Zeitraum Auswahl Daten anzeigen,
> gerne auch leicht interaktiv also wenn man drauf klickt dass das dann
> irgendwie mit Animation größer wird und ein kleines Textfeld den zugehörigen
> wert anzeigt, bspw ein Tortendiagramm aber gerne auch anderes.“

## Umsetzung

### 1. Entfernte Blöcke

Der Screen rendert nicht mehr:

- die Karte **„Veränderungen zur Vorperiode"** (`insights_changes_title`),
- die **Insight-Karten** („Größter Zeitblock", „Mehr sichtbar", „Digitalzeit",
  „Rhythmus", „Abwechslung").

Die Felder `changes` und `insightCards` bleiben im `InsightsUiState` und in
`InsightsAnalytics` bestehen — sie sind durch bestehende Tests abgedeckt und
werden von anderen Auswertungen genutzt. Der Insights-Screen liest sie nur
noch nicht. Dadurch ist die Entfernung rein visuell und kann ohne
Datenmigration zurückgenommen werden.

### 2. Drei neue Diagramme (alle periodenabhängig)

Alle drei Karten liegen unter der Top-Aktivitäten-Liste, also an der Stelle der
entfernten Blöcke. Sie lesen denselben `InsightsUiState`, den der ViewModel
beim Wechsel von Heute/Woche/Monat und beim Durchskippen (`‹`/`›`, Picker)
ohnehin neu baut — kein zusätzlicher Ladevorgang, kein Flackern.

#### Verteilung — Donut

- Kategorien des Zeitraums (inkl. Tagespauschalen, wie im Hero) als Donut.
- **Interaktion:** Tippen auf ein Segment **oder** auf die Legende wählt es
  aus. Das Segment fährt animiert nach außen, wird dicker und bekommt einen
  Glow; die Mitte wechselt von „Gesamt + Summe" auf Label, exakte Dauer und
  Prozent. Erneutes Tippen hebt die Auswahl auf.
- Segmente wachsen beim Erscheinen und bei jedem Periodenwechsel einmal von
  0° auf 360° (Eintritts-Animation).

#### Verlauf — Balken

- Auflösung folgt der Perioden-Auswahl:
  - **Heute:** 12 Blöcke à zwei Stunden (00:00–02:00 … 22:00–24:00),
  - **Woche:** sieben Kalendertage Mo–So,
  - **Monat:** jeder Kalendertag des Monats.
- **Interaktion:** Tippen auf einen Balken lässt ihn um 10 % wachsen, gibt ihm
  einen Glow und zeigt im Wertefeld über dem Diagramm Zeitspanne bzw. Datum +
  exakte Dauer. Null-Tage bleiben als kleiner Punkt sichtbar.
- Achsenbeschriftung wird je Datenmenge ausgedünnt (jede 2. Stunde, jeder
  5. Tag), damit 31 Monatstage nicht kollidieren.

#### Balance — Radar

- Fünf Lebensbereiche (Arbeit, Erholung, Bewegung, Digital, Soziales) als
  Radar mit drei Gitterringen.
- **Interaktion:** Tippen auf eine Achse oder den zugehörigen Chip wählt den
  Bereich aus. Punkt wächst animiert, Achse und Label werden hervorgehoben,
  das Wertefeld zeigt Bereich · Dauer · Anteil. Die Punkt­farben entsprechen
  der Legende, damit die Zuordnung nicht nur über die Position läuft.
- Der Glow des ausgewählten Punkts wird **vor** der Datenfläche gezeichnet.
  Grund: Bei kleinen Werten liegen benachbarte Punkte nah am Zentrum; ein
  nachträglicher Glow verdeckte die kurze Verbindungskante und das Fünfeck
  wirkte offen (im visuellen Check gefunden und behoben).

### 3. Datenmodell

Neuer, rein periodenabhängiger Typ in `InsightsAnalytics`:

```kotlin
data class TrendBucket(
    val label: String,      // Achse: "08" / "Mo" / "17"
    val detail: String,     // Wertefeld: "08:00–10:00 Uhr" / "Mo, 28. Okt."
    val durationMs: Long
)
```

`buildTrendBuckets` verteilt die aktive Dauer einer Session proportional zu
ihrer Wanduhr-Überlappung auf die Blöcke. Dadurch gilt:

- die Summe der Balken ist exakt die erfasste Zeit der Periode,
- Sessions über Mitternacht werden ohne Doppelzählung auf zwei Tage gesplittet,
- Pausen verfälschen die Verteilung nicht (die aktive Gesamtdauer wird
  verteilt, nicht pro Block neu gemessen).

Die kurzen Wochentagslabels kommen aus `PeriodLabels.weekdayShort`; die
Datumslabels aus dem bestehenden `PeriodLabels.dayLabel`, damit Deutsch und
Englisch dieselbe Formatierung wie Navigationszeile und Picker nutzen.

### 4. Technik

- Reine Compose-Canvas-Zeichnung, **keine neue Chart-Bibliothek**. Die App
  zeichnet bereits an mehreren Stellen mit Canvas (Timeline,
  QualityTrendCard, DigitalBalance); eine zusätzliche Abhängigkeit würde nur
  APK-Größe und Build-Risiko kaufen.
- Animationen über `Animatable` (Eintritt) und `animateFloatAsState` mit
  Spring (Auswahl), passend zum vorhandenen Glassmorphism-Stil.
- Jede Karte hat Test-Tags für Karte, Wertefeld und Legenden-/Achsen-Chips.

## Dateien

- `app/src/main/java/.../ui/screens/insights/InsightsCharts.kt` (neu, Donut +
  Balken + Radar)
- `app/src/main/java/.../ui/screens/insights/InsightsScreen.kt` (Blöcke
  entfernt, Diagramme eingebunden)
- `app/src/main/java/.../ui/screens/insights/InsightsAnalytics.kt`
  (`TrendBucket`, `buildTrendBuckets`, Fallback-Text)
- `app/src/main/java/.../ui/screens/insights/PeriodLabels.kt`
  (`weekdayShort`)
- `app/src/main/res/values/strings_weekly_insights.xml` (EN)
- `app/src/main/res/values-de/strings_weekly_insights.xml` (DE)
- `app/src/test/java/.../InsightsTrendBucketsTest.kt` (neu, 5 Tests)
- `app/src/test/java/.../InsightsChartsUiTest.kt` (neu, 4 Tests)
- `app/build.gradle.kts` — 1.0.51 / versionCode 52
- `docs/CHANGELOG.md`, `docs/ROADMAP.md`

## Verifikation

### Unit-Tests

```
:app:testDebugUnitTest
1426 Tests, 0 Fehler, 0 Errors, 1 skipped
```

Neu: 9 Tests.

`InsightsTrendBucketsTest` (5):

1. Heute → 12 Zwei-Stunden-Blöcke, Labels 00/04/08 …, Session 09–11 landet
   hälftig in Block 4 und 5.
2. Woche → 7 Tage ab Montag; Mittwoch-Session landet auf Index 2.
3. Monat → 31 Tagesblöcke; Session am 1. und 31. landen auf Index 0 und 30.
4. Session 23:00–01:00 wird je 1 h auf Mittwoch und Donnerstag verteilt, ohne
   Doppelzählung.
5. Leere Woche → sieben Null-Balken, `hasData = false`.

`InsightsChartsUiTest` (4):

1. „Veränderungen" und die Insight-Karten werden nicht mehr gerendert, obwohl
   der UiState sie noch trägt.
2. Alle drei Diagramme sind per Scroll erreichbar.
3. Donut zeigt standardmäßig die Gesamtsumme; Klick auf die Sport-Legende
   wechselt die Mitte auf „30m".
4. Klick auf den Radar-Chip „Arbeit" zeigt „1h 0m · 50 %".

### Visueller Check

Temporärer Robolectric-Screenshot-Test (400 × 900 dp, Dark Theme, NATIVE
Grafik), danach wieder entfernt — nicht Teil des Commits:

- Donut: kein Clipping, Mitte lesbar, Legendechips passen.
- Donut-Auswahl: Segment bleibt vollständig im Diagrammrahmen (Außenradius
  von 0,94 auf 0,86 reduziert, Kartenhöhe 200 → 210 dp).
- Balken: Hinweistext genau einmal, Achsenlabels 00/04/…/20 lesbar, kein
  Clipping.
- Radar: alle fünf Punkte zu einem geschlossenen Fünfeck verbunden, Punkte
  farblich identisch zur Legende.
- Doppelter Hinweistext unter Balken und Radar entfernt (das Wertefeld oben
  trägt den Hinweis bereits).

### Build

```
:app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 29s
```

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Größe: 125.272.984 Bytes (119,5 MiB)
- SHA-256: `2a008d758db504bed42c5a3bee3b2a33ea0e10777835bcff05dd4cbe45b09efb`
- `aapt dump badging`: `versionCode='52'`, `versionName='1.0.51-debug'`,
  `compileSdkVersion='36'`, `sdkVersion:'29'`
- `apksigner verify`: kanonischer Key
  `9c3055c4810b5cc2e548d8de8357344352fbe0e8` — Update ohne Deinstallation

### Was auf dem Gerät zu prüfen ist

Kein `adb`-Gerät und kein `/dev/kvm` — die Diagramme wurden nicht auf Hardware
bedient. Nach dem Update:

1. Insights öffnen, zwischen Heute / Woche / Monat wechseln: Donut, Verlauf
   und Radar müssen jeweils andere, zum Zeitraum passende Werte zeigen.
2. Mit `‹`/`›` einen vergangenen Tag/eine vergangene Woche/einen vergangenen
   Monat öffnen: alle drei Diagramme müssen mitwandern.
3. Donut-Segment und Legendechip antippen: Segment fährt heraus, Mitte zeigt
   Label + Dauer + Prozent.
4. Balken antippen: Balken wächst, Wertefeld zeigt Zeitspanne + Dauer.
5. Radar-Achse/-Chip antippen: Punkt wächst, Wertefeld zeigt Bereich + Dauer +
   Anteil.
6. Leerer Zeitraum: Es erscheint weiterhin die „Noch keine Daten"-Karte, keine
   leeren Diagramme.

## Bewusste Grenzen / Nicht Bestandteil

- **Keine Chart-Bibliothek** (Vico, MPAndroidChart o.ä.) — bewusst, siehe
  Technik. Kein Zoom, kein Pan, keine Achsen-Tooltips beim Ziehen.
- **Auswahl nicht persistiert:** Die Auswahl eines Segments/Balkens/Bereichs
  ist reiner UI-Zustand und wird beim Periodenwechsel oder erneuten Öffnen
  zurückgesetzt.
- **Balken zeigen erfasste Zeit, keine Pauschalen.** Pauschalen bleiben im
  Hero und im Donut sichtbar; im Verlauf würden sie als virtuelle Tageswerte
  die tatsächliche Verteilung verfälschen.
- **Radar ist relativ normiert** (größter Bereich = Außenring). Es gibt keine
  absolute Skala; die exakten Werte stehen im Wertefeld und in den Chips.
  Diese Darstellung ist bewusst qualitativ („Balance"), nicht als Ersatz für
  die Top-Liste.
- **Kein Geräte-Test:** siehe oben; Verifikation über 1426 Unit-/Robolectric-
  Tests plus temporäre Screenshots.

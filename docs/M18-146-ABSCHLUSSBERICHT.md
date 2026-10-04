# M18.146 — Abschlussbericht: Pauschalen-Bug, Zeitraum-Navigation, Aktivitäts-Statistikseite

**Datum:** 2026-10-04
**Version:** 1.0.19 (M18.136) → Arbeitstitel M18.146
**Status:** Implementiert, 1039/1039 Unit-Tests grün, APK signiert.

---

## 1. Der gemeldete Bug (Kern des Auftrags)

**Meldung:** „Wenn ich auf Heute klicke, wird es korrekt angezeigt. Bei
dieser Woche auch. Aber bei der Monatsansicht scheint er nicht diesen Monat
zu summieren, sondern stattdessen die letzten 30 Tage — andere Werte aus
anderen Quellen berücksichtigen nur diesen Monat."

### Diagnose

Der Fehler war ein **Abstraktions-Bug, kein Rechenfehler**: In der App
existierten ZWEI divergierende Perioden-Definitionen.

- `InsightsAnalytics.window()` rechnete für Sessions **kalendarisch**
  (Monat ab dem 1., Woche ab Montag).
- `InsightsViewModel.computePeriodRange()` rechnete NUR für die Tages-
  pauschalen **rollierend** (`anchorDate.minusDays(29)` = letzte 30 Tage).

Sichtbar wurde das als: Sessions (aus anderen Quellen) zählten den
Kalendermonat, die Pauschale (90 min/Tag „Essen") aber 30 Tage.

**Warum fiel es erst im Monat auf?** Am Tag der Meldung war Sonntag, der
4. Oktober. Bei einem Sonntag sind die rollierenden letzten 7 Tage
zufällig **identisch** mit der Kalenderwoche (Sonntag minus 6 Tage =
Montag). Die Wochenansicht war also nur durch eine Kalender-Koinzidenz
„zufällig richtig". An jedem anderen Wochentag wäre auch sie falsch
gewesen. Genau diese Koinzidenz versteckte den Bug und ist der Grund,
warum der Regressionstest den Gegenbeweis mit einem **Mittwoch** führt
(siehe `AllowanceCalendarPeriodTest.weekPeriodStartsOnMondayNotSixDaysAgo`).

### Fix (Verteidigung in der Tiefe, drei Schichten)

1. **EINE Quelle der Wahrheit:** `InsightsAnalytics.periodDates(period,
   anchorDate)` — kalendarische Grenzen (Monat: 1. bis Ultimo; Woche:
   Montag bis Sonntag). `window()` wurde darauf refactored;
   `computePeriodRange()` wurde **entfernt**.
2. **ViewModel nutzt periodDates:** Die Pauschalen-Abfrage beim Repository
   und die On-the-fly-Accumulation nutzen dieselbe Definition wie die
   Sessions. Ein Integrationstest liest die **tatsächlich übergebene
   Range** ab (`monthPeriodRequestsTheCalendarMonthFromTheAllowanceRepository`).
3. **Analytik begrenzt selbst (neu):** `InsightsAnalytics.build` filtert
   die Accumulations jetzt SELBST auf das gezeigte Fenster
   (`windowAccumulations`). Selbst wenn ein künftiger Aufrufer eine
   breitere Range übergibt, kann keine Pauschale aus einem anderen Monat
   mehr in die Summe geraten. Der Bug-Typ „Aufrufer und Analytik sind sich
   über den Zeitraum uneinig" ist damit strukturell ausgeschlossen.

### Nebenfund + Fix

Bei den Regressionstests fiel eine **zweite** Unstimmigkeit auf:
`InsightsAnalytics.hasData` prüfte `sessions.isNotEmpty()` **ohne
Fensterbezug**. Eine Aktivität, die nur ältere Aufzeichnungen außerhalb
des Fensters hatte, zeigte damit einen Graphen voller Nullen statt des
Leerzustands. Jetzt: `hasData = windowTotal > 0` — „Daten vorhanden"
heißt „Zeit im gezeigten Fenster".

---

## 2. Zeitraum-Navigation („durchskippen")

**Anforderung:** „Etwas zum Aufklappen oder ein Pop-up, wo ich durch die
Wochen (mit Datum und Wochennummer) und Monate und Tage durchskippen kann."

**Umsetzung:**
- **Navigationszeile** unter dem Period-Toggle: `‹ KW 40 · 28. Sep. – 4. Okt. ›`
  — der konkrete Zeitraum statt nur „Diese Woche". Das Label ist klickbar
  und öffnet das **Picker-Sheet**; die Pfeile skippen eine Periode weiter.
- **Picker-Sheet** (`PeriodPickerSheet`): die letzten 12 Perioden der
  gewählten Granularität, neueste zuerst — Wochen **mit Datum und
  Wochennummer** (ISO-Kalenderwoche, dieselbe Zählung wie die Aggregation),
  Tage mit Wochentag+Datum („Heute"/„Gestern" als Sonderfall), Monate als
  „Oktober 2026". Die gezeigte Periode trägt die Auswahlmarkierung, die
  aktuelle Periode ein „jetzt".
- **„Zurück zu jetzt"**-Chip erscheint, sobald man in der Vergangenheit ist.
- **Warum getrennt vom Period-Toggle:** Der Toggle wählt die Granularität,
  die Navigationszeile BEWEGT sich darin. Zwei getrennte Affordanzen —
  sonst wäre unklar, ob ein Klick auf „Woche" umschaltet oder weiterblättert.
- **Beim Granularitätswechsel springt der Anker auf heute** — sonst würde
  ein Mai-Anker nach dem Wechsel auf „Woche" plötzlich eine Mai-Woche
  zeigen, obwohl der Nutzer „jetzt" erwartet.

---

## 3. Detail-Statistikseite pro Aktivität („fancy graph")

**Anforderung:** komplett neue Detailseite für jede Aktivität, „fancy
Graphen, nicht Standard Android", Umschalter täglich/wöchentlich/monatlich,
Finger auf dem Graphen halten für Details, hochwertig und aufschlussreich,
ohne viele Wörter, DE+EN, erreichbar aus Insights, aus der
Aufzeichnungs-Detailseite und aus den Aktivitätstypen-Einstellungen.

**Umsetzung (`activitystats/`):**

- **Der Verlaufs-Graph** (Canvas, kein Standard-Chart-Paket):
  - geglättete Bezier-Linie mit horizontalem Farbverlauf in der
    Aktivitätsfarbe, doppelt gezeichnet (weicher Glow + Kernlinie),
  - Fläche darunter als vertikaler Gradient, **animiertes Einwachsen**
    beim Öffnen (echter 0→1-Lauf via `Animatable` — `animateFloatAsState`
    hätte auf dem Zielwert gestartet und wäre unsichtbar geblieben),
  - Punkte je Bucket, Gegenwarts-Punkt mit Ring-Marker,
  - dezente Hilfslinien, Achsenbeschriftung nur erstes/letztes Label
    („ohne viele Wörter").
- **Finger-Scrubber:** Auflegen oder Halten zeigt eine Scrubber-Linie plus
  Karte mit Datum (voll ausgeschrieben), Dauer und Anzahl Aufzeichnungen;
  haptisches Feedback bei Positionswechsel. Bewusst reagiert es schon auf
  das **Auflegen** (`awaitFirstDown`), nicht erst nach einer Drag-Schwelle —
  „Finger halten" muss ohne Bewegung funktionieren.
- **Umschalter täglich/wöchentlich/monatlich** (Glas-Segmented-Control) —
  Fenster: 30 Tage / 12 Kalenderwochen / 12 Kalendermonate, persistiert.
- **EINE Tagesbasis** in `ActivityStatsAnalytics`: Alle drei Sichten
  bucketieren dieselben Tageswerte — die drei Sichten können per
  Konstruktion nicht auseinanderlaufen (Lehre aus dem Pauschalen-Bug).
- **Kennzahlen** (2×3, spracharm): Gesamt, Ø/Tag, Aufzeichnungen, Längste,
  aktive Tage, beste Serie; **Wochenmuster** (7 Mini-Balken, stärkster Tag
  leuchtet in Aktivitätsfarbe); **Trend** ±% gegenüber dem gleich langen
  Zeitraum davor (entfällt ohne Vergleichsbasis — „+∞ %" wäre irreführend).
- **Leerzustand** statt leerem Chart, wenn die Aktivität im Fenster keine
  Aufzeichnungen hat.

**Bewusste Abgrenzungen („Nicht-Bestandteile"):**
- **Keine Digital-Balance** auf dieser Seite: Die Bildschirmzeit hat mit
  dem Balance-Tab eine eigene Heimat; eine UsageStats-Abfrage über Jahre
  wäre teuer und würde die Seite verlangsamen.
- **Keine Bearbeitung** auf der Statistikseite — sie ist eine Ansicht,
  die Aufzeichnungs-Detailseite bleibt der Bearbeitungsort.
- **Kein zweiter Fetch-Pfad:** Alles ist reaktiv über Room (wie die
  übrigen Statistik-Sichten).

**Die drei Einstiegspunkte (alle verdrahtet):**
1. **Insights** → Klick auf eine Aktivitäts-Zeile der Top-Liste. Nur
   Zeilen mit echtem ActivityType verlinken (Kategorie-Ansicht und
   Titel-Fallbacks ausgenommen — sie würden auf eine leere Seite führen).
2. **Timeline → Aufzeichnung → Detailseite** → Knopf „Verlauf ansehen"
   (nur wenn die Aufzeichnung einer Aktivität zugeordnet ist).
3. **Einstellungen → Aktivitätstypen** → dezente Zeile „∿ Verlauf ansehen ›"
   je Aktivität.

---

## 4. Was NICHT umgesetzt wurde (bewusste Abgrenzung)

- **Kein Zoom/Pinch im Graphen:** Der Scrubber deckt „Details zur Position"
  ab; Pinch-Zoom wäre auf 30–365 Tage Daten ohne Mehrwert und deutlich
  mehr Zustand (Zoom-Fenster × Scrubber × Achsenbeschriftung).
- **Keine Wochen-/Monats-Navigation INNERHALB der Aktivitäts-Statistik:**
  Die Seite zeigt je Granularität ein festes Fenster (letzte 30 Tage / 12
  Wochen / 12 Monate). Die Durchskipp-Navigation aus dem Auftrag bezieht
  sich auf die Insights-Seite und ist dort umgesetzt. Nachtrag möglich.
- **Kein Export/Teilen des Graphen** — nicht gefordert, eigenes Feature.

---

## 5. Verifikation

- **1039 Unit-Tests, 0 Fehler** (davon neu: `AllowanceCalendarPeriodTest`
  7, `ActivityStatsAnalyticsTest` 19, `ActivityStatsScreenTest` 5, plus 2
  neue Integrationstests in `InsightsWiringIntegrationTest`).
- Die neuen Tests sind als Regressionstests konstruiert: Sie schlagen
  fehl, wenn `minusDays(29)` oder `minusDays(6)` zurückkehren, und der
  Wochen-Gegenbeweis nutzt bewusst einen Mittwoch (am Sonntag hätte die
  Koinzidenz den Bug versteckt).
- Debug-APK signiert mit kanonischem Key (`9c3055c4…`), Nachweis im
  Build-Log.

---

## 6. Reflexion: Warum diese Entscheidungen?

**Hinterfrage-Pass (Standing Order: autonom, keine Zwischenfragen —
daher hier schriftlich):**

1. **Warum Fix an der Wurzel statt Sonderfall?** Die naive Reparatur wäre
   gewesen, `minusDays(29)` auf „Anfang des Monats" zu korrigieren. Das
   hätte den sichtbaren Fehler behoben, aber die ZWEITE Definition am
   Leben gelassen — der nächste Verbraucher (z. B. ein künftiges Feature)
   hätte wieder eine eigene Perioden-Rechnung eingebaut. Die Reparatur
   entfernt die zweite Definition komplett und legt eine einzige
   kalendarische Quelle an, die alle teilen.

2. **Warum Verteidigung in der Tiefe in der Analytik?** Nach dem Fix war
   der Bug bereits weg. Die zusätzliche Fenster-Begrenzung in
   `InsightsAnalytics.build` schützt gegen die WIEDERKEHR des Bug-Typs:
   Wenn morgen jemand eine breitere Accumulation-Range übergibt (etwa für
   einen künftigen Trend-über-Jahre), kann die Summe trotzdem nicht mehr
   falsch werden. Kosten: ein `filter` — Nutzen: die Fehlerklasse ist
   strukturell ausgeschlossen.

3. **Warum Canvas statt Chart-Bibliothek?** „Nicht so Standard Android
   Graphen" — eine Bibliothek hätte eine dritte Abhängigkeit, eigene
   Theming-Arbeit und würde die App-Größe erhöhen. Der Canvas-Graph ist
   ~200 Zeilen, nutzt die Aktivitätsfarbe direkt und liefert den
   Glow/Gradient-Look, den Standard-Charts nicht können. Der Scrubber
   greift auf dieselben Bucket-Daten zu — keine Parallel-Datenstruktur.

4. **Warum eine Tagesbasis?** Die teuerste Lektion des Bugs: Zwei
   Rechenwege für „denselben" Zeitraum laufen früher oder später
   auseinander. Die Aktivitäts-Statistik rechnet die Tageswerte EINMAL
   und bucketiert nur — Täglich/Wöchentlich/Monatlich können nicht
   widersprechen.

5. **Was wurde bewusst weggelassen?** Digital-Balance auf der neuen Seite
   (eigene Heimat + teure Abfrage), Zoom (kein Mehrwert bei 30–365
   Punkten), Bearbeitung (gehört auf die Detailseite). Diese Liste ist
   wichtiger als die Feature-Liste: sie ist das, was der Nutzer
   nachträglich einfordern oder ablehnen kann.

---

## 7. Geänderte Dateien

**Neu:**
- `ui/screens/insights/PeriodLabels.kt` — Datums-/Wochen-/Monatslabels,
  Picker-Items, `normalize()` (DE/EN, ohne Context testbar)
- `ui/screens/activitystats/ActivityStatsAnalytics.kt` — Buckets, Stats,
  Wochenmuster, Trend (pure, testbar)
- `ui/screens/activitystats/ActivityStatsViewModel.kt` — reaktiv über Room,
  Sprach-Flow angebunden, Granularität persistiert
- `ui/screens/activitystats/ActivityStatsScreen.kt` — Fancy-Chart + Karten
- Tests: `AllowanceCalendarPeriodTest.kt`, `ActivityStatsAnalyticsTest.kt`,
  `ActivityStatsScreenTest.kt`

**Geändert:**
- `insights/InsightsAnalytics.kt` — `periodDates()` (EINE Quelle),
  Fenster-Begrenzung der Accumulations, `knownActivityTypeIds`
- `insights/InsightsViewModel.kt` — navigierbarer Anker, Navigation
  (step/set/reset), Pauschalen auf `periodDates`
- `insights/InsightsScreen.kt` — Navigationszeile, Picker-Sheet,
  klickbare Top-Liste
- `insights/TopActivitiesToggle.kt` — Zeilen-Klick (Aktivitätsmodus)
- `navigation/AppDestination.kt` + `AppNavHost.kt` — Route `stats/{id}`,
  alle drei Einstiegspunkte verdrahtet
- `timeline/TimelineScreen.kt` — „Verlauf ansehen" in der Detailseite
- `activitytypes/ActivityTypesScreen.kt` — Verlauf-Link je Aktivität
- Strings DE/EN (`strings_weekly_insights.xml`)
- `InsightsWiringIntegrationTest.kt` — 2 Regressionstests (Range-Nachweis)

# M18.31–M18.35 — Todos-FAB, Timeline-Usability, Pauschalen-Fix, Insights-Persistenz, Lebenszeit-Ansicht

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## 1. M18.31 — Todos-FAB (User-Report: "keine Buttons zum Hinzufügen")

**Problem:** Der TodosScreen hatte keinen sichtbaren Weg, ein Todo anzulegen (nur Hero-Text).
**Fix:** Scaffold + `FloatingActionButton` (Plus-Icon) unten rechts, wie in allen anderen Listen-Screens. Bottom-Padding 96dp, damit der FAB nichts überdeckt.

## 2. M18.32 — Timeline-Usability (User-Report)

**a) Listenansicht: weiter scrollen können**
- Bottom-Padding (88dp) in der Timeline-Column — der FAB überdeckt keine Inhalte mehr, man kann bis zum letzten Eintrag scrollen.

**b) Tag-Ansicht: obere Elemente kompakter**
- Header: eine Zeile statt zwei, Titel 22sp statt 30sp, "Heute"/"Lücken prüfen" als kompakte Chips statt großer OutlinedButtons, kleinere IconButtons (36dp)
- Summary: kleinere Werte (17sp), `SpaceEvenly`, weniger vertikales Padding
- Ergebnis: die Timeline bekommt deutlich mehr Platz nach oben

**c) Schönere Timeline-Elemente (kreativ)**
- `EventListRow`: horizontaler Farbverlauf (Akzent → transparent) statt flacher Fläche — jede Zeile wirkt wie eine kleine Karte mit Tiefe
- **Dauer-Chip** rechts (Monospace, in Aktivitätsfarbe) — die Dauer war vorher nur im Detail-Text versteckt, jetzt sofort lesbar

## 3. M18.33 — Tagespauschalen erscheinen SOFORT (User-Report)

**Problem (Root Cause):** Der `MidnightAllowanceWorker` schreibt Accumulations NUR um 00:05 für den **Vortag**. Eine heute erstellte Pauschale erschien erst am nächsten Tag — weder im Dashboard noch in den Insights.

**Fix (on-the-fly, idempotent):**
- **Dashboard:** `DailyAllowanceRepository` injiziert, enabled Allowances werden direkt in `buildState()` eingerechnet → `totalTracked`, `openTime`, Narrative und Insights zeigen die Pauschalen SOFORT.
- **Insights:** Zusätzlich zu den Accumulations werden enabled Allowances für alle Tage des Zeitraums on-the-fly ergänzt (PK-Check gegen existierende Accumulations → kein Doppel-Zählen).

## 4. M18.34 — Insights merkt sich die Period-Auswahl (User-Report)

- `_selectedPeriod` wird in SharedPreferences persistiert (`aevum_insights` / `selected_period`)
- **Default: Today** (User-Präferenz: "ich präferiere die Heute-Ansicht")
- `InsightPeriod.fromStorage()` in der Enum selbst (companion object)

## 5. M18.35 — Lebenszeit-Ansicht (LifeView) — NEUE SEITE

**Einstieg:** Insights → Hero → dezenter Chip "⏳ Lebenszeit — Wie viel Zeit bleibt dir wirklich?"

**Konzept (reflektiert):** "Angst machen" — aber mit Zahlen und Grafiken, nicht mit Worten.

- **Geburtstag-Eingabe** (Material DatePicker) + **erwartetes Alter** (Slider 60–100, Default 80) — beides in SharedPreferences persistiert
- **LIFE CALENDAR** (die umhauende Grafik): 1 Zelle = 1 Monat, 24 Spalten pro Zeile. Verbrauchte Monate leuchten im Verlauf Grün (Kindheit) → Gelb → Rot (heute), verbleibende sind dunkel. 80 Jahre = 960 Monate auf einen Blick.
- **Countdown:** "X Jahre, Y Tage" bis zum 80. Geburtstag (40sp Monospace, Tertiär-Farbe)
- **Gestapelter Lebenszeit-Balken:** Schlaf / Autofahren / Pauschalen / Erfasst / Frei — hochgerechnet aus den letzten 14 Tagen auf die verbleibenden Jahre
  - Schlaf: Durchschnitt NUR der Tage MIT Schlaf-Daten (≥3 Tage, sonst Default 8h — verhindert Verzerrung durch schlaflose Nächte)
  - Autofahren: echter Tagesdurchschnitt (0-Tage zählen — man fährt nicht jeden Tag)
  - Pauschalen: enabled Allowances × Minuten/Tag
- **Aktivitäten-Details:** Top 6 nach Lebensjahren, animierte Bars (800ms), min/Tag
- **Wake-Up-Call-Karte:** "Du wirst etwa X Jahre deines restlichen Lebens schlafen. Das sind Y% der Zeit, die dir noch bleibt."

**Dateien:** `ui/screens/lifeview/{LifeViewScreen,LifeViewViewModel}.kt`, `AppDestination.LifeView`, NavHost-Route, InsightsScreen-Chip.

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (59s)
- Commits: `48426e3` (M18.31–34), `e8eaed3` (M18.35)

## Test-Anleitung
1. **Todos:** Plus-Button unten rechts → Editor öffnet sich
2. **Timeline:** Listenansicht → bis ganz nach unten scrollen (FAB überdeckt nichts); Tag-Ansicht → mehr Platz für die Timeline; Zeilen haben Farbverlauf + Dauer-Chip
3. **Pauschalen:** Neue Pauschale erstellen → Dashboard "Erfasst" steigt SOFORT; Insights (Heute) zeigt sie ebenfalls
4. **Insights:** Auf "Woche" klicken → App schließen → wieder öffnen → "Woche" ist noch ausgewählt; beim ersten Start ist "Heute" aktiv
5. **Lebenszeit:** Insights → Chip → Geburtstag eingeben → Life Calendar + Countdown + Breakdown ansehen; Alter-Slider testen

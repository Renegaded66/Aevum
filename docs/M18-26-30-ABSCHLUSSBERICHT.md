# M18.26–M18.30 — Timeline-Fix, Walking-Fix, Kalender, Pauschalen, Todos

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**DB:** v19 (Migration 18_19)
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (110 MB) — **BUILD SUCCESSFUL**

---

## 1. M18.26 — Timeline "Tag"-Ansicht: Scroll-Konflikt BEHOBEN + Redesign

**Problem (User-Report):** "Beim Scrollen scrollt mal die Timeline, mal das Fragment."
**Ursache (bewiesen):** Äußere `LazyColumn` + innere `verticalScroll` (fixe 560dp) = klassischer Nested-Scroll-Konflikt.

**Fix:**
- Äußere LazyColumn **komplett entfernt** → `Column` mit fixem Header/Summary und `weight(1f)`-Viewport
- `DayCalendarTimeline` füllt den ganzen verbleibenden Platz — es gibt **NUR NOCH EINEN** Scroll-Container (die Timeline selbst)
- Scrollbar-Thumb misst die Viewport-Höhe jetzt dynamisch (`onSizeChanged`) statt hart 520dp

**Tag-Ansicht-Redesign (Google-Calendar-Prinzip):**
- Tagesabschnitt-Tönung: Nacht = tiefblau, Morgen = warmes Gelb, Mittag = hell, Abend = Orange-Rot (sanfter vertikaler Farbverlauf im Canvas)
- Stärkere Stundenlinien alle 3h statt 6h

## 2. M18.27 — Walking-False-Positives GEFIXT (Raumwechsel)

**Problem:** Google meldet WALKING/RUNNING bei Raumwechseln drinnen (Wohnzimmer→Küche) mit 50% Confidence.

**Fix (hinterfragt & präzise):**
- GPS-Check im TriggerWorker: User in bekannter Geofence + WALKING/RUNNING → Trigger **verworfen** (kein Raw-Event, kein Timeline-Marker)
- **WICHTIG:** Suppression nur bei EXIT-Transitions oder Geofence OHNE Auto-Start — ein WALKING-ENTER beim Ankommen in der Arbeit blockiert den Geofence-Auto-Start **NICHT** (der Check steht NACH dem Geofence-Handling)
- Confidence 0.5 → 0.65 (Google liefert keine echte Confidence; 0.5 war willkürlicher Platzhalter)

## 3. M18.28 — NEUER KALENDER-TAB (Heatmap der Zeitqualität)

**5. Bottom-Tab "Kalender" (▦):**
- Monatsgrid als **Heatmap**: Jeder Tag farbcodiert nach gewichteter Positivität (Dauer × Score über den Tag) — rot = negativ, gelb = neutral, grün = positiv, grau = leer
- Eigene Compose-Pill-Zellen (kein Standard-Widget), animierte Heat-Bars
- Monatswechsel mit `AnimatedContent` (Fade)
- Tag antippen → Detail-Panel: **Mini-24h-Timeline** mit farbigen Aktivitätsbalken (`BoxWithConstraints`, bruchteil-genau) + Session-Liste (Icon, Farbe, Dauer)
- Tap auf Session → Detail-Screen
- Mitternacht-sicheres Clipping (Schlaf über 2 Tage)
- Legende, Erfasst-Summe, Heute-Button, Score-Chip pro Tag

**Neu:** `ui/screens/calendar/{CalendarScreen,CalendarViewModel}.kt`, `AppDestination.Calendar`

## 4. M18.29 — Tagespauschalen-Upgrade

- **Edit-Funktion**: Stift-Icon öffnet denselben Dialog vorbefüllt (`update()` via REPLACE)
- Karten: Aktivitäts-Icon im farbigen Kreis + Akzentbalken + Minuten-Chip (Monospace)
- ActivityType-Picker: LazyColumn mit Icon + Farbe + Check-Mark
- Ein Dialog für Neu UND Edit

## 5. M18.30 — TODOS (komplett neu, DB v19)

**Zwei Typen:**
- **Checkbox:** "Heute Müll rausbringen" — einfach fertig/nicht-fertig
- **Dauer-Ziel:** "Heute 2 Stunden lernen" — **automatisch abgehakt**, sobald die zugeordnete Aktivität die Ziel-Dauer heute erfasst hat (inkl. laufender Session)

**Recurrence-System (RecurrenceEngine, durchdacht):**
| Typ | Verhalten |
|---|---|
| ONCE | Einmalig; mit Fälligkeitsdatum nur an dem Tag, ohne Datum relevant bis erledigt → dann archiviert |
| DAILY | Jeden Tag |
| WEEKDAYS | Mo–Fr |
| WEEKLY_ON | Bestimmte Wochentage (Bitmask) |
| EVERY_N_DAYS | Alle x Tage (ab Startdatum) |
| N_PER_WEEK | x-mal pro Woche — flexibel, Quote zählt |
| N_PER_MONTH | x-mal pro Monat — flexibel |

**Fancy UI:**
- Animierter farbiger Fortschrittsbalken (`animateFloatAsState`, 600ms)
- Custom-Checkbox-Kreis in Aktivitätsfarbe, Durchstreichen bei Done
- Recurrence-Chips, Icon + Farbe der Aktivität, Erfasst/Ziel-Anzeige (Monospace)
- Archiv-Sektion (aufklappbar), Hero mit Fortschritts-Mini-Bar

**DB v19:** `todo` + `todo_completion` Tabellen (Migration 18_19, CREATE TABLE — kein Rebuild). DAO, Repository, Hilt-Provider, Navigation (`todos`, `todo/new`), Settings > Erweitert verlinkt.

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL**
- Alle 5 Commits: `cba09e8` (M18.26), `170144c` (M18.27), `4cd6c7f` (M18.28), `e151366` (M18.29), `0cce9d5` (M18.30)

## Test-Anleitung
1. **Timeline:** Tag-Ansicht öffnen → Scrollen scrollt JETZT IMMER nur die Timeline, nie das Fragment
2. **Walking:** Zuhause Raum wechseln → kein "Walking 65%"-Trigger mehr in der Timeline; Arbeit betreten → Auto-Start funktioniert weiterhin
3. **Kalender:** Neuer Tab → Monats-Heatmap, Tag antippen → Balken + Liste, Monat wechseln
4. **Pauschalen:** Settings > Erweitert > Tagespauschalen → Edit-Button, Icon/Farbe sichtbar
5. **Todos:** Settings > Erweitert > Todos → "2h lernen" mit Aktivität Lernen anlegen → 2h Lernen tracken → Todo hakt sich automatisch ab; "Müll" als ONCE → abhaken → verschwindet aus "Heute"

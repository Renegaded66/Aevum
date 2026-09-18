# M18.131 — Timeline-Fix, Limit-Vorwarnung, Termin-Auswahl

**Datum:** 18.09.2026
**DB-Version:** 41 → **42** (`calendar_event_pin`)
**Tests:** 691 grün (neu: 4 Testklassen)

Drei Aufträge, davon zwei mit gefundenen Alt-Fehlern in der Ursache.

---

## 1. Timeline: tagesübergreifende Aufzeichnung im Starttag

### Symptom
Eine Aufzeichnung, die über Mitternacht geht (z. B. Schlaf 23:00–07:00), erschien
im Starttag nur als **einminütiger Strich am Startzeitpunkt**. Der volle Eintrag war
erst ab dem Folgetag 00:00 sichtbar.

### Root Cause
`TimelineViewModels.buildTimelineState()` rechnete die Block-Geometrie mit
`TimeFormatting.minutesOfDay()`. Diese Funktion liefert die **Uhrzeit im Tag**
(0..1439), nicht den Offset ab Tagesbeginn. Für das auf `dayEnd` geclippte Ende
einer Mitternachts-Session ist dieser Zeitpunkt exakt **00:00 des Folgetags → 0
Minuten**. Der Renderer liest `endMinuteOfDay <= 0` als „ungültig" und zeichnet
`startMinuteOfDay + 1`:

```
23:00–24:00  →  startMin=1380, endMin=0  →  renderer: 1380..1381  = 1-Minuten-Strich
```

Am Folgetag trat der Fehler nicht auf, weil dort `clippedStart = 0` UND ein echtes
Ende in der Zukunft liegt — deshalb war nur der Starttag betroffen. Geplante
Kalender-Blöcke waren nie betroffen, weil `PlannedSessions.kt` schon immer den
Offset ab Tagesbeginn rechnet.

### Fix
Neue reine Funktion `clippedMinuteOffset(millis, dayStartMs)` in
`ui/screens/timeline/TimelineGeometry.kt` — Offset ab Tagesbeginn, begrenzt auf
`0..1440`. Angewendet in `buildTimelineState` (Tagesansicht) **und**
`buildWeekSessions` (Wochenansicht, hatte denselben Fehler).

### Verifikation
`TimelineMidnightGeometryTest` (9 Tests), u. a.:
- Tagesende → **1440** (nicht 0)
- Mitternachts-Session Starttag → 1380..1440 = **60 sichtbare Minuten**
- Folgetag → 0..420 (unverändert)
- Gegenprobe: die alte Uhrzeit-Funktion liefert am Tagesende tatsächlich **0** —
  beweist, dass der Fix die Ursache trifft und nicht ein Symptom verdeckt.

---

## 2. Digital Balance: 5-Minuten-Vorwarnung vor der App-Sperre

### Auftrag
5 Minuten vor Erreichen des Limits eine Benachrichtigung, dass die App gesperrt wird.

### ZWEI gefundene Alt-Fehler (wichtiger als die neue Funktion)

**a) Der Notification-Channel existierte nie.**
`AppBlockService.createChannel()` wurde seit M19 **nirgends aufgerufen** — M19 hatte
`startForeground` auf `BackgroundNotificationHelper` umgestellt und den Aufruf
entfernt, die Methode blieb als toter Code stehen. Seit Android 8 verwirft das System
Notifications in einen **nicht existierenden Channel stillschweigend**. Die
80-%-Warnung aus M18.61 hat den Nutzer deshalb **nie erreicht**. Fix: `createChannel()`
in `onCreate()`; Channel auf `IMPORTANCE_HIGH` (nachträglich nicht änderbar, deshalb
jetzt einmalig korrekt).

**b) Die Warnung feuerte bei jedem App-Wechsel erneut.**
`warnedPkgs` war ein In-Memory-`HashSet`. Der Service wird täglich neu gestartet
(Reboot, Limit-Änderung, Prozess-Tod) → der Zustand war weg → dieselbe Warnung
kam wieder, was wie eine neue nahende Sperre wirkt. Fix: `LimitWarningGuard`
persistiert pro App **und Tag** (SharedPreferences, eigene Datei
`aevum_limit_warnings`). Der Tages-Key macht einen Aufräumjob überflüssig.

**c) Die 80-%-Schwelle war fachlich falsch.**
Bei 60 min Limit warnte sie **12 Minuten** vor der Sperre, bei 10 min Limit nur
**2 Minuten** vorher — die Aussage „in 5 Minuten gesperrt" traf nie zu. Ersetzt
durch eine zeitpunktbasierte Prüfung (Restzeit ≤ 5 min).

### Design-Entscheidungen
- **Ausnahmen werden respektiert:** `exceptionAllowsBlocking()` ist aus `isBlocked`
  extrahiert und wird von der Warnung mitbenutzt. Eine Warnung für eine
  `ALWAYS_ALLOW`-App oder ein Limit außerhalb seines Zeitfensters wäre gelogen —
  der Nutzer bekäme eine Sperre angekündigt, die nie kommt.
- **Wortlaut:** „Noch etwa N Minuten **bei weiterer Nutzung**". Die Sperre greift,
  sobald das Limit erreicht ist — nicht nach Ablauf einer Uhr. Ohne den Zusatz
  behauptet die Meldung eine feste Frist, die nur bei Dauer-Nutzung stimmt.
- **Restzeit wird AUFGERUNDET** (mind. 1): „noch 4 Minuten" kurz vor der Sperre ist
  ehrlicher als „noch 4,3" → „4", und „0 Minuten" wäre irreführend.
- `AppLimitChecker.isBlocked(limit, used, nowMs)` nutzt jetzt wirklich `nowMs` für das
  Zeitfenster — vorher wurde der Parameter ignoriert und die echte Uhr gelesen.

### Verifikation
`AppLimitWarningTest` (16 Tests) + `LimitWarningGuardTest` (13 Tests):
Schwelle bei exakt 5 min, 1 s vor dem Limit, Ausnahmen (ALWAYS_ALLOW, TIME_WINDOW
inkl. Mitternachts-Fenster), Warnung und Sperre schließen sich aus, Persistenz
über Service-Neustart, Tageswechsel um lokale Mitternacht.

---

## 3. Kalender-Einstellungen: einzelne Termine aufzeichnen

### Auftrag
Kalender komplett auslesen, Termine anklicken, Aktivität wählen, von Terminstart bis
Ende aufzeichnen. Bei Löschung des Eintrags nicht mehr aufzeichnen. Bei Konflikt mit
einer Regel soll das **Benutzerdefinierte überwiegen**.

### Warum eine neue Tabelle statt Regeln
Regeln (`calendar_rule`) matchen über Textmuster und erfassen damit zwangsläufig
**jedes Vorkommen**: „Vorlesung" als Regel trifft auch die Vorlesung in drei Wochen.
Für „genau dieser Termin am Donnerstag" müsste der Nutzer eine Regel mit
Titel-Unikat und Zeitfenster bauen und danach wieder löschen — genau die
Fehlerquelle, die `calendar_event_pin` (DB v42) beseitigt.

**Der Schlüssel ist der Instanz-Schlüssel** `<calendarId>:<eventId>:<instanceStart>`
und enthält den Startzeitpunkt. Daraus folgt das gesamte Verhalten:

| Fall | Verhalten |
|---|---|
| Wiederkehrender Termin | Jedes Vorkommen hat eine andere ID → **nur das markierte** wird aufgezeichnet |
| Termin im Kalender **gelöscht** | Verschwindet aus dem Cache → Markierung wird nie ausgewertet und aufgeräumt → **nichts wird mehr aufgezeichnet** |
| Termin **verschoben** | Neuer `instanceStart` → neue ID → Markierung verwaist, der verschobene Termin ist **nicht** markiert (ehrlich: keine heimliche Aufzeichnung zu unbestätigten Zeiten) |
| Termin inhaltlich geändert (Titel/Zeit gleich) | ID bleibt → Markierung gilt weiter |

### Vorrang-Regel (Auftrag: „benutzerdefiniert überwiegt")
Umgesetzt in **einer** Funktion `CalendarMatchEngine.evaluateWithPins()`, die von
Auto-Start-Worker **und** Timeline-Vorschau genutzt wird — die Vorschau kann damit
nicht von der Realität abweichen:

1. Termin markiert → **Markierung gewinnt immer**, unabhängig von der
   Regel-Priorität. Priorität ordnet nur Regeln **untereinander**.
2. Sonst → Regel mit höchster Priorität (Bestandsverhalten).
3. Sonst → keine Aufzeichnung.

Zusätzlich in `CalendarAutoRunEngine.pickStartCandidate`: `CalendarMatch.effectivePriority`
gibt einer Markierung 100 000 — sonst hätte sie beim **gleichzeitigen** Fälligwerden
zweier Termine gegen eine Regel verloren und der Vorrang wäre im Doppel-Start-Fall
verloren gegangen.

### Behandelte Randfälle
- **Regeln-Gate aufgehoben:** Der Worker brach früher ab, wenn keine Regel existierte —
  ein markierter Termin ohne Regeln wäre nie gestartet. Jetzt genügt eine Markierung.
- **Aufräumen nur nach erfolgreichem Sync:** dort ist die Schlüsselliste vollständig.
  Zwei Schutzregeln: leerer Cache löscht **nichts** (Berechtigung entzogen o. ä.),
  und eine Markierung, deren Termin **gerade läuft**, bleibt erhalten — sie trägt die
  Endzeit, sonst liefe die Aufzeichnung bis zum 8-Stunden-Watchdog.
- **`CalendarMatch(rule, pin)`** verlangt per `require()` genau **eine** Quelle: der
  Zustand „beides" wäre ein stiller Fehler mit falschen Aufzeichnungen.
- **Aktivität gelöscht** (FK `ON DELETE SET NULL`): Markierung ist inert, UI zeigt
  „Aktivität fehlt", kein Crash.
- **Kein FK auf den Termin-Cache** — der wird bei jedem Sync geleert; CASCADE hätte
  die Nutzer-Markierung mitgelöscht.
- **Kein Doppel-Start:** bestehender Mechanismus (`isAlreadyRunningFor`) greift auch
  für Markierungen, jetzt über `candidate.activityTypeId` statt `candidate.rule`.

### UI
Neue Seite **Kalender → „Termine auswählen"** (`CalendarEventPickerScreen`):
- Tages-Navigation, Terminliste mit Status je Zeile
  („Wird aufgezeichnet" / „Tippen, um aufzuzeichnen" / „Aktivität fehlt")
- Antippen → Dialog mit Zeitraum-Anzeige („Aevum zeichnet von 10:00 bis 11:00 auf —
  nur an diesem Termin, nicht bei Wiederholungen"), Aktivitätswahl (mit Icon),
  optionalem Titel, Overlap-Schalter; „Aufzeichnen entfernen" nur wenn markiert
- Erkennbarkeit in der Timeline: markierte Blöcke tragen **✓**, regel-basierte **⋯**
- Hinweis bei laufendem bzw. vergangenem Termin; Vorbelegung aus bestehender Markierung

### Verifikation
`CalendarPinPrecedenceTest` (16), `CalendarPlannedPinPreviewTest` (13),
`CalendarAutoRunEngineTest` (+3 neue Fälle für den Doppel-Start-Vorrang).

---

## Berührte Dateien (Auszug)
**Neu:** `TimelineGeometry.kt`, `LimitWarningGuard.kt`, `CalendarEventPin.kt`,
`CalendarEventPinDao.kt`, `CalendarEventPinRepository.kt`,
`CalendarEventPickerViewModel.kt`, `CalendarEventPickerScreen.kt`,
4 Testklassen
**Geändert:** `TimelineViewModels.kt`, `TimelineScreen.kt`, `AppBlockService.kt`,
`AppLimitChecker.kt`, `CalendarMatchEngine.kt`, `CalendarAutoRunEngine.kt`,
`CalendarAutoRunWorker.kt`, `CalendarSyncWorker.kt`, `PlannedSessions.kt`,
`AppDatabase.kt` (v42), `RepositoryModule.kt`, `DatabaseModule.kt`,
`AppDestination.kt`, `AppNavHost.kt`, `CalendarRulesScreen.kt`,
`strings_calendar.xml` (DE+EN), `strings_background.xml` (DE+EN)

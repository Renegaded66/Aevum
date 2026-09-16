# M18.129 — Kalender-Integration (Vorab-Reflexion)

> **Status:** Design eingefroren, Implementierung gestartet.
> **Datum:** 2026-09-16
> **Branch:** `hermes/m18.129-calendar-integration`

Dieses Dokument beantwortet die vom Auftrag geforderte Vorab-Frage:
*„Hinterfrage zuerst, wie das mit bester Usability und schönem UI umsetzbar ist
und was du dabei alles beachten musst."*

---

## 1. Das Kern-Dilemma (und wie es gelöst wird)

Aevum zeichnet **Activity Types** auf. Der Kalender kennt nur **Titel +
Beschreibung**. Das ist ein semantischer Bruch: der Kalender liefert Freitext,
Aevum braucht eine ID.

**Naiver Ansatz (verworfen):** Fuzzy-Matching Titel → Activity-Name.
→ Scheitert sofort: „Vorlesung Analysis" matcht weder „Studium" noch sonst
etwas sinnvoll. Nutzer müsste seine Kalender-Titel umbenennen. Untauglich.

**Gewählter Ansatz: explizite, nutzer-konfigurierbare Regeln.**
Eine Regel = *Bedingung* → *Aktion*.

```
WENN  Kalender-Eintrag Titel ODER Beschreibung enthält "Vorlesung" ODER "Übung"
DANN  zeichne Activity "Studium" auf
      (Start = Termin-Start, Stop = Termin-Ende)
```

Das ist erklärbar, vorhersagbar, testbar und in 10 Sekunden verstanden.
Der Nutzer sieht in der Timeline **vorab** genau, was passieren wird —
also gibt es keine Überraschungen.

### Warum ODER und nicht UND?
Im Auftrag steht „im Titel oder/und in der Beschreibung". Das „oder/und" ist
ein klassischer Usability-Stolperstein. Lösung: **explizite Auswahl im Editor**
statt stiller Default.

- **Schalter „Nur Beschreibung durchsuchen"** (aus = Titel + Beschreibung)
- **Schalter „Alle Wörter müssen vorkommen (UND)"** (aus = ein Treffer genügt)

Beide als sichtbare Toggles mit Klartext-Label, nicht als versteckte Logik.

---

## 2. Usability-Entscheidungen im Detail

### 2.1 Regel-Typen — mehr als Wortsuche

Der Auftrag verlangt „denke dir gerne noch weitere Regeln aus". Sechs Typen,
alle über **einen** Editor bedienbar (das Typ-Dropdown tauscht nur die
Felder, nicht das Layout):

| Typ | Beschreibung | Beispiel |
|---|---|---|
| `TITLE_CONTAINS` | Wort/Wörter im Titel | „Vorlesung", „Übung" |
| `DESCRIPTION_CONTAINS` | Wort/Wörter in der Beschreibung | „Übungsblatt" |
| `ANY_FIELD_CONTAINS` | Wort/Wörter in Titel **oder** Beschreibung (Default!) | „Vorlesung" |
| `TITLE_REGEX` | Regulärer Ausdruck im Titel | `^VL\s+\d+` |
| `CALENDAR_IS` | Bestimmte Kalender/Accounts (Mehrfachauswahl) | „Uni", „Arbeit" |
| `ATTENDEE_CONTAINS` | Teilnehmer/E-Mail (Gäste) enthält | `@uni-` |
| `ALL_DAY_ONLY` | Nur ganztägige Termine | Urlaub, Feiertag |

Zusätzlich gelten für **jede** Regel optionale Filter, weil sie die häufigste
Fehlerquelle beim Auto-Tracking sind:

- **Zeitfenster** (z. B. nur 06:00–22:00) — verhindert Aufzeichnung von
  nächtlichen Kalender-Resten.
- **Wochentage** (Mo–So-Chips) — Vorlesungen am Wochenende ignorieren.
- **Mindestdauer** (z. B. ≥ 10 min) — 5-Minuten-Erinnerungen filtern.
- **Verhalten bei Überschneidung:**
  - *Überschreiben* (Default): die neue Aktivität beendet die laufende
    (Aevum-Standard, entspricht `LiveActivityManager.start()`).
  - *Nur starten, wenn nichts läuft*: konservativ — paralleler Fokus wird
    nicht zerstört.

### 2.2 Der Permission-Flow (Iron Rule aus `android-permission-flow-composition`)

`READ_CALENDAR` ist eine **dangerous** Runtime-Permission → 4-State-Modell:

```
NotAsked          → "Kalender verbinden" + Erklärung der Vorteile
Denied            → "Erneut versuchen" + "Ohne fortfahren"
PermanentlyDenied → NUR "App-Einstellungen öffnen" (Deep-Link)
Granted           → Banner verschwindet, Regel-Liste erscheint
```

**Niemals** `if (hasPermission) { doIt() }` ohne UI-Pfad. Der Nutzer muss
jederzeit sehen: *ist die Berechtigung erteilt?* und sie muss in den
System-Einstellungen widerrufbar sein (System-Garantie, aber die App muss den
Widerruf **erkennen**).

### 2.3 Widerruf-Erkennung — der kritische Pfad

Der Nutzer kann die Permission jederzeit in den Android-Einstellungen
entziehen. Dann gilt:

- Kein Crash, kein stiller Fehlschlag.
- Der Sync-Worker prüft die Permission **bei jedem Lauf** und beendet sich
  sauber (kein Retry-Spam).
- Der Auto-Start-Worker prüft sie ebenfalls → keine Sessions mehr.
- Die Regeln werden **nicht gelöscht** (Nutzer-Intention bleibt erhalten).
- Die UI zeigt sofort wieder den Banner (Lifecycle-Resume-Reread).

### 2.4 Der „Was würde passieren"-Gedanke

Der Auftrag verlangt: die **nächsten 7 Tage** in der Timeline, gefiltert durch
die eigenen Bedingungen — „diagonal gestrichelt als Textur", mit Farbe + Icon.

Das ist die wichtigste Usability-Entscheidung des Features: **Transparenz vor
Aktion**. Der Nutzer sieht vorab, was die App tun wird, und kann die Regel
korrigieren, *bevor* falsche Daten entstehen.

Darstellung:
- **Diagonal-Streifen** (`LinearGradient` + `TileMode.Repeated`) — klar
  unterscheidbar von echten Aufzeichnungen (flach gefüllt).
- **Reduzierte Deckkraft** (~0.32) + gestrichelte Kontur — „noch nicht real".
- **Activity-Farbe und -Icon werden übernommen** — Zuordnung erkennbar.
- **Nicht klickbar für Edit** — ein Plan ist keine Session. Ein Tap zeigt
  stattdessen einen Info-Hinweis („Geplant aus Kalender · Regel: X").
- **Nicht in Statistiken, Insights, Summen** — geplante Blöcke dürfen die
  Zahlen **niemals** verfälschen. Sie existieren ausschließlich als Overlay.

### 2.5 Sync — akku-schonend

| Auslöser | Takt | Begründung |
|---|---|---|
| Periodisch | **6 Stunden** | Kalender ändern sich selten; 4×/Tag ist reichlich |
| Constraint | `BATTERY_NOT_LOW` | Kein Sync auf dem letzten Prozent |
| Exakte Alarme | **NEIN** | `SCHEDULE_EXACT_ALARM` ist ab Android 14 nicht mehr automatisch erteilt → unnötige Permission. Inexakt reicht völlig. |
| Manuell | Button, jederzeit | mit sichtbarem Zeitstempel + Ergebnis-Feedback |
| Bei App-Start | 1× | falls der Periodik-Takt verpasst wurde |

Der Sync liest nur **Instances** (bereits expandierte Wiederholungen) im
Fenster `[heute − 1 Tag, heute + 8 Tage]` — nie den ganzen Kalender.

### 2.6 Der Auto-Start ohne Dauer-Alarm

**Problem:** Ein Kalendertermin beginnt um 10:15. Ohne exakte Alarme kann
WorkManager nicht sekundengenau feuern.

**Lösung: ein einziger selbst-erneuernder Minutentakt — mit Gates davor.**

```
CalendarAutoRunWorker
  ├─ Gate 1: Feature an?
  ├─ Gate 2: READ_CALENDAR erteilt?
  ├─ Gate 3: Regeln vorhanden?
  ├─ → fällige Ereignisse prüfen (aus dem lokalen Cache, kein ContentResolver)
  ├─ → starten / stoppen
  └─ → nächsten Lauf in ≤ 15 min planen (selbst-erneuernd)
```

Das ist derselbe Baustein wie beim `PingTriggerWorker` (M18.62-FIX): ein
`OneTimeWorkRequest` mit `setInitialDelay`, das sich selbst neu einplant —
**kein** `PeriodicWorkRequest` unter 15 min (das ist eine
`IllegalArgumentException` und war genau der Bug, der den Ping-Trigger
monatelang tot machte).

Für die Startgenauigkeit: Der Worker plant den nächsten Lauf **auf die nächste
Ereignis-Grenze** (Start oder Ende, gedeckelt auf 15 min) — nicht blind alle
15 min. Bei einer Vorlesung um 10:15 landet der Lauf also bei 10:15, nicht
bei 10:20.

**Akku-Schutz:** Nachts (z. B. 23:00–06:00) wird auf einen größeren Takt
gedeckelt, wenn keine Nacht-Regeln existieren. Zusätzlich prüft der Worker
zuerst den **lokalen Cache** — der `ContentResolver` wird nur vom Sync-Worker
angefasst (nicht alle 15 min).

### 2.7 Was explizit **nicht** passiert (Anti-Features)

- **Kein exakter Alarm**, keine `SCHEDULE_EXACT_ALARM`-Permission.
- **Kein Schreiben in den Kalender** — nur Lesen. Die App ist nie Autor
  eines Termins.
- **Keine Kalender-Benachrichtigungen** — Aevum dupliziert nicht, was der
  Kalender schon tut.
- **Kein Auto-Löschen** von Aufzeichnungen, wenn ein Termin verschwindet.
- **Geplante Blöcke zählen nirgends** in Statistiken.

---

## 3. Technische Risiko-Analyse (Devon-Regel: 2–3 Failure-Modes vorab)

### Risiko 1 — Room-Schema-Mismatch → App-Crash beim Start
**Hohes Risiko, bekanntes Muster (M18.40/41, M18.60).**
Room validiert das Schema zur **Runtime**. Weicht die Migration von der Entity
ab (Spaltennamen, NOT NULL, Defaults, **Indices inkl. unique-Flag**), crasht
die App beim DB-Öffnen — und `fallbackToDestructiveMigration` ist seit M18.109
bewusst entfernt (Fail-Fast). Ein Fehler hier = alle Nutzer-Daten weg oder
App tot.

**Gegenmaßnahme:**
1. Migration exakt spiegelbildlich zur Entity schreiben.
2. Generiertes Schema-JSON (`app/schemas/.../41.json`) **aktiv gegen die
   Migration diffen** — nicht nur „sieht gut aus".
3. Kein `@Index(unique=true)` auf einem Feld, das PK ist (M18.67-FIX1).

### Risiko 2 — Auto-Start-Kollision mit anderen Automatiken
Geofence, Fahrt, Wanderung, App-Tracking und Bildschirm-Aufzeichnung greifen
alle auf `LiveActivityManager.start()` zu. Ein Kalender-Start, der eine
laufende **fremde** Session blind beendet, zerstört Daten.

**Gegenmaßnahme:**
- Die Regel-Aktion ist explizit wählbar (*Überschreiben* vs. *Nur wenn frei*).
- Der Kalender-Worker **stoppt nur Sessions, die er selbst gestartet hat**
  (`sourceType == "CALENDAR_AUTO"` + gemerkte Session-ID) — exakt das
  Schutz-Muster von `AppTrackingService`.
- Jeder Stop-Pfad ruft `cancelAutoDiscardForSession` auf (M18.66-FIX21).

### Risiko 3 — Endlos-Session / verpasstes Ende
Findet der Worker das Ende nicht (Termin verschoben, Permission entzogen,
Gerät aus), läuft die Session ewig — das ist der Fehler aus M18.61e
(„Auto-Start impliziert Auto-Stop").

**Gegenmaßnahme:**
- Der Worker stoppt **beides**: fälligen Start *und* fälliges Ende in jedem
  Lauf, aus einer sortierten Ereignisliste des Tages.
- **Watchdog-Charakter:** Läuft eine `CALENDAR_AUTO`-Session und ist ihr
  Termin in der Vergangenheit (mit 15-min-Toleranz), wird sie beendet —
  auch wenn der reguläre Stopp-Lauf verpasst wurde.
- Beim Deaktivieren des Features werden laufende `CALENDAR_AUTO`-Sessions
  sauber beendet.

### Risiko 4 — Zukunfts-Bug in der Timeline (bekannt aus M18.60c)
`endAt == null` erfüllt jeden Overlap-Filter → laufende Sessions tauchen in
allen Zukunftstagen auf. Bei geplanten Blöcken ist das Risiko größer, weil
sie *per Definition* in der Zukunft liegen.

**Gegenmaßnahme:**
- Geplante Blöcke sind ein **separates Feld** im `TimelineUiState`
  (`plannedSessions`) — sie mischen sich nie in `sessions`.
- Filterung ausschließlich über `[dayStart, dayEnd]`-Overlap mit **explizitem**
  `endAt` (geplante Blöcke haben immer ein Ende; unbegrenzte Termine werden
  auf +1h gedeckelt).

### Risiko 5 — Timeline-Performance
`TimelineScreen.kt` ist 194 KB / ~4100 Zeilen. Ein zusätzliches
`Canvas`-Overlay in `ZoomableDayTimeline` und `WeekTimeline` bedeutet zwei
geänderte Zeichen-Routinen mit **identischer Geometrie** wie der Hit-Test
(M18.66-FIX17/21: „Zeichnung und Hit-Test brauchen dieselbe Geometrie").

**Gegenmaßnahme:**
- Geplante Blöcke **erben die Lane-Zuweisung** der echten Sessions-Berechnung
  (`assignTimelineLanes`), damit keine neuen Geometrie-Pfade entstehen.
- Zeichnung als **eine** zusätzliche Schleife nach der Session-Schleife,
  gleiche Koordinatenformeln, nur anderer Brush.
- Kein eigener Hit-Test → kein Tap-Ziel, kein Konflikt mit dem bestehenden
  Editor-Tap.

### Risiko 6 — Permission-Entzug mitten im Sync
`ContentResolver.query` wirft `SecurityException`, wenn die Permission
zwischen Prüfung und Abfrage entzogen wird.

**Gegenmaßnahme:** Permission-Check **vor** und Abfrage in `try/catch` mit
`suspendCancellableCoroutine`-freiem, einfachem `use {}`-Block. Ein Entzug
wird als normaler Zustand („Berechtigung fehlt") protokolliert, nicht als
Crash gewertet. (Ausnahme von Devons „kein defensives try/catch": Hier ist
der Entzug ein **legitimer, erwarteter** Zustand des Betriebssystems, kein
verschleierter Bug — im Code begründet.)

---

## 4. Datenmodell

### `calendar_rule` (neu)
| Spalte | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | UUID |
| `name` | TEXT | Anzeigename („Studium aus Kalender") |
| `enabled` | INTEGER | Regel aktiv |
| `match_type` | TEXT | `ANY_FIELD_CONTAINS`, `TITLE_CONTAINS`, … |
| `match_value` | TEXT | Wörter (kommagetrennt) oder Regex |
| `match_calendar_ids` | TEXT? | JSON-Array für `CALENDAR_IS` |
| `case_sensitive` | INTEGER | Groß-/Kleinschreibung beachten |
| `require_all_words` | INTEGER | UND statt ODER |
| `title_only` | INTEGER | nur Titel statt Titel+Beschreibung |
| `activity_type_id` | TEXT | **FK** → `activity_type(id)`, ON DELETE SET NULL |
| `default_title` | TEXT? | überschreibt den Aktivitätsnamen als Session-Titel |
| `min_duration_minutes` | INTEGER | 0 = egal |
| `window_start_minute` | INTEGER | -1 = kein Fenster |
| `window_end_minute` | INTEGER | -1 = kein Fenster |
| `weekday_mask` | INTEGER | Bitmaske Mo=1 … So=64, 127 = alle |
| `overlap_policy` | TEXT | `OVERRIDE` / `ONLY_IF_IDLE` |
| `priority` | INTEGER | bei Mehrfach-Match: höher gewinnt |
| `created_at` / `updated_at` | INTEGER | |

### `calendar_event_cache` (neu)
| Spalte | Typ | Zweck |
|---|---|---|
| `event_id` | TEXT PK | `calendarId:eventId:instanceStart` — stabil & idempotent |
| `calendar_id` | TEXT | |
| `calendar_name` | TEXT | |
| `title` | TEXT | |
| `description` | TEXT? | |
| `location` | TEXT? | |
| `start_at` / `end_at` | INTEGER | |
| `all_day` | INTEGER | |
| `attendees` | TEXT? | E-Mails, kommagetrennt |
| `synced_at` | INTEGER | |

Kein FK auf `activity_session` — geplante Blöcke sind keine Sessions.

### `automation_settings` (erweitert)
| Spalte | Default | Zweck |
|---|---|---|
| `calendar_sync_enabled` | 0 | Feature-Master-Schalter |
| `calendar_auto_tracking_enabled` | 0 | Auto-Start/Stop separat schaltbar |
| `calendar_last_sync_at` | 0 | Zeitstempel für „Letzter Sync" |
| `calendar_sync_interval_hours` | 6 | Sync-Takt |

**Feature-Trennung ist Absicht:** Der Nutzer kann den Kalender *lesen*
(Timeline-Vorschau) und trotzdem *nicht* automatisch aufzeichnen lassen.
Das ist eine andere Entscheidung als „Feature an/aus" und wird oft gewollt sein.

---

## 5. Dateien

**Neu:**
```
data/model/CalendarRule.kt
data/model/CalendarEventCache.kt
data/db/CalendarRuleDao.kt
data/db/CalendarEventCacheDao.kt
data/repository/CalendarRepository.kt (+Impl)
domain/calendar/CalendarMatchEngine.kt        (pure JVM, testbar)
domain/calendar/CalendarRuleType.kt
domain/calendar/CalendarPermissionState.kt
automation/calendar/CalendarReader.kt
automation/calendar/CalendarSyncWorker.kt
automation/calendar/CalendarSyncScheduler.kt
automation/calendar/CalendarAutoRunWorker.kt
automation/calendar/CalendarAutoRunScheduler.kt
ui/screens/calendar/CalendarRulesScreen.kt
ui/screens/calendar/CalendarRulesViewModel.kt
ui/screens/calendar/CalendarRuleEditorSheet.kt
ui/theme/PlannedBlockTexture.kt               (Diagonal-Streifen-Brush)
```
**Geändert:** `AppDatabase.kt`, `DatabaseModule.kt`, `RepositoryModule.kt`,
`AutomationSettings.kt`, `TimelineViewModels.kt`, `TimelineScreen.kt`,
`AppDestination.kt`, `AppNavHost.kt`, `SettingsScreen.kt`,
`AevumApplication.kt`, `AndroidManifest.xml`, Strings DE/EN.

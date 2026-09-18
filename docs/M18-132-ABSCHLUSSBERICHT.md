# M18.132 — Kalender-Termin-Auswahl: 7-Tage-Ansicht, Ghost-Fix, QUEUE-Policy

**Datum:** 18.09.2026
**Version:** 1.0.14 → **1.0.15** (versionCode 16), DB bleibt v42 (kein Schema-Eingriff)
**Tests:** 712 grün (neu: 2 Testklassen mit 21 Tests)

Der Nutzer-Auftrag hatte drei Teile: die Termin-Auswahl als echte
7-Tage-Kalender-Ansicht, die Klärung der „Halluzination" (Termin, der nicht
im Kalender steht) und der fehlenden echten Termine — plus die dritte
Overlap-Option („startet, sobald keine Aufzeichnung mehr läuft").

---

## 1. Ursachen-Analyse der gemeldeten Symptome

### Symptom A: „Fahrradtour für 1 Stunde" wird angezeigt, existiert aber nicht im Kalender

**Befund:** Der String „Fahrradtour" existiert an keiner Stelle im Code,
in den Resources, in Tests oder Migrationsdaten (global gesucht). Der
Picker zeigt ausschließlich Zeilen aus `calendar_event_cache` — es gibt
keine Demo- oder Platzhalter-Daten.

**Root Cause (echter Code-Bug, kein Daten-Fake):** `replaceWindow()` in
`CalendarRepositoryImpl` löschte nur Termine, die **vor dem Sync-Fenster
enden** (`deleteEndedBefore`). Ein Termin, der im Handy-Kalender
**gelöscht** wurde, blieb deshalb als Ghost im Cache, bis er selbst aus
dem 9-Tage-Fenster fiel — bis zu **9 Tage lang** sichtbar in Picker und
Timeline-Vorschau, obwohl er längst weg war. Der gemeldete „Fahrradtour"-
Termin war mit hoher Wahrscheinlichkeit ein echter Kalendereintrag, der
inzwischen gelöscht wurde (z. B. automatisch erzeugter Health-/Fitness-
Eintrag), und blieb wegen dieses Bugs stehen.

**Fix:** Der Cache wird pro Sync zum vollständigen Spiegel des Fensters:
neue DAO-Operationen `getIdsEndingAfter(from)` + `deleteByIds(ids)`
 entfernen alles, was im Fenster liegt, aber vom Reader nicht mehr
geliefert wurde. Gestückelt (500/Batch) wegen des SQLite-Variablen-
Limits. Reihenfolge: Prune → Ghost-Delete → UPSERT (ein wiedergekehrter
Termin wird nie vom Delete erfasst).

### Symptom B: echte Termine werden nicht angezeigt

**Root Cause 1 — STATUS-NULL-Bug in der ContentProvider-Query:**
Die `Instances`-Query filterte `STATUS != CANCELED`. In SQL ist
`NULL != 1` aber nicht TRUE, sondern NULL — die Zeile fällt aus dem
Ergebnis. Kalender, deren Provider keinen Status setzen (viele lokale
und manche synchronisierte Kalender), waren damit **komplett
unsichtbar**. Fix: `(STATUS IS NULL OR STATUS != ?)`.

**Root Cause 2 — veralteter Cache:** Der Picker liest nur den Cache;
der periodische Sync läuft nur alle 6 h (Default). Ein gerade erst im
Handy angelegter Termin erschien erst Stunden später. Fix: Beim Öffnen
der Termin-Auswahl läuft jetzt ein bedarfsgesteuerter Sync, wenn der
letzte älter als 15 Minuten ist (Gates: Feature-Schalter → Permission →
Staleness), mit sichtbarem „Kalender wird gelesen…"-Indikator.

### Symptom C: fehlende dritte Overlap-Option

Der Picker bot nur „Termin übernimmt" vs. „nur wenn nichts läuft" als
Toggle. Neu: drei explizite Radio-Optionen pro Termin:
- **OVERRIDE** — aktuelle Aufzeichnung beenden, Termin aufzeichnen (Standard)
- **ONLY_IF_IDLE** — gar nichts machen
- **QUEUE_IF_BUSY** (neu) — warten und automatisch starten, sobald keine
  Aufzeichnung mehr läuft; auch noch mitten im Termin (Nachholer startet
  bei „jetzt", nicht rückwirkend — keine doppelte Zeiterfassung).

---

## 2. Weitere echte Bugs, gefunden bei der Analyse

### 2a. Falsche Termin-Zuordnung beim Auto-Stop (M18.129-Alt-Bug)

`stopFinishedSession` suchte den zugehörigen Termin über **Typ ODER
Titel** und stoppte den ersten Treffer. Gehören zwei Termine dieselbe
Aktivität („Soziales" für Großeltern 15–18 UND Kino 20–22), wurde die
laufende Session am Ende des **falschen** Termins gestoppt — Beispiel:
Session läuft ab 15:00, der abgelaufene 10–12-Termin desselben Typs
wird beim nächsten Worker-Lauf gefunden und stoppt sie um 12:05.

**Fix:** Neue einheitliche Funktion
`CalendarAutoRunEngine.findRelatedMatch(matches, sessionStartAt, type)`:
Eine CALENDAR_AUTO-Session gehört zu dem Termin, in dessen
[startAt, endAt]-Fenster ihre Startzeit liegt; bei mehreren Fenstern
gewinnt der nächste Beginn, bei Gleichstand das spätere Ende (Datenabzug
ist die schlimmere Fehlerrichtung). Dies ersetzt die Heuristiken im
Stop-Pfad UND im Doppelstart-Schutz (dort war es davor „gleicher Typ und
|Start-Abstand| < 5 min", was QUEUE-Nachholer fälschlich doppelt gestartet
hätte).

### 2b. Toter Match blockierte startbare Termine

`pickStartCandidate` ließ Matches mit gelöschter Aktivität (ON DELETE
SET NULL) zu; ein solcher konnte das Feld belegen und einen startbaren
Termin verdrängen. Fix: Filter `activityTypeId != null` vor der
Kandidatenwahl.

### 2c. Kein Kalender→Kalender-Wechsel

Lief schon eine eigene Kalender-Session, wurde jeder weitere Termin
still verworfen — auch der mit OVERRIDE markierte nachfolgende Termin
wurde nie aufgezeichnet. Fix: OVERRIDE wechselt jetzt zum neuen Termin
(die Trim-Logik von LiveActivityManager M18.71 kürzt die alte Session
ordnungsgemäß); ONLY_IF_IDLE und QUEUE verhalten sich konservativ.

### 2d. QUEUE-Nachholer-Startzeit

Ein Nachholer, der erst nach der 20-Minuten-Toleranz drankommt, startet
bei **jetzt**, nicht rückwirkend zum Termin-Beginn — sonst läge seine
Aufzeichnung vor der fremden Session, die vorher lief (doppelte
Zeiterfassung). Ehrlichkeit der Daten über optische Termin-Treue.

---

## 3. Die 7-Tage-Ansicht (Auftragskern)

Die Termin-Auswahl (Einstellungen → Kalender → „Termine auswählen")
zeigt jetzt statt Einzeltag-Navigation eine durchgehende
**7-Tage-Liste ab heute**:

- Jeder Tag eine Sektion: Header mit Wochentags-Kreis (Google-Kalender-
  Stil: Kürzel über Tageszahl), „Heute"/„Morgen"-Hervorhebung in der
  Akzentfarbe, darunter alle Termine des Tages (echter Overlap —
  Mitternachts-Termine an beiden Tagen).
- Leere Tage zeigen „Keine Termine" statt zu verschwinden — sonst
  wirkte die Liste, als endete der Kalender.
- Jeder Termin antippbar → Dialog mit Aktivitätswahl, eigenem Titel,
  Zeitraum-Info und den drei Overlap-Optionen; „Aufzeichnung entfernen"
  für bestehende Markierungen.
- Beim Öffnen läuft der Staleness-Sync (s. o.) mit Spinner und
  „Kalender wird gelesen…".

**Bewusste Design-Entscheidung — Anker statt rollierendes heute:** Die
7 Tage werden relativ zum Öffnungs-Zeitpunkt berechnet und frieren ein.
Rolt man über Mitternacht, verschiebt sich die Liste nicht unter dem
Nutzer (Tag 7 fiele sonst hinten raus); beim nächsten Öffnen wird der
Anker neu gesetzt.

**Nicht Bestandteil:** Wochen-Navigation über die 7 Tage hinaus (Sync-
Fenster des Readers ist 9 Tage, eine zweite Woche würde leere Versprechen
machen — der periodische Sync liefert sie erst später); Monatsansicht;
Mehrfachauswahl von Terminen; Kalender-Filter pro Account.

---

## 4. Verifikation

**Neue Tests (21):**
- `CalendarQueuePolicyTest` (16): Policy-Erkennung an Match/Regel/Pin,
  Fälligkeit des Nachholers (während/nach Termin, Grenzen), Kandidaten-
  Ordnung (frischer OVERRIDE schlägt wartenden QUEUE, FIFO-Warteschlange,
  toter Match blockiert nicht), findRelatedMatch (pünktlicher Start,
  Nachholer mitten im Termin, fremder Termin gleicher Aktivität wird
  NICHT zugeordnet, leer → Watchdog).
- `CalendarCacheGhostPruneTest` (5): gelöschter Termin verschwindet,
  aktuelle bleiben, Pruning vor Fenster, leerer Kalender leert Fenster,
  wiedergekehrter Termin überlebt.

**Gesamt:** `:app:testDebugUnitTest` — 712 Tests, 0 Failures, 0 Errors
(frisch ausgeführt, XML-Zeitstempel verifiziert). Alle 9 Kalender-
Domain-Testklassen grün (144 Tests).

**Signatur:** Robust-Build v4 mit kanonischem Debug-Keystore
(SHA-1 9c3055c4…), apksigner-Verify im Build-Skript.
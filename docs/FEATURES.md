# FEATURES — Aevum

## MVP Kernfeatures

### 1. Automatische Lebenszeit-Erfassung

**Ziel:** Der Nutzer gibt so wenig wie möglich manuell ein.

Quellen:

- Geofencing für Orte wie Arbeit/Fitnessstudio
- Activity Recognition für Bewegung/Autofahren
- Health Connect für Schlaf
- UsageStatsManager für Smartphone-Nutzung

Jede erkannte Aktivität ist bearbeitbar:

- Titel
- Kategorie
- Tags
- Startzeit
- Endzeit
- Beschreibung

### 2. Visuelles Dashboard

Dashboard ist der wichtigste Screen („Der Puls deines Tages").

**Hero 2.0 (M19.4)** — das „Tag-Cockpit" oben:

- Tages-Navigation ‹ Heute › — Tag blättern (Slide-Animation), Datum →
  Kalender-Popup, „Heute"-Rücksprung.
- Güte-Gauge (180°): feste Rot→Gelb→Grün-Skala mit leuchtendem Marker
  und Nadel; große Zahl = Zeitqualität (Dauer × Positivität je Aktivität).
  Tipp öffnet die Tages-Güte-Anpassung (✎-Badge bei Override).
- Drei versetzte Glas-Kacheln: **Erfasst / Schlaf / Bildschirm** —
  Zähler-Animation, Mini-Balken (Anteil an 24 h), dezenter Schimmer.
- Zonen-Chip neben dem Gauge: aktueller Geofence-Name oder „Abwesend".
- Tagesleiste (24 h): Segment-Blöcke in Kategoriefarben, gedimmte
  Zukunft rechts vom pulsierenden Jetzt-Marker, Stundenlabels
  0/6/12/18/24; Tipp öffnet die Timeline.
- Aufnahme-Status: „Aufnahme läuft" (grün + Timer + Schimmer) /
  „Pausiert" (amber, Timer eingefroren) / „Aufnahme bereit" mit
  Tagesfortschritt.
- **Sheet über Hero:** alle Karten liegen in einer oben abgerundeten
  Fläche, die beim Scrollen über den gepinnten Hero schiebt (Parallax,
  Farbraum schimmert durch die Ecken — Referenz-Aufbau des Nutzers).

Karten im Sheet: Live-Banner (Pause/Weiter/Stop/Wechsel), Güte-Verlauf
(7/30/365 Tage), Garmin-Kacheln (Schlaf/Stress/Body-Battery), „Wo deine
Zeit hingeht" + Insights, Todos, Tagespauschalen, Review-Hinweis,
schwebender +-Button (nur ohne laufende Aufnahme) mit Orbit-Launcher.

### 3. Timeline & Activity Editor

- Tages-/Wochen-Timeline
- erkannte Aktivitäten bestätigen/bearbeiten/verwerfen
- manuelle Aktivität erstellen
- Konflikte anzeigen

### 4. Ziele

Beispiele:

- „Heute 2 Stunden lernen“
- „30 Minuten Sport machen“

Zieltypen:

- Dauer pro Kategorie/Tag
- Anzahl Sessions
- Zeitraumziel täglich/wöchentlich/monatlich

Aevum prüft Ziele automatisch gegen bestätigte Activity Sessions.

### 5. Habits & Streaks

Frequenzen:

- täglich
- wöchentlich
- mehrfach pro Woche
- individuell

Metriken:

- aktuelle Serie
- Rekord
- Erfolgsquote
- Verlauf/Heatmap

### 6. Bucket List

Jeder Eintrag:

- Titel
- optional Bild
- Beschreibung
- optional Datum
- Status
- Fortschritt

### 7. Life Analytics v1

- Eigener Insights-Tab in der Hauptnavigation
- Zeiträume: Heute, Woche, Monat
- Zeitverteilung als Donut Chart nach Kategorien
- Vorperiodenvergleich ohne künstliche Zahlen
- Top-Aktivitäten nach Activity Type
- Balance-Blick auf Arbeit, Erholung, Bewegung, Digital und Soziales
- Regelbasierte, nicht belehrende Insight Cards
- Wochen-Heatmap mit Sprung in die Timeline

## Nicht-Ziele für MVP

- Kein Login
- Kein Backend
- Keine Cloud
- Kein Social Sharing
- Keine KI-Auswertung, solange kein klarer lokaler Mehrwert definiert ist

## Spätere Erweiterungen

- Lokaler Export/Import
- Verschlüsseltes Backup
- Widgets
- Wear OS Integration
- Kalender-Import lokal
- lokale, private Insight-Engine

## Kalender-Integration (M18.129)

**Ziel:** Termine aus dem Handy-Kalender steuern die Aufzeichnung — ohne den
Nutzer zu zwingen, seine Kalender-Titel an eine App anzupassen.

### Regeln: Bedingung → Aktivität

Der Kalender kennt nur Titel und Beschreibung, Aevum zeichnet Activity-Types
auf. Die Brücke sind explizite Regeln, konfigurierbar in
Einstellungen → Kalender:

> WENN Titel **oder** Beschreibung "Vorlesung" oder "Übung" enthält
> DANN Aktivität **Studium** aufzeichnen (Start = Termin-Start, Ende = Termin-Ende)

Sieben Bedingungstypen:

| Typ | Bedeutung |
|---|---|
| Wort in Titel oder Beschreibung | Standard-Fall |
| Wort nur im Titel | z. B. „VL 12" |
| Wort nur in der Beschreibung | z. B. „Übungsblatt" |
| Regulärer Ausdruck (Titel) | für Muster wie `^VL\s+\d+` |
| Bestimmte Kalender | nur Uni- oder nur Arbeitskalender |
| Teilnehmer enthält | z. B. `@uni-` |
| Nur ganztägige Termine | Urlaub, Feiertage |

Wortregeln sind wahlweise ODER (Default: ein Treffer genügt) oder UND (alle
Wörter nötig). Zusätzlich pro Regel: Mindestdauer, Zeitfenster („nur
06:00–22:00"), Wochentage, eigener Session-Titel und das Verhalten bei
Überschneidung (laufende Aufzeichnung übernehmen oder nur starten, wenn frei).
Bei mehreren Treffern gewinnt die Regel mit der höchsten Priorität.

### Automatisches Starten und Stoppen

Ein selbst-erneuernder WorkManager-Job prüft fällige Termine und startet bzw.
stoppt die Aufzeichnung zum Terminbeginn und -ende. Er plant sich auf die
**nächste Termingrenze** (max. 15 Minuten), läuft also pünktlich um 10:15 und
nicht „irgendwann zwischen 10:15 und 10:30“. Er liest ausschließlich den
lokalen Termin-Cache — nie den Kalender-Provider direkt.

**Sicherheit:** Der Job stoppt nur Sessions, die er selbst gestartet hat.
Andere Automatiken (Geofence, Fahrt, Wanderung, App-Aufzeichnung) bleiben
unangetastet. Verpasste Enden werden per Watchdog nachgeholt.

### Kalender als Fallback: Aufzeichnung mit Wiedereinstieg (M18.134)

Ein Kalender-Termin ist ein **Fallback mit Wiedereinstieg**: Er zeichnet
immer dann auf, wenn nichts anderes läuft — und kommt nach einer
Verdrängung zurück, solange sein Termin noch läuft.

So verhält es sich für dich:

1. **Der Termin läuft, solange nichts anderes aufzeichnet.** Beginnt während
   des Termins eine andere automatische Aufzeichnung (z. B. eine Fahrt),
   übernimmt diese — die Kalender-Aufzeichnung wird an deren Beginn sauber
   beendet (keine Überlappung, keine doppelte Zeit).
2. **Nach dem Ende der anderen Aufzeichnung kommt der Termin sofort
   zurück** — die Autofahrt endet, der Termin läuft noch: Die
   Kalender-Aufzeichnung startet automatisch wieder, ohne auf den nächsten
   15-Minuten-Takt zu warten. Das gilt für alle automatischen Quellen:
   Fahrt (Stopp + Watchdog), Wanderung, Geofence, App-Tracking, Ping und
   Bildschirm-Auto-Ende.
3. **Der Wiedereinstieg beginnt bei der aktuellen Uhrzeit.** Es wird nicht
   auf den Terminbeginn rückdatiert — die Lücke während der Fahrt bleibt
   ehrlich leer. Es entsteht nie doppelt erfasste Zeit.
4. **Ein Termin endet nie vor seiner Zeit.** Die Aufzeichnung läuft bis zum
   Terminende; ein minimal zu später Stopp ist bewusst besser als ein
   abgeschnittener Block.
5. **Ein manueller Stop ist endgültig.** Stoppst du die Aufzeichnung
   selbst, pausierst sie oder wechselst die Aktivität, wird der Termin
   **nicht** automatisch wieder aufgenommen — eine menschliche Entscheidung
   wird nicht heimlich umgedreht.
6. **Es gibt dafür keine Konfiguration.** Das Fallback-Verhalten ist die
   Semantik der Kalender-Aufzeichnung und lässt sich nicht abschalten.

**Abgrenzung zur Übernahme-Policy:** Die pro Regel (bzw. Termin) wählbare
Overlap-Policy regelt unverändert nur, wer eine **laufende** fremde
Aufzeichnung übernehmen darf. Der Wiedereinstieg greift dagegen nur, wenn
**keine** andere Aufzeichnung mehr läuft — und nur für Termine, deren
Aufzeichnung nachweislich von einer anderen Session verdrängt wurde.
Ein Termin, der nie gestartet war (z. B. weil das Handy aus war), wird nach
Ablauf der 20-Minuten-Toleranz nicht mehr gestartet.

### 7-Tage-Vorausschau in der Timeline

Die Timeline zeigt für die kommenden 7 Tage vorab, welche Termine durch die
eigenen Regeln aufgezeichnet **würden** — nur die passenden, nicht pauschal
den ganzen Kalender. Diese Blöcke sind:

- **diagonal gestrichelt** (Textur statt Vollfläche),
- in **Aktivitätsfarbe und mit Icon**,
- **nicht klickbar** (ein Plan ist keine Aufzeichnung),
- **nirgends mitgezählt** (nicht in Summen, Insights oder Statistiken).

### Synchronisierung

- **Manuell:** Button mit Spinner und Ergebnis-Meldung.
- **Automatisch:** Default alle 6 Stunden (1/3/6/12/24 h wählbar), nur bei
  ausreichendem Akku (`BATTERY_NOT_LOW`).
- **Zeitstempel:** „Zuletzt synchronisiert: heute 14:23" direkt unter dem
  Button; wird auch bei null gefundenen Terminen gesetzt („erfolgreich leer"
  statt altem Datum, das wie ein Fehler wirkt).
- **Kein Dauerbetrieb:** Der Kalender wird nur während des Syncs gelesen.

### Berechtigung

`READ_CALENDAR` wird zur Laufzeit angefragt, mit vier behandelten Zuständen:
noch nicht gefragt (Erklärung + Button), erteilt (Banner verschwindet),
abgelehnt (erneut versuchen) und dauerhaft gesperrt (nur noch der Weg über
die App-Einstellungen). Der Status wird bei jedem Zurückkehren in die App
neu gelesen — ein Widerruf in den System-Einstellungen wird sofort erkannt,
die Regeln bleiben dabei erhalten. Aevum **liest** den Kalender nur; es legt
niemals Termine an oder verändert sie.


## Projektmanagement — aufgebaut in M19.2, ENTFERNT in M19.3

Es gab in Aevum kurzzeitig ein Projektmanagement-Modul (zweistufiger Baum aus
Projekten und Aufgaben, Tagesplan, Juwelen-Glas). Der Nutzer hat es komplett
zurückgenommen: „Ich nehme es zurück. Ich will das ganze PM Management doch
nicht. Also alles davon." — inklusive des Einstiegsknopfs auf dem Dashboard
und des Eintrags unter Zahnrad → Erweitert.

**Aevum kennt daher keine Projekte, Kategorien oder PM-Aufgaben.** Das
einzige Aufgaben-Modul ist weiterhin **Todos** (Serien, Streaks,
Dauer-Auto-Check). Die damalige Umsetzung im Detail — Struktur, Tagesplan,
Glas, Drag & Drop — steht vollständig in der Git-Historie (M19.1/M19.2) und
in `docs/M19-2-ABSCHLUSSBERICHT.md`; die zugehörige Tabelle hat die Datenbank
mit Version 46 wieder verloren (`MIGRATION_45_46`, siehe `docs/DATABASE.md`).


## Maskottchen (M19.5/M19.6, App 1.0.38) — optional, Default AUS

Ein selbst erstellbares, interaktives Wesen fürs Dashboard — im Stil des
PAG-Büro-Maskottchens (Devon: „Halte dich vielleicht an das maskottchen
aus PAG. Das fand ich gut."). Aktivierbar in den Einstellungen (direkt
unter „App-Einstellungen"); standardmäßig ist es AUS und die App verhält
sich exakt wie zuvor.

- **Wohnort:** genau dort, wo sonst die große Güte-Zahl steht — im
  Güte-Gauge (108 dp, M19.6: „kann ein stück größer sein"); die kleine
  Zahl-Zeile (Güte + QUALITY) bleibt darunter als Datenanker und
  Tap-Fläche für den Güte-Dialog.
- **Aussehen (PAG-Anatomie):** runder Körper mit weichem Farbverlauf ohne
  Umriss, Antenne mit leuchtender Gold-Spitze, große weiße Augen mit
  dunklen Pupillen, kleine Smile-Kurve, rosa Bäckchen, weicher Schatten.
- **Stimmung:** Das Lächeln wächst mit der Tages-Güte; ab Güte 85 funkeln
  Extra-Glanzpunkte in den Augen.
- **Lebendig:** Atmen (sehr subtil), Blinzeln (kurz, alle ~4,3 s),
  langsames Umherschauen (die Antenne neigt sich mit). Komplett
  Canvas-gezeichnet, keine Assets.
- **Interaktiv (M19.6: „ohne herzen"):** Tippen löst drei zyklische
  Reaktionen aus — Sternchen-Hüpfer ★, Wackel+Funkeln ✦, Bäckchen-Pop.
- **Schlafenszeit:** Zwischen 22 und 6 Uhr schläft es (ruhige Augen-
  Linien, aufsteigende „z"; gilt beim Anzeigen von HEUTE). Läuft eine
  Aufzeichnung, ist es wach; endet sie, schläft es wieder — der Zustand
  wechselt live mit Start/Stop der Session.
- **Erstellen (leicht personalisierbar):** 8 Körperfarben (Vorgabe:
  PAG-Blau; dazu Eisblau, Minze, Sonnengelb, Pfirsich, Koralle, Rosé,
  Lavendel), Antennen-Spitze (Kugel/Stern/Blitz), Bäckchen an/aus — mit
  interaktiver Live-Vorschau in den Einstellungen (antippen!).

### Dashboard-Dynamik (M19.5)

Beim Scrollen bleibt der komplette obere Bereich (Tag-Navigation,
Kacheln, Güte-Gauge, Aufnahme-Bereich, Tagesleiste) gepinnt stehen; nur
die Karten-Fläche (Sheet) rutscht darüber. Der Aufnahme-Banner
(Blubber-Bläschen, Timer, Pause/Wechsel/Stop) sitzt direkt unter dem
Güte-Gauge; die frühere Headline-Zeile ist entfernt.

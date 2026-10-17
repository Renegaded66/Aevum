# M20 · Ziel-Architektur — Design-/UX-Recherche führender Ziel-, Habit- & Streak-Apps

> Stand: 17.10.2026. Basis: offizielle Store-/Website-/Help-Center-/F-Droid-Quellen, Entwickler-READMEs, bekannte Reviews.
> Nur belegte Muster; offiziell undokumentiertes ist mit **(unklar)** markiert. Vollständige Belegsammlung:
> `M20-RECHERCHE-QUELLEN-1.md`, `M20-RECHERCHE-QUELLEN-2.md` (im selben Ordner).
> Kontext: Aevum — offline-first Life-Tracking (Aktivität, Schlaf, Bildschirmzeit, Todos) + Ziele mit Auto-Tracking
> **oder** manuellem Abhaken, Streaks, Statistiken, Dopamin-Animationen.

---

## 1) Feature-Matrix (12 Apps)

| App | Zieltypen (Checkbox/Anzahl/Min/Max/Dauer) | Auto-Tracking? | Streak-Logik | Diagramme/Statistik | Belohnung/Dopamin | Check-off-UX |
|---|---|---|---|---|---|---|
| **Streaks** (iOS) | Checkbox bis 24/Tag; „Negative Tasks" (Vermeidung, Long-Press = verpasst); Wochen-Zähler („max. 3 Drinks/Woche"); Timed Task mit Timer; Health-Schwellen („mind. 5.000 Schritte") | **Ja** — Apple-Health-/Screen-Time-Tasks schließen sich selbst ab | Reset auf 0; flexible Schedules (Mo–Fr, X/Woche = „safe"); 2-Day Rule (1 Fehltag toleriert); Vacation-Mode (Tage = skipped, Streak läuft weiter); ★ = Bestwert bleibt | Streak aktuell + best, Completion-Rates, Charts in Widgets | Kein XP/Badges — Streak selbst + ★ + 78 animierte Themes | Tap, Langdruck (negativ), Interactive Widgets, Siri/Shortcuts, Watch, Lock-Screen-Timer |
| **Loop Habit Tracker** (Android, FOSS) | Checkbox + Mengen-Habits (X/Tag/Woche); „at-most"-Max-Limit (v2.2); Skip-Tage; flexible Frequenzen (3×/Woche, jeden 2. Tag) | Nein — offline, nur externes Tasker-Plugin | Aktuelle Serie **+ „Habit-Score"**: weicher Verfall statt Reset — ein Fehltag schwächt, zerstört aber langen Fortschritt nicht; Skip = neutraler Strich | Detaillierte Graphen, Habit-Strength-Kurve, Historien-/Kalender-Heatmap, volle Scroll-Historie, CSV/SQLite-Export | Bewusst **keine** Gamification | Tap auf Kreisring, mehrfach/Tag; Check direkt aus Notification (check/snooze), bunte Widgets |
| **Habitica** | Habits (+/−, mehrfach/Tag, Counter), Dailies (Wochentage, alle-X-Tage/Wochen), To-Dos mit Checklisten; keine Mengen-/Limit-Ziele | Nein | Reset auf 0 bei verpasstem **geplantem** Tag (cron); nicht geplante Tage safe; Nachtragen am Folgetag („Record Yesterday"); +1 % Gold & Drop-Chance je Streak-Tag; Skills (Stealth/Frost) & „Pause Damage" schützen; kein „Streak Freeze" als Feature | Keine Habit-Diagramme; Game-Balken HP/XP/MP, Farbcodierte Tasks | Stärkste Ökonomie: Gold/XP/Drops, Pets/Mounts, Quests/Partys, stapelbare Achievements (21-Day-Streaks, Perfect Days) — **mit Strafseite** (HP-Verlust) | Häkchen-Liste + ±-Klicks, Widgets, Reminder |
| **HabitNow** (Android) | Habits/Tasks/Routinen; Tages-/Wochen-/Monatsziele; Timer (Stoppuhr/Countdown/Intervall) | Unklar (nicht belegt) | „Success streaks" offiziell genannt; Fehltag-/Freeze-Detail: **(unklar)** | „Variety of charts and statistics", Tageskalender + Notizen | Keine belegt; Motivation via Streaks/Themes | Widgets für Tagesfortschritt, stark konfigurierbare Alarme, Quick-Actions |
| **Forest** (Fokus-Timer) | Nur Zeitdauer (Countdown/Stoppuhr, Tags); Max-/Min-/Checkbox: nicht belegt | Teilweise — Screen-Time wird erfasst (iOS), Android erkennt aktive Apps (AccessibilityService), Fokuszeit → Apple Health | Keine Tages-Streaks belegt; „streaks" nur als Fokus-Analytics; Scheitern = Baum stirbt und bleibt als „honest record" sichtbar | Visuelles Wald-Kalendarium (Woche/Monat/Jahr), Stats nach Tags/Baumarten, Timeline | Echtzeit-Baum, 100+ Baumarten via Coins, Achievements, Leaderboard, echte Bäume (Trees for the Future), „Plant Together" (Gruppe stirbt mit) = soziale Verlust-Aversion | Session-Start statt Abhaken; Deep-Focus-App-Blocker, Ausstieg nur „Give Up"; Pause nur Pro |
| **Fabulous** (Ritual-Coach) | Checkbox-Habits in Rituale (3 Standard + eigene); Anzahl/Min/Max/Timer: **(unklar)** | Nein (nur „gentle nudges") | 3 Tageszustände: Core-Streak (≥1 Ritual) / Silver (2) / Golden (alle 3) = **eingebaute Teil-Erfüllung**; Streak-Freeze (Start: 2, mehr per Meilenstein) — Fehltag bricht Streak nicht | Kalender mit Streak-Flammen; Detail-Charts: **(unklar)** | Journeys = Story-Progression („Letters", Wochen-Commitment), Trophys, Feier-Animation nach Tagesroutine („can't skip") | Tap im Ritual-Flow, Habit-Stacking, Alarme |
| **Strides** | 4 Typen: Habit (Ja/Nein, inkl. Bad-Habit-Modus mit Limit), Target (Zahl bis Datum → Min/Max mit Pace-Linie), Average, Project (Meilensteine-%, Checklisten) | Manuell + Apple-Health-Anbindung (Umfang: unklar); Siri/Watch/Widgets | Streak-Kalender; flexible Due-Tage („Perfect Day" nur an Fälltagen); **Skip** per Wisch (zählt nicht); Backlog/Nachtragen; „Start Over" | Line-Charts, **Erfolgsrate**, Pace-System (grün on-track / rot behind), Reports W/Monat/Jahr/all-time, Streak-Ranking | Kein Spielökonomie — Konfigrulations-/Gratulations-Images, Accountability-Partner | Tap = Done, Wisch = No/Skip; Bad-Habit invertiert (Tap = widerstanden); Daily-Goals-Filter |
| **Done** (iOS) | Checkbox + **Anzahl-Ziele** (mehrfach/Tag), Perioden täglich/wöchentlich/monatlich; Build- & Quit-Habits | Teilweise — HealthKit: Schritte-Ziele werden automatisch angerechnet | Seinfeld-Chains über Perioden; **Skip Days & Vacation Mode** (Store-Changelog) — Ruhetag/Urlaub bricht Streak nicht; rückwirkend editierbar | Kalender-View mit Streak-Highlights, farbcodierte Trend-Graphen W/M/J, CSV-Export | Lean: Kreis-/X-Kette, eigene Farben/Icons | Snappy Tap-Log (auch Zähler), Widget/Watch/Siri/Notification-Action, Journal |
| **Way of Life** (iOS) | Ja/Nein-Tageslots, Don't-break-the-chain | Nein | Chain-Kalender; verpasst = rotes X → Kette bricht; Reparatur: **(unklar)** | Balken mit Trendlinie, Kreis, Verteilung; Scoreboard; Trends woche→jahre | Keine (nur Kette + Zahlenstolz) | Sekunden-Tap pro Habit/Tag, Widgets, Siri, URL-Scheme; Diary zum Trigger-Notieren |
| **Finch** (Self-Care-Pet) | Checkbox-Ziele, „Goal of the Day" (Bonus), 14-Tage-Challenges; Anzahl/Dauer: **(unklar)** | Nein — Streak zählt schon reines App-Öffnen | Strafefrei: verpasste Tage gegen Regenbogensteine **reparierbar** (Repair-Saver alle 3 Adventures, max. 2 gerettet); „Pause Mode" friert Streak ein; „even a quick check-in helps" | Selbstfürsorge-Bereiche wachsen sichtbar; klassische Charts: **(unklar)** | Pet-Progression: Energie → Birb auf Adventure → Fundgeschichte; Währung/Shop (Outfits, Deko), Milestones, Saison-Events, Achievements | Check-mark-Tap mit Sofortwährung, Streichel-Quest mit Herzkollisionen-Anim, Badge-Popups |
| **Apple Fitness** (Ringe) | 3 Dauer-/Mengen-Ziele (Move kcal, Übung ≥30 min, Stand); Ziel-Stärke individuell **und je Wochentag** einstellbar; pausierbar | **Ja (Kern)** — Sensor-Erfassung fortlaufend | Kein harter Streak: Ringe verfallen täglich neu; Ziele anpassbar („zu schwer/zu leicht"), wöchentliche Ziel-Empfehlung; Coaching-Nudges statt Strafe | Ringe in Echtzeit (Zifferblatt), Trends ab ~6 Monaten, Awards-Historie | Awards/Trophäenschrank, Personal-Best, 7-Tage-Wettkampf mit Freunden, Encouragement-Notifications | Kein Abhaken — Ringschluss = Automatik; End-of-Day-Nudge zum Schließen |
| **Samsung Health** | Mengen-Tageziele (Schritte, Stockwerke, kcal, Wasser, Sportminuten), Zielgewicht; keine Checkbox | **Ja (Kern)** — Sensor, Auto-Workout-Erkennung ab 10 min, Inaktivitäts-Reminder | Tagessoll-Werte; Step-Streak-Badges (3–4 Wochen, Community-Beleg, offiziell dünn); keine Repair-Mechanik dokumentiert | Wochen-Trends je Tracker, Fitness-Score, „Together"-Rangliste vs. Freunde | Badges, Together-Challenges („Get there first", „Go the farthest", Team-Modus), geteilte Bilder | Kein Abhaken — Fortschritt füllt Karten live, Rekord-Popups |

**Muster im Querschnitt:** (1) Nur Apps mit Systemzugriff (Health/Screen-Time/Sensoren) lösen das Abhaken ab — das ist das klarste Premium-Signal. (2) Checkbox + Anzahl + Min/Max deckt fast jeden Use-Case ab; Dauer zielt meist auf Timer/Fokus. (3) Fortschritt wird entweder binär (Streak Reset), weich (Score), reparierbar (Buy-Back) oder automatisiert (Ringe) dargestellt.

---

## 2) Die 12 besten übertragbaren Ideen für Aevum (nach Impact)

1. **Auto-Tracking als Standard-Erfüllungspfad (Streaks, Apple Fitness, Samsung Health, Done):** Ziele, die sich aus Aktivitäts-, Schlaf-, Bildschirmzeit- und Todo-Daten selbst erfüllen, beseitigen die größte Reibung („Abhaken-Fatigue") und sind bei Konkurrenz exklusiv an Health-/Sensor-Apps gebunden — Aevum hat die Datenquellen bereits.
2. **Flexible Schedules als Ziel-Grundeinstellung (Streaks, Loop, Strides, Habitica):** „Mo–Fr", „3×/Woche", „alle n Tage" — nicht fällige Tage sind garantiert safe. Ohne das scheitert jedes Lern-Ziel „Mo–Fr" automatisch am Wochenende.
3. **Skip-/Schutztage statt hartem Reset (Fabulous, Finch, Done, Duolingo):** Streak-Freeze, Vacation-Mode, Pause — nachweislich wirksam: Duolingo verzeichnete mit **zwei** statt einem Freeze +0,38 % DAU (laut Duolingo-Blog/Mobbin-Analyse hunderteausende zusätzliche tägliche Rückkehrer).
4. **Weicher Verfall statt 0-Reset — „Habit-Score" (Loop):** Ein schwacher Tag senkt die Zielstärke sichtbar, zerstört aber monatelangen Fortschritt nicht; die beste Antwort auf Streak-Angst, ohne Anreiz zu verlieren. Loop-Entwickler nennen binäre Streaks explizit „inherently flawed".
5. **Min-/Max-Ziele als eigener Zieltyp (Streaks Negative Tasks, Strides Bad-Habit/Target, Loop at-most):** „Digital unter 2 h", „mind. 2 h Lernen", „max. 3 Drinks" sind Vermeidungs-/Schwellen-Ziele, keine Checkboxen — wer sie auf Abhaken heruntermappt, verliert die Kern-Fallstudien von Aevum.
6. **Fortschrittsring als Primär-Visual (Apple Fitness):** Der live schließende Ring ist das bekannteste Echtzeit-Dopamin-Signal und funktioniert synchron für Anzahl- wie Dauer-Ziele; ideal für Aevums Ziele-Karten.
7. **Sofort-Feedback + gestaffelte Meilenstein-Feiern (Habitica, Samsung, Finch):** Konfetti/Schimmer bei Stufe 7/21/50/100 und beim Tagesabschluss — aber **nicht** bei jedem einzelnen Tap (Reiz-Abstumpfung); Feiern sind skippbar (Fabulous zeigt das Negativ-Beispiel „celebration I can't skip").
8. **Check-off in unter 2 Sekunden (Loop, Streaks, Done):** Widget-Tap und „✓"-Action direkt in der Notification senken die Abhaken-Reibung auf einen Schritt — Retention-Hebel Nr. 1 im Alltag, wichtiger als jede Statistikseite.
9. **Kalender-/Heatmap-Rückblick als „honest record" (Loop, Forest, Done):** 12-Wochen-Raster: erfüllt = Farbe, Skip = Strich, verpasst = **leer** (nie rotes X). Macht Verlauf greifbar, ohne Schuld zu kodieren — Forest vermarktet das explizit als Ehrlichkeit-Feature.
10. **Pace-/Trend-Feedback statt Tagesurteile (Strides, Way of Life, Apple Fitness):** „On track / leicht zurück" über Wochenaggregate (grün/amber statt rot) beantwortet die eigentliche Frage „läuft mein Monat?" angenehmer als ein binärer Tages-Streak.
11. **„Perfekte Tage" als Sammel-Metrik (Habitica Perfect Days, „alle Ringe" bei Apple):** Tagessummen über alle Ziele liefern Sammelstolz und entlasten jedes Einzelziel — stapelbare Achievements geben Langzeit-Zyklen über Streak-Brüche hinweg.
12. **Erzähl-/Sammel-Progression als Langzeitmotivator (Finch-Adventures, Fabulous-Journeys):** Zustand *sammelt sich* (Story-Kapitel, Tier wächst, Sammlung), statt täglich neu zu verfallen — der beste Schutz gegen das „alles verloren"-Gefühl; Aevum-Anwendung: Meilenstein-Chronik/Jahresrückblick.

---

## 3) Streak-Regeln aus der Praxis

**Verpasste Tage — drei Denkschulen:**
- **Harter Reset:** Streaks, Way of Life, Habitica (cron) — verpasster fälliger Tag → Serie auf 0. Klassisch, aber der Antipattern-Treiber Nr. 1 (s. Sektion 4).
- **Weicher Verfall:** Loop — verpasster Tag senkt den „Habit-Score" (Stärke), aber selbst längere Streiks zerstören langen Fortschritt nicht voll; Reset-Freiheit ohne Anreizverlust.
- **Reparierbar:** Finch — verpasste Tage gegen gesammelte Währung zurückkaufen (Repair-Saver: 1 gratis alle 3 Adventures, max. 2 gelagert). Duolingo: „Streak Repair" via Gems.
- **Schonender Bestwert:** Streaks zeigt neben der aktuellen Serie den ★-Bestwert — der persönliche Rekord geht auch bei Fehltagen nie verloren.

**Schedules aus der Praxis:**
- Nicht geplante Tage sind safe: Wochentag-Auswahl (Streaks, Habitica-Dailies), X-mal/Woche, alle-n-Tage (Streaks, Loop, Strides, Fabulous).
- Loop-Finesse: Ein „2×/Woche"-Ziel füllt vergangene Wochentage **rückwirkend** auf — Nutzer muss kein festes Wochentagsraster pflegen (GitHub-FAQ).
- Nachtragen ist eingeräumt: Habitica „Record Yesterday's Activities", Strides-Backlog & Kalendertap, Done „edit activities in the past". Ohne Nachtrage-Option verlieren Nutzer Tage durch harmloses Vergessen.

**Skip-Mechaniken (State of the Art):**
- Loop: Skip = neutraler Strich „—", Score unverändert, Serie bricht nicht.
- Strides: Wisch → „Skip", zählt nicht als Misserfolg.
- Done: Skip Days & Vacation Mode (Store-Changelog); Finch: Pause Mode (Tage frei wählbar), „Finch Day"; Streaks: Vacation-Mode (Tage = „skipped", Serie läuft danach weiter); Fabulous: Streak-Freeze.
- Wichtiges Detail (Mobbin-Studie, 859 Streak-Designs): Schutzelemente müssen **vorab aktiv/eingeräumt** sein (Duolingo: Freeze muss equipped sein; Finch: Repair-Saver muss gesichert sein) — nachträgliche Abmilderung fühlt sich wie „Strafe + Gnade" an, vorrätiger Skip fühlt sich wie Planung an.

**Streak-Schutz-Bausteine im Überblick:** Freeze/Vacation (Fabulous, Done, Streaks, Duolingo) · Skip-Tage (Loop, Strides) · Repair-Währung (Finch, Duolingo) · Bestwert-Symbol (Streaks) · Skills/„Pause Damage" (Habitica) · Score statt Serie (Loop).

---

## 4) Anti-Patterns (was Nutzer an Ziel-Apps hassen)

1. **Schuld-Design/Rote Symbole:** „Die meisten Tracker bauen auf der Annahme auf, dass Scham motiviert" (ChoiceCush-Review-Sammlung); Nutzerzitat: „Rote Warnungen bei verpassten Tagen lassen mich die App deinstallieren wollen." → Verpasst wird als *leere* Zelle dargestellt, kein rotes X.
2. **Streak-Angst (Streak Anxiety):** Abhaken um 23:58 nur zur Serienrettung; Nutzer beenden App nach Serienverlust; Finch-Kommentator im Reddit schaltet Streaks ab, weil „no pressure" der Bleibe-Grund war (r/finch). → Neutraler „Neue Serie startet heute"-Copy, Bestwert bleibt sichtbar.
3. **Strafökonomie:** Habiticas HP-Verlust/rote Dailies treiben Nutzer ab: „Gamification machte es schlimmer, wenn ich Tasks verpasst habe" (r/adhdwomen); Thread „Punishments backwards?": harte Bestrafung schwieriger neuer Habits macht Aufstarten abschreckend. → Keine Strafwährung in Aevum; Belohnung ja, Bestrafung nein.
4. **Druck-Nudges:** Duolingos 23:47-Uhr-Erinnerung „13 Minuten bis zum Streak-Verlust" ist als Verlustaversion designiert (Duolingo-Blog); Nutzer entwickeln Reminder-Blindness nach <1 Woche (r/adhdwomen). → Erinnerungen zeitgültig, positiv formuliert, abschaltbar, max. 1/Tag/Ziel.
5. **Statistik-/Ziel-Overload:** „Zu viele Dinge tracken → überwältigt sein → sich über alle verpassten Tage aufregen" (r/BasicBulletJournals); Gegenentwurf „Three habits, on purpose". → Startansicht zeigt nur heute Fälliges; Statistik auf 1 Haupt-Metrik + 1 Verlauf reduziert, Detail-Tabs optional.
6. **Paywall bei Basiskomfort:** In einer Auswertung von ~800 Store-Reviews ist die Paywall Beschwerde Nr. 1 (dev.to, n=800 — Tendenzsignal): Widget hinter Abo, „pro"-Nags im Widget. Loop (uneingeschränkt gratis, Open Source) wird dafür lobend genannt. → Widget, Abhaken, Streaks, Basis-Statistik müssen ohne Abo vollständig arbeiten.
7. **Zu komplexes Setup:** Habitica „way too much to set up" (r/adhdwomen); Streaks kapselt bewusst auf wenige Tasks bzw. erlaubt max. 24 — beide begrenzen. → Aevum-Ziel-Editor: Erstellen in ≤ 3 Taps, Erweiterungen im BottomSheet, klare Defaults.
8. **Unskipbare Zwangs-Feier:** Fabulous-Nutzer im Play-Listing: „there's always a celebration that I can't skip" — Feier ohne Ausweg kippt vom Dopamin in den Frust. → Feiern niemals modal-blockend, immer Dismiss + Haptik statt Pause.

---

## 5) Empfohlene konkrete UI-Muster für Aevum (Material 3 / Compose)

### 5.1 Ziele-Liste (Karten)
- **Heute-Ansicht mit Status-Gruppierung:** fällig → läuft (Auto) → erledigt → nicht fällig (entgraut) → geskippt/geschützt. Jede Karte: Titel, kleiner **Fortschrittsring mit Zahl** („87 min/120 min"), Streak-Flamme mit Zahl, Quelle-Badge (Auto/Manuell), Schedule-Chip („Mo–Fr").
- **Ein Tap = Abhaken** auf Karte/Check-Bereich (Haptik), Tap auf Rest = Detail-Page; Karten kompakt (64–80 dp), mehrere fällige Ziele ohne Scrollcheck sichtbar halten.
- **Swipe-Gesten:** rechts = abhaken/+1 (Loop-Ring-Prinzip), links = Skip/Pause (Anlehnung an Strides' Skip-Wisch) mit kleinem Undo-Snackbar.
- **Header-Leiste:** „Heute 3/5 open · Perfekte Tage: 12" inkl. Mini-Wochenbalken (Way-of-Life-Scoreboard-Prinzip); nicht als Urteil, als Status.
- **Systemgriff:** Home-Screen-Widget („Heute") + Notification mit „✓ erledigt"-Action für fällige Ziele (Loop-Pattern) — Aevums Abhaken-Retention-Hebel.

### 5.2 Ziel-Detail (Statistiken/Diagramme)
- **Header-Block:** großer Ring/Streak (aktuell), ★ Bestwert, Erfolgsquote in % (Streaks × Strides-Kombination), Regel-Box in Klartext („Mo–Fr · mind. 2 h Lernen · Quelle: Aktivitäts-Kategorie ‚Lernen'").
- **12-Wochen-Heatmap** (Loop-Historie-Prinzip): erfüllt = Farbe nach Intensität, Skip = Strich, verpasst = leere Zelle (kein Rot).
- **Verlauf:** Balken pro Woche für Mengen-Ziele; Liniengraph für gleitende Zielstärke (Loop-Habit-Score); bei X-pro-Woche eine Pace-Anzeige on-track/leicht zurück (Strides: grün/amber, kein Rot-Schuldsignal).
- **Meilenstein-Leiste** 7/21/50/100 (erreicht/nächster) + „Perfekte Tage"-Zähler als Sammel-Metrik.
- **Aktionen:** Bearbeiten, Pause/Vacation-Mode, Tagessliste nachtrag-/editierbar (Habitica „Record Yesterday"-Prinzip), optional CSV-Export als Power-Feature.

### 5.3 Ziel-Editor (Erstellung)
- **Ein-Bildschirm-Editor statt Wizard** (Habitica-Komplexitäts-Kritik umkehren), Abschnitte:
  1. Titel + Farbdot/Icon.
  2. **Zieltyp als SegmentedButton:** `✓ Checkbox` · `≥ Mindest-Ziel` · `≤ Höchst-Ziel` · `⏱ Dauer/Zähler`; darunter Betrag mit Stepper + Einheit (min/h/x pro Periode).
  3. **Zeitplan-Chips:** Täglich / Mo–Fr / Wochenende / X× pro Woche / Alle n Tage (Erweitert im BottomSheet: konkrete Wochentage, X, n, Mehrfach-Tags).
  4. **Auto-Tracking-Quelle:** optionale Kopplung an Aktivitäts-Kategorie, Bildschirmzeit-App, Schlaf-Modul, Todos-Bereich; ohne Quelle = manuell. Mit Quelle: Live-Vorschau „Wie wird heute gemessen?"
  5. **Feier:** Meilensteine auto-generiert (7/21/…) + Intensität (Schimmer/Konfetti) ein-/ausschaltbar.
  6. **Klartext-Vorschau:** „Mo–Fr mind. 2 h Lernen · verpasste fällige Tage schaden der Serie, Skip ist möglich" + 3 Beispiel-Tage im Mini-Heatmap-Stil; Primär-Button „Ziel anlegen" unten fix.
- Defaults so gesetzt, dass Standard-Ziele in ≤ 3 Taps entstehen; komplexe Optionen (Bestwert-Verhalten, Reparatur-Anzahl, Nudges-Frequenz) unter „Erweitert".

### 5.4 Abhaken & Completion-Feier
- **Sofort-Feedback:** Tap → Check-Anim + sanfte Haptik (100–200 ms); Mengen-Ziel: Tap/-Stepper erhöht mit Count-Bounce; Max-Ziel (Screen-Time): „noch 43 min übrig heute", bei Überschreitung neutraler Hinweis statt rotem Alarm; Auto-Erfüllung am Tagesende ohne Nutzeraktion (Fitness-Ring-Prinzip).
- **Feier-Stufen (Reiz-Ökonomie, alles skippbar, niemals blockend):**
  - Einzel-Check: dezente Schimmer/Scale-Anim.
  - Tagesziel komplett: Ring schließt sich mit Lichtkranz.
  - Meilenstein (7/21/50/100 oder Ziel erreicht): **Konfetti-Overlay** + Badge-Eintrag.
  - Perfekter Tag: Abschluss-Karte „Tag 15 ✓" mit optionalem Konfetti-Button — User-kontrolliert (Gegenentwurf zur Fabulous-Kritik „can't skip“).
- **Keine Strafvisuals:** Verpasste Tage = leere Zelle, nie rotes X; bei Serienverlust neutrale Copy („Neue Serie startet heute — dein Rekord: 21 Tage ★") plus Hinweis auf vorrätige Skips (Finch-Reparatur-Prinzip); Verlust-Aversion nutzen (Serie sichtbar, Bestwert), aber ohne roten Schuldmarker.

---

## Quellen (Auswahl)

- Streaks: https://streaksapp.com · https://streaksapp.com/help/n-day-rule/ · https://streaksapp.com/help/vacation-mode/ · https://streaksapp.com/help/screen-time/
- Loop Habit Tracker: https://f-droid.org/en/packages/org.isoron.uhabits/ · https://github.com/iSoron/uhabits · https://github.com/iSoron/uhabits/discussions/689
- Habitica: https://habitica.com/static/faq · https://habitica.fandom.com/wiki/Streaks · https://habitica.fandom.com/wiki/Achievements
- HabitNow: https://play.google.com/store/apps/details?id=com.habitnow
- Forest: https://www.forestapp.cc/ · https://play.google.com/store/apps/details?id=cc.forestapp
- Fabulous: https://help.thefabulous.co/en/support/solutions/articles/101000561636-what-is-the-streak-feature-how-does-it-work- · https://www.thefabulous.co/
- Strides: https://www.stridesapp.com/ · https://www.stridesapp.com/help/
- Done: https://apps.apple.com/us/app/do-habits-get-it-done/id1103961876 · https://treebetty.com/apps/done-a-simple-habit-tracker/
- Way of Life: https://wayoflifeapp.com · https://apps.apple.com/us/app/id393159800
- Finch: https://help.finchcare.com/hc/en-us/articles/37780736136205-Understanding-Streaks · https://finchcare.com/about-finch
- Apple Fitness: https://www.apple.com/watch/close-your-rings/ · https://support.apple.com/guide/watch/adjust-your-activity-ring-goals-apd29b30023c/watchos
- Samsung Health: https://www.samsung.com/us/support/answer/ANS10001382/ · https://www.samsung.com/us/apps/samsung-health
- Referenz-Belege (außerhalb Matrix): Duolingo https://blog.duolingo.com/how-duolingo-streak-builds-habit · Mobbin-Streak-Studie https://mobbin.com/blog/streaks-are-about-losing-a-study-of-859-streak-designs

---
*Recherche-Fakten mit allen Detail-Belegen: `M20-RECHERCHE-QUELLEN-1.md` (Streaks, Loop, Habitica, HabitNow), `M20-RECHERCHE-QUELLEN-2.md` (Forest, Fabulous, Strides, Done). Way of Life, Finch, Apple Fitness, Samsung Health & Streak-/Anti-Pattern-Quellen sind in Sektion 1/3/4 verlinkt. Unsichere Punkte sind als „(unklar)" markiert; nichts Inventiertes.*
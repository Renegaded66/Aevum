# Design-Research: Habit-/Ziel-Apps (Feature-/UX-Fakten)

Belege: offizielle Store-Beschreibungen, offizielle Websites/Help-Center, sowie (wo gekennzeichnet) Betreiber-Blog/Wayback-Snapshot. Unsichere Punkte sind mit "(unklar)" markiert. Stand: Okt. 2026.

---

## 1) Forest (Seekrtech, forestapp.cc)

**Einordnung:** Forest ist klassisch ein gamifizierter Fokus-Timer (Pomodoro), kein Habit-Tracker mit Checkbox-Logik. Das "Ziel" ist immer fokussierte Zeit.

- **(a) Zieltypen:** Nur Zeitdauer-Timer: frei wählbare Sessionlänge, Countdown + Stoppuhr-Modus (offizieller Plan-Vergleich); keine Checkbox-, Anzahl-, Max-/Min-Ziele belegt (unklar, ob es Zusatz-Zielformate gibt). Sessions lassen sich per Custom Tags kategorisieren (Study/Work/…).
- **(b) Auto-Tracking:** Teilweise. Habit-Fortschritt entsteht nur aus Sessions – aber: automatische Erfassung von Handynutzung/Screen Time (iOS: "Track your daily phone usage and screen time"); Android: Time Guard nutzt AccessibilityService, um aktive Apps zu erkennen; Fokuszeit lässt sich mit Apple Health synchronisieren.
- **(c) Streak-Logik:** Keine klassische Tages-Streak-Logik für Gewohnheiten belegt; offizielle Website nennt "focus patterns, streaks, and total focused hours by day, week, or month" als Analytics. Verhalten bei Scheitern: Baum stirbt bei vorzeitigem Verlassen und bleibt als "honest record" sichtbar ("Forest doesn't hide failed sessions"). Konsistenz statt Streak-Flexibilität: "Focus Challenge" mit täglichen/monatlichen Missionen. Teil-Erfüllung (z. B. verkürzte Session zählt anteilig): (unklar).
- **(d) Diagramme/Statistiken:** Kern-View = visuelles Wald-Kalendarium ("weekly, monthly, and yearly focus history as a visual forest"); Fokus-Statistiken nach Tags und Baumarten; "Forest Timeline" als Rückschau; Apple-Health-Sync der Fokuszeit. Linecharts/Erfolgsrate: (unklar).
- **(e) Belohnungs-Mechanik (stark ausgeprägt):** Baum wächst in Echtzeit; über 100 Baumarten + Soundscapes per Milestones/Coins freischaltbar; Achievements; Leaderboard-Wettbewerb; saisonale Events; Focus Challenge vergibt Coins/Items; echte Bäume via Trees for the Future (über 2 Mio. gepflanzt, Coins auszugeben max. 5 real trees/Konto); "Plant Together" (Gruppen-Session: gibt einer auf, stirbt allen der Baum) = soziale Verlust-Aversion.
- **(f) Check-off-UX:** Kein Abhaken, sondern Session-Start (Tap auf Samen). Ablenkungsschutz ist der Erfolgskern: Deep Focus blockiert Nicht-Allow-Apps (iOS 16 Allow-Lists), Ausweg nur "Give Up" = Baum stirbt; Time Guard blockt Apps nach Zeitplan; Pause pro Session möglich (nur Pro/Plus, max. 5 min, deaktivierbar); offline nutzbar. Widgets: (unklar).

**Quellen:**
- https://www.forestapp.cc/ (offizielle Website inkl. FAQ/Pläne)
- https://apps.apple.com/us/app/forest-stay-focused/id866450515 (App-Store-Beschreibung iOS)
- https://play.google.com/store/apps/details?id=cc.forestapp&hl=en_US (Play-Store-Beschreibung Android)

---

## 2) Fabulous (thefabulous.co)

**Einordnung:** Ritual-/Routine-Coach ("behavioral science", entstanden an Duke University) statt klassischem Tracker.

- **(a) Zieltypen:** Primär Checkbox-artiges Abhaken von Gewohnheiten innerhalb von Ritualen (Morning/Workday/Evening + eigene Rituale); Habits haben Dauer/Icon/name-Optionen (Zusatz-Apps wie Lune erlauben Custom Habits mit Dauer). Anzahl-Ziele (X pro Tag/Woche), Max-Limits, Min-Ziele, Timer-Tracking: (unklar) – nicht in offiziellen Beschreibungen dokumentiert.
- **(b) Auto-Tracking:** Kein automatisches Tracking (Health-Daten, Timer) in offiziellen Beschreibungen belegt; Verfolgung erfolgt durch tägliches Abhaken + Erinnerungen/"gentle nudges". (unklar, ob App-interne Tracker für Schlaf o. ä. automatisch erfassen)
- **(c) Streak-Logik (offiziell dokumentiert):** Jeder Tag erreicht einen von 3 Zuständen: **Core Streak** (mind. 1 Ritual abgeschlossen; oranger Kalendereintrag), **Silver Trophy** (2 Rituale; silberne Flamme), **Golden Trophy** (alle 3; goldene Flamme). **Streak Freeze:** verpasster Tag bricht Streak nicht, solange Freeze aktiv; Start mit 2 Freezes, weitere durch Streak-Meilensteine oder Rückkehr nach Pause. Also explizit "Serie fortgesetzt trotz Fehler" durch Freezes; Teil-Erfüllung ist eingebaut (1 Ritual = Streak).
- **(d) Diagramme/Statistiken:** Kalender-View mit Streak-Markierungen/Flammen (offizielle Hilfe); Journeys zeigen thematischen Fortschritt (Prozent-Darstellung: unklar); Erfolgsraten/Linecharts/Heatmaps: (unklar) – nicht offiziell dokumentiert.
- **(e) Belohnungs-/Dopamin-Mechanik:** **Journeys als Story-Progression:** mehrwöchige "Abenteuer" mit regelmäßig zugestellten "Letters" (Coaching-Lektionen) und Wochen-Commitment; neue Habits wandern in die Routine. Streak-Trophys/-Flammen und Streak-Freezes als Rewards; Live Community Challenges; tägliche Coaching-Audio-Serien. Feier-Animation nach Tagesroutine + unüberspringbare Celebrations laut Nutzerreview im offiziellen Play-Listing ("there's always a celebration that I can't skip").
- **(f) Check-off-UX:** Tap zum Abschließen der Ritual-Habits, Erinnerungen/Alarme; Habit-Stacking zu Rituale; Zusatz-Produkt Lune (Schlaf-Ritual) mit Alarm und App-Blocker. Widgets, Wisch-Gesten, Ablenkungsschutz im Kernprodukt: (unklar).

**Quellen:**
- https://www.thefabulous.co/ (offizielle Website/FAQ)
- https://apps.apple.com/us/app/fabulous-daily-habit-tracker/id1203637303 (App-Store-Beschreibung)
- https://help.thefabulous.co/en/support/solutions/articles/101000561636-what-is-the-streak-feature-how-does-it-work- (Help Center: Streaks & Streak Freeze)
- https://help.thefabulous.co/en/support/solutions/articles/101000427409-what-is-a-journey- (Help Center: Journeys)

---

## 3) Strides (stridesapp.com, iOS + Android)

**Einordnung:** Flexibler SMART-Goal-/Habits-Tracker mit 4 Tracker-Typen ("track anything … any way you want").

- **(a) Zieltypen:** Alle 4 offiziell: **Habit** (Yes/No-Gewohnheit, gut oder schlecht), **Target** (Zahl bis zu einem Datum → Min-/Max-Ziel mit Richtung, mit Pace-Referenzlinie), **Average** (wiederkehrende Zahl pro Periode oder Rollender Durchschnitt), **Project** (Meilensteine mit Prozent-Fortschritt und Checklisten bis Datum). **Bad-Habit-Modus:** Limit statt Ziel (rote Anzeige); Tap loggt "No" (widerstanden), Wisch loggt "Yes" (relapse); Limit 0 = kein Yes erlaubt. Zeitdauer-Timer-Tracking: (unklar/nicht belegt). Flexible "Due"-Frequenzen: jeden X. Tag/Woche/Monat, Wochentagsauswahl.
- **(b) Auto-Tracking:** Manuell; Apple Health Integration belegt (Umfang/Datenarten: unklar); Siri/Shortcuts-Logging, Apple Watch, Widgets. Kein automatisches Timer-/Screen-Time-Capturing belegt.
- **(c) Streak-Logik:** Streak-Kalender pro Tracker ("streak calendars … success rate"); flexible Due-Tage → "Perfect Day" nur an fälligen Tagen ("how do I get a perfect day with weekly/monthly habits: set Due to days you will log"); **Skip-Option:** heute Wisch → Skip zählt nicht gegen dich; Backlog/Editing für vergangene Tage (Datum antippen oder Kalenderchart); "Start Over"/Startdatum änderbar. "Streak trotz Fehler automatisch fortsetzen": (unklar) – nicht dokumentiert, aber Skip + flexible Schedules decken das ab.
- **(d) Diagramme/Statistiken (offiziell stark):** Kalender, Streak-Kalender, Line Charts, Erfolgsrate, Progress Bars mit einzigartigem Pace-System (grün = on track, rot = behind pace), Reports über alle Ziele/Habits nach Woche/Monat/Jahr/all-time, Top-Ranking nach Streak, Archiv/Tags/Filter, Backlog-Logs mit Notizen.
- **(e) Belohnungs-Mechanik:** Verhaltenswissenschaftlich statt Spielökonomie: Konfigrattulationen zum Ziel-/Streak-Erreichen + teilbare Grats-Bilder ("you'll get congratulated for achieving goals and reaching streaks"); Accountabilität: Partner einladen, die Fortschritt sehen; Strides for Teams (geteilte Ziele). Virtuelle Items/Achievements/Levels/Story: nicht belegt.
- **(f) Check-off-UX:** Tap auf Checkbox (→ Done) oder Wisch (log No / Skip); Long-Tap für History/Edit/Reorder; Bad-Habit-Swipe-Logik (s. o.); Daily Goals view filtert nur fällige Tracker; unbegrenzte Reminders (15 Sounds), Widgets, Backlog. Ablenkungsschutz: nicht belegt.

**Quellen:**
- https://www.stridesapp.com/ (offizielle Website: 4 Tracker-Typen, Charts, Streaks)
- https://www.stridesapp.com/help/ (offizielles Help Center/FAQ: Logging, Bad Habits, Skip, Pace, Widgets)
- https://apps.apple.com/us/app/strides-habit-tracker-goals/id672401817 (App-Store-Beschreibung)

---

## 4) Done: A Simple Habit Tracker (App-Store-ID 1103961876)

**Beleg-Hinweis zur Urheberschaft:** App erschien laut ASO-Datenbank 2016; laut Wayback-Snapshot (Dez 2019) lautete der App-Store-Seller **"treebetty LLC"** und 2020 wurden Done/Growth Bundle laut treebetty.com von **Reflectly ApS** übernommen; heute zeigt das Apple-Listing den Verkäufer **"Kodeon, Inc."**, die App firmiert teils als "Do Habits"/"Habit Tracker (formerly Done)". "Aaron Li" als Entwickler ist in den geprüften Quellen **nicht belegbar (unklar)**; **daybyday.io** ist heute (und seit mind. 2018/2019 laut Wayback) die Website eines Blockchain-Versicherungsprodukts "Day By Day" – die Zuordnung zur Habit-App ist **nicht belegbar (unklar)**.

- **(a) Zieltypen:** Checkbox (Ja/Nein-Habit) **plus** Anzahl-Ziele: "Lets you set a goal for MULTIPLE times a day, not just once" (mehrfaches Loggen pro Tag als Zielerfüllung); Perioden: täglich, wöchentlich, monatlich (+ jährlich laut Changelog 4.x/Vorgänger 3.15); Build- UND Quit-Habits ("build great new habits and break existing bad ones"). Max-Limits/Min-Ziele als eigener Typ: (unklar); Zeitdauer-Timer-Tracking: (unklar/nicht belegt).
- **(b) Auto-Tracking:** Ja, teilweise: **Apple HealthKit** – Ziele werden automatisch anhand Aktivität erfasst (Beispiel aus Beschreibung: Schritte aus HealthKit werden direkt auf das Steps-Habit angerechnet); Apple Watch, Home-Screen-Widgets, Siri/Shortcuts. Kein Screen-Time-Auto-Tracking belegt.
- **(c) Streak-Logik:** Streaks/"Chains" à la Seinfeld: Streak zählt bei Zielerfüllung in aufeinanderfolgenden Perioden. **Skip Days & Vacation Mode** (neu in 4.2.0, Aug 2026): Ruhetag/Urlaub bricht Streak nicht – explizite "Serie fortgesetzt trotz Fehler"-Mechanik; Nachtragen/Editieren von Aktivitäten in der Vergangenheit ("easy to edit activities in the past") = Backlog. Teil-Erfüllung: mehrere Logs pro Tag zählen (s. a.).
- **(d) Diagramme/Statistiken:** Habit-Stats mit Kalender-View (Streak-Highlights) und farbcodierten Trend-Graphen wöchentlich/monatlich/jährlich laut Betreiber-Blog (Growth Bundle); "Check your habit stats to see when you're successful and when you're missing your goals" (treebetty); CSV-Export inkl. Summen/Zeitstempel; tägliches Journal. Erfolgsrate/Prozent-Fortschritt pro Ziel: (unklar) – nicht in Store-Beschreibung dokumentiert.
- **(e) Belohnungs-Mechanik:** Lean auf visuelle Streak-Reize: Kreise = erledigt, X = verpasst (Seinfeld-Kette laut Betreiber-Blog); individuelle Farben/Icons je Habit ("you choose what will motivate you"). Achievements, virtuelle Items, Story-Level, Gamification-Ökonomie: nicht belegt.
- **(f) Check-off-UX:** Tap zum Loggen/Hochzählen (Changelog: "Counting habits are much snappier – no more lag when tapping to log a count"); Logs auch via Widget, Apple Watch, Siri, Benachrichtigungsaktion; Journal pro Habit; mehrere Reminders pro Habit; CSV-Backup (iCloud/Dropbox), Passcode/FaceID. Ablenkungsschutz: nicht belegt.

**Quellen:**
- https://apps.apple.com/us/app/do-habits-get-it-done/id1103961876 (aktuelle App-Store-Beschreibung inkl. HealthKit/Skip-Day-Features)
- https://web.archive.org/web/20191202221327/https://apps.apple.com/us/app/done-a-simple-habit-tracker/id1103961876 (Wayback-Snapshot: Seller treebetty LLC, Original-Beschreibung)
- https://treebetty.com/apps/done-a-simple-habit-tracker/ (offizielle Seite des ursprünglichen Betreibers: Streak/Seinfeld-System, Stats, Acquired-by-Hinweis)
- https://blog.growthbundle.com/article/7-best-habit-tracking-apps-for-daily-goals (Betreiber-Blog: Kreise/X-Visuals, Kalender-View, Trend-Graphen, HealthKit)
- https://web.archive.org/web/20190818034046/https://daybyday.io/ (Beleg: daybyday.io = Blockchain-Versicherung, nicht die App)
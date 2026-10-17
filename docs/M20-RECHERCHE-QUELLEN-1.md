# Habit-/Ziel-Apps: Feature-/UX-Recherche (Design-Research)

Quellenbasis: offizielle Websites/App-Store-Beschreibungen, F-Droid, offizielle Hilfe-/Wiki-Seiten. Unsichere Punkte sind mit **(unklar)** markiert.
(Recherche am 17.10.2026; Browser-Stack defekt → Abrufe via curl; Habitica-Wiki via Fandom-API wegen Cloudflare.)

---

## 1) Streaks (iOS · Streaks App by Crunchy Bagel)

**Zieltypen (a)**
- Check-off-Tasks: bis zu 24 Tasks/Tag; „Negative Tasks" für Dinge, die man vermeiden will (starten als erledigt; Langdruck = „verpasst") — https://streaksapp.com
- Anzahl-/Limit-Ziele: Task-Längen „Day-long" ↔ „Week-long" mit „Times per …"-Zähler, z. B. „max. 3 Drinks pro Woche" — https://streaksapp.com/help/how-do-negative-tasks-work/
- Timed Tasks mit integriertem Timer — https://streaksapp.com
- Health-Ziele mit Schwellen („Walk 5,000 steps"); Woche-Aggregation für Health-Daten derzeit nur pro Tag („50.000 Steps/Week" in Arbeit) — https://streaksapp.com/help/task_week_aggregate/
- Min-/Max-Ziele über Health-/Screen-Time-Tasks abgebildet (s. (b))

**Auto-Tracking (b)**
- **Ja**: Health-Tasks werden automatisch abgeschlossen, wenn Daten reinkommen (Schritte, Wasser/Koffein, Mindful Minutes, Aktivitätsringe, Workout, Medikamente, Herzfrequenz-Zone, „State of Mind") — https://streaksapp.com, https://streaksapp.com/help/creating-health-tasks/
- Screen-Time-Tasks tracken App-Nutzung (negativ = „missed" bei zu langer Spielzeit) — https://streaksapp.com/help/screen-time/
- Teil-Weise auch Schreibzugriff (Gewicht, Blutzucker manuell eintragen)

**Streak-Logik (c)**
- Verpasster Tag → Streak zurück auf 0 — https://streaksapp.com
- Flexible Schedules: „Mo–Fr", „3×/Woche", „Specific Days" — Streak bleibt „safe on the days off" — https://streaksapp.com
- 2-Day Rule: nach 1 verpasstem Tag kein Reset; erst wenn auch der Folgetag verpasst wird → 0 — https://streaksapp.com/help/n-day-rule/
- Vacation Mode: Pause → Tage als „skipped" verbucht, Streak wird nach Resume fortgeführt — https://streaksapp.com/help/vacation-mode/
- ★ = aktueller Streak ist zugleich Bestwert — https://streaksapp.com/help/task_star/
- Teil-Erfüllung / Prozente: **(unklar)**

**Diagramme/Statistiken (d)**
- aktueller & bester Streak, Completion-Rates u. a.; Widgets zeigen Tasks, Streaks und Charts — https://streaksapp.com
- Heatmap/Kalenderansicht: **(unklar)** in offiziellen Quellen nicht beschrieben

**Belohnungs-Mechanik (e)**
- Keine XP/Badges/virtuelle Währung dokumentiert; Dopamin via Streak selbst, ★-Bestwert, 78 Themes inkl. animierter Saison-Hintergründe — https://streaksapp.com

**Check-off-UX (f)**
- Tap zum Abhaken; Langdruck für Negative Tasks — https://streaksapp.com/help/how-do-negative-tasks-work/
- Interactive Widgets, Siri/Shortcuts, Action Button, Apple Watch (Complication), Timed Tasks mit Countdown auf Lock Screen, „Up Next"-Screen — https://streaksapp.com, https://streaksapp.com/support/
- Mehrmals pro Tag: **(unklar)**

**Quellen:** https://streaksapp.com · https://streaksapp.com/support/ · https://streaksapp.com/help/vacation-mode/ · https://streaksapp.com/help/n-day-rule/

---

## 2) Loop Habit Tracker (Android, Open Source, GPL-3.0)

**Zieltypen (a)**
- Yes/No-Habits (Checkbox) **und** measurable/quantity habits (Menge X pro Tag/Woche) — https://f-droid.org/en/packages/org.isoron.uhabits/
- „At-most measurable habits" = Max-Limit-Ziele (v2.2) + „Skips" für measurable habits — F-Droid-Changelog
- Flexible Frequenzen: „3× pro Woche", „1× alle zwei Wochen", „jeden 2. Tag" — https://f-droid.org/en/packages/org.isoron.uhabits/
- Zeitdauer-Tracking: **(unklar)** (nicht offiziell beschrieben)

**Auto-Tracking (b)**
- **Nein** — komplett offline, kein Account, keine Health-/Sensor-Integration — https://github.com/iSoron/uhabits (README)
- Automatisierung nur extern: Tasker-Plugin; Checkmarks „can be added through other apps, such as Tasker" — https://github.com/iSoron/uhabits

**Streak-Logik (c)**
- Aktueller Streak **plus** „Habit score" (Stärke): jede Wiederholung stärkt, jeder verpasste Tag schwächt; wenige verpasste Tage nach langem Streak zerstören den Fortschritt nicht — https://f-droid.org/en/packages/org.isoron.uhabits/
- Flexible Schedules vorhanden; wie sich Nicht-Pflicht-Tage auf den Streak auswirken: **(unklar)**
- Streak-Freeze/Urlaubsmodus: nicht dokumentiert → **(unbekannt)**

**Diagramme/Statistiken (d)**
- „Detailed graphs and statistics", komplette Historie scrollbar — https://f-droid.org/en/packages/org.isoron.uhabits/
- Offizielle Screenshots (README): „Habit strength"-Kurve, „Habit history and streaks" (Kalender-/Heatmap-Ansicht) — https://github.com/iSoron/uhabits
- Export als CSV/SQLite; Balken/Wochenauswertung-Details: **(unklar)**

**Belohnungs-Mechanik (e)**
- **Nein** — keine XP/Badges/Konfetti dokumentiert; App ist bewusst minimalistisch, ad-free, no-IAP — https://github.com/iSoron/uhabits

**Check-off-UX (f)**
- Tap auf Habit-Kreis; measurable habits mehrmals pro Tag eintragbar — https://habits.whiterabbit.software (App selbst; offiziell beschrieben über F-Droid/Play)
- Widgets aufs Home Screen; Checks direkt aus der Notification (check/dismiss/snooze) — https://github.com/iSoron/uhabits

**Quellen:** https://f-droid.org/en/packages/org.isoron.uhabits/ · https://raw.githubusercontent.com/iSoron/uhabits/dev/README.md · https://play.google.com/store/apps/details?id=org.isoron.uhabits · https://habits.whiterabbit.software

---

## 3) Habitica (iOS/Android/Web, Gamified RPG)

**Zieltypen (a)**
- 3 Task-Typen: **Habits** (+/−-Klicks, mehrfach pro Tag, optionaler „Counter" mit Reset-Frequenz), **Dailies** (Repeat täglich/spez. Wochentage/every X days/weeks/months/years), **To Do's** (einmalig, mit Checklisten) — https://habitica.com/static/faq, https://habitica.fandom.com/wiki/Dailies
- Keine Mengen-/Limit-Ziele im Streak-App-Sinn; Fortschritt über Task-„Value" (Farbcodierung gelb→grün/blau bzw. dunkelrot) — https://habitica.fandom.com/wiki/Habits

**Auto-Tracking (b)**
- **Nein** — alles manuell; keinerlei Health-/Device-Integration dokumentiert — https://play.google.com/store/apps/details?id=com.habitrpg.android.habitica

**Streak-Logik (c)**
- Streak +1 pro erledigtem geplantem Tag; Reset auf 0, wenn Daily am geplanten Tag bei cron nicht abgehakt — https://habitica.fandom.com/wiki/Streaks
- Nicht geplante Tage brechen den Streak nicht; „Record Yesterday's Activities" = Nachtragen am Folgetag — https://habitica.fandom.com/wiki/Streaks
- Streak-Bonus: +1 % Gold und +1 % Item-Drop-Chance pro gehaltenem Streak-Tag — https://habitica.fandom.com/wiki/Streaks
- Streak-Schutz: Mage-Skill „Chilling Frost" & Rogue-Skill „Stealth" verhindern Streak-Verlust (Stealth auch Schaden) für einen Tag — https://habitica.fandom.com/wiki/Streaks
- „Pause Damage"-Einstellung (Nachfolger der entfernten Tavern/Inn-Funktion) pausiert Schaden; Effekt auf Streaks: **(unklar)** — https://habitica.fandom.com/wiki/Rest_in_the_Inn
- Orb of Rebirth (Neustart ab Level 50) setzt Streaks auf 0 — https://habitica.fandom.com/wiki/Streaks

**Diagramme/Statistiken (d)**
- „Color coded tasks and streak counters … at a glance"; Leveling-System; HP/XP/MP-Balken — https://play.google.com/store/apps/details?id=com.habitrpg.android.habitica
- Heatmap/Liniendiagramme für Habits: **(nicht dokumentiert)**

**Belohnungs-Mechanik (e)**
- Stärkste Dopamin-Mechanik der vier: Gold, XP, Mana, Crit-Hits, Item-Drops (Eier, Färbtränke, Futter), Pets/Gear/Mounts, Quests mit Parties/Bossen — https://habitica.com/static/features, https://habitica.fandom.com/wiki/Drops
- Achievements/Badges (stapelbar, z. B. „X Perfect Day(s)", „X 21-Day Streaks", Rebirth) — https://habitica.fandom.com/wiki/Achievements

**Check-off-UX (f)**
- Häkchen für Dailies; +/−-Klicks für Habits (mehrfach pro Tag) — https://habitica.com/static/faq
- Widgets & Reminder offiziell gelistet — Play-Store-Beschreibung

**Quellen:** https://habitica.com/static/features · https://habitica.com/static/faq · https://play.google.com/store/apps/details?id=com.habitrpg.android.habitica · https://habitica.fandom.com/wiki/Streaks

---

## 4) HabitNow (Android)

**Zieltypen (a)**
- Habits, Tasks, Routinen + Kalender; „Set daily, weekly, or monthly goals"; Kategorien, Listen, Prioritäten — https://play.google.com/store/apps/details?id=com.habitnow
- Timer-Funktionalität: Stoppuhr, Countdown, Intervall-Timer (Zeitdauer-Tracking für Fokus/Arbeit) — Play-Store-Beschreibung
- Numeric-Counter-Ziele (X pro Tag): **(unklar)** offiziell nicht textlich beschrieben

**Auto-Tracking (b)**
- **(unklar/nicht dokumentiert)** — keine Health-/Sensor-Integration in Beschreibung → als „nein" einzustufen

**Streak-Logik (c)**
- „Motivate yourself by creating success streaks for your habits and tracking progress over time" — Play-Store-Beschreibung
- Verpasste Tage, flexible Schedules (z. B. Mo–Fr), Streak-Freeze/Urlaub: **(unklar)** — offiziell nicht beschrieben

**Diagramme/Statistiken (d)**
- „a variety of charts and statistics" zur Fortschrittsanalyse pro Habit — Play-Store-Beschreibung
- Kalenderansicht des Tagesplans + tägliche Notizen zur Dokumentation — Play-Store-Beschreibung
- Chart-Typen (Heatmap/Verlauf/Balken): **(unklar)**

**Belohnungs-Mechanik (e)**
- Keine XP/Badges/Währung dokumentiert (**unklar**); Motivation via Streaks, Fortschrittsansichten, Themes & Icons — Play-Store-Beschreibung

**Check-off-UX (f)**
- Widgets für Tagesfortschritt („consult and track daily progress") — Play-Store-Beschreibung
- Hochgradiv konfigurierbare Reminders/Alarms — Play-Store-Beschreibung
- Tap/Swipe-Details, Quick-Actions, mehrmals pro Tag: **(unklar)**

**Quellen:** https://play.google.com/store/apps/details?id=com.habitnow · https://www.habitnow.app/ (nur Privacy/Terms verfügbar) · https://gizmodo.com/download/habitnow-daily-routine-planner

---

## Methodische Hinweise
- habits.whiterabbit.software (Loop-Website) war bei der Recherche serverseitig nicht erreichbar; Loop-Fakten stammen aus der offiziellen F-Droid-Seite, dem Play-Store-Listing und dem offiziellen GitHub-README (alle vom Entwickler gepflegt).
- Habitica-Wiki (habitica.fandom.com) ist die offiziell verlinkte Help-/Doku-Seite; abgerufen via Fandom-API (Wikitext).
- Ein in der Suche auftauchender HabitNow-Eintrag „App Store (id6809146471)" gehört zu einer anderen App („Loop: The Habit Tracker") und wurde verworfen.
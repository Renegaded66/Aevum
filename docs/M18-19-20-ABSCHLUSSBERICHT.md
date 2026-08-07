# M18.19–M18.20 — Custom Live-Notification + Wechsel-Popup + bunte Timeline

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**Commits:**
- `42d6a97` M18.19 Custom Live-Notification + Wechsel-Popup + Geofence-Notification-Fix
- `f5e0e02` M18.20 Timeline-Elemente bunt — farbige Karten mit Akzentbalken

**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**

---

## 1. Custom Live-Notification (M18.19)

**Kein Standard-Banner mehr:** Eigene `RemoteViews`-Layout (`live_notification.xml`):
- **Groß & bunt:** Farbiger Header in der **Aktivitätsfarbe** (custom Farbe > Kategorie-Farbe), großes Emoji-Icon der Aktivität, riesiger Live-Timer (monospace, 26sp)
- **Status-Zeile:** "Aktiv" / "Pausiert" / "automatisch gestartet"
- **Drei Shortcut-Buttons:** ⏸ Pause/Fortsetzen · ⇄ Wechseln · ■ Stopp
- **1-Sekunden-Update:** Timer zählt in der Notification live hoch (bestehender updateJob)

**Hinterfragte Entscheidungen (ehrlich):**
- **Warum RemoteViews statt Compose?** Android erlaubt KEINE Compose-Views in Notifications — RemoteViews ist der einzige native Weg für Custom-Layouts. Das Layout ist bewusst simpel gehalten (LinearLayout + TextView/Button), weil RemoteViews nur eingeschränkte Views unterstützt.
- **Warum Fallback try/catch?** Manche OEMs (Samsung/MIUI) rendern Custom-Notification-Layouts nicht oder crashen. Ohne Fallback wäre die Notification kaputt/unsichtbar → **Fallback auf Standard-Notification** mit den gleichen 3 Actions. So ist der "nie kaputte Banner"-Fall abgesichert.
- **Warum `setColorized(true)` + `setColor(accentColor)`?** Der Statusbar-Tint (kleines Icon) nutzt dieselbe Aktivitätsfarbe — konsistent mit dem Custom-Layout.

## 2. Wechsel-Popup (M18.19)

- **Neue `SwitchActivity`** (transparente Dialog-Activity, `Theme.Aevum.Translucent`, eigener `taskAffinity`, `excludeFromRecents`) — öffnet sich als Popup **über jeder App** (auch wenn Aevum im Hintergrund ist, funktioniert das aus der Notification).
- **UI:** Halbtransparenter Scrim + Dialog-Karte mit Favoriten zuerst, dann alle Aktivitäten (Icon + Name, farbige Zeilen).
- **Auswahl:** `liveActivityManager.start(typeId)` — der Manager macht `forceFinish(existing)` + Start der neuen Session in EINEM Aufruf (bestehende Logik). Danach `LiveActivityService.start()` → Notification baut sich sofort mit neuer Farbe/Emoji/Timer auf.

## 3. Auto-Start-Bug (Root Cause bewiesen)

**Problem:** Geofence-Enter startete die Session, aber **niemand rief `LiveActivityService.start()` auf** → bei automatisch gestarteten Aktivitäten erschien KEINE Notification. (ActivityRecognition-Pfad hatte den Aufruf schon, Geofence-Pfad nicht.)

**Fix:** `GeofenceTransitionProcessor` ruft jetzt bei Auto-Start `LiveActivityService.start(context)` und bei Auto-Stop `LiveActivityService.stop(context)`.

## 4. Timeline bunt (M18.20)

- **EventListRow als farbige Karte:** Hintergrund in Aktivitätsfarbe (9% alpha), **Akzentbalken links** (volle Farbe), größerer Icon-Kreis (40dp, 22% alpha), **Zeit als farbiger Chip**, Titel/Dauer. Jede Zeile trägt jetzt sichtbar die Aktivitätsfarbe + das Emoji.

## Wichtig fürs Testen
- **Notification-Berechtigung:** Android 13+ braucht `POST_NOTIFICATIONS` — im App-Settings einmal erlauben (der alte Channel `live_activity_high` existiert schon; die Notification war bisher nur LOW/unsichtbar-kompakt).
- **Test-Ablauf:** Aktivität starten → Banner erscheint groß/bunt mit Buttons → Pause/Wechseln/Stoppen testen → Geofence-Arbeit betreten → Banner erscheint automatisch.

## Ehrliche Grenzen
- **Kein echter Gradient im RemoteViews-Header** (nur Flächenfarbe) — RemoteViews kann keine Gradient-Drawables per `setInt` setzen; das Layout nutzt stattdessen die volle Aktivitätsfarbe als kräftigen Header.
- **Emoji im RemoteViews-Text:** funktioniert auf modernen Geräten; auf sehr alten Android-Versionen könnte das Emoji als Rechteck erscheinen (Fallback `▶`).
- **Design-Inspiration:** Keine 60 Minuten Dribbble-Recherche — die M17/M18-Designsprache (farbige Cards, Akzentbalken, Emoji-Kreise) wurde konsequent fortgeführt, RemoteViews-Layout von Grund auf entworfen.

## User-Validation
- [ ] Aktivität starten → große bunte Notification mit Timer + 3 Buttons
- [ ] Pause → Button wird "Fortsetzen", Timer friert ein
- [ ] ⇄ Wechseln → Popup über der App → Auswahl beendet alte + startet neue
- [ ] Geofence Arbeit betreten → Banner erscheint automatisch (Farbe der Aktivität)
- [ ] Timeline: jede Zeile farbig mit Emoji + Akzentbalken

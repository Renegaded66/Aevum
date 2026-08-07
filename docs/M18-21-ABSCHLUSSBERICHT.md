# M18.21 — Notification-Restore + Dashboard-Schlaf-Bug + Timeline-Mindesthöhe

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**Commit:** `bb6b2a9`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**

---

## 1. Notification erscheint auch bei bereits laufender Aktivität (M18.21)

**Problem:** Nach App-Update / Ultra-Energie-Sparmodus (Foreground-Service gekillt) erschien keine Notification, obwohl eine Session lief.

**Fix:** `AevumApplication.onCreate()` prüft jetzt beim App-Start asynchron, ob eine Live-Session in der DB existiert, und startet `LiveActivityService` — die Notification erscheint sofort mit Farbe/Emoji/Timer.

**Wichtige Falle (bewiesen):** `liveSession.value` liefert beim App-Start **immer null**, weil der StateFlow mit `SharingStarted.WhileSubscribed` initialisiert ist — ohne aktiven Subscriber (kein ViewModel da) startet die Room-Query nie. Fix: `liveSession.first()` sammelt den Flow aktiv und bekommt den echten DB-Wert.

## 2. Dashboard-Bug: "1:16 Erfasst" = "1:16 Schlaf" (Root Cause bewiesen)

**Problem:** Der Schlaf-Block zeigte exakt die Erfasst-Zeit, obwohl heute kein Schlaf aufgezeichnet wurde.

**Root Cause:** `sleepSessionsToday` filterte NUR auf Zeitüberlappung mit dem heutigen Tag, aber **NIE auf die Aktivität**. Das 36h-Fenster (`getOverlappingRange`) enthält ALLE Sessions — dadurch landete die laufende Studium-Session in `sleepSessionsToday` und `lastSleepDurationMs` zeigte deren Dauer als "Schlaf".

**Fix:** Filter prüft jetzt `activityTypeId == "sleep" || categoryId == "sleep"` (deckt auch custom Schlaf-Typen ab). Ohne echten Schlaf zeigt der Block jetzt "—".

## 3. Timeline Tagesansicht: kurze Aktivitäten sichtbar (M18.21)

**Problem:** 5-min-Aktivitäten waren bei 60px/h nur ~5px hoch — Farbe und Icon unsichtbar.

**Fix (Google-Calendar-Prinzip):**
- **Mindesthöhe 18dp** für jeden Block — auch 5-min-Aktivitäten sind jetzt klar farbig sichtbar
- **Icon-Schwelle 26dp → 16dp** — dank Mindesthöhe haben auch kurze Blöcke ihr Emoji

## Ehrliche Reflexion
- **Mindesthöhe verzerrt die Zeitachse** bei sehr kurzen Blöcken (5 min sieht aus wie 15 min) — bewusster Trade-off: Sichtbarkeit > exakte Skalierung. Das ist das etablierte Kalender-Prinzip.
- **Notification-Restore läuft nur beim App-Start** — wenn der User die App nie öffnet, kann Android den Service nicht wiederbeleben (System-Limit). Der Restore deckt genau das vom User beschriebene Szenario ab (Handy im Sparmodus → App öffnen → Notification da).

## User-Validation
- [ ] Aktivität läuft → App neu starten → Notification erscheint sofort
- [ ] Dashboard: "Schlaf" zeigt "—" wenn kein Schlaf aufgezeichnet (nicht mehr Erfasst-Zeit)
- [ ] Timeline Tagesansicht: 5-min-Aktivität ist farbig mit Icon sichtbar

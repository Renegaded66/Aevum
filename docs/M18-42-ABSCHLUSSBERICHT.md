# M18.42 — Dashboard-Minuten-Tick + Gym-Auto-Stop-Fix + Autofahrt-Fix

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## 1. Dashboard aktualisiert minütlich

**Problem:** Room-Flows emittieren nur bei DB-Änderungen — eine laufende Session ändert die DB aber nicht jede Sekunde/Minute. Dadurch blieben "Erfasst", die Pauschalen-Sichtbarkeit (ab 00:30) und die Balken veraltet, solange nichts anderes die DB änderte.

**Fix:** 60s-Ticker (`minuteTick`) als zusätzlicher Flow im combine — jede Minute wird der State neu gebaut, `buildState()` nutzt frisches `System.currentTimeMillis()`.

## 2. Gym Auto-Stop feuerte NIE (Root Cause, gewiss)

**Bug:** Der Match verglich `existing.sourceTriggerId == trigger.id` — aber `trigger` ist der **gerade erstellte EXIT-Trigger** (neue UUID). Die Session wurde beim ENTER mit der **ENTER-Trigger-ID** gestartet → Match war **IMMER false** → die Fitness-Session lief nach dem Gym-Verlassen endlos weiter (kein Auto-Stop).

**Fix:** Alle ENTER-Trigger des Geofence laden und prüfen, ob die Session von einem davon gestartet wurde (deckt ENTER- UND DWELL-Start ab).

## 3. Autofahrt wurde nie aufgezeichnet (Root Cause, gewiss)

**Bug:** `MIN_CLUSTER_DURATION_MS = 90s` blockierte **jeden** Start: Google liefert mit `requestActivityTransitionUpdates` **nur ENTER/EXIT-Übergangs-Events** (keine kontinuierlichen Samples). Ein einzelnes ENTER hat `startMs == endMs` → `durationMs = 0` → fiel **immer** unter die 90s-Schwelle → die Session wurde nie gestartet. Die Triggers wurden zwar erzeugt (DRIVING_STARTED/ENDED), aber nie eine Live-Session.

**Fix:** Dauer-Schwelle entfernt — der ENTER-Transition ist die Bestätigung. Session startet sofort, EXIT stoppt über den `vehicleExitedAt`-Marker (bereits implementiert).

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (46s)
- Commit: `fddd672`

## Test-Anleitung
1. **Dashboard:** Laufende Session → "Erfasst" aktualisiert sich jetzt jede Minute
2. **Gym:** Betreten → Fitness startet → **Verlassen → Fitness stoppt automatisch** (vorher lief sie endlos)
3. **Autofahrt:** Einsteigen → "Mobilität" startet sofort → Aussteigen → stoppt automatisch

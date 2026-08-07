# M18.41 — Geofence-Auto-Start-Fix + Notification/Vibrations-Fix

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## 1. Geofence startet keine Session (3 Root Causes, gewiss)

### a) DWELL wurde als ENTER gemappt (Kernproblem)
`GeofenceBroadcastReceiver` mappte `GEOFENCE_TRANSITION_DWELL` auf `GeofenceTransition.Enter`. Dadurch:
- Der **Dedup-Check** (ENTER nach ENTER) fraß das DWELL
- Der **Auto-Discard-Refresh** kam nie an → echte Sessions wurden nach 60s verworfen
- Google Play Services liefert oft NUR DWELL (ENTER kam bei Neuregistrierung und wurde dedupliziert)

**Fix:** DWELL als eigenes Enum → startet die Session, refresht den Auto-Discard, wird NIE dedupliziert.

### b) Dedup-Check ohne Zeitfenster
Wenn ein EXIT verpasst wurde (GPS-Verlust, App-Kill), blieb der letzte Trigger ewig ENTER → der nächste Besuch (z.B. nächster Tag im Gym) wurde IMMER übersprungen.

**Fix:** Dedup nur innerhalb von **10 Minuten** — danach ist ein ENTER ein echter neuer Besuch.

### c) DWELL startete nie eine Session
Nur ENTER startete. **Fix:** DWELL wird wie ENTER behandelt (startet + refresht).

## 2. Notification bleibt ewig (Root Cause, gewiss)

`updateNotification()` stoppte bei `session == null` NIE (M18.24-Entscheidung) → `buildEmptyNotification()` "Aktivität läuft" wurde **FÜR IMMER** gezeigt, auch ohne Session.

**Fix:** 3s Initialisierungsphase (Room-Query kann beim Start noch laufen), danach stoppt der Service bei `null`.

## 3. Vibration alle paar Sekunden (Root Cause, gewiss)

`buildEmptyNotification()` hatte **kein `setOnlyAlertOnce`/`setSilent`** → jeder 1s-`notify()` war ein neuer Alert → Channel hat `enableVibration(true)` + `IMPORTANCE_HIGH` → **Vibration + Heads-up jede Sekunde**.

**Fix:** `setOnlyAlertOnce(true)` + `setSilent(true)` + `setShowWhen(false)`.

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (31s)
- Commit: `718bba2`

## Test-Anleitung
1. **Gym betreten:** Session "Fitness" startet automatisch (nach ~8s Stabilisierung), Notification erscheint einmal
2. **Gym verlassen:** Session endet, Notification verschwindet
3. **Keine Vibration/Spam:** Notification bleibt ruhig, kein wiederholtes Heads-up
4. **Nächster Besuch:** Funktioniert wieder (Dedup-Fenster 10 Min)

# M18.24 — Notification IMMER sichtbar + Crash-Fix + Tags entfernt

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**Commit:** `1d7ff38`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**
**5 Dateien geändert, 67 Insertionen, 24 Deletionen**

---

## 1. 🔔 Notification verschwand — ROOT CAUSE gefunden und behoben

**Das Kernproblem (M18.21-Fix war falsch):**
`liveSession` StateFlow nutzte `SharingStarted.WhileSubscribed(5_000)` — das bedeutet: **ohne aktiven Subscriber ist `.value` IMMER null**, egal ob eine Session in der DB läuft.

Das hatte zwei fatale Folgen:
1. **`LiveActivityService.updateNotification()`** (Zeile 133): sah `null` → rief sofort `stopSelf()` → **Notification verschwand Sekunden nach dem Start**
2. **`MainActivity.onResume()` + `AevumApplication`**: nutzten `first()` mit Race Condition — der initiale null-Wert wurde geliefert, bevor Room die DB-Query lieferte → Service wurde nie gestartet

**Der Fix:**
- `liveSession`/`liveState` auf **`SharingStarted.Eagerly`** umgestellt — `.value` liefert IMMER den echten DB-Wert, auch ohne Subscriber
- `updateNotification()` stoppt nur noch bei `session != null && !session.isLive` (nicht mehr bei null — Service-Start-Race eliminiert)
- **`MainActivity.onResume()` prüft jetzt `activeNotifications` (ID 9001):** Falls die Notification fehlt, aber eine Session läuft → Service wird neu gestartet → Notification "ploppt" wieder auf. Genau das vom User geforderte Verhalten.
- `activeNotifications` defensiv abgefangen (wirft auf manchen OEM-Geräten SecurityException)

## 2. 💥 Crash nach Screen-Timeout/Entsperren — behoben

**Kandidaten (alle abgesichert):**
- **`ForegroundServiceStartNotAllowedException`** (Android 12+): Wenn der Service vom System gekillt wurde und beim Unlock neu gestartet wird, crasht `startForegroundService` → jetzt try/catch mit `startService`-Fallback
- **`ForegroundServiceDidNotStartInTimeException`**: `startForeground()` in try/catch — bei Fehler `stopSelf()` statt Crash
- **Notification-Update-Loop**: try/catch — ein fehlerhaftes RemoteViews-Update killt den Timer nicht mehr

## 3. 🏷️ Tags aus Einstellungen entfernt
"Tags verwalten" aus dem SettingsScreen entfernt (ungenutztes Feature ohne UI-Wert).

## Verifiziert
- **BUILD SUCCESSFUL** (compileDebugKotlin + assembleDebug)
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Ehrliche Grenzen
- **`activeNotifications`-Check** funktioniert nur, wenn die App die Berechtigung hat, Notifications zu sehen (Android 13+ braucht POST_NOTIFICATIONS). Falls der User die Notification-Berechtigung entzogen hat, kann die App die Notification nicht wiederherstellen — das ist ein Android-Systemlimit.
- **Der Crash nach Screen-Timeout** ist nicht reproduzierbar auf diesem Rechner (kein Gerät). Die abgesicherten Stellen decken die wahrscheinlichsten Ursachen ab, aber es kann noch andere geben. Falls der Crash weiterhin auftritt: `last-crash.log` unter `Android/data/de.devondroste.aevum.debug/files/` prüfen.
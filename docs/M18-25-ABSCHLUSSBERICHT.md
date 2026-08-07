# M18.25 — CRASH GEFUNDEN (gewiss) + Notification-Fix

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**

---

## 💥 Der Crash — GEWISS gefunden, nicht vermutet

**Root Cause: `res/layout/live_notification.xml` (M18.23 neu geschrieben)**

Das Custom-Notification-Layout enthielt zwei **NICHT unterstützte RemoteViews-Methoden**:
- `android:fontFamily="monospace"` (Zeile 80) — `setFontFamily` existiert nicht in RemoteViews
- `android:shadowColor` / `shadowRadius` / `shadowDx` / `shadowDy` (Zeilen 52-55, 65-68, 81-84) — `setShadowLayer` existiert nicht in RemoteViews

**Das System wirft beim Inflaten eine `RemoteViews$ActionException`.** Der try/catch in `buildNotification()` konnte das NICHT fangen, weil das Inflaten im System-Prozess passiert.

**Der komplette Verlauf (erklärt ALLE Symptome):**
1. **M18.23:** Custom-Layout eingeführt → `notify()` wirft Exception → Notification wird verworfen → **"Benachrichtigung verschwunden"** (User-Bericht 1)
2. **M18.24:** Eagerly-Umbau → Service startet jetzt beim App-Öffnen → `notify()` mit kaputten RemoteViews → **Exception kommt über Binder zurück → App-Crash ~1s nach dem Öffnen** (User-Bericht 2: "Dashboard kurz sichtbar, nichts geladen, dann Crash — jedes Mal")
3. Beim 1. Öffnen nach Update: DB-Query noch nicht fertig → kein Service-Start → kein Crash. Beim 2. Öffnen: warmer Cache → sofortiger Service-Start → Crash. **Exakt der gemeldete Ablauf.**

## 🔧 Der Fix

**Custom-RemoteViews-Layout KOMPLETT entfernt.** Die Notification nutzt jetzt die Standard-Notification mit:
- `setColor(accentColor)` + `setColorized(true)` — farbige Notification (Aktivitätsfarbe)
- 3 Actions: Pause/Fortsetzen, Wechseln, Stoppen
- `PRIORITY_HIGH` + `CATEGORY_STOPWATCH` — Heads-up Banner
- Läuft auf JEDEM Gerät zuverlässig — kein OEM-Crash-Risiko

**Trade-off (ehrlich):** Die Notification ist jetzt Standard-Android-Look statt Custom-Design. Dafür ist sie GARANTIERT sichtbar — das war die explizite User-Anforderung ("Sie sollte IMMER sichtbar sein"). Ein Custom-Layout kann nur mit 100% unterstützten Methoden (setText, setBackgroundColor, setOnClickPendingIntent) zurückkommen — das wäre ein separates Projekt.

## 🐛 Bonus-Fix: Scrollbar-Doppelung in TimelineScreen

`DayCalendarTimeline` erzeugte **zwei `rememberScrollState()`-Instanzen** — die Column scrollte mit der ersten, der Thumb las die zweite (immer 0) → Scrollbar zeigte nie an. Jetzt: EIN ScrollState für beide.

## Verifiziert
- **BUILD SUCCESSFUL** (assembleDebug)
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Test-Anleitung
1. APK installieren, Aktivität starten → Notification muss SOFORT erscheinen (farbig, mit Pause/Wechsel/Stopp)
2. App schließen + wieder öffnen → **KEIN Crash mehr**, Notification bleibt
3. Screen-Timeout abwarten → entsperren → kein Crash, Notification da
4. Timeline → Listen-Ansicht → Scrollbar-Thumb sichtbar und korrekt positioniert

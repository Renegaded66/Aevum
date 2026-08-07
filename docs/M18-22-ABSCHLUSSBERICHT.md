# M18.22 — Notification-Icon + onResume-Restore + Geofence-Dedup + Trigger-Scrollbar

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**Commit:** `b6a9709`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**

---

## 1. Notification-Icon (nicht mehr Viereck)
**Root Cause:** `ic_launcher_foreground` ist ein vollfarbiges Vector-Drawable — Android zeigt vollfarbige Icons in der Statusleiste als **Viereck** an. Das System verlangt ein **weißes/transparentes** Icon.
**Fix:** Eigenes `ic_notification.xml` (weiße Timer-Silhouette, 24dp) — korrektes kleines Icon in der Statusleiste.

## 2. Notification-Design (weniger langweilig)
- Header von 56dp → **64dp**, Hintergrund von `#1E1E2E` → **Aktivitätsfarbe** (vom Service gesetzt)
- Timer von 26sp → **28sp**, Emoji von 24sp → **28sp**
- Buttons: eigener Hintergrund `#2A2A3E` (statt einheitlich schwarz), farbige Texte (gelb/cyan/rot)
- Layout ist jetzt deutlich kräftiger und individueller

## 3. Notification-Restore beim App-Öffnen (onResume)
**Root Cause:** `AevumApplication.onCreate` läuft nur beim **Cold-Start**. Wenn die App im Hintergrund war (nicht gekillt) und der User öffnet sie wieder, läuft `onCreate` NICHT → die Notification erscheint nicht.
**Fix:** `MainActivity.onResume()` prüft **jedes Mal**, wenn die App in den Vordergrund kommt, ob eine Live-Session läuft (`liveSession.first()`) und startet den Notification-Service.

## 4. Geofence-False-Trigger (Root Cause bewiesen)
**Problem:** Mehrere "zuhause angekommen"-Trigger (08:48, 10:45, 11:57) ohne einen einzigen "zuhause verlassen"-Trigger — obwohl der User seit gestern durchgängig zuhause ist.

**Root Cause:** Google Play Services feuert wiederholt ENTER-Events bei:
- Geofence-Neuregistrierung (GeofenceRefreshWorker läuft periodisch)
- GPS-Drift am Geofence-Rand
- App-Update (Geofences werden neu registriert)
- DWELL-Events (User ist lange im Geofence → Google feuert DWELL als ENTER)

Der Processor speicherte **jeden** ENTER ungefiltert in die DB — es gab **keinen** Dedup-Check.

**Fix:** Dedup-Check am Anfang von `processTransition`:
- Lade den letzten Trigger für diese Geofence
- Wenn der letzte Trigger auch ENTER war (ohne EXIT dazwischen) → **skippen**
- Gilt entsprechend für EXIT nach EXIT

## 5. Trigger-Liste Scrollbar
- `EventListTimeline` in `heightIn(max=520dp)` + `verticalScroll(rememberScrollState())` gewrappt
- Bei vielen Events erscheint jetzt eine Scroll-Leiste

## 6. SwitchActivity (Wechseln)
- War bereits korrekt implementiert (M18.19): `ACTION_SWITCH` → `openSwitchActivity()` → transparente Dialog-Activity
- War vermutlich nicht sichtbar wegen der alten APK — jetzt mit neuem Build verfügbar

## Ehrliche Grenzen
- **Geofence-Dedup ist ein Filter, kein Ground-Truth** — Google Play Services kann weiterhin False-Trigger feuern, aber sie werden jetzt nicht mehr verarbeitet. Wenn der User wirklich kommt und geht, funktioniert es (ENTER → EXIT → ENTER wird korrekt durchgelassen).
- **Scroll-Leiste** ist `verticalScroll` — Compose zeigt den System-Scrollbar-Indikator, der auf manchen OEMs deaktiviert ist. Eine eigene gezeichnete Scrollbar wäre aufwendiger und ist nicht Teil dieser Runde.
- **GPS-Check bei Activity-Recognition** — der User wünscht sich, dass GPS nur bei Activity-Recognition-Events geprüft wird (nicht 24/7). Das ist eine architektonische Änderung, die in der nächsten Runde umgesetzt wird (Geofence-Monitoring-Strategie von "always-on" auf "event-driven" umstellen).

## User-Validation
- [ ] Aktivität läuft → App schließen → neu öffnen → Notification erscheint
- [ ] Notification: kleines Timer-Icon (kein Viereck), bunter Header, 3 Buttons
- [ ] ⇄ Wechseln → Popup erscheint → Auswahl beendet alte + startet neue
- [ ] Zu Hause → keine wiederholten "angekommen"-Trigger mehr
- [ ] Timeline Liste: viele Events → Scroll-Leiste sichtbar
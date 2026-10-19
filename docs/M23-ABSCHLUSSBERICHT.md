# M23 — Abschlussbericht: Fahrt-Vorlauf, Widget, Timeline-Herkunft

**Datum:** 2026-10-19
**Version:** Aevum 1.0.43 (versionCode 44)
**DB-Schema:** v48 (`activity_session.source_app_package`, additiv)
**Artefakte:** `Aevum-1.0.43-Debug.apk`, `Aevum-1.0.43-Release.apk`,
`Aevum-1.0.43.aab` — http://192.168.178.40:8098/

---

## 1. Anlass

Devon-Befund und -Wünsche aus einer Nachricht:

1. „ich [bin] erst 5 Minuten zum Auto gegangen und dann losgefahren […] die
   Aufzeichnung [hat] zwar erst gestartet [als] ich losgefahren bin
   allerdings auch noch die 5 Minuten Gehzeit vorher am Anfang gesetzt".
2. „Die App zugewiesene start der activitys funktioniert jetzt deutlich
   besser aber immer noch nicht 100 Prozent zuverlässig."
3. „Im Dashboard ist immer noch unter den einzelnen aufzeichnungen dieser
   Block wo gerade steht ‚Ruhiger Start', der soll komplett weg."
4. „Ich möchte ein Homescreen Widget haben, 2x1 … ob eine Activity gerade
   aufgezeichnet wird, wenn ja welche und wie lange, und Buttons zum stoppen
   oder starten mit zugehörigen schönen Pop Up zur Auswahl der zu startenden
   Activity. Und falls das Maskottchen aktiviert ist sollte das auch sichtbar
   sein. Das Widget gerne schön farbig im Duolingo Stil."
5. „In der Timeline sollte bei Aufzeichnungen durch das App Tracking auch
   stehen welche App das gestartet hat, bestenfalls mit Icon und Name. Die
   Felder start und Zeit könnten bestimmt richtig hochwertig modern irgendwie
   mit Zeit dargestellt werden, vielleicht Uhren oder Zeitstrahl."
6. „Ich habe ein Ziel überschritten … da sollte dann im Dashboard irgendwie
   auch angezeigt werden dass es nicht erreicht wurde heute. Nicht mit
   Wörtern, eher Farben und Icons."
7. „Danach sollte der Punkt in Aevum auch grün sein" (Nutzungszugriff bzw.
   Akku-Ausnahme nach dem Settings-Besuch).

---

## 2. Fahrt-Vorlauf — Ursache und Fix

### 2.1 Ursache (code-belegt)

`DriveDetectionEngine.toVehicleCluster` nahm als Startanker den **ältesten
Bewegungs-Probe** im 15-Minuten-GPS-Puffer. Die Filtergrenze lag bei
`MIN_ANCHOR_MOTION_MPS = 0,5 m/s` — also auch Gehen (1,0–1,8 m/s). Beim
Losfahren lagen die fünf Geh-Probes noch im Puffer; der AR-ENTER-Backfill
(`addSample(burstCluster.startMs)`) zog den Session-Start damit auf den
Beginn des Fußwegs. `resolveDriveStart` kappte nur Cluster älter als 15 min,
nicht die Gehphase davor.

### 2.2 Fix

- Neuer optionaler Fahrzeug-Anker in `toVehicleCluster`:
  `MIN_DRIVE_ANCHOR_SPEED_MPS = 3,0 m/s` (10,8 km/h), **zwei** Probes
  innerhalb von 90 s. Ein einzelner GPS-Spike beim Gehen zählt nicht.
- Gibt es keinen Fahrzeug-Probe (Stop&Go ohne Speed-Feld), ist der **jüngste**
  Bewegungs-Probe der Anker — lieber Sekunden zu spät als Minuten Gehzeit
  davor.
- Alle drei Startpfade übergeben den Anker: AR-ENTER-Burst,
  GPS-Confirm-Fallback und DriveProbeWorker.
- Der bewusst legitime 5-Minuten-Vorlauf von „Spazieren" bleibt unverändert.

### 2.3 Tests

`ActivitySessionLifecycleTest` +3:
- 5 Minuten Geh-Probes + Fahrt → Start exakt am ersten Fahrzeug-Probe.
- Ein einzelner 4,2-m/s-Geh-Spike → **kein** Anker (kein zweiter Probe).
- Nur Geh-Probes → Start am jüngsten Probe, nie am ältesten.

---

## 3. App-Aufzeichnung — Zuverlässigkeit

### 3.1 Gefundener Zustandsfehler

Endete eine **fremde** Session (Fahrt, Geofence, Sport, manuell), während die
getrackte App weiterhin im Vordergrund war, setzte der Service im
`KEEP_FOREIGN`-Zweig `currentTrackedApp = foreground`. Der nächste Poll lief
damit in den „gleiche App"-Zweig; `onRepeatedForeground` sah keine Live-
Session und tat nichts. Die App-Aufzeichnung startete erst, wenn der Nutzer
die App verließ und erneut öffnete — genau das „nicht 100 Prozent
zuverlässig".

**Fix:** `KEEP_FOREIGN` merkt sich die App nicht mehr als erledigt
(`currentTrackedApp = null`). Jeder 2-s-Poll bewertet den Zustand neu und
startet, sobald die fremde Session endet. Ein bewusst manuell gestoppter
Zustand wird weiterhin **nicht** automatisch neu gestartet (der
„gleiche App"-Zweig bleibt dafür unverändert).

### 3.2 Weitere Verbesserungen

- Rebaseline des Vordergrunds, wenn der Faltungszustand leer hängt:
  120 s → **30 s** (nur auf dem Homescreen, nicht bei offener App).
- Nach der Rückkehr aus den System-Einstellungen wird
  `hasUsagePermission` sofort neu gelesen (`permissionEpoch` + Resume-Hook).
  Vorher änderte keine der Daten-Flows etwas → der rote Hinweis blieb stehen.
- Die Akku-Zeile in der Diagnose hat jetzt einen sichtbaren Status-Punkt
  (grün = Ausnahme aktiv, rot = eingeschränkt).

---

## 4. Dashboard

### 4.1 „Ruhiger Start" komplett entfernt

`buildInsights` liefert ohne offene Reflexionen eine **leere** Liste. Der
Fallback `dashboard_insight_calm` ist gelöscht; `DayReflectionCard` wird nur
noch gerendert, wenn es echte Inhalte gibt. Unter den Aufzeichnungen bleibt
kein Platzhalterblock mehr.

### 4.2 Ziel-Überschreitung ohne Worte

Bei `AT_MOST`-Zielen mit `currentValue > targetValue`:

- Zeile rot getönt, Punkt und Fortschrittsbalken in `colorScheme.error`,
  Schimmer schneller,
- ⚠-Symbol neben dem Wert,
- weißer Endpunkt am Balkenende (überläuft sichtbar).

Kein zusätzlicher Text wie „überschritten". Die Zahlen bleiben als
Datenanker sichtbar.

---

## 5. Timeline — App-Herkunft und Zeitfenster

### 5.1 DB v48

`activity_session.source_app_package` (TEXT NULL), Migration 47→48 als
einzelnes `ALTER TABLE … ADD COLUMN`. Altdaten und alle Nicht-App-Quellen
bleiben `null`. `LiveActivityManager.start` nimmt den Paketnamen entgegen;
`AppTrackingService` übergibt den aktuellen Vordergrund.

### 5.2 Anzeige

- **Liste:** App-Aufzeichnungen tragen statt „Erfasst" den Chip
  „📱 <App-Name>".
- **Detail:** eigene Karte „Gestartet von" mit echtem App-Icon (96×96,
  gecacht) und Namen; die Quelle-Kachel zeigt den App-Namen statt
  `APP_TRACKING`.
- **Zeitfenster:** neue Karte mit zwei analogen Uhren (Stunden-/Minutenzeiger,
  Viertelstunden-Ticks), großer Range, Datum und 24-h-Zeitstrahl mit farbigem
  Fensterband, Start-/Endmarkern und Jetzt-Linie. Die früheren doppelten
  Start-/Ende-Kacheln sind entfernt; Dauer und Quelle bleiben als Kacheln.

---

## 6. 2x1-Homescreen-Widget

### 6.1 Aufbau

- **Zustände:** `BEREIT` (creme, grüner Rahmen), `LÄUFT` (dunkelgrüner
  Verlauf, leuchtender Rahmen), `PAUSIERT` (goldener Status).
- **Inhalt:** Maskottchen (nur bei aktivierter Einstellung, 30 dp,
  gecachte Canvas-Bitmap), Status, Aktivitätsname, Timer in eigener Zeile
  (`MM:SS` bzw. `H:MM:SS`), runder Start-/Stopp-Knopf.
- **Start:** öffnet `WidgetStartActivity` — ein cremefarbenes Duolingo-Popup
  mit Maskottchen, Favoriten zuerst, allen Aktivitäten (Icon/Farbe) und
  „Abbrechen". Ein Tipp startet sofort.
- **Stopp:** Broadcast an den Provider; beendet die Session direkt und räumt
  Auto-Flags auf (Drive/Walking), gleiche Semantik wie der Notification-Stopp.
- **Aktualisierung:** `AevumWidgetUpdater` wird aus dem bestehenden
  `LiveActivityService`-Takt gerufen (1 s bei Bildschirm an, 10 s aus).
  Kein zusätzlicher AlarmManager/WorkManager; ohne Widget oder Session
  entstehen keine Kosten.
- **Klick auf den Körper:** öffnet die App.

### 6.2 Sichtprüfung

Robolectric rendert das echte RemoteViews-Layout in PNGs:

- `aevum-1.0.43-widget-running.png` — LÄUFT / Lernen / 02:05 / Maskottchen /
  roter Stopp-Knopf.
- `aevum-1.0.43-widget-idle.png` — BEREIT / Starten / Maskottchen / grüner
  Start-Knopf.

Beide sind auf dem APK-Server (HTTP 200). Die erste Fassung zeigte den Timer
noch in der Statuszeile — er wurde bei 2×1 Breite abgeschnitten. Die
Sichtprüfung führte zur eigenen Timer-Zeile.

---

## 7. Eigene Design-Entscheidungen hinterfragt

| Entscheidung | Naheliegende Alternative | Bewusst gezahlter Preis |
|---|---|---|
| Fahrzeug-Anker 3,0 m/s + zwei Probes/90 s | AR-ENTER-Zeitstempel als einzigen Start | Ohne Speed-Feld startet die Fahrt am jüngsten Probe (Sekunden später), nie mit Geh-Vorlauf. |
| `KEEP_FOREIGN` merkt sich nichts | Bei „nichts live" immer neu starten | Ein manuell gestoppter Zustand würde sonst sofort wieder starten; deshalb bleibt der „gleiche App"-Zweig unangetastet. |
| RemoteViews + eigener Updater | Glance | Kein Compose-Remote-Stack, keine Animationen; Maskottchen ist eine statische Bitmap. |
| Neue Spalte `source_app_package` | Paketname in `note` | Eine Migration und ein zusätzliches Feld im Export; dafür bleiben Notizen unangetastet und Altdaten eindeutig. |
| Zwei Canvas-Uhren + 24-h-Strahl | Zwei Standard-Statistik-Kacheln | Mehr Zeichencode und keine echte Uhrzeitanimation; dafür ist die Zeit sofort räumlich lesbar. |
| Ziel-Überschreitung rot + ⚠ | Text „überschritten" | Farbe allein ist für Farbsehschwächen schwächer; das ⚠ ergänzt eine Form, aber es gibt keinen zusätzlichen TalkBack-Satz. |

---

## 8. Nicht Bestandteil (bewusst nicht gebaut)

- Keine weiteren Widget-Größen (2x2/4x1) und keine Widget-Konfigurations-
  Activity — nur das geforderte 2x1.
- Kein Pause-/Fortsetzen-Knopf im Widget — Start/Stopp waren gefordert;
  Pause bleibt in App und Notification.
- Kein Anlegen/Bearbeiten von Aktivitäten im Widget-Popup — nur Auswahl
  vorhandener Aktivitäten.
- Keine rückwirkende App-Zuordnung für alte Sessions (`source_app_package`
  bleibt bei Altdaten `null`).
- Keine Änderung am 5-Minuten-Vorlauf von „Spazieren" — der ist gewollt.
- Keine Änderung am PC-Tracker.
- Kein Launcher-/Geräte-E2E-Test des Widgets (siehe §9).

---

## 9. Ehrliche Einschränkungen

- **Kein Geräte-/Launcher-Test:** Widget-Platzierung, echtes RemoteViews-
  Rendering im Launcher, Popup über dem Homescreen und der Sekundentakt
  wurden nicht auf einem physischen Gerät durchgeklickt. Verifiziert sind
  Layout-Inflation, Texte/Zustände und zwei Robolectric-Screenshots.
- **Fahrt-Vorlauf nicht real nachgestellt:** Die Unit-Tests bilden die
  Probe-Serie exakt nach, aber es gab keinen echten 5-Minuten-Fußweg mit
  anschließender Fahrt im Test.
- **App-Tracking:** Der Fremd-Session-Fix ist code-belegt und getestet, aber
  OEM-spezifische Service-Kills bleiben ein Restrisiko; die Diagnose-Karte
  zeigt sie.
- **Maskottchen im Widget:** statische Bitmap, keine Blinzel-/Atem-Animation
  (RemoteViews kann keine Compose-Animation rendern).
- **Release-APK:** mit dem Aevum-Upload-Key signiert; die Debug-APK mit dem
  kanonischen Debug-Key. Ein Play-Upload wurde nicht durchgeführt.

---

## 10. Messwerte

| Punkt | Wert |
|---|---|
| Tests | **1271** Tests, **0 Fehler**, **1 skipped** |
| Build | `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:bundleRelease`, Exit **0**; Test+Artefakte **4m 12s**, finaler Artefakt-Rebuild nach der Widget-Sicherheitsaktualisierung **2m 53s** |
| Debug-APK | 127.323.250 B (121,4 MB), SHA-256 `e072ce02…e83069`, SHA-1 `9c3055c4…fbe0e8` (kanonisch) |
| Release-APK | 52.271.051 B (49,9 MB), SHA-256 `2e7e83ad…a34159`, SHA-1 `c8430d35…361ffe9` (Upload-Key) |
| AAB | 24.600.934 B (23,5 MB), SHA-256 `5c7c8156…82163` |
| Server | alle drei Dateien HTTP 200; Widget-Screenshots HTTP 200 |
| DB | v48, Migration 47→48, `SchemaV48Test` + `GoalV47MigrationTest` + `PmRemovalMigrationTest` grün |
| Commit | `16b819d` (Code + Doku); dieser Berichtsnachtrag ist ein Folge-Commit |

Build-Log: `/root/builds/m23-final2.log`; Testberichte:
`app/build/test-results/testDebugUnitTest/`.

---

## 11. Von den Tests gefundene Fehler

1. **`PmRemovalMigrationTest` erwartete noch v47** — nach der Versionserhöhung
   auf 48 schlug die assertion fehl. Erwartung auf 48 + Identitäts-Hash aus
   `48.json` korrigiert; die Migrationskette läuft jetzt bis 48.
2. **`SchemaV48Test` las `notNull` als Pflichtfeld** — Room lässt
   `"notNull": false` im JSON weg. Auf `optBoolean("notNull", false)`
   umgestellt; die Nullable-Zusage ist damit korrekt geprüft.
3. **Widget-Test war sprachabhängig** — Robolectric nutzte trotz
   `qualifiers="de-rDE"` die englischen Default-Strings (App-Locale-Default
   ist EN). Der Test prüft jetzt die Verdrahtung (Timer/Werte/Sichtbarkeit)
   statt einer festen Übersetzung; die Sichtprüfung setzt Deutsch explizit
   über `LocaleHelper`.
4. **Screenshot fand den abgeschnittenen Timer** — in der ersten
   Widget-Fassung stand „RECORDING · 02:05" in einer 8-sp-Zeile und wurde bei
   2×1 Breite abgeschnitten. Der Timer hat jetzt eine eigene Zeile
   (`widget_timer`); der Screenshot zeigt ihn vollständig.
5. **Maskottchen-Shader** (Code-Review vor dem Build): Das Bauchlicht hätte
   den Körper-Verlauf geerbt, weil `paint.shader` nicht zurückgesetzt wurde.
   Vor dem ersten Build behoben.

---

## 12. Verifikation am Gerät (Empfehlung)

1. Widget 2×1 auf den Homescreen legen → BEREIT/Starten; ▶ antippen →
   Popup, „Lernen" wählen → Widget zeigt LÄUFT + Timer; ■ stoppt.
2. Maskottchen in den Einstellungen aktivieren → Widget und Popup zeigen es.
3. 5 Minuten zu Fuß zum Auto, dann losfahren → Fahrt beginnt am Losfahren,
   keine Gehzeit davor.
4. Fahrt/Sport manuell starten, während eine getrackte App offen ist; Session
   beenden → App-Aufzeichnung startet ohne App-Wechsel.
5. Timeline → App-Aufzeichnung öffnen → „Gestartet von" mit Icon + Name;
   Start/Ende als Uhren + Tagesstrahl.
6. Ziel mit Höchstgrenze überschreiten → Dashboard-Zeile rot + ⚠, kein Text.
7. App-Aufzeichnung → System-Einstellungen → Nutzungszugriff/Akku-Ausnahme
   erteilen → zurück → Status sofort grün.

# M18.151 — Abschlussbericht: App-Aufzeichnung × Digital — Übergabe-Regeln der Smartphone-Aufzeichnungen

**App-Version:** 1.0.30 (versionCode 31) · **Milestone:** M18.151 · **Datum:** 2026-10-16
**Build:** `aevum-m18151` — `testDebugUnitTest assembleDebug assembleRelease bundleRelease`
**Tests:** 1114/1114 grün (103 Suiten, 0 Fehler/Errors, 1 vorbestehender Skipped) — davon 30 neue

---

## 1. Auftrag (User-Nachricht, zerlegt)

Der User meldete (wörtlich, gekürzt auf die Kernforderungen):

1. „Irgendwie funktioniert die App-Aufzeichnung nicht so ganz. Grundsätzlich
   wird schon passend Digital aufgezeichnet, wenn der Zeit-Threshold
   überstiegen ist."
2. „Aber wenn der Threshold bei 2 Minuten liegt, ich aber bereits nach
   30 Sekunden Instagram öffne, und unter App-Aufzeichnung Instagram einer
   Activity zugewiesen habe, dann wird einfach weiterhin die ganze Zeit
   Digital aufgezeichnet. Eigentlich sollte dann nach den 2 Minuten
   Bildschirmzeit die Activity von zugewiesenem Instagram starten, statt
   Digital, falls ich noch auf der App drauf bin."
3. „Auch nach den zwei Minuten Threshold, wenn also die Aufzeichnung von
   Digital schon gestartet ist und ich dann Instagram öffne, stoppt leider
   auch nicht die Aufzeichnung von Digital [stattdessen soll] die neue
   Aufzeichnung von der zugewiesenen [App] gestartet [werden]."
4. „Außerdem sollte auch ein Wechsel passieren: wenn ich 2 Apps Activities
   zugeordnet habe und dann eine Aufzeichnung von einer App gerade
   stattfindet und ich dann die App wechsle, dann soll die alte Aufzeichnung
   stoppen und die neue beginnen. Und zwar direkt. Also direkt nach Beenden
   der App soll die aktuelle stoppen."
5. „Falls dann 2 Minuten das Display an ist ohne eine ausgewählte App, dann
   soll Digital wieder starten."
6. „Und falls eine andere zugewiesene App sich öffnet, soll direkt die
   aktuelle Aufzeichnung gestoppt werden und die zugewiesene Activity der
   neuen App starten."
7. **Rangfolge (zwingend):** „Beachte aber trotzdem die Rangfolge, dass sich
   das nur auf Smartphone-Aufzeichnungen bezieht — Geofence-Aufzeichnung
   oder sportliche Aufzeichnungen oder manuell gestartete Aufzeichnungen
   oder Autofahrt-Aufzeichnung haben immer Vorrang und dürfen nicht durch
   Digitales unterbrochen werden, egal ob Standard-Digital oder
   App-zugewiesen."

Die betroffenen Bausteine existierten alle bereits (M18.67 App-Aufzeichnung,
M18.70/M18.138 Digital, M18.104 Akku-Redesign) — es fehlten die **Übergabe-
Regeln zwischen den beiden Smartphone-Aufzeichnungen** und mehrere
Lebenszyklus-Pfade.

---

## 2. Diagnose (belegt am Code, drei Lücken)

### Lücke 1 — Eine laufende Digital-Session blockierte die App-Aufzeichnung

`AppTrackingService.pollForegroundApp()` stoppte/blockierte bei JEDER
laufenden Session, die nicht die eigene war:

```kotlin
if (live != null && !ownSessionRunning) {
    // „Fremde Session läuft — kein Auto-Start"
    currentTrackedApp = foreground; return
}
```

`SCREEN_AUTO` (Digital) fiel in diesen Zweig: Wer nach den 2 Minuten
Threshold (Digital läuft) Instagram öffnete, bekam **nichts** — Digital lief
weiter. Genau der gemeldete Fall 3. Dasselbe blockierte den Wechsel, solange
Digital lief.

### Lücke 2 — Nach dem App-Ende wurde Digital nie wieder angesetzt

Der Digital-Start hing ausschließlich an:

- `ScreenEventReceiver` (Broadcast `ACTION_SCREEN_ON` → Worker mit
  Vorlauf-Delay) und
- der Selbstheilung in `AevumApplication` (nur beim App-Prozess-Start).

Endete eine App-Aufzeichnung mitten in einem laufenden
Bildschirm-Einschaltvorgang (User wechselt zu einem nicht getrackten
Bereich), wurde **kein** neuer Digital-Lauf eingeplant — es blieb bis zum
nächsten Screen-Aus/An-Zyklus alles leer. Gemeldeter Fall 5.

### Lücke 3 — App-Sessions überlebten Screen-OFF

`ScreenEventReceiver` bei `ACTION_SCREEN_OFF` stoppte **nur** `SCREEN_AUTO`.
Eine App-Session lief mit eingefrorenem Vordergrund-Status weiter (der
Service pollt bei Screen aus nicht; das Event-Fenster beim Aufwachen ist
17 s groß und sah die Pause-Events der Nacht nie). Folge: Lag das Handy über
Nacht mit offener Instagram-Session, entstand ein falscher Block von
Screen-OFF bis zum nächsten Entsperren. Widerspricht der User-Forderung
„direkt nach Beenden soll die aktuelle stoppen" und dem M18.138-Prinzip
(„ein Block, der über das Weglegen hinausläuft, ist eine
Falschaufzeichnung").

### Zusatzbefund

Eine App-Session, die aus dem Leerlauf heraus startete, rief **nie**
`LiveActivityService.start()` auf (das tat nur der Digital-Pfad) — die
Live-Notification erschien erst beim nächsten App-Restore. Beim
Digital-Übernahme-Fall lief der Service ohnehin.

---

## 3. Fix im Detail

### 3.1 Übergabe-Regeln zentral — `AppTrackingTakeoverPolicy` (neu, pure)

Reine Entscheidungslogik (keine Android-Imports, JVM-getestet, 11 Tests):

| Situation bei neuem Vordergrund einer getrackten App | Aktion |
| --- | --- |
| nichts läuft | `START_APP` (M18.67-Verhalten) |
| **eigene** App-Session läuft (A → B) | `STOP_AND_START` (A endet jetzt, B beginnt) |
| **Digital** (`SCREEN_AUTO`) läuft | `TAKE_OVER_DIGITAL` (Digital endet exakt jetzt, App beginnt) |
| alles Fremde (Geofence/Fahrt/Sport/manuell/Kalender) | `KEEP_FOREIGN` — **nichts anfassen** |

Der Service nutzt die Policy als einzige Entscheidungsinstanz im
`pollForegroundApp()`-Pfad.

### 3.2 Race-Schutz — `LiveActivityManager.stopIfLive(sessionId)`

Stoppt nur, wenn **exakt** diese Session noch live ist. Zwischen „lesen" und
„stoppen" könnte z. B. die Fahrterkennung übernommen haben — die darf die
Übergabe nie anfassen (Rangfolge). Wird an beiden Stop-Pfaden verwendet
(Digital-Übernahme + eigener Stop).

**Familien-Erkennung verwaister Sessions:** Stirbt der FGS durch einen
System-Kill, während eine App-Session läuft, ist nach dem Neustart
`trackedSessionId` leer — die Session lebt aber weiter. Damit sie nicht für
immer als „fremd" jeden Wechsel blockiert (bzw. nie gestoppt wird), zählt
sie über `sourceType == APP_TRACKING` weiter zur Smartphone-Familie:
Take-over-Prüfung und `stopOwnSessionIfRunning()` behandeln sie wie eine
eigene Session. Fremde Quellen bleiben unberührt — die Familien-Regel gilt
NUR für den einen Quellen-Typ dieses Features.

### 3.3 Screen-OFF beendet die App-Aufzeichnung sofort

Der Screen-Receiver des AppTrackingService (läuft als FGS) stoppt bei
`ACTION_SCREEN_OFF` die eigene Session und setzt `currentTrackedApp`
zurück — der nächste Einstieg in die App nach dem Entsperren startet sauber
als neue Session. Konsistent zu M18.138 (Digital).

### 3.4 Digital-Neuansatz nach dem App-Ende

`stopOwnSessionIfRunning()` plant nach einem echten Stop den
`ScreenRecordingWorker` mit der konfigurierten Vorlaufzeit neu ein
(`scheduleDigitalRecheck()` → `ScreenRecordingWorker.schedule()`,
respektiert `DEACTIVATED`). Der Worker prüft beim Feuern selbst alle
Bedingungen — der Aufruf ist idempotent (REPLACE, ein UniqueWork-Name).
Damit greift Fall 5: „2 Minuten Display an ohne ausgewählte App → Digital
wieder starten", ohne auf den nächsten Screen-ON zu warten.

### 3.5 Digital-Worker startet nie über einer getrackten App

Neuer Guard im `ScreenRecordingWorker.doWork()` **vor** dem Digital-Start:

- Quelle 1: `ForegroundAppProbe.currentForeground()` — Event-Faltung der
  letzten 2 Minuten (sieht einen gerade geöffneten ODER gerade verlassenen
  Vordergrund sofort);
- Quelle 2 (Fallback): `AppTrackingService.recentPollSnapshot(90s)` —
  prozess-lokaler Schnappschuss des 15s-Polls (deckt „App seit Stunden
  ruhig im Vordergrund" ab, wo es keine Übergangs-Events gibt).

Treffer → **kein Digital** + Nudge `AppTrackingService.start()` (läuft er
nicht, startet er und übernimmt die Aufzeichnung selbst).

Die Vordergrund-Semantik selbst liegt in `ForegroundTracker` (neu, pure,
8 Tests) und wird von Service UND Probe benutzt — es gibt nur EINE
Definition von „Vordergrund".

### 3.6 Nebenbefund: Live-Notification für App-Sessions

Nach jedem erfolgreichen App-Session-Start ruft der Service
`LiveActivityService.start()`. Die Session ist damit sichtbar wie jede
andere Live-Aufzeichnung (Stop/Pause direkt aus der Notification möglich).

### 3.7 Akku (veröffentlichte App)

- Keine Erhöhung der Poll-Frequenz (bleibt 15 s bei Screen an / 10 min bei
  Screen aus).
- Die Event-Probe läuft nur EINMAL pro Worker-Feuern (nicht periodisch).
- Der Schnappschuss ist prozess-lokal (kein I/O, keine neue Persistenz).
- Screen-OFF reduziert sogar Arbeit: App-Session endet, statt bis zum
  Morgen weiterzulaufen.

---

## 4. Szenario-Abgleich (User-Fälle → neues Verhalten)

1. **Screen an, nach 30 s Instagram öffnen, Threshold 2 min, nichts läuft:**
   Die App-Session startet (M18.67-Auto-Start); feuert der 2-Minuten-Worker
   später, sieht er „nichts anderes live" bzw. „getrackte App im
   Vordergrund" → **kein Digital**. Die zugewiesene Activity läuft.
2. **Digital läuft, dann Instagram öffnen:** nächster Poll (≤15 s) →
   `TAKE_OVER_DIGITAL`: Digital endet exakt jetzt, Instagram-Activity
   beginnt. (Vorher: nichts passierte.)
3. **App A aktiv, Wechsel zu App B:** `STOP_AND_START` — A endet, B beginnt
   im selben Poll.
4. **App verlassen (zu nicht getrackter App/Launcher):** eigene Session
   stoppt sofort (bestehender Pfad, jetzt auch bei Screen-OFF), Digital-
   Neuansatz wird eingeplant.
5. **2 Minuten Display an ohne ausgewählte App:** Digital startet mit
   Rückdatierung (bestehendes M18.70-Verhalten) — jetzt auch ohne neuen
   Screen-ON-Zyklus.
6. **Fremde Aufzeichnung läuft (Geofence/Fahrt/Sport/manuell/Kalender):**
   App-Öffnen bewirkt nichts, Digital-Worker startet nicht, kein Stop —
   Rangfolge strikt eingehalten (Policy + stopIfLive + Bestands-Guards).

---

## 5. Verifikation (Belege)

- **Unit-Tests:** 1114/1114 grün, 103 Suiten, 0 Failures/Errors, 1
  vorbestehender Skipped (`CyclingVsCarDetectionTest`). Neu: 11
  `AppTrackingTakeoverPolicyTest`, 8 `ForegroundTrackerTest`, 11
  `AppTrackingDigitalWiringTest` (Datei-Scan-Regression: Übergabe-, Stop-,
  Screen-OFF-, Neuansatz- und Worker-Guard-Verdrahtung).
- **Builds `aevum-m18151` und `aevum-m18151b` (finale Artefakte aus b):**
  Exit 0 (5m32s / 4m06s) — Debug-APK kanonisch signiert (Key-Guard),
  Release-APK (Aevum Upload Key) + AAB für die Play Console:
  - Debug-APK: 123,95 MB — SHA-1 `9c3055c4…` (kanonischer Key, verifiziert)
  - Release-APK: 51,97 MB — SHA-1 `c8430d35…` (Aevum Upload Key);
    `aapt dump badging`: versionCode 31, versionName 1.0.30, Label „Aevum"
  - AAB: 24,2 MB (Play-Console-Upload)
  - R8-Ressourcen-Einträge Release: **1779** (optimiertes
    Ressourcen-Schrumpfen weiterhin aktiv, wie M18.150)
- **Regressionstechnisch abgesichert:** Die Rangfolge ist doppelt
  abgesichert (Policy-Tests + wiring-Test auf `stopIfLive`), die
  Vordergrund-Semantik ist als pure Funktion getestet.

## 6. Geänderte/neue Dateien (Kern)

**Neu:**
- `automation/apptracking/AppTrackingTakeoverPolicy.kt` (pure, 11 Tests)
- `automation/apptracking/ForegroundTracker.kt` (pure, 8 Tests)
- `automation/apptracking/ForegroundAppProbe.kt`
- `test/.../apptracking/AppTrackingDigitalWiringTest.kt` (11 Tests)
- `test/.../apptracking/AppTrackingTakeoverPolicyTest.kt`, `ForegroundTrackerTest.kt`

**Geändert:**
- `automation/apptracking/AppTrackingService.kt` (Übergabe, Screen-OFF-Stop,
  Digital-Neuansatz, Schnappschuss, Live-Service-Start, Doku)
- `automation/screen/ScreenRecordingWorker.kt` (Vordergrund-Guard +
  zentraler `schedule()`)
- `automation/sleep/ScreenEventReceiver.kt` (zentraler Einplan-Punkt)
- `domain/liveactivity/LiveActivityManager.kt` (`stopIfLive`)
- `app/build.gradle.kts` (1.0.30 / 31), `docs/CHANGELOG.md`

## 7. Offen / Hinweise für den Publisher

- **Play Console:** Neues Release mit der AAB aus diesem Build
  (1.0.30 / versionCode 31). Keine neuen Berechtigungen, keine
  Datenfluss-Änderungen.
- **Bewusste Latenz:** App-Wechsel werden innerhalb eines 15s-Poll-Takts
  erkannt („direkt" im Rahmen der Akku-Entscheidung M18.93v11). Ein
  kürzerer Takt würde den Akku-Verbrauch erhöhen — nicht gewollt.
- **Kalender-Fallback** (`CALENDAR_AUTO`) behält wie bisher Vorrang vor
  Smartphone-Aufzeichnungen (nicht Teil der User-Übernahmeliste).

# M26 — Abschlussbericht: Anruf-Erkennung

**Datum:** 2026-10-20
**Version:** Aevum 1.0.46 (versionCode 47)
**DB-Schema:** v49 (`automation_settings` + zwei Spalten, additive Migration 48→49)
**Artefakte:** `Aevum-1.0.46-Debug.apk`, `Aevum-1.0.46-Release.apk`,
`Aevum-1.0.46.aab` — http://192.168.178.40:8098/

---

## 1. Anlass

Devon:

> „Aevum neue Erkennung. Und zwar Anruf-Erkennung. Sollte in den Einstellungen
> erlaubt werden, so wie die anderen Berechtigungen. Und dann eine Zuweisung
> der Activity. Default sozial (auf deutsch). Und immer während ein Anruf
> läuft wird die activity direkt aufgezeichnet, immer falls gerade nichts
> läuft, es soll auch aktuelle Aktivitäten unterbrechen die durch digital
> Nutzung stattfinden, also Bildschirmaufzeichnung oder App Aufzeichnung, auch
> PC digital, aber mehr darf nicht unterbrochen werden, also keine manuell
> gestartet Aktivität und auch keine geofences und Kalender etc."

Vier Anforderungen, alle umgesetzt:

1. **Berechtigung in den Einstellungen** — genau wie die übrigen
   (gleiche Statuszeile, gleicher Erklärungsdialog, gleicher Grant-Flow).
2. **Zuweisung der Aktivität** — Vorgabe `social` („Soziales").
3. **Aufzeichnung während des Gesprächs** — startet automatisch, wenn nichts
   läuft; unterbricht Digital-Nutzung.
4. **Harte Tabu-Liste** — manuell gestartete Aktivitäten, Geofences, Kalender,
   Fahrten, Sport und alles Unbekannte werden **nie** unterbrochen.

---

## 2. Lösung

### 2.1 Rangfolge als reine Logik (`CallDetectionPolicy`)

`domain/call/CallDetectionPolicy.kt` — keine Android-Abhängigkeiten,
JVM-getestet (Muster: `AppTrackingTakeoverPolicy`, `GeofenceAutoStopPolicy`):

| Laufende Quelle | Entscheidung beim Gesprächsbeginn |
|---|---|
| nichts | `START_CALL` |
| `SCREEN_AUTO` (Bildschirmaufzeichnung) | `TAKE_OVER_DIGITAL` |
| `APP_TRACKING` (App-Aufzeichnung) | `TAKE_OVER_DIGITAL` |
| `PC_TRACKER` / `PC_REMOTE` (PC-digital) | `TAKE_OVER_DIGITAL` |
| `CALL_AUTO` (eigene Aufzeichnung) | `ALREADY_TRACKING` |
| `MANUAL`/`LIVE`, `GEOFENCE_AUTO`, `CALENDAR_AUTO`, `ACTIVITY_RECOGNITION_AUTO`, `WALKING_AUTO`, `GARMIN_*`, `PING_AUTO`, **alles Unbekannte** | `KEEP_FOREIGN` |

Die Digital-Menge ist eine **Allowlist**, keine Blockliste: Eine künftige
Aufzeichnungsart (neuer Milestone) ist automatisch tabu, statt versehentlich
vom Anruf übernommen zu werden. Genau dieser Fehler war bei der
Geofence-Policy (M18.144) und der Digital-Übergabe (M18.151) je einmal real.

**Beim Gesprächsende** beendet `onCallEnded` ausschließlich die eigene
`CALL_AUTO`-Session (`STOP_OWN`); hat zwischenzeitlich eine andere Quelle
übernommen, passiert nichts (`NOTHING`).

### 2.2 Warum der Dienst die Policy VOR `start()` fragt

`LiveActivityManager.start()` schneidet eine laufende Live-Session
grundsätzlich ab (Überlappungs-Trimm M18.71) — für **jede** Quelle. Ein Anruf,
der blind startet, würde also auch eine manuell gestartete Aktivität oder eine
Geofence-Session zerschneiden. Deshalb:

```
onCallStarted()
  → CallDetectionPolicy.onCallActive(...)      // entscheidet
  → mayRecord(action)? sonst return            // Gate
  → liveActivityManager.start(sourceType = "CALL_AUTO")
```

Der Trimm trifft dann ausschließlich eine Digital-Session — genau die
gewünschte Wirkung.

### 2.3 Erkennung: Vordergrunddienst + Telefonie-Callback

`automation/call/CallDetectionService.kt`, FGS `specialUse`
(Subtyp `call_state_detection`):

- **API 31+:** `TelephonyManager.registerTelephonyCallback(mainExecutor, …)`
  mit `CallStateListener`; **API 29/30:** `PhoneStateListener(LISTEN_CALL_STATE)`.
- Ausgewertet werden `CALL_STATE_OFFHOOK` (Gespräch läuft → Start) und
  `CALL_STATE_IDLE` (Ende → eigener Stop). **`RINGING` startet bewusst
  nicht:** „während ein Anruf läuft" heißt Gespräch, nicht Klingeln — ein
  abgelehnter Anruf soll nichts aufzeichnen.
- Berechtigung `READ_PHONE_STATE` (dangerous, Laufzeit). **Keine**
  `READ_CALL_LOG`, `READ_PHONE_NUMBERS`, `CALL_PHONE`, `ANSWER_PHONE_CALLS` —
  die App liest nur den Zustand.
- Ende über `stopIfLive(sessionId)` (Race-Schutz), nie über ein blindes
  `stop()`.
- Startet zusätzlich `LiveActivityService`, damit die Aufzeichnung Banner +
  Notification hat (Muster wie der PC-Fernstart, M23).

**Warum kein Broadcast-Receiver:** `android.intent.action.PHONE_STATE` steht
nicht auf der Implicit-Broadcast-Ausnahmeliste — ein Manifest-Receiver bekäme
den Broadcast seit Android 8 unzuverlässig. Der Callback im lebenden Prozess
plus Vordergrunddienst ist der dokumentierte Weg für `READ_PHONE_STATE`.

### 2.4 Wiederbelebung (vier Beine)

1. `AevumApplication` (Prozess-Start, liest den Schalter),
2. `BootReceiver` (BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED),
3. `CallDetectionWatchdogWorker` (15-Minuten-Takt, `KEEP`, Gates: Schalter +
   Berechtigung),
4. `START_STICKY` des Dienstes.

### 2.5 Einstellungen (UI)

- **Berechtigungszeile** „Anruf-Erkennung (Telefonstatus)" in der
  fusionierten Berechtigungs-Karte — gleiche Optik, gleicher
  `PermissionDisclosureDialog` (`PermissionDisclosureKind.CALLS`), gleicher
  Settings-Deep-Link bei Dauer-Ablehnung (kein stiller No-Op).
- **Karte „Anruf"** mit Schalter (gesperrt ohne Berechtigung, Klick führt in
  den Erklärungsdialog), Zuweisungs-Zeile (öffnet den Aktivitäten-Picker) und
  Hinweistext, was unterbrochen wird und was nicht.
- Grant schaltet den Trigger sofort scharf (`pendingTrigger ==
  "call_detection"` → `setCallDetection(true)`) — die zweite
  Verdrahtungsstelle, ohne die der Schalter trotz Grant aus bliebe.

### 2.6 Datenbank

`automation_settings` + `call_detection_enabled` (Default `0`) und
`call_activity_type_id` (Default `'social'`). Migration `48 → 49` rein
additiv (zwei `ADD COLUMN`), kein Tabellen-Neubau, kein Index. Bestandszeilen
behalten alle bisherigen Einstellungen (Test: `CallDetectionMigrationTest`
auf echter SQLite aus `48.json`).

### 2.7 Timeline

`CALL_AUTO` ist in `AUTO_SOURCES` (Auto-Kennzeichnung wie Geofence/Kalender) und
hat auf der Detailseite ein eigenes Label: „📞 Anruf (Auto)" / „Call (auto)".

---

## 3. Tests

**+35 Tests** (1289 → **1324**), 0 Fehler, 1 skipped.

| Klasse | Inhalt |
|---|---|
| `CallDetectionPolicyTest` (15) | Alle vier Digital-Quellen übernehmen; manuell/Geofence/Kalender/Fahrt/Wanderung/Garmin/Ping tabu; **unbekannte künftige Quelle tabu** (Allowlist-Gegenprobe); Feature aus → `DISABLED`; tote Live-Session blockiert den Start nicht; `mayRecord`-Matrix; Anruf-Ende beendet nur Eigenes; Digital-Begriff = genau vier Quellen; Quell-Typen identisch mit `AppTrackingTakeoverPolicy` |
| `CallDetectionWiringTest` (10) | Manifest (Berechtigung, FTS-Typ, Subtyp, **keine** weitergehenden Telefonie-Rechte — geprüft an echten `<uses-permission>`-Deklarationen); Policy **vor** `start()`; `mayRecord`-Gate; `stopIfLive` statt `stop()`; beide API-Wege; `RINGING` wird nicht aufgezeichnet; fehlende Berechtigung mit Logzeile; drei Wiederbelebungs-Pfade; Watchdog-Gates; UI-Verdrahtung inkl. Grant-Pfad; DB-Registrierung; `AUTO_SOURCES` |
| `SchemaV49Test` (3) | v49 = v48 + genau zwei Spalten, alle anderen Tabellen Element für Element identisch, beide Spalten NOT NULL mit Default (`0` / `'social'`) |
| `CallDetectionMigrationTest` (4, Robolectric) | Echte SQLite aus `48.json` → Room mit `MIGRATION_48_49`: Vorgaben gesetzt, **Bestands-Einstellungen unverändert** (Geofencing AN, Digital Balance AN, `sleep_source=health_connect`, Vorlauf 7 min, Kalender-Sync AN, Fahrt-Stopp AUS), `user_version=49`, Schreiben über die neuen Spalten funktioniert |
| `PermissionDisclosureTest` (erweitert) | `calls` in der Kind-Liste: Titel als Frage, Einleitung ≥ 120 Zeichen, drei konkrete Punkte ≥ 25 Zeichen, Hinweis nennt lokale Verarbeitung + Widerruf, DE/EN-Schlüsselparität |
| `M12RegressionTest`, `GoalArchitectureWiringTest`, `GoalV47MigrationTest`, `PmRemovalMigrationTest` | auf v49 / `AUTO_SOURCES` nachgezogen |

---

## 4. Design-Entscheidungen hinterfragt

| Entscheidung | Alternative | Bewusst gezahlter Preis |
|---|---|---|
| Neuer FGS `specialUse` (`call_state_detection`) | Broadcast-Receiver für `PHONE_STATE` | Ein dritter Vordergrunddienst und ein neuer Play-Subtyp; dafür funktioniert die Erkennung auch bei geschlossener App (Broadcast kommt seit Android 8 unzuverlässig). |
| `READ_PHONE_STATE` + Telefonie-Callback | `AudioManager.getMode()` (ohne Berechtigung) | Eine dangerous-Permission mehr; dafür echte Zustandswechsel (OFFHOOK/IDLE) statt Polling-Raten — und der User wollte es ausdrücklich „wie die anderen Berechtigungen". |
| Start erst bei `OFFHOOK` | Start bei `RINGING` | Ein klingelnder, aber abgelehnter Anruf erzeugt keine Aufzeichnung; dafür beginnt die Aufzeichnung erst mit dem Gespräch. |
| Allowlist der unterbrechbaren Quellen | Blockliste der tabu-Quellen | Eine künftige Quelle muss bewusst eingetragen werden; dafür kann sie nie versehentlich abgeschnitten werden. |
| `PC_REMOTE` gilt als Digital | Als Nutzerabsicht (manuell) behandeln | Ein vom PC gestarteter Lauf wird vom Anruf unterbrochen; dafür entspricht es der User-Formulierung „auch PC digital". **Ein-Zeilen-Änderung** in `CallDetectionPolicy.DIGITAL_SOURCES`, falls anders gewünscht. |
| Anruf-Ende beendet nur Eigenes | Nach dem Anruf die vorherige Digital-Aufzeichnung wiederherstellen | Die App-Aufzeichnung startet beim nächsten Poll von selbst wieder (2-s-Takt, `onRepeatedForeground`); dafür fasst das Anruf-Ende nie eine fremde Session an. |

---

## 5. Nicht Bestandteil (bewusst nicht gebaut)

- **Keine VoIP-Erkennung** (WhatsApp/Signal/Teams-Anrufe). Ein
  `TelephonyCallback` sieht nur die klassische Telefonie (GSM/VoLTE über den
  System-Dialer). VoIP wäre ein eigener Weg (Audio-Fokus/Call-Audio-Modus) —
  nicht beauftragt.
- **Keine Rufnummern-, Kontakt- oder Verlaufsauswertung** (kein
  `READ_CALL_LOG`, kein `READ_CONTACTS`) — und damit auch keine
  „Anruf bei Familie = Aktivität X"-Regeln.
- **Keine nachträgliche Korrektur** der Dauer („Anruf war nur 2 Minuten") —
  die Session läuft, bis `IDLE` kommt.
- **Keine Änderung** an Digital-Balance, App-Tracking, Geofence-, Kalender-,
  Fahrt- oder Schlaf-Logik; die Rangfolge der anderen Quellen bleibt
  unangetastet.
- **Keine eigene Diagnose-Seite** (wie `AppTrackingLog`): Der Dienst
  protokolliert jede Entscheidung mit Tag `CallDetection`; eine eigene
  Log-Ansicht ist nicht Teil dieses Auftrags.

---

## 6. Ehrliche Einschränkungen

- **Kein Geräte-Test.** Auf dieser Maschine gibt es kein Android-Gerät und
  keinen Emulator mit Telefonie: Der reale Zustandswechsel
  (Anruf kommt an → `OFFHOOK` → Aufzeichnung → `IDLE` → Stopp) wurde **nicht**
  auf Hardware durchgespielt. Verifiziert sind: die Rangfolge (JVM-Tests), die
  Verdrahtung (Quelltext-Wächter), die Migration (echte SQLite unter
  Robolectric) und der vollständige Build von Debug-APK, Release-APK und AAB.
  Die Prüfschritte am Gerät stehen in §8.
- **Play-Console:** Der neue Vordergrunddienst nutzt `specialUse` mit dem
  Subtyp `call_state_detection`. Wird die App über den Play Store verteilt,
  muss die Erklärung für Vordergrunddienste in der Play Console um diesen
  Subtyp ergänzt werden (Formular, kein Code). Die Manifest-Deklaration ist
  vorhanden und getestet.
- **`PC_REMOTE` als Digital** ist eine Auslegung der Formulierung „auch PC
  digital" (siehe §4). Wenn ein vom PC gestarteter Lauf als Nutzerabsicht
  gelten soll, ist er aus `DIGITAL_SOURCES` zu entfernen — dann unterbricht
  ihn der Anruf nicht mehr.
- **Zustandsloses Verhalten beim Dienst-Neustart mitten im Gespräch:**
  Stirbt der Dienst während eines Anrufs und wird neu gestartet, kennt er die
  Session-ID nicht mehr; das Ende räumt dann über die Policy
  (`liveSourceType == CALL_AUTO`) auf. Das ist abgedeckt, aber nicht
  geräteseitig verifiziert.

---

## 7. Messwerte

| Punkt | Wert |
|---|---|
| Tests | **1324** Tests, **0 Fehler**, **1 skipped** (+35 gegenüber M25) |
| Build | `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:bundleRelease`, Exit **0** |
| DB | **v49**, Migration 48→49 (zwei `ADD COLUMN`) |
| Neue Dateien | `domain/call/CallDetectionPolicy.kt`, `automation/call/CallDetectionService.kt`, `automation/call/CallDetectionWatchdogWorker.kt` + 4 Testklassen |

Build-Log: `/root/builds/m26-final.log`; Testberichte:
`app/build/test-results/testDebugUnitTest/`.

---

## 8. Verifikation am Gerät (Empfehlung)

1. **Einstellungen → Trigger & Erkennung → Berechtigungen**:
   „Anruf-Erkennung (Telefonstatus)" antippen → Erklärungsdialog → „Erlauben"
   → Systemdialog → erteilt (Status wird grün).
2. **Karte „Anruf"**: Schalter einschalten (ohne Berechtigung führt der Klick
   in den Dialog, der Schalter bleibt gesperrt).
3. **Zuweisung prüfen**: Zeile zeigt „Soziales" (Default); antippen und eine
   andere Aktivität wählen.
4. **Anruf bei laufender Digital-Aufzeichnung** (z. B. Instagram geöffnet,
   App-Aufzeichnung läuft): Anruf annehmen → Digital-Session endet, „Soziales"
   läuft (Notification/Banner).
5. **Anruf bei manuell gestarteter Aktivität**: manuelle Session starten, dann
   anrufen → die manuelle Aktivität läuft **unverändert weiter**, keine
   Anruf-Aufzeichnung.
6. **Anruf bei laufendem Geofence/Kalender-Termin**: dieselbe Erwartung —
   nichts wird unterbrochen.
7. **Anruf beenden**: Die „Soziales"-Session endet exakt beim Auflegen; die
   Timeline zeigt „📞 Anruf (Auto)".

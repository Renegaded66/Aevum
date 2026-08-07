# M18.43 — 7 Bugfixes + Bucket-List-Gamification

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**
**DB:** v21 (MIGRATION_20_21: `difficulty`-Spalte für Bucket-List-Gamification)

---

## 1. Abgehakte wiederkehrende Todo zeigt im Dashboard noch "offen"

**Root Cause (gewiss):** `todoRepository.getByDate(today)` wurde beim `combine`-Setup **einmal** mit dem damaligen Datum abonniert. Über Mitternacht (oder wenn das ViewModel vor Mitternacht erstellt wurde) blieb der Flow auf dem **alten Datum** — die Completion von HEUTE wurde nie geladen → "1 offen" obwohl abgehakt.

**Fix:** Alle Completions laden (`getAllCompletions`), `buildState` filtert selbst auf das **frische** `today` (Getter, nicht mehr val). Der Minuten-Tick erzwingt den Rebuild.

## 2. "Gym betreten" häufiger hintereinander, obwohl nicht verlassen

**Root Cause (gewiss):** DWELL feuert alle ~90s, solange der User im Geofence bleibt. Jedes DWELL erzeugte (seit M18.41-Mapping) einen neuen ENTER-Trigger → "Gym betreten" alle paar Minuten.

**Fix:** DWELL erzeugt nur dann einen neuen Trigger, wenn der letzte Trigger **kein** ENTER war (also ein EXIT dazwischen lag). Der Session-Start/Refresh läuft trotzdem IMMER — der Auto-Discard-Schutz bleibt erhalten.

## 3. Beim Gym-Verlassen: "Zuhause verlassen" + "Arbeit verlassen" gleichzeitig

**Root Cause (gewiss):** `triggerTypeFor()` mappte DWELL als LEFT/EXIT, weil nur `transition == Enter` geprüft wurde. GPS-Drift an den Rändern anderer Geofences erzeugt DWELLs für Zuhause/Arbeit, während der User im Gym ist → jedes wurde als "verlassen" gespeichert.

**Fix:** DWELL ist ein **bestätigter ENTER** (User verweilt 90s) und wird jetzt wie Enter gemappt.

## 4. Walking-Trigger erst nach 5 Minuten am Stück

**Fix:** WALKING/RUNNING-ENTER starten einen 5-Minuten-Timer (UniqueWork + REPLACE — jedes weitere ENTER-Sample refresht). Ein EXIT dazwischen **cancelt** den Timer → kein False-Trigger bei kurzen Wegen. ON_BICYCLE bleibt sofort (klares Signal).

## 5. "Zuhause angekommen" wurde nicht als Trigger angemerkt

**Root Cause (gewiss):** Der Echo-Schutz im Debouncer hatte **kein Zeitfenster**. Wenn ein EXIT durch GPS-Flattern verworfen wurde, blieb `confirmedState` ewig "Enter" → der nächste echte ENTER (nach Stunden draußen) wurde als "Echo" unterdrückt.

**Fix:** Echo nur innerhalb von `ECHO_WINDOW_MS` (10 Minuten). Danach ist ein gleicher Übergang ein echter neuer Besuch.

## 6. Bucket List: Gamification (User: "viel zu unspektakulär")

Nach Recherche (Goji-Case-Study, Buckist): Der Kern-Unterschied zur To-Do-Liste ist **Gamification**:
- **Level + XP:** Jedes abgehakte Item gibt XP (10 pro Schwierigkeits-Stern, 1-5). XP füllen eine Level-Leiste.
- **Level-Titel:** Neuling → Träumer → Abenteurer → Entdecker → Weltenbummler → Legende.
- **Schwierigkeits-Sterne** (1-5) pro Item — im Editor (großer Picker) und direkt auf der Karte antippbar, mit "+X XP"-Anzeige.
- **Hero:** Fortschritts-Ring + Level-Badge + XP-Fortschrittsbalken + "Gesamt: X XP".
- DB v21: `difficulty`-Spalte (Default 1, bestehende Einträge bleiben erhalten).

## 7. Autofahrt wurde überhaupt nicht aufgezeichnet + kein Trigger (KRITISCHSTER FIX)

**Root Cause (gewiss):** `ActivityTransitionReceiver` hatte `android:exported="false"` — **Google Play Services ist eine ANDERE App** und sendet den Transition-Broadcast über den System-Broadcast. Mit `exported="false"` wurde der Broadcast von GMS **blockiert** → KEINE IN_VEHICLE-Events, keine Trigger, keine Sessions. Die Action ist GMS-spezifisch (`com.google.android.gms.location.ACTION_ACTIVITY_TRANSITIONS`), daher ist `exported="true"` sicher.

**Fix:** Manifest: `exported="true"`.

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (1m 11s)
- Commit: `c7cac9a` (18 Dateien, Schema 21.json generiert)

## Test-Anleitung
1. **Todo:** Wiederkehrende Todo abhaken → Dashboard zeigt sofort "Alle erledigt 🎉"
2. **Gym:** Betreten → EIN "Gym betreten"-Trigger, Session startet. Verlassen → EIN "Gym verlassen", Session stoppt. Keine Zuhause/Arbeit-Falschtrigger mehr.
3. **Walking:** 5 Min durchgehend laufen → Trigger erscheint. Kurzer Weg → kein Trigger.
4. **Zuhause:** Nach längerer Abwesenheit heimkommen → "Zuhause angekommen" erscheint.
5. **Bucket List:** Eintrag mit Schwierigkeit anlegen → Sterne setzen → abhaken → XP + Level steigen.
6. **Autofahrt:** Einsteigen → "Mobilität" startet + DRIVING_STARTED-Trigger. Aussteigen → stoppt.

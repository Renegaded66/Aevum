# M18.44 — Edit-Redesign, Tap-to-Create, Trigger-Settings, Todo-Fix

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (112 MB) — **BUILD SUCCESSFUL**
**DB:** v22 (MIGRATION_21_22: `driving/walking/bicycle_detection_enabled`)

---

## 1. Todo-Dashboard-Bug — der ECHTE Root Cause (M18.43-Fix war unvollständig!)

**Symptom:** Dashboard zeigt "1 offen", Todos-Screen zeigt abgehakt.

**Diagnose (Code-Beweis):** Der M18.43-Fix (Datum-Staleness) war zwar korrekt, aber **unvollständig**. Der eigentliche Unterschied zwischen den Screens:
- `TodosViewModel` Zeile 95: `done = completion || **autoDone**` — ein **Dauer-Todo** (`targetMinutes > 0`, z.B. "30 Min lesen") gilt als erledigt, sobald die heutige Aktivitätszeit des zugehörigen ActivityType das Ziel erreicht — **ohne** Completion-Eintrag in der DB.
- `DashboardViewModel` zählte **nur** DB-Completions → jedes auto-erledigte Dauer-Todo erschien als offen.

**Fix:** Dashboard berechnet jetzt identische autoDone-Logik (Duration-By-Type aus den heutigen Sessions, Ziel-Vergleich).

## 2. Activity-Edit-Redesign (User: "Drag & Drop kann weg, stattdessen fancy Picker")

- **380dp-Drag-TimeRail entfernt** (komplett — User-Wunsch).
- **Zwei AevumTimePicker-Uhren** nebeneinander: Start = Sonnengold (0xFFF5A623), Ende = Primary. Analoge 24h-Uhr mit Minuten-Ring, Snap auf 5 Min, digitale Anzeige (Stunde/Minute antippbar), ± Feinjustierung. **Keine Standard-Library** — eigenes Canvas-Design.
- **AevumTimePicker-Bug gefixt:** `remember { mutableStateOf(initialHour) }` ohne Key → beim Wechsel zwischen Sessions blieb der Zeiger beim alten Wert. Jetzt `remember(initialHour, initialMinute)`.
- **TimeRow/TimeBump-Chips (−h/+h/−15/+15) entfernt** — der User wollte stattdessen echte Picker.
- **Trigger-Snap** bleibt als dezente Quick-Action (Tap setzt Start/Ende auf Trigger-Zeitpunkt) — Architektur-Vorbereitung.

## 3. Tap-to-Create in der Tagesansicht (Google-Calendar-Prinzip)

- **Tap auf leere Zeitstelle** → berechnet Minute aus Y-Position (Scroll-Versatz wird von Compose automatisch zurückgerechnet) → **QuickCreateDialog**.
- **Tap auf Session-Block** → öffnet weiterhin die Session (Hit-Test mit X-Bereichs-Prüfung — nur rechts der Uhr-Achse).
- **QuickCreateDialog:**
  - Getippte Startzeit (groß, gold) + Endzeit-Vorschau (+1h Standard)
  - Aktivitäts-Auswahl (Icon + Name, scrollbar, mit Auswahl-Highlight)
  - Endzeit-Picker (aufklappbarer AevumTimePicker)
  - **"Erstellen"** → fixe Session (`createQuickSession`)
  - **"● Jetzt aufzeichnen"** → Session startet ab getippter Zeit und **läuft weiter** (`endAt = null`, `startQuickSession`) — exakt wie das automatische Tracking, nur manuell ausgelöst.

## 4. Trigger-Settings-Seite (User: "alle Trigger in eigener Seite, einzeln entscheiden")

**Neue Seite "Trigger & Erkennung"** (Settings → Automatisierung → Trigger & Erkennung):

| Trigger | Toggle | Effekt |
|---|---|---|
| 📍 Geofences | `geofencingEnabled` | Betreten/Verlassen erkannt |
| 🚗 Autofahren | `drivingDetectionEnabled` | IN_VEHICLE-Erkennung |
| 🚶 Walking & Laufen | `walkingDetectionEnabled` | 5-Min-Regel-Trigger |
| 🚴 Radfahren | `bicycleDetectionEnabled` | ON_BICYCLE-Trigger |
| 🌙 Schlaf-Erkennung | `sleepFusionEnabled` | 3-Signal-Fusion |

**Reflexions-Fund:** `geofencingEnabled` war vorher **nirgends als echtes Gate verdrahtet** — reine Kosmetik! Die Toggles sind jetzt **echte Gates**:
- `GeofenceRegistrar`: bei off → alle Geofences beim System **deregistriert** (kein Broadcast, kein Akku-Verbrauch), bei an → neu registriert. Sofort wirksam beim Umschalten.
- `GeofenceBroadcastReceiver`: prüft pro Event (Doppel-Absicherung).
- `ActivityTransitionReceiver`: prüft pro Activity-Typ (IN_VEHICLE/WALKING/RUNNING/ON_BICYCLE), 30s-Cache in der Bridge gegen DB-Last.
- **Zukunftsoffen:** `AutomationSettings`-Entity ist bewusst erweiterbar — neue Trigger-Arten = Feld + Eintrag in der Liste (Hinweis-Karte "Mehr Trigger folgen").

## 5. Settings-Gruppierung (User: "besser sortieren und gruppieren")

Neue Struktur:
1. **Automatisierung** — Trigger & Erkennung (primär) · Berechtigungen & Status · Geofences verwalten · Trigger Events
2. **Meine Orte** — Zuhause · Arbeit (mit Vorhanden/Jetzt-anlegen-Status)
3. **Deine Aktivitäten** — Activity Types
4. **Erweitert** — Ziele · Gewohnheiten · Todos · Tagespauschalen · Bucket List
5. **Datenschutz & Daten**

Toter "Smartphone-Nutzung & Activity Recognition"-Hinweis entfernt (die Konfiguration ist jetzt real über Trigger & Erkennung erreichbar).

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (1m 8s)
- Commit: `45462b7` (15 Dateien, Schema 22.json generiert)
- DI-Kette geprüft: `AutomationSettingsRepository`-Binding existiert (RepositoryModule), `GeofenceRegistrar`-Erweiterung kompatibel.

## Test-Anleitung
1. **Todo:** Dauer-Todo ("30 Min lesen") anlegen, Ziel-Zeit tracken → Dashboard zeigt "Alle erledigt 🎉"
2. **Edit:** Aktivität bearbeiten → zwei Uhren, Drag-Rail weg. Uhr drehen → Zeit ändert sich live.
3. **Tap-to-Create:** Tagesansicht, leere Stelle antippen → Dialog. "Erstellen" → Block erscheint. "Jetzt aufzeichnen" → Session läuft ab getippter Zeit.
4. **Trigger-Settings:** Geofences aus → Orte werden sofort deregistriert (Log: "geofencingEnabled=false"). Auto/Walking/Rad einzeln abschaltbar.
5. **Settings:** Neue Gruppen sichtbar, Trigger & Erkennung führt die Automatisierung an.

# M18.144 — Zwei Geofence-Fehler behoben

**Datum:** 2. Oktober 2026
**Version:** 1.0.23 (versionCode 24) — vorher 1.0.22 (versionCode 23)

---

## Der Bericht

> „Beim Erstellen eines Geofences sollte direkt geprüft werden, ob man sich
> im Geofence befindet. Weil ich habe gerade am aktuellen Standort einen
> Geofence erstellt mit einer Automatisierung, aber die Aufzeichnung ist
> nicht gestartet, weil ich war ja schon drin. Und als ich verlassen habe,
> wurde die manuell gestartete Aktivität nicht geendet."

Zwei getrennte Fehler — beide bestätigt, beide behoben.

---

## FEHLER 1 — Kein Start beim Anlegen INNERHALB des Geofence

### Ursache

Der Registrar erzwingt bewusst **kein** `INITIAL_TRIGGER_ENTER`
(M18.64: das hätte bei jedem App-Öffnen einen False-Start ausgelöst, weil
Google dann sofort einen ENTER liefert, sobald man im Geofence ist).

Und der nächste Zonen-Check kommt erst mit dem
`ProactiveGeofenceCheckWorker` — **bis zu 5 Minuten später**. Wer den
Geofence anlegt und dann losfährt, ist beim ersten Check längst draußen.
Der ENTER wurde nie gesehen, die Automatisierung startete nie.

Zusätzlich: `checkNow()` erkennt einen Auto-Start nur bei einem
**Zonenwechsel**. Der gemerkte Zonen-Zustand stammte aus einem Lauf, in dem
der neue Geofence noch gar nicht existierte — er war unbrauchbar.

### Fix

Nach dem Speichern wird der aktuelle Standort **sofort** gegen den neuen
Geofence geprüft:

1. `invalidateZoneState()` — verwerfen von `prev_zone_id`, Presence-Zustand
   und gemerkter Zone. Ohne das sieht `checkNow()` keinen Wechsel.
2. `checkNowInBackground()` — führt den Check aus, kehrt sofort zurück.
3. Läuft **nach** `insert()` und `refreshRegisteredGeofences()`, weil
   `checkNow()` die Geofence-Liste aus der DB liest.
4. **Nur** wenn der gespeicherte Geofence selbst eine Automatisierung hat.

### Zwei Fallen, die beim Bauen aufgefallen sind

**Falle A — Blockade:** `checkNow()` holt einen GPS-Fix (1–5 s). Synchron im
`viewModelScope` hätte der Nutzer so lange auf einen reagierenden Bildschirm
gewartet. Schlimmer: Die UI navigiert nach `saved = true` sofort zurück,
womit der `viewModelScope` abgebrochen würde — der Check käme nie zum Ende.

Gelöst über einen eigenen Scope am Singleton-Provider
(`checkNowInBackground()`), der die Navigation überlebt. Dasselbe Muster
wie im `LiveActivityManager`. Ein Guard-Test verhindert den Rückfall auf
den synchronen Aufruf.

**Falle B — Nebenwirkung:** Der Check läuft bei **jedem** Speichern. Stand
der Nutzer Zuhause (Zone aktiv) und bearbeitet einen Geofence „Gym" weit
weg, hätte das Verwerfen des Zustands die „Zuhause"-Automatisierung neu
angestoßen — eine Nebenwirkung, die der Nutzer beim Bearbeiten eines
fremden Ortes nicht erwartet.

Gelöst über die Kopplung an `gf.autoStartActivityTypeId != null`: nur der
Geofence mit eigener Automatisierung löst den Check aus. Auch dafür ein
Guard-Test.

---

## FEHLER 2 — Manuell gestartete Aktivität endete nicht

### Ursache

**Beide** Stop-Pfade verlangten `sourceType == "GEOFENCE_AUTO"`:

```
CurrentZoneProvider.kt:       existing.sourceType == "GEOFENCE_AUTO"
GeofenceTransitionProcessor:  isAutoSession = (sourceType == "GEOFENCE_AUTO")
                              → sonst „Auto-Stop übersprungen: Session .. ist manuell"
```

Eine vom Nutzer selbst gestartete Aktivität trägt `sourceType = "MANUAL"`.
Sie wurde beim Verlassen deshalb **nie** beendet.

### Warum das falsch war

Die Herkunft beantwortet nicht die Frage, die der Stop-Pfad klären muss:
„Gehört die laufende Aktivität zu diesem Geofence?"

Der Nutzer hat den Geofence mit einer Aktivität konfiguriert und fährt mit
einer laufenden Aufzeichnung dieser Aktivität los. Für ihn ist die Erwartung
eindeutig: Beim Verlassen endet sie. Ob er sie selbst oder Aevum sie beim
Betreten gestartet hat, ist für ihn unsichtbar.

### Fix: `GeofenceAutoStopPolicy`

Neue reine Funktion, die **beide** Pfade nutzen — damit können sie nicht
mehr auseinanderlaufen:

| Regel | Ergebnis | Deckt ab |
|---|---|---|
| Session-Aktivität == Geofence-Aktivität | **Stop** | **Der gemeldete Fall** (manueller Start), und Auto-Start |
| Ursprungs-Trigger gehört zum Geofence | **Stop** | Aktivitätswechsel während des Aufenthalts |
| Sonst | kein Stop | fremde Aktivität, anderer Geofence |

Zusätzlich getrennt: `mayDiscardUnconfirmed(sourceType)`. Der
Auto-Discard-Schutz (GPS-Spike) bleibt **herkunftsgebunden** — nur
unbestätigte AUTO-Sessions dürfen verworfen werden, eine manuelle nie. Das
ist die einzige Stelle, an der die Herkunft weiterhin zählt, und sie ist
jetzt explizit benannt statt implizit vermischt.

Der Verlauf der ENTER-Trigger (letzte 24 h, `getByGeofenceId`) liefert die
Evidenz für die zweite Regel.

### Nachtrag: die Regel lag zunächst doppelt — und einmal tot

Beim Verdrahtungs-Check fiel auf: `mayDiscardUnconfirmed` war zunächst
**nur von Tests aufgerufen**. Die echte Produktivstelle
(`LiveActivityManager.discardLiveSession`, M12.1) verglich `sourceType`
weiter hart mit `"GEOFENCE_AUTO"`.

Damit hätte ich zwei Probleme geschaffen:

1. **Eine zweite Wahrheit.** Die Auto-Quellen stehen zentral in
   `AUTO_SOURCES` (`GEOFENCE_AUTO`, `HEALTH_SLEEP_AUTO`,
   `ACTIVITY_RECOGNITION_AUTO`, `WALKING_AUTO`). Wird die Liste erweitert,
   hätte der hartkodierte Vergleich in `discardLiveSession` stillschweigend
   falsch entschieden.
2. **Ein Fake-Guard.** Ein Test, der eine Funktion prüft, die im
   Produktivcode niemand aufruft, beweist nichts über die App — nur über
   sich selbst.

Korrigiert: `discardLiveSession` nutzt jetzt die Policy. Der frühere
String-Vergleich ist weg. Ein Guard-Test erzwingt genau das („die
Discard-Regel ist zentral verdrahtet, nicht hartkodiert") — inklusive der
Gegenprobe, dass der harte Vergleich nicht zurückkehrt. Sabotage-Test
bestanden: String-Vergleich wieder eingebaut → Test **FAILED** ✓

---

## Tests: 1001 gesamt, 0 Fehler (+20 neue)

### `GeofenceAutoStopPolicyTest` (11)

Verhaltenstests der Stop-Entscheidung, u. a.:
- **`manuell gestartete Aktivität wird beim Verlassen beendet`** — der gemeldete Fall
- `fremde Aktivität bleibt unangetastet`
- `Geofence ohne Automatisierung stoppt nichts`
- `automatisch gestartete Session wird weiterhin gestoppt` (Regression)
- `Aktivitaetswechsel im Geofence wird ueber den Trigger erkannt`
- `Trigger eines anderen Geofence stoppt nicht`
- `nur automatische Sessions duerfen verworfen werden`
- `der alte sourceType-Check haette den gemeldeten Fall verpasst` — Beweisführung

### `GeofenceCreateAtCurrentPlaceTest` (8)

Verdrahtungs-Guards, u. a.:
- `die Discard-Regel ist zentral verdrahtet, nicht hartkodiert`
- `save prueft den aktuellen Standort gegen den neuen Geofence`
- `der Check laeuft nach dem Speichern und nicht vorher`
- `die Zone wird vor dem Check zurueckgesetzt`
- `der Check blockiert das Speichern nicht` (Falle A)
- `der Sofort-Check laeuft nur bei eigener Automatisierung` (Falle B)
- `Ausgangslage - sourceType-Check ist ENTFERNT`

### Reproduktion vor dem Fix

Die Tests wurden **zuerst** geschrieben. Vor dem Fix schlugen 5 fehl —
das ist der Reproduktionsbeleg für beide gemeldeten Fehler.

### Gegenproben

| Sabotage | Ergebnis |
|---|---|
| Aktivitäts-Vergleich in der Policy entfernt (`if (false)`) | 3 Tests **FAILED** ✓ |
| Harter `sourceType`-Vergleich in `discardLiveSession` wieder eingebaut | 1 Test **FAILED** ✓ |

Danach jeweils wiederhergestellt, Suite grün (erzwungener Lauf mit
`--rerun-tasks`: 1001 Tests, 0 Fehler).

---

## Geänderte Dateien

| Datei | Änderung |
|---|---|
| `automation/geofence/GeofenceAutoStopPolicy.kt` | **neu** — Stop-Entscheidung als reine Funktion |
| `automation/geofence/CurrentZoneProvider.kt` | `invalidateZoneState()`, `checkNowInBackground()`, Stop über Policy |
| `automation/geofence/GeofenceTransitionProcessor.kt` | Stop über Policy statt sourceType |
| `domain/liveactivity/LiveActivityManager.kt` | Discard-Regel über Policy statt hartkodiertem String |
| `ui/screens/automation/AutomationViewModels.kt` | Sofort-Check nach dem Speichern |
| `test/.../GeofenceAutoStopPolicyTest.kt` | **neu** — 11 Verhaltenstests |
| `test/.../GeofenceCreateAtCurrentPlaceTest.kt` | **neu** — 8 Verdrahtungs-Guards |
| `app/build.gradle.kts` | versionCode 24 / 1.0.23 |

---

## Was Devon prüfen sollte

1. **Geofence am aktuellen Standort anlegen, mit Automatisierung** →
   die Aufzeichnung sollte jetzt **sofort** starten (nicht erst nach 5 Min)
2. **Aktivität manuell starten, dann den Geofence verlassen** →
   die Aufzeichnung sollte jetzt **enden**
3. Gegenprobe: Aktivität manuell starten und den Geofence verlassen, für den
   eine **andere** Aktivität konfiguriert ist → sie darf **nicht** enden
4. Version 1.0.23 (versionCode 24) hochladen

**Wichtig für Test 1:** Der Zonen-Check braucht einen GPS-Fix. Bei
Innenräumen kann der 1–5 s dauern oder ungenau sein — wenn der Fix deutlich
neben dem Geofence-Mittelpunkt liegt und der Radius klein ist, erkennt er
die Zone möglicherweise nicht. Bei den typischen Radien (≥ 150 m) ist das
unkritisch.

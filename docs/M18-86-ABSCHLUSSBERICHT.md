# M18.86 — ABSCHLUSSBERICHT

## Echte Fahrtstrecken + Fancy Marker in der Orts-Timeline

**Datum:** 30.08.2026 · **Branch:** `main` · **APK:** `app/build/outputs/apk/debug/app-debug.apk`

---

## 1. Was der User wollte

> „Timeline noch präziser. Dass man zumindest halbwegs die Fahrtstrecke sieht.
> Nicht jede Kurve aber alle paar Minuten mal. Und die einzelnen Standorte
> nicht einfach langweilige Kreise, mach mal wirklich was schönes, vielleicht
> falls vorhanden mit den Geofence Icons. Mach das ganze mal wirklich richtig
> schön."

Zwei Teile: **(A)** echte Fahrtstrecken statt Luftlinien, **(B)** schöne
Marker mit Geofence-Icons statt Kreisen.

---

## 2. Teil A — Echte Fahrtstrecken (Track-Recording)

### Architektur (ADR-0030)

- **Neue Tabelle `location_track_point`** (DB v39 → v40): `id`, `session_id`
  (FK → `activity_session`, ON DELETE CASCADE), `recorded_at`, `latitude`,
  `longitude`, `accuracy_meters`, `speed_mps`. Index auf `recorded_at`.
- **Aufzeichnung im bestehenden `DriveDetectionService`** (5s-GPS-Stream,
  kein neuer Service): Während einer laufenden Auto- oder Walking-Session
  wird jeder Fix verdichtet gespeichert:
  - **≥ 30 m Bewegung** seit letztem Punkt → neuer Punkt („alle paar Minuten")
  - **≥ 60 s Stillstand** (Ampel, Stau) → Heartbeat-Punkt, damit die Strecke
    nicht reißt
  - Genauigkeit > 200 m → verworfen (Multipath-Ausreißer)
  - Session-Wechsel (Auto → Spazieren) → Anker neu gesetzt, Strecken
    vermischen sich nicht
- **Verdichtung:** ~17.000 Fixes/24h Fahrt → ~150–300 Punkte/Tag (Google-
  Timeline-Maßstab).
- **Retention:** 90 Tage, Fire-and-Forget beim App-Start (AevumApplication).
- **Anzeige:** `TrackSegmentBuilder` (pure Funktion, 7 Unit-Tests) filtert
  Punkte auf die Unterwegs-Lücke, verwirft ungenaue, zerschneidet an
  Zeitlücken > 5 Min (GPS-Ausfall = keine erfundene Gerade). Die Karte
  zeichnet pro Lücke eine **echte Polyline** (Farbe des Start-Orts, kräftig,
  gestrichelt im Google-Rhythmus). **Luftlinie nur noch Fallback** für
  Lücken ohne Track (Tage vor M18.86) — dezenter (opacity 0.35).

### Warum diese Entscheidungen (Selbst-Hinterfragung)

| Frage | Antwort |
|---|---|
| Neuer Service? | Nein — DriveDetectionService läuft eh, hält den Stream, kennt die Session-Id. Doppelte Location-Infrastruktur wäre Verschwendung. |
| Rohe 5s-Punkte speichern? | Nein — 17k/Tag blähen die DB auf, die Karte braucht „alle paar Minuten". Verdichtung im Service. |
| Track in `trigger_event`? | Nein — Trigger sind Diagnose-Events, keine Geometrie. Eigene Tabelle, saubere Semantik. |
| Punkte im combine-Flow? | Nein — Live-Inserts alle 25s würden die Visits-Berechnung ständig neu triggern. Separater StateFlow, einmal pro Tagwechsel. |
| Retention-Job? | Kein neuer Worker — App-Start reicht bei 90 Tagen locker. |

---

## 3. Teil B — Fancy Marker (Google-Timeline-Optik)

`placePinIcon()` zeichnet pro Ort einen **Pin** (54×64 dp, an der Spitze
geankert):

- **Bubble** in der Ortsfarbe mit weißem Rand + Schatten-Tiefe (3D-Gefühl)
- **Geofence-Emoji** im Kopf (z. B. 🏋️ fürs Gym, 🏠 Zuhause) — Fallback 📍
- **Reihenfolge-Badge** (① ② ③ …) oben rechts — Google-Timeline-Nummerierung
- **Pin-Spitze** in abgedunkelter Ortsfarbe

Marker-Tap → Callout (Name, Zeiten, Dauer) + Liste scrollt; Listen-Tap →
Kamera fliegt zum Ort. Dark-Mode tönt die Kacheln. Alles wie gehabt.

---

## 4. Verifikation

- ✅ **223/223 Unit-Tests grün** (7 neue TrackSegmentBuilder-Tests: Lücken-
  Filter, Zeitlücken-Schnitt, Genauigkeits-Filter, Stillstand, leere Lücke)
- ✅ `compileDebugKotlin` BUILD SUCCESSFUL
- ✅ Room-Schema `40.json` generiert (Migration 39→40 validiert)
- ✅ Full Build via robust_build.sh detached — **BUILD SUCCESSFUL (exit 0)**
- ✅ APK-Signatur: kanonischer Debug-Key (Update ohne Uninstall)
- ❌ **Geräte-Verifikation offen** — Test: Fahrt machen, Orts-Timeline öffnen,
  Strecke sollte der echten Route folgen (nicht Luftlinie); Marker zeigen
  Emojis; alte Tage (vor M18.86) zeigen weiter Luftlinien

---

## 5. Ehrliche Limitation

- **Keine Punkte vor M18.86** — alte Tage zeigen Luftlinien (Fallback).
- **Kein Straßen-Snapping** — die Strecke folgt den GPS-Punkten, nicht dem
  Straßengraphen. Bei gutem GPS ist das kaum unterscheidbar; in Tunneln/
  Innenstädten kann sie leicht abweichen (Zeitlücken-Schnitt verhindert
  aber erfundene Geraden).
- **Walking-Tracks** werden mit aufgezeichnet (gleiche Pipeline) — die
  Karte zeigt auch Spaziergänge als Strecke.

---

## 6. Geänderte Dateien

| Datei | Änderung |
|---|---|
| `data/model/LocationTrackPoint.kt` | NEU — Entity |
| `data/db/LocationTrackPointDao.kt` | NEU — DAO (Insert, Range-Query, Retention) |
| `data/db/AppDatabase.kt` | v40, Entity+DAO, MIGRATION_39_40 |
| `data/repository/LocationTrackPointRepository(Impl).kt` | NEU |
| `di/DatabaseModule.kt`, `di/RepositoryModule.kt` | Bindings |
| `automation/activityrecognition/DriveDetectionService.kt` | Track-Recording (Verdichtung, Session-Anker, Flush) |
| `domain/placetimeline/TrackSegmentBuilder.kt` | NEU — pure Segment-Logik |
| `ui/screens/placetimeline/PlaceTimelineMap.kt` | Fancy Pins, echte Polylines, Fallback-Luftlinien |
| `ui/screens/placetimeline/PlaceTimelineViewModel.kt` | Track-StateFlow, Tagwechsel-Load |
| `ui/screens/placetimeline/PlaceTimelineScreen.kt` | Wiring |
| `AevumApplication.kt` | Retention (90 Tage) |
| `app/schemas/.../40.json` | NEU — Room-Schema |
| `test/.../TrackSegmentBuilderTest.kt` | NEU — 7 Tests |
| `docs/DECISIONS.md` | ADR-0030 |

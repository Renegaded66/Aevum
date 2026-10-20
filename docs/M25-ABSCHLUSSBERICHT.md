# M25 — Abschlussbericht: Geofence benennt bestehende Orts-Timeline-Einträge

**Datum:** 2026-10-20
**Version:** Aevum 1.0.45 (versionCode 46)
**DB-Schema:** unverändert v48 (keine Migration, kein Backfill)
**Artefakte:** `Aevum-1.0.45-Debug.apk`, `Aevum-1.0.45-Release.apk`,
`Aevum-1.0.45.aab` — http://192.168.178.40:8098/

---

## 1. Anlass

Devon nach dem Geofence-Flow aus M24:

> „Bei den geofences fände ich es noch cool, wenn nachdem ich einen neuen Ort
> erstellt habe wo vorher unbenannt stand, dann soll in der Ortstimeline dann
> auch an dem Punkt wo ich es hinzugefügt habe der Ort stehen. Bestenfalls
> auch frühere und spätere Male in der Ortstimeline wo auch der Ort ist mit
> unbenannt."

Kern: Der Geofence wird aus einem `UnknownPlaceSession` heraus angelegt. Die
historische Unknown-Place-Zeile bleibt in der DB aber `name = null`; die
Timeline zeigte sie deshalb weiter als „Ort (unbenannt)" — auch am gerade
angelegten Punkt.

---

## 2. Lösung

### 2.1 Rendering-Zuordnung statt Daten-Backfill

`PlaceTimelineEngine` prüft in Quelle 3 (Unknown-Places) jetzt für jeden
unbenannten Besuch:

1. Koordinaten des Unknown-Place,
2. alle nicht gelöschten Geofences,
3. Haversine-Distanz ≤ `radiusMeters`,
4. bei mehreren Treffern der nächstgelegene.

Trifft ein Geofence zu, wird der `PlaceVisit` mit **Name, Icon und Farbe des
Geofences** und `geofenceId` emittiert; die Evidenz wird aus der Dauer
abgeleitet (`GEOFENCE_LONG` / `GEOFENCE_SHORT`). Sonst bleibt der bisherige
`UNNAMED_PLACE`-Pfad.

```kotlin
val matchedGeofence = geofenceForPoint(u.latitude, u.longitude, geofences)
if (matchedGeofence != null) {
    visits += PlaceVisit(
        id = "unknown_${u.id}",
        geofenceId = matchedGeofence.id,
        name = matchedGeofence.name,
        icon = matchedGeofence.icon,
        color = matchedGeofence.color,
        ...
        evidence = visitEvidence(displayEnd - displayStart)
    )
    continue
}
```

### 2.2 Warum kein DB-Backfill?

- Die Unknown-Place-Rohdaten bleiben unverändert — keine Migration, keine
  Massen-Updates, keine Gefahr, historische Evidenz zu verlieren.
- Der Abgleich läuft bei jedem Tag-Rendering. Damit erscheinen **frühere und
  spätere Besuche** automatisch mit dem Namen, sobald der Geofence existiert.
- Wird der Geofence gelöscht (`deletedAt != null`), fällt der Ort wieder auf
  „Ort (unbenannt)" zurück — das Verhalten bleibt ehrlich und umkehrbar.
- Deaktivierte, aber nicht gelöschte Geofences zählen mit: Sie erklären
  historische Aufenthalte, genau wie in den übrigen Engine-Quellen.

### 2.3 Abgrenzung

- Orte **außerhalb** des Radius bleiben unbenannt (kein falsches Zuordnen
  benachbarter Orte). Wer einen größeren Bereich will, vergrößert den Radius
  im Geofence-Editor.
- Bei überlappenden Geofences gewinnt der nächstgelegene Mittelpunkt.
- Benannte Unknown-Places (`NAMED_PLACE`) bleiben unverändert; die Regel
  betrifft nur „Ort (unbenannt)".

---

## 3. Tests

`PlaceTimelineEngineTest` +3 (jetzt 42 Tests in der Klasse):

1. **Unbenannter Ort innerhalb eines Geofence zeigt dessen Namen** —
   prüft Name, `geofenceId`, Icon und `GEOFENCE_LONG`.
2. **Frühere und spätere Besuche desselben Ortes** — zwei Unknown-Places
   (10:00 und 16:00, leicht versetzte Koordinaten) tragen beide „Büro".
3. **Außerhalb des Radius bleibt unbenannt** — 52.01/8.0 bei 100-m-Radius
   bleibt `UNNAMED_PLACE` mit leerem Namen.

Vor der Implementierung waren Test 1 und 2 rot (der alte Code emittierte
`UNNAMED_PLACE`), danach grün.

---

## 4. Design-Entscheidungen hinterfragt

| Entscheidung | Alternative | Bewusst gezahlter Preis |
|---|---|---|
| Koordinatenabgleich beim Rendern | Unknown-Place-Namen in der DB auf den Geofence-Namen setzen | Kein Backfill; dafür bleibt die Roh-Evidenz unangetastet und die Benennung ist beim Löschen des Geofences umkehrbar. |
| Radius als Match-Grenze | Großzügiger Cluster-Radius (z. B. Radius + 100 m) | Frühere Besuche knapp außerhalb bleiben unbenannt; dafür werden benachbarte Orte nicht fälschlich zusammengelegt. |
| Nächstgelegener Geofence bei Überlappung | Kleinster Radius gewinnt | Bei zwei Ringen um denselben Punkt ist der nächste Mittelpunkt die intuitivere Zuordnung; ein Sonderfall bleibt theoretisch. |
| Deaktivierte Geofences zählen mit | Nur aktive Geofences matchen | Ein deaktivierter Ort kann historische Aufenthalte weiter benennen; dafür taucht sein Name auch auf, wenn er aktuell nicht scharf ist. |

---

## 5. Nicht Bestandteil (bewusst nicht gebaut)

- Kein rückwirkendes Umbenennen der `unknown_place_session`-Tabelle.
- Keine Änderung an der Unknown-Place-Erkennung oder am Geofence-Radius.
- Kein Bearbeiten des Geofences direkt aus der Timeline (der bestehende
  Geofence-Editor bleibt der einzige Pfad).
- Keine Änderung an der Karten-/Pin-Logik: Der gematchte Visit nutzt die
  Geofence-Mitte, nicht den rohen Unknown-Point.
- Keine Änderung an Digital Balance, Widget oder PC-Tracker.

---

## 6. Ehrliche Einschränkungen

- **Kein Geräte-Test:** Der Ablauf „Geofence anlegen → zurück zur
  Orts-Timeline → alte Zeile heißt jetzt Büro" wurde nicht auf einem
  physischen Gerät durchgeklickt. Verifiziert ist die Engine mit echten
  `UnknownPlaceSession`-/`PlaceGeofence`-Objekten; die Timeline liest die
  Engine-Ausgabe unverändert.
- **Radius-Grenze:** Besuche knapp außerhalb des gewählten Radius bleiben
  unbenannt. Das ist bewusst, kann aber bei sehr klein gewähltem Radius
  wie eine Lücke wirken.
- **Zwei Geofences am selben Punkt:** Der nächstgelegene gewinnt; bei
  identischen Mittelpunkten entscheidet die Listenreihenfolge. Kein
  Nutzerfall, aber nicht spezifiziert.

---

## 7. Messwerte

| Punkt | Wert |
|---|---|
| Tests | **1289** Tests, **0 Fehler**, **1 skipped** (+3 gegenüber M24) |
| Build | `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:bundleRelease`, Exit **0**, **4m 33s** |
| Debug-APK | 124.510.364 B (118,7 MB), SHA-256 `07723eb6…ce360f14`, SHA-1 `9c3055c4…fbe0e8` (kanonisch) |
| Release-APK | 52.290.703 B (49,9 MB), SHA-256 `216dd6ce…630c693b`, SHA-1 `c8430d35…361ffe9` (Upload-Key) |
| AAB | 24.619.426 B (23,5 MB), SHA-256 `ccb6cc3f…ce25bb88` |
| Server | alle drei Dateien HTTP 200, `versionCode=46`, `versionName=1.0.45-debug` |
| DB | unverändert v48, keine Migration |
| Commit | `0db8ac6` (Code + Doku); dieser Berichtsnachtrag ist ein Folge-Commit |

Build-Log: `/root/builds/m25-final.log`; Testberichte:
`app/build/test-results/testDebugUnitTest/`.

---

## 8. Verifikation am Gerät (Empfehlung)

1. Orts-Timeline → vergangener Tag mit „Ort (unbenannt)".
2. Ort antippen → „Geofence hinzufügen" → Namen vergeben (z. B. „Gym") →
   speichern.
3. Zurück zur Orts-Timeline: Die gerade angelegte Zeile muss „Gym" mit
   Geofence-Icon/Farbe zeigen.
4. Andere Tage mit demselben Ort prüfen — auch dort muss „Gym" stehen.
5. Einen Ort außerhalb des Radius prüfen — er bleibt „Ort (unbenannt)".

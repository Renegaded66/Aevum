# M24 — Abschlussbericht: Digital-Balance-Tageswechsel & Geofence aus Orts-Timeline

**Datum:** 2026-10-20
**Version:** Aevum 1.0.44 (versionCode 45)
**DB-Schema:** unverändert v48 (keine Migration)
**Artefakte:** `Aevum-1.0.44-Debug.apk`, `Aevum-1.0.44-Release.apk`,
`Aevum-1.0.44.aab` — http://192.168.178.40:8098/

---

## 1. Anlass

1. **Bug:** „Bei Digital Balance kann man ja im Balkendiagramm durch die Tage
   skippen. […] Irgendwie bleibt die Zahl oben die ganze Zeit die Zeit vom
   heutigen Tag und wechselt nicht beim Klick auf das Balkendiagramm auf die
   Bildschirmzeit vom jeweiligen Tag. Andere Sachen hingegen laden korrekt
   beim Wechseln des Tages."
2. **Feature:** „Ich möchte, dass man von dort aus einen Geofence einrichten
   kann. Beispielsweise war ich vorgestern für einige Stunden an einem Ort
   und in der Ortstimeline steht dann Ort (unbenannt). Ich möchte dann darauf
   klicken können und dann auf Geofence hinzufügen und dann soll man zur
   Geofence hinzufügen Seite kommen, wo schon der Standort übernommen wird."

---

## 2. Digital-Balance-Bug — Ursache und Fix

### 2.1 Ursache (code-belegt)

`DigitalBalanceViewModel.buildState` startete vier parallele Queries. Die
Detail-Query war **fest auf heute** verdrahtet:

```kotlin
val detailDeferred = async { aggregator.todayDetail() }
```

`todayDetail()` berechnet Gesamtzeit, Unlock-Zähler und 24 Stunden-Buckets
immer ab `LocalDate.now()` bis jetzt. Beim Klick auf einen Balken wechselten
zwar `usageByAppForDay(date)` (App-Liste) und `dailyTotals`, aber der Hero-Ring
las `state.todayTotalMs`, die Unlock-Kachel `state.unlockCount` und der
Stundenverlauf `state.hourlyMs` — alle drei kamen aus der heutigen
Detail-Query. Zusätzlich nutzte `selectedDayTotalMs` denselben heutigen Wert,
wodurch die App-Anteils-Balken an vergangenen Tagen falsch skalierten.

### 2.2 Fix

- Neue `AppUsageAggregator.detailForDay(date)`:
  gleicher Rechenpfad wie `todayDetail` (Activity-Faltung,
  Mitternachts-/Stunden-Clipping, Unlock-Zählung), aber mit dem Fenster
  `[date 00:00, date+1 00:00)`; zukünftige Tage liefern 0, heute wird bis
  jetzt geklemmt.
- `detailForRange(start, end, zone)` als gemeinsame, private Basis — keine
  duplizierte Stundenlogik.
- ViewModel: `if (isToday) todayDetail() else detailForDay(effectiveDay)`.
- Feld `todayTotalMs` → **`totalMs`** (Gesamtzeit des angezeigten Tages).
  Hero-Ring, Zähler-Animation und Fortschritt lesen `state.totalMs`.
- `selectedDayTotalMs` ist jetzt ebenfalls die Detail-Gesamtzeit des
  gewählten Tages (App-Anteils-Balken korrekt).
- Kopfzeile zeigt bei gewähltem Tag dessen Datum (`Mi, 15.10.`) statt
  „Heute"; bei heute bleibt „Heute".

### 2.3 Tests

- `AppUsageAggregatorDetailTest` (Robolectric + `ShadowUsageStatsManager`):
  baut echte Usage-Events für gestern (10:00–11:30) und heute, prüft
  Gesamtzeit, Unlock-Zahl, Stundenbuckets 10/11 und dass gestern ≠ heute.
  **Red vor der Implementierung** (fehlende Methode), grün danach.
- `DigitalBalanceDaySwitchWiringTest`: Quell-Wächter, dass der ViewModel
  `detailForDay(effectiveDay)` aufruft und die UI `state.totalMs` (nicht
  `todayTotalMs`) liest.

---

## 3. Orts-Timeline → Geofence

### 3.1 Nutzerfluss

1. Orts-Timeline öffnen, Tag wählen.
2. Auf eine Besuchszeile tippen (z. B. „Ort (unbenannt)", 15:10–18:40).
3. Detail-Sheet zeigt Ort, Zeitraum, Dauer, Evidenz-Badge und Koordinaten.
4. **„Geofence hinzufügen"** → Geofence-Editor öffnet mit übernommenem
   Standort; Name (falls benannt) und 150-m-Radius sind vorausgefüllt.
5. Name vergeben, speichern — der neue Geofence ist sofort aktiv
   (bestehender Speicher-/Registrierungspfad).

### 3.2 Technik

- **UI:** `PlaceTimelineScreen` bekommt `onCreateGeofence(lat, lon, name)`.
  Die Besuchszeile öffnet zusätzlich zur bisherigen Karten-Auswahl ein
  `ModalBottomSheet` (`VisitDetailSheet`, internal für Compose-Tests).
- **Sheet-Zustände:**
  - kein Geofence + Koordinaten → Button „Geofence hinzufügen";
  - bestehender Geofence → Hinweis „Dieser Ort ist bereits ein Geofence.";
  - keine Koordinaten → Hinweis „Für diesen Ort sind keine Koordinaten
    gespeichert.";
  - unbenannter Ort → zusätzlicher Hinweis, den Namen im Editor zu vergeben.
- **Navigation:** `geofenceCreateRoute(lat, lon, name)` baut
  `geofence/new?lat=…&lon=…&name=…`; der Name wird URL-kodiert
  (Leerzeichen `%20`, Sonderzeichen dekodierbar).
- **Route:** `composable("geofence/new?lat={lat}&lon={lon}&name={name}")`
  mit optionalen String-Argumenten (Default `""`). Der bisherige Aufruf
  `navigate("geofence/new")` aus der Geofence-Liste funktioniert unverändert.
- **Editor:** `GeofenceEditorViewModel` liest die drei Args aus dem
  `SavedStateHandle` und setzt sie vor dem Laden eines bestehenden Geofence
  ins Formular. `geofencePrefillFromArgs` validiert:
  - lat ∈ [-90, 90], lon ∈ [-180, 180],
  - Komma-Dezimaltrenner wird akzeptiert,
  - 0/0 („Null-Insel") wird abgelehnt,
  - Name wird getrimmt und auf 80 Zeichen begrenzt.
  Ungültige Args → Editor bleibt leer statt mit falschen Koordinaten zu
  speichern.

### 3.3 Tests

- `GeofencePrefillTest` (5): gültige Koordinaten, leerer Name, Komma,
  ungültige/0/0-Werte, Namenslänge.
- `GeofenceCreateRouteTest` (3): Koordinaten, `%20`-Kodierung, Sonderzeichen.
- `PlaceTimelineGeofenceSheetTest` (4 Compose-Tests): unbenannter Ort →
  Callback mit korrekten Koordinaten und `null`-Namen; benannter Ort →
  Name; bestehender Geofence → kein Button; keine Koordinaten → kein Button.

---

## 4. Design-Entscheidungen hinterfragt

| Entscheidung | Naheliegende Alternative | Bewusst gezahlter Preis |
|---|---|---|
| `detailForDay` im Aggregator, gemeinsame `detailForRange`-Basis | Im ViewModel `todayDetail()` nehmen und Stunden selbst umrechnen | Eine zusätzliche Aggregator-Methode; dafür bleibt die Event-Faltung die EINE Wahrheitsquelle. |
| `todayTotalMs` in `totalMs` umbenennen | Feldname behalten, nur anders befüllen | Anfassen von vier UI-Stellen; dafür ist der Name ehrlich und ein Rückfall fällt beim Compiler auf. |
| Detail-Sheet statt Button direkt in der Zeile | Kleines „+"-Icon pro Zeile | Ein zusätzlicher Tap; dafür sieht der Nutzer vor dem Anlegen Koordinaten/Zeitraum und kann bewusst entscheiden. |
| Koordinaten als Query-Args der Route | Globalen „Pending Geofence"-State im Repository | Keine unsichtbare globale Übergabe; dafür ist die Route lang, aber reproduzierbar/testbar. |
| Prefill-Validierung im ViewModel | Auf Nav-Argumente vertrauen | Ein paar Zeilen; dafür kann nie 0/0 oder eine ungültige Koordinate gespeichert werden. |
| Bestehende Geofences ohne Button | Button „Bearbeiten" ergänzen | Kein Edit-Einstieg aus der Timeline; dafür keine doppelte Bearbeiten-Logik (der Editor bleibt der einzige Pfad). |

---

## 5. Nicht Bestandteil (bewusst nicht gebaut)

- Kein Geofence-Editor direkt im Bottom-Sheet — der bestehende Editor bleibt
  die einzige Speicherstelle.
- Keine automatische Namensermittlung (Reverse Geocoding) für „Ort
  (unbenannt)" — der Nutzer vergibt den Namen, keine Netzabhängigkeit.
- Kein Bearbeiten/Umbenennen bestehender Geofences aus der Orts-Timeline.
- Keine Änderung an der Geofence-Erkennung, Radius-Logik oder
  Automatisierungsregeln.
- Kein DB-Schema-Change, keine Migration.
- Keine Änderung an der PC-Tracker- oder Widget-Logik.

---

## 6. Ehrliche Einschränkungen

- **Kein Geräte-Test:** Der komplette Klickpfad (Zeile → Bottom-Sheet →
  Editor → Speichern) wurde nicht auf einem physischen Gerät durchgeklickt.
  Verifiziert sind Compose-Hierarchie (4 Tests), Routen-/Prefill-Logik
  (8 Tests) und die Kompilierung/der Release-Build.
- **Digital Balance:** Der Aggregator-Test nutzt Robolectric-Usage-Events,
  keine echten System-UsageStats. Die Tagesfenster-Logik ist damit
  deterministisch geprüft, nicht gegen ein reales Gerät.
- **Bottom-Sheet-Optik:** Kein Screenshot-Test; die Compose-Tests prüfen
  Sichtbarkeit und Callback, nicht Pixel/Layout auf kleinen Displays.
- **Navigation:** Der Route-Match mit optionalen Query-Args ist durch die
  Compose/Nav-Konfiguration und die Route-Tests abgesichert, aber nicht
  durch einen instrumentierten Nav-Test.

---

## 7. Messwerte

| Punkt | Wert |
|---|---|
| Tests | **1286** Tests, **0 Fehler**, **1 skipped** (+15 gegenüber M23) |
| Build | `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:bundleRelease`, Exit **0**, **5m 43s** (finaler Rebuild nach der Feld-Umbenennung `appCount`) |
| Debug-APK | 125.987.738 B (120,1 MB), SHA-256 `b4d9170b…c7af9262`, SHA-1 `9c3055c4…fbe0e8` (kanonisch) |
| Release-APK | 52.290.707 B (49,9 MB), SHA-256 `f583dee4…04159762`, SHA-1 `c8430d35…361ffe9` (Upload-Key) |
| AAB | 24.618.012 B (23,5 MB), SHA-256 `bb336255…90b98bc1` |
| Server | alle drei Dateien HTTP 200, `versionCode=45`, `versionName=1.0.44-debug` |
| DB | unverändert v48, keine Migration |
| Commit | `268c554` (Code + Doku); dieser Berichtsnachtrag ist ein Folge-Commit |

Build-Log: `/root/builds/m24-final2.log`; Testberichte:
`app/build/test-results/testDebugUnitTest/`.

---

## 8. Von den Tests gefundene Punkte

1. **Erster Aggregator-Test war um Mitternacht rot:** Der Test legte das
   „heute"-Event auf 09:00, der Testlauf war aber kurz nach 00:00 — das
   Event lag in der Zukunft und `detailForDay` lieferte korrekt 0. Der Test
   nutzt jetzt ein garantiert vergangenes Fenster (letzte 30 min bis vor
   2 min) und prüft die tatsächlich erwartete Dauer.
2. **`assertDoesNotExist` ist keine Import-Funktion**, sondern eine Methode
   auf `SemanticsNodeInteraction` — Import entfernt.
3. **Compose-Test braucht die echte String-Ressource:** Der Button-Text wird
   über `context.getString(...)` geholt, damit der Test unabhängig von der
   Test-Locale ist.

---

## 9. Verifikation am Gerät (Empfehlung)

1. Digital Balance öffnen → 7 Tage → gestrigen Balken antippen → große Zahl,
   Unlocks, Top-App und Stundenverlauf müssen gestrige Werte zeigen; Kopf
   zeigt das Datum. Nochmal tippen → zurück zu heute.
2. Orts-Timeline → vergangenen Tag → „Ort (unbenannt)" antippen → Sheet
   prüfen → „Geofence hinzufügen" → Editor zeigt Latitude/Longitude und
   150 m; Namen vergeben, speichern → Geofence erscheint in der Liste.
3. Benannten Ort antippen → Name ist im Editor vorausgefüllt.
4. Bestehenden Geofence antippen → kein „Hinzufügen"-Button, nur Hinweis.

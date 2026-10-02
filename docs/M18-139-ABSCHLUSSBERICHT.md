# M18.139 — Play-Store-Ablehnung behoben (Standort im Hintergrund)

**Datum:** 2. Oktober 2026
**Version:** 1.0.20 (versionCode 21) — vorher 1.0.19 (versionCode 20)
**Auslöser:** Zwei Meldungen der Play-Prüfung zum Update 1.0.19

---

## Zusammenfassung

| Meldung | Ursache (belegt) | Lösung |
|---|---|---|
| **1 — „Richtlinie zu Berechtigungen und APIs … Unklare Funktionsbeschreibung"** | Formulartexte zu knapp; vor allem fehlte die Begründung, warum Vordergrund nicht reicht | Formulartexte neu (Devon hat eingereicht) — App-seitig zusätzlich FGS-Anwendungsfall korrigiert |
| **2 — „Pflicht zur deutlichen Offenlegung und Einwilligung … Unzureichende deutliche Offenlegung"** | **Es gab keinen Disclosure-Dialog.** Der Runtime-Request lief direkt aus den Einstellungen | Prominent In-App Disclosure eingebaut — Dialog vor jedem Request, beim Start, im Datenschutz-Screen |

**Kern-Erkenntnis:** Meldung 2 war **kein Textproblem**, sondern ein Code-Problem.
Die Prüfung schreibt wörtlich: „Anfragen zur Nutzereinwilligung und zu
Laufzeitberechtigungen in der App geht keine unmittelbare Offenlegung in der App
voraus." Ohne Dialog in der App wäre jede weitere Einreichung erneut abgelehnt
worden.

---

## 1 — Prominent In-App Disclosure (der eigentliche Blocker)

### Ausgangslage (verifiziert)

Suche nach `prominent|Offenlegung|Standortdaten|collects location` in
`app/src/main/` → **null Treffer**. Die drei Standort-Permission-Pfade riefen den
System-Dialog direkt auf:

| Stelle | vorher |
|---|---|
| `TriggerSettingsScreen` (PermissionStatusCard, Geofence-Toggle) | `locationLauncher.launch(...)` / `openAppDetails()` |
| `AutomationScreens` / `GeofenceEditorScreen` (Quick-Setup, Quick-Mode, „Aktueller Standort") | `foregroundPermission.launch(...)` / `viewModel.useCurrentLocation()` |
| `MainActivity` / `AppNavHost` | kein Disclosure-Pfad |

### Lösung

Neues Modul `ui/disclosure/LocationDisclosure.kt`:

- **`LocationDisclosureDialog`** — Compose-Dialog mit den vier Google-Pflichtteilen:
  Begriff „Standort", Hintergrund-Phrase („auch wenn die App geschlossen ist oder
  nicht verwendet wird"), **Liste aller** Hintergrund-Funktionen (4 Stück),
  ausdrückliche Zustimmung per Button.
- **`LocationDisclosureGateState`** — Zustandsautomat: merkt die gewünschte
  Aktion, zeigt bei fehlender Zustimmung den Dialog und führt die Aktion erst
  danach aus. Ein Aufrufer kann die Reihenfolge damit nicht verdrehen.
- **`LocationDisclosure`** — Persistenz der Zustimmung (SharedPreferences) mit
  `DISCLOSURE_VERSION`: bei inhaltlicher Textänderung erscheint die Offenlegung
  erneut.
- **`onDismissRequest = onDecline`** — Wegtippen/Zurück gilt ausdrücklich NICHT
  als Einwilligung (Richtlinie: „Must not interpret navigation away from the
  disclosure as consent").

Eingebaut an **vier** Stellen:

1. `TriggerSettingsScreen` — beide Pfade (Vordergrund-Request und App-Details für
   „Immer erlauben") laufen über `requestLocationAccess(...)`.
2. `AutomationScreens` (Geofence-Editor) — Quick-Setup, Quick-Mode-Button und
   „Aktueller Standort" laufen über das Gate.
3. `MainActivity` — einmalige Offenlegung beim Start. Die Richtlinie verlangt
   „normal usage of the app and not require the user to navigate into a menu".
4. `DataSettingsScreens` (PrivacyScreen) — Offenlegung nachlesbar + erneut
   aufrufbar + Link zur Datenschutzerklärung.

### Vollständigkeit der Funktionsliste

Die Offenlegung nennt **alle** Hintergrund-Standort-Funktionen (nicht nur die,
für die der Nutzer gerade tippt — so verlangt es die Richtlinie):

1. Ortserkennung (Geofences)
2. Fahrterkennung
3. Erkennung von Spaziergängen und Radfahrten
4. Orts-Timeline und unbekannte Orte

---

## 2 — Foreground-Service-Policy (in Kraft seit 26.08.2026)

**Google hat Geofencing als genehmigten Anwendungsfall für Dienste im
Vordergrund gestrichen.** Ankündigung 15.04.2026, wirksam seit 26.08.2026 —
also bereits in Kraft, das Update 1.0.19 verstieß schon dagegen:

> „Apps that only use the foreground service permission for geofencing must
> remove their foreground service permissions (including
> `FOREGROUND_SERVICE_LOCATION` and `FOREGROUND_SERVICE`) from the app manifest
> across all active tracks."

### Befund: Der Service war reine Kosmetik

`GeofenceForegroundService` wurde geprüft — er verrichtet **keine** Geofence-Arbeit:

- keine GPS-Anfragen, kein `requestLocationUpdates`
- keine Geofence-Registrierung (die läuft über `GeofencingClient.addGeofences()`
  in `GeofenceRegistrar`)
- keine Verarbeitung von Übergängen (die kommen per `PendingIntent` in den
  `GeofenceBroadcastReceiver`)
- er zeigt nur eine Notification und prüft alle 12 h, ob er sich beenden darf

Google selbst zur Geofence API: „**removes the need to have a service running in
the background for geofencing purposes**".

### Lösung

`GeofenceForegroundService.kt` gelöscht, Manifest-Eintrag entfernt (mit
Begründungskommentar), Start-Aufruf in `AevumApplication` entfernt. Die
Geofence-Funktion bleibt unverändert: Registrierung und Events laufen
ausschließlich über `GeofencingClient` + `GeofenceBroadcastReceiver`.

**Geofencing funktioniert ohne FGS** — der Service war nur eine
Zuverlässigkeits-Maßnahme aus M18.66, kein Funktionsträger.

### DriveDetectionService

Bleibt als `location|shortService` bestehen — er macht echte Standortarbeit
(GPS-Stream für Fahrterkennung). **Aber:** Der Play-Console-Anwendungsfall muss
auf „vehicle activity tracking" lauten. „Geofencing" ist kein zulässiger
Anwendungsfall mehr (Manifest-Kommentar entsprechend ergänzt).

---

## 3 — Manifest-Hygiene (Mindestumfang)

Drei Befunde behoben:

### 3.1 Ungenutzte Berechtigungen entfernt

| Berechtigung | Befund |
|---|---|
| `FOREGROUND_SERVICE_HEALTH` | kein Service deklariert den Typ `health`; Health Connect läuft über die HC-SDK-Berechtigungen |
| `SYSTEM_ALERT_WINDOW` | keine `canDrawOverlays`/`TYPE_APPLICATION_OVERLAY`-Nutzung (Digital Balance läuft über `BlockActivity`, seit M18.61g ausdrücklich ohne Overlay). Verschärft ab Android 15 zusätzlich die FGS-Start-Regeln |

Google fordert ausdrücklich „the minimum permission scope necessary" —
ungedeckte Deklarationen erzeugen nur Prüffläche.

### 3.2 Fehlende `specialUse`-Subtypen ergänzt (Play-Pflichtfeld)

`AppTrackingService` und `LiveActivityService` standen als
`foregroundServiceType="specialUse"` im Manifest, **ohne** das von Google
verlangte `<property>`-Element:

```xml
<property
    android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="app_usage_tracking" />          <!-- bzw. active_activity_session_display -->
```

Google: „In addition to declaring the `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
foreground service type, developers should declare use cases in the manifest.
(…) These values and corresponding use cases are reviewed when you submit your
app in the Google Play Console." Ein fehlender Subtyp ist eine **unvollständige
Deklaration** — genau die Sorte Befund, die zu Rückfragen führt. Nur
`AppBlockService` hatte ihn (M18.61).

Neuer Test `jeder specialUse-Dienst deklariert den Pflicht-Subtyp` erzwingt das
für jeden künftigen Dienst.

---

## 4 — Datenschutzerklärung (online + Repo)

`docs/index.html` (= die unter https://renegaded66.github.io/Aevum/
veröffentlichte Fassung) und `docs/DATENSCHUTZ.md` synchron erweitert:

- neuer Abschnitt **3.1.1 „Standort im Hintergrund — welche Funktionen ihn
  nutzen"** mit Liste aller vier Funktionen und Begründung
- Standort-Berechtigungen präzisiert („Immer erlauben" ist erforderlich, nicht
  optional)
- Hinweis auf den In-App-Dialog und den Entzug über die Systemeinstellungen
- Datum auf 2. Oktober 2026 gesetzt

---

## 5 — Version und Upload

- `versionCode` 20 → **21**, `versionName` 1.0.19 → **1.0.20**
  (Play verlangt für die erneute Prüfung einen höheren versionCode)

---

## Tests (maschinelle Absicherung)

**29 neue Tests**, die ein erneutes Durchfallen verhindern:

### `LocationDisclosureTextTest` (Wortlaut, DE + EN)
Prüft die String-Ressourcen direkt gegen die Google-Kriterien:
- „erhebt Standortdaten"/„collects location data" vorhanden
- „auch wenn die App geschlossen ist"/„even when the app is closed" vorhanden
- alle vier Funktionen einzeln beschrieben (Mindestlänge 40 Zeichen)
- der Pflichtsatz zählt die Funktionen selbst auf (kein Verweis auf eine Liste)
- lokale Speicherung + Werbe-/Weitergabe-Ausschluss benannt
- Zustimmungsbutton ist eine ausdrückliche Handlung (nicht „OK")
- DE/EN-Schlüsselparität
- Datenschutz-URL exakt `https://renegaded66.github.io/Aevum/` und HTTPS

### `LocationDisclosureWiringTest` (Verdrahtung)
Der wichtigste Guard — die Ablehnung entstand durch fehlende Verdrahtung, nicht
durch fehlenden Text:
- jeder Standort-Screen nutzt `rememberLocationDisclosureGate()` **und**
  ruft `disclosureGate.request(` auf
- jeder Standort-Screen **rendert** den Dialog (sonst bliebe `pendingAction`
  gesetzt und die Anfrage liefe nie)
- Zustimmung läuft über `consent`, Ablehnung über `dismiss`
- `locationLauncher.launch(` kommt in TriggerSettingsScreen **genau einmal** vor
  — in der gate-geschützten Funktion
- `MainActivity` rendert die Start-Offenlegung und prüft `isAccepted`
- PrivacyScreen bietet Policy-Link und erneute Offenlegung
- `GeofenceForegroundService.kt` existiert nicht mehr und ist nicht im Manifest
- `FOREGROUND_SERVICE_HEALTH`/`SYSTEM_ALERT_WINDOW` sind nicht im Manifest

### `LocationDisclosureTest` (Logik)
Gate-Entscheidungen, Aktions-Erhalt, `DISCLOSURE_VERSION`, stabiler
SharedPreferences-Name.

**Bestandsanpassung:** `StickyGuardInitRegressionTest` prüft jetzt 4 statt 5
Services (GeofenceForegroundService entfällt).

---

## Geänderte/neue Dateien

| Datei | Änderung |
|---|---|
| `ui/disclosure/LocationDisclosure.kt` | **neu** — Dialog, Gate, Persistenz |
| `res/values/strings_disclosure.xml` | **neu** — DE-Texte |
| `res/values-en/strings_disclosure.xml` | **neu** — EN-Texte |
| `res/values/strings_disclosure_settings.xml` | **neu** — Privacy-Screen-Texte |
| `res/values-en/strings_disclosure_settings.xml` | **neu** |
| `ui/disclosure/LocationDisclosureTest.kt` | **neu** — 7 Tests |
| `ui/disclosure/LocationDisclosureTextTest.kt` | **neu** — 13 Tests |
| `ui/disclosure/LocationDisclosureWiringTest.kt` | **neu** — 9 Tests |
| `automation/geofence/GeofenceForegroundService.kt` | **gelöscht** |
| `AndroidManifest.xml` | Service + 2 ungenutzte Berechtigungen entfernt |
| `AevumApplication.kt` | FGS-Start entfernt, Begründung dokumentiert |
| `MainActivity.kt` | Start-Offenlegung |
| `ui/screens/settings/TriggerSettingsScreen.kt` | Gate an beiden Pfaden |
| `ui/screens/automation/AutomationScreens.kt` | Gate an drei Pfaden |
| `ui/screens/settings/DataSettingsScreens.kt` | Policy-Link + Disclosure |
| `util/BackgroundNotificationHelper.kt` | Kommentar angepasst |
| `automation/StickyGuardInitRegressionTest.kt` | 4 statt 5 Services |
| `app/build.gradle.kts` | versionCode 21 / 1.0.20 |
| `docs/index.html`, `docs/DATENSCHUTZ.md` | Abschnitt 3.1.1 + Datum |

---

## Ausblick: Nächste Frist (nicht akut, aber vormerken)

Google hat im April-2026-Paket eine **dritte** Standort-Regel angekündigt
(„Minimum Scope: Foreground Location Access and the Location Button"):

- **Ab `targetSdk 37` (Android 17)** ist der **Android Location Button** das
  vorgeschriebene Minimum-Scope-Verfahren für *einmalige* Standortabfragen
  (Suche in der Nähe, einmaliges Teilen, Adress-Autofill).
- **Durchsetzung: Ende Januar 2027** (Googles Angabe: „late January 2027";
  Policy-Compliance verpflichtend ab 27.01.2027).
- Alle Apps, die `ACCESS_FINE_LOCATION` anfragen, brauchen dann zusätzlich eine
  **Deklaration** in der Play Console, die begründet, warum der Location Button
  oder grober Standort nicht ausreichen.

**Aevum-Betroffenheit:** aktuell **nicht akut** — das Projekt steht auf
`targetSdk 36`. Die Automatik-Funktionen (Geofences, Fahrterkennung, Timeline)
sind ohnehin **keine** einmaligen Abfragen, sondern dauerhafte Kernfunktionen;
genau für solche Fälle ist `ACCESS_FINE_LOCATION` weiterhin vorgesehen
(„Features requiring continuous, real-time tracking while the app is active").
**Aber:** Sobald auf `targetSdk 37` angehoben wird, muss die
Minimum-Scope-Deklaration ausgefüllt werden. Dann ist die Begründung dieselbe wie
im Hintergrund-Formular: dauerhafte automatische Erkennung, kein Einmal-Abruf.
Das ist ein **Termin zum Vormerken, kein Blocker für diese Einreichung.**

---

## Was Devon noch tun muss (Play Console, nicht im Code lösbar)

> **Wichtig zur Frist:** Die FGS-Regel ist seit **26.08.2026 in Kraft** (Google:
> „effective on 26 August 2026") — nicht erst im Oktober. Das eingereichte
> Update 1.0.19 verstieß also bereits dagegen. Das ist mit diesem Commit behoben.

1. **Video (≤ 30 s)** für das Background-Location-Formular — YouTube-Link bevorzugt,
   Google-Drive-MP4 geht auch. Pflichtinhalte (Google zählt sie einzeln auf):
   - die Funktion, **aus dem Hintergrund aktiviert**
   - der **Prominent-Disclosure-Dialog** in der App
   - der **Runtime-Prompt** danach
   Mit dem neuen Dialog ist das erstmals aufnehmbar. Wichtig: Aufnahme auf einem
   **Android-Gerät** zeigen (kein iOS-Video).
2. **Nur EINE Funktion im Formular erklären.** Google wörtlich: „We can only
   evaluate one feature at a time. The inclusion of multiple features will result
   in an app's rejection." → Empfehlung: **Fahrterkennung** als die eine Funktion
   nennen (die anderen drei nicht im Formular aufzählen!). Die Genehmigung gilt
   danach für die ganze App.
3. **AAB mit versionCode 21** hochladen und einreichen.
4. **FGS-Erklärung**: Anwendungsfall für `location` auf
   „Background Location Updates: **vehicle activity tracking**" umstellen —
   NICHT „Geofencing" (kein zulässiger Anwendungsfall mehr).
5. **Store-Beschreibung erweitern** — Google verlangt für die Offenlegung
   ausdrücklich: „Must be within the app itself **as well as in the app
   description and website**" und „The core feature(s) must all be prominently
   documented and promoted in the app's description." Also in der
   Play-Beschreibung ausformulieren, dass Aevum Standort im Hintergrund nutzt für
   Ortserkennung, Fahrterkennung, Wege und Orts-Timeline. Dazu einen Screenshot
   mit Karte/Standort.
6. **Drei Offenlegungsorte müssen zusammenpassen**: App (Dialog ✓), Play-Beschreibung
   (Devon), Website/Datenschutz (✓ aktualisiert).
7. **Datenschutz-URL im Store-Eintrag** muss gesetzt bleiben
   (https://renegaded66.github.io/Aevum/).
8. **Data-Safety-Formular**: „Genauer Standort" = erhoben, Zweck
   „App-Funktionalität".
9. **Alle Tracks prüfen**: Die FGS-Regel gilt für geschlossene und offene
   Test-Tracks gleichermaßen — dort dürfen keine Alt-APKs mit
   `FOREGROUND_SERVICE_LOCATION` liegen.

---

## Rollback

Ein Commit. `git revert <commit>` genügt. Der entfernte Service ist eine Datei im
Commit-Verlauf.

## Offener Punkt / Testbedarf am Gerät

Nicht automatisch prüfbar (kein Emulator mit `/dev/kvm`):
- Erscheint der Disclosure-Dialog beim ersten Start **und** vor dem ersten
  Permission-Request?
- Bleibt das Wegtippen des Dialogs folgenlos (kein Permission-Dialog danach)?
- Feuern Geofences unverändert ohne den entfernten Service?
  (Erwartung: ja — Registrierung und Events liefen nie über den Service.)

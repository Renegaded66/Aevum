# M18.147 — Abschlussbericht: Neues App-Icon, flüssige App-Aufzeichnung, zuverlässigerer PC-Sync

**Datum:** 2026-10-16
**Version:** 1.0.26 (versionCode 27) → **1.0.27 (versionCode 28)**
**Status:** Implementiert, 1084/1084 Unit-Tests grün (neu: 18), Debug-APK mit
kanonischem Keystore verifiziert, Release-APK signiert („Aevum Upload Key")
gebaut.

---

## 1. Auftrag (User-Nachricht, wörtlich zerlegt)

> „Bei Aevum. Bei digital Balance. Kannst du das auflisten der apps dynamisch
> bei der nutzung machen, dass immer mehr aufploppen. Aber auch während man
> die app flüssig weiter benutzen kann. Insbesondere auf der Seite wo man
> hinkommt wenn man oben auf App-Aufzeichnung klickt. Bei diesem klick hängt
> die app kurz für 3 sekunden und danach wird alles angezeigt. Das könnte
> vielleicht direkt laden und dann während der nutzung weiter laden. Ebenso
> die interaktionen auf dieser seite."

> „Und vielleicht kann die synchronisation zum pc etwas zuverlässiger laufen.
> Beachte dass es eine veröffentlichte app ist die bedingungen einhalten muss
> und nicht zu viel akku verbrauchen darf. Nur vielleicht wenn man die app
> öffnet oder wenn die app gerade eh was im hintergrund macht (und in einem
> wlan ist) dass der einmal synchronisiert falls möglich. Ohne dass man es
> merkt."

> „Und das Bild das ich bei der nachricht bei schicke soll das neue App icon
> werden."

Vier Teile:
1. **App-Aufzeichnung** (über den Button oben im Digital-Balance-Tab):
   Liste soll beim Öffnen sofort da sein und sich beim Benutzen nach und nach
   füllen — ohne 3-Sekunden-Hänger.
2. **Interaktionen auf dieser Seite** sollen flüssig sein.
3. **PC-Sync** zuverlässiger — aber Play-konform, akkuschonend, still;
   Gelegenheiten: App-Öffnen oder ohnehin laufender Hintergrund, und nur im
   WLAN.
4. **Neues App-Icon** aus dem gesendeten Bild (1254×1254, glühende
   Kugel + Orbit-Ring mit 10 Lebensbereich-Icons).

---

## 2. Teil 1: App-Aufzeichnung — 3-Sekunden-Hänger (Root-Cause + Fix)

### Diagnose (belegt am Code)

Der Bildschirm `AppTrackingScreen` hängt nicht beim Navigieren, sondern
bereits bei der **ersten Zustands-Emission**: Das `combine(...)`-Transform
des `AppTrackingViewModel` rief `buildState()` **synchron auf dem
Main-Thread** auf (viewModelScope = Main.immediate). Darin:

- `PackageManager.getInstalledApplications(GET_META_DATA)` — alle Apps,
- `.filter { pm.getLaunchIntentForPackage(...) != null }` — **Intent-Auflösung
  je App**,
- `pm.getApplicationIcon(...)` + Bitmap-Konvertierung **je App** (beim ersten
  Aufruf ohne Cache: mehrere hundert Dekodierungen).

Das erklärt die gemessenen ~3 s exakt — es passt exakt zur User-Beobachtung
„hängt kurz für 3 Sekunden und danach wird alles angezeigt" (ein einziger,
blockierender Durchlauf, der alles auf einmal liefert). **Zweiter Befund:**
Derselbe Aufwand lief bei **jeder** Änderung erneut, weil `addApp()`/
`removeApp()`/`assignActivity()` einen `refreshTick` setzten und der
komplette Scan inklusive Intent-Prüfung je App wiederholt wurde — und ebenso
bei **jedem Suchbuchstaben** (jede `onValueChange` → combine → Full-Scan).
Deshalb fühlten sich auch die Interaktionen zäh an.

### Fix (drei Schichten)

1. **Neuer `InstalledAppsProvider`** (`data/apps/InstalledAppsProvider.kt`,
   `@Singleton`): Der Scan läuft ausschließlich hier auf `Dispatchers.IO` und
   meldet das Ergebnis **chunkweise** (24 Apps je Stück; `onChunk` + finales
   `onComplete`). Icons werden prozessweit gecacht und auf 144 px gekappt
   (angezeigt werden 28 dp). Ein warmer Cache liefert den letzten Stand
   **sofort** (`cachedApps()`), der Neu-Scan aktualisiert still im
   Hintergrund und deckt dabei auch Deinstallationen auf.
2. **ViewModel umgebaut:** Das combine-Transform ist jetzt eine reine,
   günstige Listen-Operation (DB + Cache + Suchfilter, keine
   PackageManager-Aufrufe mehr). Die Chunks werden per
   `AppTrackingOptimism.mergeChunk` (dedupliziert nach Paket, sortiert)
   eingehängt — die Liste wächst sichtbar beim Öffnen („immer mehr ploppen
   auf"), der Screen ist dabei voll bedienbar (Scrollen, Suchen, Tippen).
   Ein `LaunchedEffect` beim Screen-Öffnen stößt einen gedrosselten
   Neu-Scan an (≥ 60 s Mindestabstand, kein Doppel-Scan).
3. **Optimistische Interaktionen** (`ui/screens/apptracking/
   AppTrackingOptimism.kt`, pure & JVM-getestet): App verschieben oder
   Activity zuordnen wirkt **im selben Frame** — ein lokaler `TrackedOverride`
   gilt sofort, die DB-Writes sind serialisiert (Mutex, Reihenfolge bleibt
   auch bei schnellem Hin-und-Her erhalten). Sobald die DB den Stand
   bestätigt, wird der Override entfernt; bei einem Schreibfehler fällt die
   UI still auf den echten Stand zurück (ehrlich statt hängend).
   `settled()` ist die exakte Regel dafür (siehe Testsuite).

### UI-Politur (gleiche Seite)

- Dezenter Spinner (12 dp) neben dem Zähler der linken Spalte und (20 dp)
  im Leerzustand, solange der Scan lädt — statt „keine Ergebnisse" auf einer
  Liste, die gerade erst wächst.
- Der Berechtigungs-Hinweis erscheint erst, wenn der Nutzungszugriff-Status
  wirklich feststeht (vorher nichts statt kurz aufblitzen).

---

## 3. Teil 2: PC-Sync — zuverlässiger, weiter still und sparsam

### Vorher (App 1.0.26)

- `PeriodicWorkRequest`, **30 Minuten**, Constraint `NetworkType.CONNECTED`.
- Planung beim App-Start/Koppeln/Umschalten; Fehler bleiben unsichtbar.

### Jetzt (App 1.0.27)

1. **Takt 30 → 15 Minuten** (WorkManager-Minimum) und **`UNMETERED` statt
   `CONNECTED`**: Der PC steht im Heimnetz — über Mobilfunk hätte der Lauf
   nur Strom und Daten gekostet. Das entspricht der User-Vorgabe „und in
   einem WLAN ist".
2. **Opportunistischer Kick** (`PcAutoSyncScheduler.triggerOpportunistic()`):
   Ein einmaliges Work-Request (gleiche Bedingungen, `UNMETERED`,
   `ExistingWorkPolicy.KEEP` gegen Stapelung) wird ausgelöst:
   - beim **App-Öffnen** (`MainActivity.onResume`),
   - bei **jedem Prozess-Start** im Hintergrund (`AevumApplication.onCreate` —
     trifft genau „wenn die App gerade eh was im Hintergrund macht").
   So holt die App Verpasstes sofort nach, statt bis zum nächsten Takt zu
   warten, den das System (Doze/App-Standby) jederzeit aufschieben kann —
   das war der Kern der „Zuverlässigkeit".
3. **Drossel `PcSyncGate`** (`automation/pctracker/PcSyncGate.kt`, pure &
   JVM-getestet): höchstens **ein Versuch pro 10 Minuten** — Erfolg wie
   Fehlschlag zählen gleich (auch ein abgelehnter Verbindungsversuch kostet
   Funkzeit). Kein Versuch ohne Kopplung, bei ausgeschaltetem Schalter oder
   wenn gerade frisch synchronisiert wurde. Jeder Worker-Lauf stempelt den
   Versuchs-Zeitstempel (`PcTrackerPrefs.lastAttemptAt`), damit Takt- und
   Kick-Pfade sich gegenseitig kennen.
4. **Weiter vollkommen still:** keine Notification, kein Toast, keine
   Fehlermeldung aus dem Hintergrund — Meldungen zeigt ausschließlich der
   manuelle Sync (unverändert). Play-Bedingungen: keine neuen Permissions,
   kein neuer Vordergrunddienst, WorkManager-Idempotenz (UPDATE-Policy)
   bleibt erhalten (übersteht Updates und Neustarts).

**Akku-Rechnung (Konservativ):** Ohne WLAN läuft der Job nie (UNMETERED).
Mit WLAN: maximal 4 reguläre Läufe/Stunde + höchstens 1 Kick je 10 Minuten;
jeder Lauf ist ein lokaler HTTP-Aufruf (Connect-Timeout 3 s, Read 8 s —
bereits vorhanden) — PC an: Millisekunden; PC aus: ein Fehlschlag, dann erst
wieder nach dem Mindestabstand. Kein Dauerdienst, kein Polling im Sekundentakt.

---

## 4. Teil 3: Neues App-Icon

Aus dem gelieferten Artwork (1254×1254) per reproduzierbarem Generator
(`docs/tools/gen_app_icon.py`):

- **Adaptives Icon** (`mipmap-anydpi-v26/ic_launcher.xml` +
  `ic_launcher_round.xml`): Das Artwork liegt **vollflächig** im
  Hintergrund-Layer (108 dp-Raster, je Dichte 108/162/216/324/432 px). Die
  Launcher-Maske (Kreis/Squircle) schneidet die Ecken — dort läuft das
  Artwork randlos dunkel aus. Verifiziert: Unter der Kreis-Maske (Worst
  Case) ragt kein Fremd-Schwarz ins Bild (Median 0 px, Maximum 18 px
  sanfter Rand-Glow an der linken Kante — Teil des Artworks).
- **Vordergrund-Layer bewusst transparent**
  (`ic_launcher_foreground_clear.xml`): Ein zusätzlich gefüllter Layer würde
  bei der Launcher-Parallaxe doppelt erscheinen.
- **Legacy-PNGs** (48/72/96/144/192 px) mit Alpha-Ecken — abgerundet
  (`ic_launcher.png`, Radius 22,5 %) und rund (`ic_launcher_round.png`,
  Kreis) — Sichtprüfung sauber.
- **Manifest:** `android:icon="@mipmap/ic_launcher"`,
  `android:roundIcon="@mipmap/ic_launcher_round"` (vorher zeigte beides auf
  die alte Logo-Vektor-Datei).
- **Play-Console-Asset:** `docs/store-assets/icon-512.png` (512×512, für den
  Icon-Upload im Store-Eintrag).
- **Nebenfund behoben:** `CandidateReviewNotifier` nutzte das vollfarbige
  Logo als Notification-SmallIcon — das System zeigt vollfarbige SmallIcons
  als weißes Viereck (in `ic_notification.xml` seit M18.22 dokumentiert,
  hier war die Korrektur übersehen worden). Jetzt einheitlich
  `R.drawable.ic_notification`.

Hinweis: Der Android-12+-Splash übernimmt das Launcher-Icon automatisch —
das neue Icon erscheint also auch beim App-Start.

---

## 5. Verifikation (Belege)

- **Unit-Tests:** vollständiger Lauf `testDebugUnitTest` — **1084 Tests,
  0 Fehler, 0 Errors** (vorher 1066; neu +18: `AppTrackingOptimismTest` 11,
  `PcSyncGateTest` 7). Der eine Skipped-Test
  (`CyclingVsCarDetectionTest.Luecke…`) ist vorbestehend und dokumentiert.
- **Build (`robust_build.sh`, Exit 0, 10m52s):** `testDebugUnitTest
  assembleDebug assembleRelease`.
  - `app/build/outputs/apk/debug/app-debug.apk` — 123,7 MB,
    „1.0.27-debug" (versionCode 28), **Signing-Guard + apksigner-Nachweis:
    kanonischer Key** („signing-verify OK").
  - `app/build/outputs/apk/release/app-release.apk` — 52,3 MB,
    „1.0.27" (versionCode 28), signiert mit dem Upload-Key
    (CN=Aevum Upload Key, SHA-1 `c8430d3589bc579db2e8c7c6af9a1619e361ffe9`).
- **Icon-Verdrahtung im Paket geprüft:** `aapt dump badging` zeigt als
  App-Icon `res/mipmap-anydpi-v26/ic_launcher.xml` (Debug; im Release von R8
  umbenannt, Inhalt als adaptives Icon mit `background`/`foreground`
  verifiziert). Alle 15 Dichte-PNGs (5 Dichten × abgerundet/rund/Background)
  sind in beiden APKs enthalten (`unzip -l` + Stichprobe per PIL:
  108/162/216/324/432 px).
- **Kompilier-Check** vorab: `:app:compileDebugKotlin` BUILD SUCCESSFUL
  (nur vorbestehende Deprecation-Warnungen).

---

## 6. Geänderte/neue Dateien (Kern)

**Neu:**
- `data/apps/InstalledAppsProvider.kt` — chunkweiser IO-Scan + Cache.
- `ui/screens/apptracking/AppTrackingOptimism.kt` — pure Optimistik-Regeln.
- `automation/pctracker/PcSyncGate.kt` — pure Drossel.
- `mipmap-anydpi-v26/ic_launcher(.round).xml`, `drawable/ic_launcher_foreground_clear.xml`,
  `mipmap-*/ic_launcher(.round)_background.png` u. a. Icon-Assets.
- `docs/tools/gen_app_icon.py`, `docs/store-assets/icon-512.png`.
- Tests: `AppTrackingOptimismTest.kt`, `PcSyncGateTest.kt`.

**Geändert:**
- `AppTrackingViewModel.kt` (Umbau), `AppTrackingScreen.kt` (Spinner,
  Öffnungs-Refresh, Berechtigungs-Guard).
- `PcAutoSyncScheduler.kt` (15 min, UNMETERED, Kick), `PcAutoSyncWorker.kt`
  (Kopplungs-Absicherung, Versuchs-Stempel), `PcTrackerPrefs.kt`
  (`lastAttemptAt`), `MainActivity.kt` (Kick bei App-Öffnen),
  `AevumApplication.kt` (Kick bei Prozess-Start).
- `AndroidManifest.xml` (Icon), `CandidateReviewNotifier.kt` (SmallIcon),
  `app/build.gradle.kts` (1.0.27 / 28), `docs/CHANGELOG.md`.

---

## 7. Offen für den Publisher

- **Play Console:** Im Store-Eintrag das neue `docs/store-assets/icon-512.png`
  hochladen (Icon-Änderungen im APK erscheinen auf Geräten nach dem Update;
  die Console-Kachel ist davon getrennt). Release-APK liegt im Build-Output.
- Keine neuen Berechtigungen, keine Datenfluss-Änderungen — die bestehenden
  Datenschutz-Angaben bleiben korrekt.

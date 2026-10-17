# M19.4 — Abschlussbericht

**Datum:** 2026-10-17
**App-Version:** 1.0.36 (`versionCode 37`, Debug-Suffix `-debug`)
**Datenbank:** v46 — **unverändert** (keine Migration, keine Schema-Berührung)
**Auftrag (Original-Wortlaut):**

> „Aevum UI Update. Ich möchte das dashboard modernisieren. Und zwar schicke
> ich dir hier einen konzeptionellen aufbau. Wie es oben aussehen soll. Ich
> möchte aber auch, dass es so den aufbau vom zweiten bild hat, wenn ich also
> nach unten scrolle dann soll das untere sich über den oberen bereich
> schieben. Hinterfrage wie du das am besten hinkriegst, dass es möglichst
> modern hochwertig aussieht. Du kannst farben aus der jetzigen app übernehmen
> in die kästen meines jetzigen aufbaus, also meine skizze ist wirklich nur ein
> entwurf, du kannst daraus was wunderschönes basteln. Mit schimmern und
> animationen, keine harten kanten, etc."

Zwei Referenzen: **(1)** die handgezeichnete Skizze („Heute" mit
Erfasst/Schlaf/Bildschirm, zentralem Rot→Grün-Gauge mit „75", Zuhause-Chip,
Zeitleiste mit 8/15/23/0, grüne „Aufnahme läuft"-Box), **(2)** das gezeigte
SEO-Dashboard als Qualitätsmaßstab (Gradient-Hero, Glas, halbrundes Gauge,
Karte mit abgerundeter Oberkante, die sich über den Hero schiebt).

---

## 1. Was gebaut wurde — das „Tag-Cockpit"

Der Dashboard-Hero (`DashboardHero.kt`) wurde komplett neu aufgebaut, exakt
nach der Skizzen-Struktur — nur in der Optik der App:

1. **Tages-Navigation ‹ Heute ›** — zentrierte Glas-Pille; Tag-Blättern mit
   Slide-Animation, Datum-Tipp → Kalender-Popup, „Heute"-Rücksprung bleiben.
2. **Drei versetzte Glas-Kacheln** — Erfasst / Schlaf / Bildschirm; die
   mittlere sitzt 10 dp tiefer (Skizzen-Detail). Jede Kachel: Farbverlauf,
   Gradient-Rand, farbiger Glow-Schatten, Kaskaden-Einblendung,
   hochzählender Wert (Monospace), Mini-Balken (Anteil an 24 h) und dezenter
   Schimmer mit **desynchronisierten Perioden** (3,6 s / 4,6 s / 5,8 s).
3. **Zentrales 180°-Güte-Gauge** — fester Rot→Gelb→Grün-Farbraum
   (sweepGradient an den absoluten Winkelpositionen 180°/270°/360°),
   animierter Füll-Sweep (Spring), leuchtender End-Marker mit Puls-Glow und
   Nadel (Skizzen-Pfeil), große Güte-Zahl in `positivityColor` (helles Theme:
   28 % Richtung Schwarz für Kontrast), ✎-Badge bei Tages-Override, Tipp →
   bestehender Güte-Dialog, `contentDescription` für den Screenreader.
4. **Zonen-Chip neben dem Gauge** (Skizzen-„Zuhause") — Glas-Pille mit
   Pin-Icon, Geofence-Name oder „Abwesend", weicher Namenswechsel.
   Der alte ZoneBanner als eigenes Listenelement ist entfallen (Information
   ist jetzt im Hero, keine Dopplung).
5. **Tagesleiste (24 h)** — 44 dp-Ribbon mit Segment-Blöcken in
   Kategoriefarben (Zustands-Alphas wie bisher), **sanft gedimmter Zukunft**
   rechts vom Jetzt-Marker, pulsierendem Glow-Punkt und Stundenlabels
   0/6/12/18/24; Tipp öffnet die Timeline (neuer Einstieg).
6. **Aufnahme-Status als Abschluss** (Skizzen-Grünbox) — live: grüner
   Glas-Balken „Aufnahme läuft" + Titel + Monospace-Timer + Schimmer +
   atmender Punkt; pausiert: Amber-Variante mit eingefrorenem Timer;
   idle: ruhige neutrale Zeile „Aufnahme bereit" mit Tagesfortschritt rechts
   (ersetzt die alte separate Fortschritts-Zeile).

**Das Sheet über dem Hero (Referenzbild-Aufbau):** Das komplette
Karten-Drumherum des Dashboards liegt jetzt in EINER großen, oben mit 28 dp
abgerundeten Fläche (`DashboardSheet.kt`, eigener Verlauf + Rim-Light +
Eisblau-Glow unter der Kante). Sie schiebt sich beim Scrollen über den
gepinnten Hero: Hero-Inhalt −0,5×scroll mit Alpha-/Scale-Fade, Deko-Fläche
−0,55× — Basis ist die **gemessene** Hero-Höhe (`onSizeChanged`). Die
Deko-Fläche übersteht den Hero um 44 dp (`DashboardHeroPeek`): durch die
abgerundeten Sheet-Ecken schimmert der Farbraum hindurch, das Sheet liegt
optisch ÜBER dem Hero (wie im Referenzbild).

**Sonstiges:** Atmosphere-Orbs von Alt-Lila/Mint auf die M19.2-Identität
(Eisblau/Feuerrot/Gold) gezogen; Hero-Gradient in vier Stopps mit
Farb-Schweif bis in den Überstand. Keine DB-, Navigations- oder
Domänen-Änderung; alle bestehenden Karten (Live-Banner, Güte-Verlauf,
Garmin, Reflexion, Todos, Pauschalen, Review) und der FAB mit Orbit-Launcher
bleiben unverändert enthalten.

## 2. Eigene Design-Entscheidungen — hinterfragt

**(a) Festes Rot→Grün-Gauge statt Vollring mit Wert-Farbe.**
Der alte `QualityRing` färbte den Bogen nach dem Wert (Farbe = Aussage). Die
Skizze zeigt dagegen eine **Skala** mit Marker. Bewusst gewählt: fester
Farbraum + Marker/Nadel — als Messgerät lesbar, konsistent bei jedem Wert.
Naheliegende Alternative: den Ring behalten und nur umbauen (weniger Risiko)
— verworfen, weil Vollring UND Gauge dieselbe Information doppelt zeigten.
Preis: `positivityColor` bleibt am Ring in anderen Screens (Timeline/Review)
weiter in Benutzung — zwei Güte-Darstellungen nebeneinander, bewusst
(aa: Detail-Screens zeigen Verlauf, Dashboard zeigt Skala).

**(b) Sheet als EINE Fläche im Scroll-Content statt gepinntem Backdrop.**
Naheliegende Alternative war ein zweites gepinntes Layer mit eigener
Offset-Mathematik (Klassiker „bottom sheet over collapsing header"). Verworfen,
weil die Koordinaten-Synchronisierung (LazyColumn-Viewport + Statusleisten-
Inset + Rounded-Corner-Überlappung) fehleranfällig ist und das im M18.100-Fix
gelöste Tap-Problem potenziell wieder eingeführt hätte (transparente
contentPadding-Fläche schluckt Taps auf den Hero). Preis der gewählten
Lösung: Die Überlappung entsteht über die schnellere Hero-Parallaxe
(1,5× vs. 1×) statt über ein echtes Sheet-Layer — der Effekt ist minimal
indirekter, dafür robust bei Tag-Wechsel-Animationen und Taps.

**(c) Status-Balken im Hero + Banner im Sheet — bewusste „Dopplung".**
M18.7 hatte Dopplungen als Design-Verbot etabliert. Hier zeigen Hero-Balken
und Live-Banner dieselbe Aufnahme — aber mit klarer Rollentrennung: der
Balken ist Statusanzeige (Skizze: grüne Box unten), der Banner bietet
Aktionen (Pause/Stop/Wechsel). Alternative (Banner entfernen, Aktionen in
den Balken) wurde verworfen: Der Banner hat etablierte, getestete
Steuerpfade; ein Umbau hätte Funktionsrisiko ohne Nutzen gebracht.

**(d) Mittelkachel 10 dp tiefer — bewusst „ungerade".**
Die Skizze zeigt die mittlere Kachel tiefer; das wirkt dynamisch, bricht
aber bewusst die strenge Grid-Ausrichtung. Falls es in der Praxis stört,
ist es eine Ein-Zeilen-Änderung (offset entfernen).

**(e) Light-Theme-Kontraste eigenständig gelöst.**
Der erste Entwurf nutzte dunkel-optimierte Grün-/Amber-Töne, die im hellen
Theme zu flach waren (Sichtprüfung!). Jetzt theme-adaptiv (live: #4ADE80 →
hell #16A34A; Güte-Zahl hell abgedunkelt; Gauge-Track hell kräftiger).
Preis: ein paar bedingte Farbableitungen mehr im Code — bewusst akzeptiert,
„Light Mode ist vollständig" ist Hausregel des Design-Systems.

**(f) Schimmer dosiert.** Kacheln (desynchronisiert), Live-Balken, FAB —
sonst nichts. „Mit Schimmern" wurde als Akzent gelesen, nicht als Dauer-
Glitzer; der Effekt liegt ohnehin in der Ruhephase der Endlos-Schleife
(PmShimmer-Muster: 72 % Wandern, Rest Pause).

## 3. Nicht Bestandteil (bewusst)

- **Keine neue Diagramm-/UI-Bibliothek** — weiterhin reine Compose-
  Bordmittel (Canvas, animation-core); Offline-/Minimal-Dependency-Pfad.
- **Kein Umbau der Sheet-Karten** (Güte-Verlauf, Garmin, Reflexion, Todos,
  Pauschalen): Sie behalten Optik und Verhalten; umgebaut wurde das
  Drumherum (einheitliche Sheet-Fläche) und der Hero darüber.
- **Kein Einstellungs-Schalter für Animationen** („prefers-reduced-motion"):
  im Design-System als Ziel notiert, aber nicht Teil dieses Auftrags.
- **Keine Änderung an Timeline, Review, Statistik** — nur der neue
  Timeline-Einstieg aus dem Hero heraus.
- **Keine Play-Store-Einreichung** in diesem Schritt (AAB liegt bereit).

## 4. Ehrliche Einschränkungen — was NICHT verifiziert werden konnte

- **Kein Durchklicken auf echtem Gerät/Emulator möglich** (kein /dev/kvm auf
  der Build-Maschine). Die UI-Evidenz kommt aus Robolectric mit NATIVE-
  Grafikmodus: echte Compose-Semantikbäume (11 Tests) und **echte Pixel** —
  drei Sichtprüfungs-Screenshots sind im Build-Output erzeugt worden
  (dunkel, hell, „Sheet über Hero", Ablage: `app/build/reports/dashboard-hero/`,
  Kopien auf dem APK-Server als `aevum-1.0.36-*.png`).
- **Die Scroll-Interaktion selbst** (Parallax-Geschwindigkeiten, Sheet-
  Überlappung in Bewegung, Fluss beim Fling) ist nur per Konstruktion und
  statisch geprüft — nicht auf Hardware durchgewischt. Die zugrunde liegende
  Mathematik (`DashboardHeroMetrics.collapseFor`) ist mit 6 JVM-Tests
  abgedeckt; das Zusammenspiel im Frame bleibt eine Sichtprüfung am Gerät.
- **Kein Test der Kachel-Optik in Randfällen** (sehr kleine Displays
  < 360 dp, Schriftgröße 200 %): Die Kacheln/Leisten sind elastisch gebaut,
  aber nicht über alle Konfigurationen gerendert.
- Die zwei anfänglichen Screenshot-Varianten (PixelCopy) schlugen unter
  Robolectric fehl — gelöst über Software-Canvas-View-Draw; der erste
  LIGHT-Screenshot zeigte dabei einen Test-Wrap-Fehler (Surface ohne
  fillMaxSize), behoben. Beides betrifft NUR die Test-Infrastruktur, nicht
  die App.

## 5. Messwerte (echt, aus diesem Lauf)

- **Tests: 1165 total, 0 Fehler, 0 Failures, 1 übersprungen** —
  die übersprungene ist die bekannte, bewusst `@Ignore`-te
  `CyclingVsCarDetectionTest` (dokumentierte Lücke, unverändert, kein
  Bezug zu M19.4). Neu in M19.4: **17 Tests** — 6 ×
  `DashboardHeroMetricsTest` (JVM, Gauge-/Parallax-/Prozent-Arithmetik inkl.
  Klemm-Grenzfälle) + 11 × `DashboardHeroUiTest` (Robolectric: Kacheln mit
  finalen Werten nach Zähler-Animation, Gauge-Zahl + Klick, Timeline-Klick,
  Live-Pausiert-Bereit-Status inkl. Timer, Zonen-Chip mit/ohne Zone,
  3 Screenshot-Tests).
- **Voll-Build** (`testDebugUnitTest assembleDebug assembleRelease
  bundleRelease`): **Exit 0**, Dauer **9m 58s**, Log `/root/builds/aevum-m194.log`,
  Debug-APK-Signatur-Gegenprobe durch den Wrapper: **kanonischer Key bestätigt**
  (SHA-1 `9c3055c4810b5cc2e548d8de8357344352fbe0e8`).
- **Artefakte:**
  - Debug-APK `Aevum-1.0.36-Debug.apk` — **123.505.646 Bytes**, SHA-256
    `c748fd00253962df995e04e0161abcd73ae5fd298d26a40b2f8b8ed28ce79c33`
  - Release-APK — **52.005.475 Bytes**, SHA-256
    `523f5941cb9fba09e94ac0df8817d62db963812ff4ea20671838744a340d2668`,
    signiert mit **Aevum Upload Key** (SHA-1 `c8430d3589bc579db2e8c7c6af9a1619e361ffe9`)
  - AAB — **24.245.967 Bytes**
  - `versionCode 37`, `versionName 1.0.36` (`1.0.36-debug` für die Test-Fassung)
- **Auslieferung:** Debug-APK auf dem Heimnetz-Server:
  `http://192.168.178.40:8098/Aevum-1.0.36-Debug.apk` (index.html aktualisiert);
  Screenshots `aevum-1.0.36-hero-dark.png`, `-hero-light.png`,
  `-sheet-over-hero.png` daneben.
- **Git:** Repo `Renegaded66/Aevum` (öffentlich, keine Secrets) — Commit
  `2b6e31d` („M19.4 … Tag-Cockpit + Sheet ueber Hero", 14 Dateien,
  +1973/−549)

## 6. Von den Tests gefundene Fehler

1. **ComposeTimeout in den Screenshot-Tests** (2 Tests): `captureToImage`
   wartet intern auf einen Redraw über PixelCopy — unter Robolectric gibt es
   weder den Redraw-Pump bei angehaltener Test-Uhr noch PixelCopy. Fix:
   Software-Canvas-View-Draw (Roborazzi-Prinzip) mit fester Test-Uhr.
   Das war ein **Test-Infrastruktur-Fehler**, kein App-Fehler.
2. **Erwartungsfehler im eigenen Test-Erstentwurf:** Der LIGHT-Screenshot
   zeigte unten eine dunkle Fläche — erst als Theme-Bruch fehlinterpretiert,
   dann korrekt als **Test-Wrap-Fehler** identifiziert (Surface ohne
   `fillMaxSize` deckte nur den Hero-Bereich ab). Erwartung im Test korrigiert,
   nicht die App.
3. **Echte Kontrast-Schwächen im hellen Theme** (kein Testfehler, aber durch
   Sichtprüfung gefunden): dunkel-optimierte Grün-/Amber-Töne waren auf Weiß
   zu flach → theme-adaptive Akzente + Gauge-Track-Verstärkung implementiert
   und im zweiten Rendering nachgeprüft.

## 7. Geänderte/neue Dateien (Kern)

- `ui/screens/dashboard/DashboardHero.kt` — Neubau: `DashboardHeroMetrics`
  (pure), `DashboardHeroBackground` (Höhe von außen), `DashboardHeroContent`
  (neues Layout), `HeroGauge`, `HeroMetricTile`, `HeroZoneChip`,
  `HeroStatusBar` (Crossfade-Status), `HeroDayFlowStrip`, Test-Tags.
- `ui/screens/dashboard/DashboardSheet.kt` — **neu**: die abgerundete
  Karten-Fläche (Verlauf, Rim-Light, Glow).
- `ui/screens/dashboard/DashboardScreen.kt` — LazyColumn-Restruktur
  (Hero-Item mit `onSizeChanged`-Messung + Sheet-Item), Parallax-Mathematik
  aus `DashboardHeroMetrics`, ZoneBanner-Item entfernt, Atmosphere-Farben.
- Assets: `values/strings_timeline_dashboard.xml` + `values-de/…` (3 neue
  Strings: Recording bereit, Gauge-/Timeline-Screenreader-Texte).
- Tests: `DashboardHeroMetricsTest.kt` (neu), `DashboardHeroUiTest.kt` (neu).
- Doku: `docs/FEATURES.md`, `docs/DESIGN_SYSTEM.md`, `docs/CHANGELOG.md`,
  dieser Bericht; `.project-memory/{tasks,decisions}.md`.

---

**Fazit:** Das Dashboard hat den Skizzen-Aufbau als „Tag-Cockpit" (Gauge,
versetzte Kacheln, Zonen-Chip, Tagesleiste, Aufnahme-Status) und den
Referenzbild-Scroll (Kartenfläche schiebt sich über den farbigen Hero).
Verifiziert über 1165 grüne Tests, kanonisch signierte Artefakte und drei
Rendering-Screenshots; die Bewegung selbst braucht die Sichtprüfung am Gerät.

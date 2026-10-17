# M19.5 — Abschlussbericht

**Auftrag (Devon, wörtlich, 17.10.2026):** „Ich meinte eigentlich, dass unter
der anzeige der güte nicht das hin kommt, was aktuell da ist (das kann
komplett weg), sondern der kasten dadrunter, der mit der animation die
aktuelle aufzeichnung anzeigt. Fixe das noch. […] Und mit der dynamik bin ich
noch nicht ganz zufrieden. Ich will, wenn ich runter scrolle, dass der
komplette obere bereich stehen bleibt und der untere bereich darüber rutscht.
[…] Und zwar ein maskottchen feature. Das man in den einstellungen ein und
ausschalten kann. default aus. Wenn aus ist soll alles so sein wie aktuell.
Wenn ein, dann kann man sich selber ein maskottchen erstellen, so ein richtig
süßes putziges wesen. Das soll dann im dashboard dort erscheinen, wo aktuell
die zahl der güte ist, und je besser die güte, desto mehr soll das maskottchen
lächeln. Es soll auch ein bisschen interaktiv sein, also hin und her schauen,
blinzeln, atmen. Die farbe des maskottchen und vielleicht ein paar optische
eigenschaften soll man in den einstellungen festlegen können. […] wnen man im
dashboard drauf klickt dann soll es irgendwie süß reagieren. Du kannst übrigens
oben die drei vierecke mit erfasst, schlaf und bildschirm von der höhe ein
wenig reduzieren, das icon kann neben der zeit und abstände einen ganz klein
wenig geringer, und dafür die skala etwas größer, sodass auch ein maskottchen
drunter passt (aber die anderen änderungen auch im nicht-maskottchen modus).
Zwischen 22 und 6 Uhr soll das maskottchen schlafen, außer es wird etwas
aufgezeichnet. Das soll auch wechseln […] Und in dem feld mit der animation mit
den blubber blasen […] da müssen die emojis für pause, wechsel und stop
angepasst werden, irgendwie sind die alle nicht 100% mittig."

**Version:** App 1.0.37 (versionCode 38) · **Build-Tag:** `aevum-m195b` ·
**DB:** v46 unverändert · **Repo:** Renegaded66/Aevum

---

## 1. Was gebaut wurde

### 1.1 Scroll-Dynamik: der obere Bereich steht, das Sheet rutscht darüber

Der komplette Hero (Tag-Navigation, Kacheln, Güte-Gauge, Aufnahme-Bereich,
Tagesleiste) ist beim Scrollen jetzt **gepinnt**: Die LazyColumn verschiebt
Item 0 um −offset nach oben, ein `graphicsLayer` kompensiert exakt mit
+offset — netto bleibt die Bildschirmposition 0 über die GESAMTE
Scroll-Strecke. Nur das Sheet-Item wandert unbeeinflusst darüber. Der
M19.4-Collapse (Fade/Scale/Alpha/Parallax) wurde komplett entfernt.

- Reine Arithmetik: `DashboardHeroMetrics.heroStickyTranslation` (JVM-getestet).
- Die Kompensation läuft in der **draw-Phase** (das graphicsLayer-Lambda liest
  den Scroll-State) → keine Rekompositions-Kosten pro Frame.
- Der Hero verlässt den Viewport erst, wenn das Sheet ihn vollständig
  verdeckt (Culling bei offset ≥ Item-Höhe).
- **Beweis:** Echter Integrationstest mit einer LazyColumn in exakt der
  App-Verdrahtung — `scrollBy(400dp)` → Hero-Position konstant (±4 px),
  Sheet sichtbar nach oben gewandert (`heroStaysPinnedWhileSheetSlidesOver`).

### 1.2 Aufnahme-Banner direkt unter der Güte

Der Blubber-Banner (Bläschen-Animation, Timer, Pause/Wechsel/Stop) wohnt
jetzt IM Hero, direkt unter dem Güte-Gauge — exakt Devons Beschreibung. Die
alte Headline-Zeile darunter ist ersatzlos entfernt („das kann komplett
weg"); der frühere Hero-Status-Balken am unteren Hero-Ende wurde ebenfalls
entfernt (er wäre jetzt eine Dopplung). Ohne laufende Aufzeichnung zeigt der
Bereich die ruhige Bereitschafts-Zeile mit Tagesfortschritt. Der Wechsel ist
weich animiert (expand/shrink vertikal + Fade). Im Sheet erscheint nichts
doppelt.

### 1.3 Emoji-Zentrierung (Pause/Wechsel/Stop)

Die Glyphen ⏸/▶ (Pause), ⇄ (Wechsel), ■ (Stop) saßen sichtbar außerhalb der
Mitte — Ursache: Default-ContentPadding des Buttons + asymmetrische
Font-Leading der Glyphen. Fix: `contentPadding = PaddingValues(0.dp)` +
`fillMaxSize`-Zentrierbox + `lineHeight = fontSize` +
`includeFontPadding = false` (bewusst KEIN Nudge-je-Glyphe — das wäre
font-abhängig). Sichtprüfung am Screenshot: alle drei Glyphen **perfekt
zentriert**.

### 1.4 Maskottchen (Default AUS)

Ein selbst erstellbares, interaktives Canvas-Wesen:

- **Ort:** im Güte-Gauge genau dort, wo sonst die große Güte-Zahl steht; die
  kleine Zahl-Zeile (Güte + QUALITY) bleibt darunter als Datenanker und
  Tap-Fläche für den Güte-Dialog.
- **Lächeln wächst mit der Güte** (`MascotBehavior.smileLevel`); ab Güte 85
  funkeln Extra-Glanzpunkte in den Augen.
- **Lebendig:** Atmen, Blinzeln (alle ~4,3 s), langsames Umherschauen.
- **Tippen → drei zyklische Reaktionen:** Hüpf-Herzchen ♥, Wackel-Funkeln ✦,
  Bäckchen-Pop (glühende Wangen + Freuden-Augen + Mini-Pop).
- **Schlaf 22–6 Uhr** (geschlossene Augen, aufsteigende „z z z"), **außer es
  läuft eine Aufzeichnung** — der Zustand wechselt LIVE mit Start/Stop
  (nur beim Anzeigen von HEUTE; beim Blättern in alte Tage gibt es kein
  „Jetzt").
- **Erstellen in den Einstellungen:** 8 Körperfarben (Eisblau, Minze,
  Apfelgrün, Sonnengelb, Pfirsich, Koralle, Rosé, Lavendel), 3 Ohrenformen
  (rund/spitz/lang), Bäckchen an/aus + interaktive Live-Vorschau.
- **Default aus:** Ist das Maskottchen aus, ist alles exakt wie vorher
  (klassische große Zahl, kein Wesen).

### 1.5 Layout-Feinschliff (gilt in BEIDEN Modi)

- Kacheln Erfasst/Schlaf/Bildschirm deutlich flacher: Icon (13 sp) jetzt
  NEBEN Wert+Label statt darüber, engere Abstände, weniger Innenhöhe.
- Güte-Gauge größer: 248×148 statt 216×126 — das Wesen passt sauber in den
  Halbkreis.
- Deko-Verlauf-Tail von 0.92 auf 0.965 gezogen, damit der Farbbereich im
  Sheet-Überstand (44 dp) weiterhin sichtbar durchschimmert (der Hero ist
  höher geworden, das Band lag sonst im Ausblend-Bereich).

---

## 2. Eigene Design-Entscheidungen — hinterfragt

1. **Pinning per graphicsLayer-Kompensation statt `stickyHeader`:**
   `stickyHeader` pinnt nur den Header selbst und hätte die
   Karten-ÜBER-Inhalt-Optik nicht geliefert; die Kompensation ist beweisbar
   exakt (±4 px über die ganze Strecke) und läuft ohne Rekomposition.
2. **Banner im Hero, nicht doppelt:** Genau EINE Steuerfläche; die Headline
   „kann komplett weg" (wörtlich) → entfernt; der alte Status-Balken wäre
   Dopplung → entfernt.
3. **Zahl-Anker unter dem Maskottchen:** Devon: „erscheinen, wo aktuell die
   zahl der güte ist". Das Wesen nimmt den Platz der großen Zahl ein; die
   kleine Zahl-Zeile bleibt als Datenanker (Güte bleibt ablesbar; Fläche
   bleibt für den Güte-Dialog tappbar). Falls unerwünscht: eine Textzeile.
4. **Schlafregel deterministisch:** `MascotBehavior.shouldSleep(hour,
   isRecording)` aus `state.currentMinute` abgeleitet (nicht
   `LocalTime.now`) → testbar mit fixer Uhr; Gating „nur Heute" verhindert
   schlafende Wesen beim Nachmittags-Blick in alte Tage.
5. **Kachel-Interpretation „das icon kann neben der zeit":** Icon links,
   Wert+Label rechts daneben — maximale Höhenersparnis, exakt die Worte.
6. **Emoji-Fix typografisch statt magischer Zahlen:** contentPadding 0 +
   includeFontPadding=false wirkt auf jeder Font; Pixel-Nudges hätten auf
   anderen Geräten still gebrochen.
7. **Keine neuen Dependencies** (alles Canvas/Compose-Bordmittel) — passt
   zum Offline-Minimal-Pfad.

## 3. Nicht Bestandteil (bewusst)

- Kein Emulator-Live-Test (kein /dev/kvm auf dieser Maschine) — dafür jetzt
  ein **dynamischer Integrationstest** der Scroll-Mechanik (siehe 1.1).
- Keine Änderungen an Aufnahme-Logik, ViewModel-Datenpfaden, DB, Navigation.
- Kein Play-Store-Upload (kommt wie üblich separat).
- Maskottchen: keine Sounds, keine Level/XP (bewusst kein Gamification).
- Keine Änderung bestehender Tests anderer Bereiche (nur die betroffenen
  Dashboard-Suiten wurden angepasst).

## 4. Ehrliche Einschränkungen — was NICHT verifiziert werden konnte

- Die Scroll-Bewegung ist per Integrationstest (echte LazyColumn, echte
  Kompensation, `scrollBy`) und Screenshots belegt — aber NICHT auf echter
  Hardware durchgewischt (kein Emulator/Gerät). Frame-Timing auf dem echten
  Handy nicht gemessen; erwartet wird minimale Last (draw-Phase).
- Emoji-Zentrierung „perfekt" = Sichturteil am Robolectric-Screenshot, keine
  Pixel-Vermessung.
- Maskottchen-Ästhetik ist subjektiv; drei Stil-Screenshots liegen bei.
- Die Schlaf-Regel „nur Heute" ist eine bewusste Interpretation (siehe 2.4).

## 5. Messwerte (echt, aus diesem Lauf)

- **Tests gesamt: 1187, 0 Fehler** (1 übersprungen — vorbestehend; +22 neue
  gegenüber M19.4). Davon neu/angepasst: 6 `DashboardHeroMetricsTest`
  (3 Sticky-Tests, JVM), 19 `DashboardHeroUiTest` (inkl. Pinning-
  Integrationstest + 6 Screenshot-Tests), 7 `MascotViewTest` (inkl. 4
  Screenshots), 7 `MascotBehaviorTest` (pure JVM-Regeln).
- **Voll-Build** `testDebugUnitTest assembleDebug assembleRelease bundleRelease`:
  **Exit 0 in 9m 12s** (Log `/root/builds/aevum-m195b.log`, Exit-File
  `…m195b.exit`), Debug-APK-Signatur-Nachweis „kanonischer Key" (SHA-1
  9C:30:55:C4… via apksigner bestätigt).
- **Artefakte:** Debug-APK 123.626.258 B (~123,6 MB), Release-APK
  52.027.783 B (Upload-Key), AAB 24.283.749 B.
- **Screenshots (10, Robolectric-View-Draw):** dashboard-hero-dark/-light/
  -sheet-dark, **dashboard-hero-mascot-dark** (Key-Ansicht),
  -mascot-sleeping-dark, mascot-awake, mascot-sleeping,
  mascot-reaction-hearts, mascot-styles, banner-buttons — als
  `aevum-1.0.37-*.png` auf dem APK-Server (HTTP 200 verifiziert).
- **Sichtprüfungen (Vision, Auszug):** Maskottchen „runder Bärenkopf mit
  großen Kulleraugen, rosa Wangen, freundlichem Lächeln — sitzt sauber im
  Gauge ohne Bogen-Überlappung"; Schlafzustand „friedlich, z z z, keine
  Zeichenfehler"; Banner-Glyphen „perfekt zentriert (alle drei)"; Sheet-Kante
  „dezent blau/gold/rot durchschimmernd"; Kacheln mit Icon neben dem Wert
  „flacher, nichts gequetscht".
- **Commit:** `77aacdd` auf `main` (Renegaded66/Aevum; 22 Dateien,
  +2723/−641).

## 6. Von Tests & Sichtprüfung gefundene Fehler (und Fixes)

1. Eigener Testfehler im Sticky-Metrics-Test (Vorzeichen: netto = translation
   − offset) → Test korrigiert, Implementierung war korrekt.
2. Sheet-Screenshot zeigte keinen Deko-Überstand: (a) Statik nutzte die zu
   klein gewordene Fallback-Höhe → auf GEMESSENE Hero-Höhe umgestellt;
   (b) Verlauf-Tail lag im Ausblend-Bereich → 0.92 → 0.965.
3. Herzchen-Reaktion: drittes Herz wirkte (durch Alpha-Staffelung über
   dunklem Grund) fast schwarz → Staffelung 0.12 → 0.06, Sichtprüfung „alle
   drei klar rosa".
4. Kacheln nach erstem Umbau noch unklar kompakt → Icon neben Wert (Devon-
   Wörtlich), Sichtprüfung „flacher, nichts gequetscht".

## 7. Geänderte/neue Dateien (Kern)

**Neu:** `MascotRepository.kt`, `domain/mascot/MascotBehavior.kt`,
`ui/screens/dashboard/MascotView.kt`, `ui/screens/dashboard/LiveActivityBanner.kt`
(aus DashboardScreen extrahiert + Emoji-Fix), `ui/screens/settings/MascotSettingsCard.kt`,
`res/values{,-de}/strings_mascot.xml`, Tests: `MascotBehaviorTest.kt`,
`MascotViewTest.kt`, `docs/M19-5-ABSCHLUSSBERICHT.md` (diese Datei).

**Geändert:** `DashboardScreen.kt` (Pinning-Verdrahtung, Banner raus aus dem
Sheet, Maskottchen-Flow), `DashboardHero.kt` (Gauge größer + Maskottchen-Modus,
Kacheln kompakt, Banner-Slot, HeroStatusBar entfernt, Tail 0.965),
`DashboardViewModel.kt` + `SettingsScreen.kt` (MascotRepository-Anbindung +
Einstellungen-Item), `DashboardHeroMetricsTest.kt`, `DashboardHeroUiTest.kt`,
`app/build.gradle.kts` (1.0.37/vc 38), `docs/CHANGELOG.md`, `docs/FEATURES.md`,
`docs/DESIGN_SYSTEM.md`, `.project-memory/{tasks,decisions}.md`.

# M19.6 — Abschlussbericht

**Auftrag (Devon, wörtlich, 17.10.2026):** „Die sehen mir nicht gut genug
aus. Halte dich vielleicht an das maskottchen aus PAG. Das fand ich gut.
Das einfach leicht personalisierbar. und ohne herzen bitte, vielleicht mit
sternchen oder so. Im dashboard kann das auch ein stück größer sein."

**Version:** App 1.0.38 (versionCode 39) · **Build-Tag:** `aevum-m196` ·
**DB:** v46 unverändert · **Repo:** Renegaded66/Aevum

---

## 1. Was gebaut wurde

### 1.1 PAG-Nachbau (das Kernstück)

Das M19.5-Blob-Wesen wurde komplett durch den Stil des **PAG-Büro-
Maskottchens** ersetzt (`personal-ai-gateway/android/mascot`, Compose-Code
+ Render-Screenshots als Vorlage gelesen). Anatomie 1:1 übernommen:

- **EIN runder Körper** (Radius 0.31 × Breite) mit weichem Vertikal-
  Verlauf, **bewusst ohne Umriss**, Bauchlicht-Oval.
- **Antenne** (quadratische Bézier), die sich in Blickrichtung neigt —
  mit leuchtender **Gold-Spitze** (#FFD166, Glow).
- **Große weiße Augen** (#F4F8FF) mit dunklen Pupillen (#232B3A), die der
  Blick-Wanderung folgen; Blinzeln = Lid-Squash; geschlossene Augen sind
  ruhige Linien (PAG-Stil); ab Güte 85 Extra-Funkeln in der Pupille.
- **Kleine Smile-Kurve**, deren Breite/Tiefe mit der Güte wächst; im
  Schlaf ein Mund-Punkt.
- **Rosa Bäckchen** (#FF8FA3), weicher Bodenschatten, sehr subtiles
  Atmen (PAG-Tempo: ~10,7 s wach / ~16,3 s schlafend statt der früheren
  2,6 s).

### 1.2 Leichte Personalisierung („einfach leicht personalisierbar")

- **Farbe:** 8 Töne; Vorgabe ist das **PAG-Blau #5E7CE8** (Devons
  Vorbild-Look auf einen Blick), dazu Eisblau, Minze, Sonnengelb,
  Pfirsich, Koralle, Rosé, Lavendel.
- **Antennen-Spitze:** **Kugel** (PAG-Original) / **Stern** (Sternchen —
  knüpft an Devons Wunsch an) / **Blitz**. Ersetzt die M19.5-Ohren.
- **Bäckchen:** an/aus. Dazu die interaktive Live-Vorschau in den
  Einstellungen (Tippen → Reaktion).

### 1.3 Ohne Herzen — Sternchen statt Herzchen

Alle drei Tipp-Reaktionen sind herzfrei:
**Sternchen-Hüpfer** (★ gold, Hüpfer + aufsteigende Sterne),
**Wackel+Funkeln** (✦ gold) und **Bäckchen-Pop** (Freuden-Linien-Augen +
Wangen-Glühen + Mini-Pop).

### 1.4 Größer im Dashboard

108 statt 92 dp im Güte-Gauge (Antenne bleibt vollständig im Halbkreis,
Sichtprüfung: „nicht am Bogen klebend, nichts abgeschnitten"); die
Einstellungen-Vorschau wuchs auf 120 dp.

### 1.5 Alt-Werte-Migration

Gespeicherte „Ohren"-Werte aus 1.0.37 (round/pointy/long) fallen über die
Validierung automatisch auf die Kugel zurück — kein Crash, kein Reset
anderer Einstellungen. Keine DB-Änderungen.

---

## 2. Eigene Entscheidungen — hinterfragt

1. **Code statt Pixel kopieren:** Die Proportionen kamen aus dem geprüften
   PAG-Quellcode (0.31 R Körper, 0.30 R Augen, 46 % Pupille, 0.42 R
   Augen-Abstand, ±0.20 R Mund), nicht aus Bildmessungen — reproduzierbar
   und exakt. Nur der Antennen-Freiraum wurde fürs Gauge angepasst
   (Körperzentrum bei 54,5 % statt 46 % der Box, damit die Antenne Platz hat).
2. **Antenne statt Ohren als Personalisierung:** Das Vorbild hat keine
   Ohren; die Spitze (Kugel/Stern/Blitz) ist das natürliche Pendant — und
   ein Sternchen war explizit Devons Idee.
3. **Screenshot-Freeze auf 3,65 s:** Bei diesem Zeitpunkt steht die
   Blick-Wanderung exakt in Mittelstellung (Antenne gerade, Pupillen
   symmetrisch). Die erste Sichtprüfung stufte die eingefrorene Antenne
   (mitten im Schwung, bei 1,2 s) als „verrutscht" ein — reines
   Test-Timing, kein App-Verhalten; im Betrieb pendelt der Blick ja bewusst.
4. **Blitz-Nachschärfung:** Erste Fassung war zu dünn/klein („weniger
   deutlich erkennbar"); Punkte breiter + 22 % größer → Sichtprüfung:
   „klar als Blitz erkennbar, sauber".

## 3. Nicht Bestandteil (bewusst)

- Kein Emulator-Live-Test (kein /dev/kvm) — Screenshots = Robolectric-
  View-Draw der echten Komponenten; Scroll-Pinning bleibt durch den
  M19.5-Integrationstest abgedeckt.
- Keine Änderungen an Dashboard-/Sheet-/Banner-Layout (nur die
  Maskottchen-Größe im Gauge).
- Keine neuen Personalisierungs-Achsen (bewusst klein gehalten).
- Kein Play-Store-Upload.

## 4. Ehrliche Einschränkungen

- Ästhetik ist subjektiv: Vier Sichtprüfungen (wach, schlafend, Stile,
  Sterne-Reaktion) fielen positiv aus; das endgültige Urteil liegt bei Devon.
- Der Blitz ist bei sehr kleiner Darstellung (< 96 dp) das am wenigsten
  scharfe Detail — im Gauge (108 dp) laut Sichtprüfung klar erkennbar.
- Die PAG-Vorlage ist ein ANDERES Produkt; übernommen wurde der Stil
  (Anatomie/Palette), nicht Code — keine geteilten Module.

## 5. Messwerte (echt, aus diesem Lauf)

- **Tests:** 1187/1187 grün (0 Fehler; 1 übersprungen — vorbestehend).
  Gezielt nach jeder Änderung grün: MascotViewTest (7, inkl. 4
  Screenshots), MascotBehaviorTest (7), DashboardHero-/Metrics-Suiten.
- **Voll-Build** `testDebugUnitTest assembleDebug assembleRelease
  bundleRelease` (Tag `aevum-m196`): **Exit 0 in 8m 41s** (Log
  `/root/builds/aevum-m196.log`), Debug-APK-Signatur kanonisch bestätigt
  (apksigner: SHA-1 9C:30:55:C4…).
- **Artefakte:** Debug-APK **123.626.270 B** (~123,6 MB), Release-APK
  52.027.795 B (Upload-Key), AAB 24.284.037 B — Auslieferung unter
  `http://192.168.178.40:8098/Aevum-1.0.38-Debug.apk` (HTTP 200
  verifiziert).
- **Screenshots (neu):** mascot-awake, mascot-sleeping,
  mascot-reaction-stars (neu statt -hearts), mascot-styles — plus die
  aktualisierten Hero-Ansichten; als `aevum-1.0.38-*.png` auf dem
  APK-Server.
- **Sichtprüfungen (Vision):** wach „süß und professionell, ausbalanciert";
  schlafend „friedlich, ruhige Linien-Augen, z z z, keine Zeichenfehler";
  Stile „gleich groß, Stern am schönsten, Blitz klar erkennbar, keine
  kaputt"; Sterne-Reaktion „drei goldene Sterne, keine Herzen"; Hero
  „deutlich präsenter, Antenne vollständig, nichts klebt am Bogen".
- **Commit:** `2cd96e6` (18 Dateien, +670/−324) direkt auf `main` gepusht.

## 6. Von Tests & Sichtprüfung gefundene Punkte (und Fixes)

1. Drei Compile-Fehler nach dem Umbau (toPx ohne Density-Receiver im
   zzz-Block; vergessene Umbenennung `onSelectEarStyle` in der Karte) →
   sofort gefixt, danach grün.
2. Blitz zu klein/undeutlich → Pfad breiter + größer (Sichtprüfung ok).
3. Styles-Screenshot: dritte Figur lief rechts aus dem 400dp-Testfenster
   (3×140dp + Abstände) → auf 112 dp / 10 dp Abstand gesetzt („alle drei
   gleich groß, vollständig im Bild").
4. Antenne wirkte auf dem eingefrorenen Screenshot „verrutscht" → Freeze
   auf 3,65 s (Mittelstellung) für alle Maskottchen-/Hero-Screenshots.

## 7. Geänderte/neue Dateien (Kern)

**Geändert (main):** `MascotRepository.kt` (Antennen-Spitzen statt Ohren,
PAG-Blau als Vorgabe, Validierung), `MascotBehavior.kt` (STAR_HOP statt
BOUNCE_HEARTS), `MascotView.kt` (KOMPLETT neu im PAG-Stil),
`MascotSettingsCard.kt` (Antennen-Sektion, Vorschau 120 dp),
`SettingsScreen.kt` (ViewModel-Rename), `DashboardHero.kt` (Maskottchen
108 dp), `values{,-de}/strings_mascot.xml`, `app/build.gradle.kts`
(1.0.38/vc 39).
**Geändert (test):** `MascotViewTest.kt` (Antennen-Stile, Sternen-Streifen,
Freeze 3,65 s), `MascotBehaviorTest.kt`, `DashboardHeroUiTest.kt`.
**Doku:** `docs/CHANGELOG.md`, `docs/FEATURES.md`, `docs/DESIGN_SYSTEM.md`,
`.project-memory/{tasks,decisions}.md`, dieser Bericht.

# M18.136 — Play-Console-Auflagen behoben

**Datum:** 22.09.2026
**Version:** 1.0.19 (versionCode 20)
**Auslöser:** Zwei Meldungen in der Play Console (Release-Kanal)

---

## Zusammenfassung

| Auflage | Ursache | Lösung | Ergebnis |
|---|---|---|---|
| **1 Problem — „Die DEX-Codeoptimierung liegt unter unserem Grenzwert"** (Verschleierung 0 %) | `isMinifyEnabled = false` im Release-BuildType — R8 war komplett aus | R8 aktiviert + aus dem Code abgeleitete Keep-Regeln | Verschleierung **78,7 %**, Optimierung **77,6 %**, Shrinking **78,1 %** |
| **1 Aktion — „Die randlose Anzeige funktioniert möglicherweise nicht für alle Nutzer"** | `enableEdgeToEdge()` fehlte; abwärtskompatibles Verhalten war damit undefiniert | `enableEdgeToEdge()` zentral in `LocalizedActivity` + vollständiges Inset-Handling | Randlos auf allen API-Leveln, keine Doppel-Paddings, IME-Schutz |

**DEX-Größe:** 52,0 MB → **5,9 MB** (−89 %)
**Tests:** 853 Unit-Tests, 0 Fehler
**AAB:** 23,4 MB (vorher 30,5 MB)

---

## Auflage 1 — DEX-Codeoptimierung / Verschleierung 0 %

### Ursachenanalyse

Play meldete für **alle drei Kategorien** Werte unter dem Grenzwert
(Verschleierung explizit **0 %**). Ursache war eine einzige Zeile:

```kotlin
// app/build.gradle.kts, buildTypes.release
isMinifyEnabled = false
```

Damit war R8 vollständig deaktiviert:
- **keine Verschleierung** (alle Klassennamen im Klartext lesbar)
- **kein Shrinking** (ungenutzter Code bleibt im DEX)
- **keine Optimierung** (kein Inlining, kein Repackaging)

Belegt durch `r8.json` fehlte im alten AAB komplett, und im DEX lagen
34,6 MB Klartext-Code in `base/dex/classes.dex` (plus 4 weitere DEX-Dateien).

### Lösung: R8 aktiviert, Keep-Regeln aus einem Code-Audit

```kotlin
isMinifyEnabled = true
isShrinkResources = true
proguardFiles(
    getDefaultProguardFile("proguard-android-optimize.txt"),
    "proguard-rules.pro"
)
```

Die `proguard-rules.pro` war vorher ein **leerer Platzhalter** („will be
tightened before a production release"). Sie wurde auf Basis eines
Reflection-Audits neu geschrieben — **kein pauschaler Keep** auf das
App-Package, sondern gezielt dort, wo R8 nicht sehen kann:

| Abschnitt | Warum nötig | Belegstelle im Code |
|---|---|---|
| Manifest-Komponenten | System instanziiert per Klassennamen | 15 Activities/Services/Receiver im Manifest |
| WorkManager-Worker | WorkManager speichert Klassennamen als String in eigener DB; Consumer-Rules decken nur `Worker`/`ListenableWorker` ab — Aevum-Worker erben von **`CoroutineWorker`** | 25 Worker-Klassen |
| Room | generierte `AppDatabase_Impl`, Entity-Mapping | `AppDatabase`, 38 Entities |
| Hilt | generierte EntryPoints per Name geladen | `EntryPointAccessors` in ~25 Klassen |
| Enum `valueOf` | Namen werden persistiert und zurückgelesen | `QuickPlaceKind.valueOf(...)`, `GeofenceTransition.valueOf(...)` |
| MapLibre | JNI-Callbacks aus `libmaplibre.so` | Geofence-Karte |
| Health Connect | Proto-Reflection der Bibliothek | `connect-client` |
| `native <methods>` | Bindung über Methodennamen | MapLibre |

### Verifikation (nicht behauptet, gemessen)

**Aus `BUNDLE-METADATA/com.android.tools/r8.json` im AAB** — das ist genau
die Quelle, aus der die Play Console ihre Werte liest:

```json
"isObfuscationEnabled": true,
"isOptimizationsEnabled": true,
"isShrinkingEnabled": true,
"isRepackageClassesEnabled": true,
"stats": {
  "noObfuscationPercentage": 21.35,
  "noOptimizationPercentage": 22.38,
  "noShrinkingPercentage": 21.93
}
```

→ Verschleierung = 100 − 21,35 = **78,65 %** (Grenzwert 25 %)

**Aus `mapping.txt`:**

| Ebene | Klassen | verschleiert |
|---|---|---|
| App-Package `com.d_drostes_apps.aevum.**` | 1345 | **92,7 %** |
| Gesamt (inkl. Bibliotheken) | 7148 | **85,5 %** |

**Reflection-Klassen im finalen DEX nachgewiesen** (Bytesuche in
`base/dex/classes.dex`): alle 25 Worker, `QuickPlaceKind`,
`GeofenceTransition` sowie alle 15 Manifest-Komponenten vorhanden — keine fehlt.

---

## Auflage 2 — Randlose Anzeige

### Ursachenanalyse

Aevum hat `targetSdk 36`. Ab Android 15 (API 35) **erzwingt** das System
randlose Anzeige, sobald die App auf SDK 35+ zielt: das Fenster zeichnet
hinter Status- und Navigationsleiste. Aevum hatte jedoch **keinen einzigen**
`enableEdgeToEdge()`-Aufruf und kein `WindowCompat`-Handling.

Konkret bestanden drei Probleme:

1. **Auf API < 35 fehlte die Abwärtskompatibilität.** Die Themes setzen
   `android:statusBarColor`/`navigationBarColor` auf den Theme-Hintergrund.
   Auf Android 14 und älter lief die App damit als undurchsichtige
   Balken-Variante — genau der Zustand, den die Play-Meldung beschreibt.

2. **Doppeltes Inset-Padding auf API 35+.** `Modifier.padding(PaddingValues)`
   konsumiert **keine** Window-Insets (belegt im Compose-Quelltext:
   `PaddingValuesModifier` ist ein reiner `LayoutModifierNode`).
   Zwölf Screens setzen zusätzlich `statusBarsPadding()` — auf API 35+ ergab
   das Scaffold-Padding **plus** Statusbar-Padding, also doppelten Abstand.
   Auf API < 35 war es unsichtbar, weil das Fenster durch
   `setDecorFitsSystemWindows(true)` schon verkleinert war (Insets = 0).

3. **Tastatur verdeckte Eingabefelder.** `enableEdgeToEdge()` ruft intern
   `WindowCompat.setDecorFitsSystemWindows(window, false)`. Damit verschiebt
   das Framework bei `android:windowSoftInputMode="adjustResize"` die Inhalte
   **nicht mehr** selbst (Android-15-Verhalten, das jetzt auf allen
   API-Leveln gilt). Aevum hat **12 Screens mit Eingabefeldern** — ohne
   `imePadding()` lägen fokussierte Textfelder hinter der Tastatur.

### Lösung

**Zentral in `LocalizedActivity`** (Basisklasse aller drei Activities
`MainActivity`, `SwitchActivity`, `BlockActivity`) — vor `super.onCreate()`:

```kotlin
enableEdgeToEdge(
    statusBarStyle = aevumStatusBarStyle(aevumIsDark),
    navigationBarStyle = aevumSystemBarStyle(aevumIsDark)
)
```

**Warum nicht das parameterlose `enableEdgeToEdge()`:**
Dessen `SystemBarStyle.auto()` leitet die Icon-Farbe aus dem **System**-Theme
(`resources.configuration.uiMode`) ab. Aevum hat aber ein **eigenes** Theme
(`ThemeRepository`, Default = Dark), das vom System unabhängig ist.
Bei System-Hell + App-Dunkel wären die System-Icons dunkel auf dunklem Grund
→ unsichtbar.

**Warum nicht `SystemBarStyle.dark()`/`.light()`:**
Diese setzen `nightMode = MODE_NIGHT_YES/NO`. Die Scrim-Berechnung
(`getScrimWithEnforcedContrast`) liefert dann auf API 29+ einen **sichtbaren
Scrim** statt Transparenz — die Randlosigkeit wäre wieder aufgehoben.

**Verwendete Lösung:** `SystemBarStyle.auto()` mit eigenen Scrims **und**
eigener `detectDarkMode`-Funktion. Damit bleibt `nightMode` auf
`MODE_NIGHT_AUTO` → transparente Leisten auf API 29+ (System sichert den
Kontrast selbst), und die Icon-Farbe folgt Aevums Theme.

**In `MainActivity`** (Inset-Verteilung für alle 34 Screens):

```kotlin
AppNavHost(
    navController = navController,
    modifier = Modifier
        .padding(innerPadding)            // Scaffold-Inset anwenden
        .consumeWindowInsets(innerPadding) // verbrauchte Insets melden (Fix 2)
        .imePadding()                     // Tastatur (Fix 3)
)
```

`consumeWindowInsets(innerPadding)` markiert den Bereich als verbraucht;
die `statusBarsPadding()`-Aufrufe in den Screens ergeben danach 0 —
genau ein Abstand statt zwei. `imePadding()` am NavHost-Wurzelmodifier
stellt `adjustResize` zentral für alle Screens wieder her (eine Stelle
statt 12 Screens zu ändern).

**In den beiden Popup-Activities** (eigene Fenster, erben `enableEdgeToEdge`
von der Basisklasse): `windowInsetsPadding(WindowInsets.safeDrawing)` —
`BlockActivity` (drei Buttons könnten sonst unter der Navigationsleiste
liegen) und `SwitchActivity` (Dialog mit fester Sheet-Höhe).

---

## Geänderte Dateien

| Datei | Änderung |
|---|---|
| `app/build.gradle.kts` | `isMinifyEnabled`/`isShrinkResources` auf `true`, versionCode 20 / 1.0.19 |
| `app/proguard-rules.pro` | leerer Platzhalter → 13 Abschnitte, aus Reflection-Audit abgeleitet |
| `LocalizedActivity.kt` | `enableEdgeToEdge()` + Theme-gekoppelte `SystemBarStyle` |
| `MainActivity.kt` | `consumeWindowInsets(innerPadding)` + `imePadding()` |
| `domain/digital/BlockActivity.kt` | `windowInsetsPadding(safeDrawing)` |
| `ui/screens/dashboard/SwitchActivity.kt` | `windowInsetsPadding(safeDrawing)` |

**Nicht geändert:** `gradle.properties` (`android.r8.strictFullModeForKeepRules=false`
und `optimizedResourceShrinking=false` bleiben bewusst wie sie sind — die
relaxierte Keep-Semantik ist konservativer und senkt das Risiko für die
25 Background-Worker).

---

## Offene Punkte / Testbedarf auf dem Gerät

Die Verifikation oben ist statisch (DEX, Metriken, Tests). Was **nicht**
automatisch prüfbar war (kein Gerät/Emulator verfügbar — kein `/dev/kvm`
auf dieser VM), muss Devon am Handy sehen:

1. **R8-Laufzeit:** Erscheint die App normal? Starten Auto-Tracking,
   Geofences und die Karte (MapLibre/JNI)? Das sind die R8-Risikostellen.
2. **Randlos:** Kein doppelter Abstand oben auf Android 15+? Statusleiste
   transparent, Icons sichtbar (auch bei System-Hell + App-Dunkel)?
3. **Tastatur:** In Timeline-Editor, Geofence- und Trigger-Settings bleibt
   das Feld über der Tastatur sichtbar?
4. **Popup-Activities:** Block- und Wechsel-Popup — Buttons vollständig
   sichtbar und klickbar?

---

## Rollback

Änderungen sind ein einzelner Commit. Für einen Rückbau genügt:

```bash
git revert <commit>
```

R8 ist der riskantere Teil. Fällt beim Gerätetest ein R8-Problem auf
(NoSuchMethodError, ClassNotFoundException), ist die schnellste
Diagnose: `app/build/outputs/mapping/release/mapping.txt` retracen
(`/opt/android-sdk/cmdline-tools/latest/bin/retrace`).

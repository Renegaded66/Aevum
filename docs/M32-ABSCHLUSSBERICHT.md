# M32 — Balance-Radar mit wählbaren Achsen

**Status:** Abgeschlossen
**App-Version:** 1.0.52 (versionCode 53)
**Datum:** 2026-10-25
**Commit:** _(wird nach dem Push ergänzt)_

## Auftrag

> „Beim balance Diagramm will ich, dass der nutzer selber 5 aktivitäten oder
> 5 kategorien auswählen kann auf die die grafik beruht. Default sollten
> entweder die 5 häufigsten activitys oder 5 häufigste kategorien angezeigt
> werden. Die ausgewählten werte sollten auch gespeichert werden, damit man
> nicht jedes mal aufs neue seine beliebigen kategorien oder activitys eingeben
> muss.“

## Umsetzung

### 1. Modus: Aktivitäten oder Kategorien

Die Balance-Karte hat unter dem Kopf zwei `FilterChip`s:

- **Aktivitäten** — Achsen sind ActivityTypes (z. B. „Deep Work", „Fitness"),
- **Kategorien** — Achsen sind Kategorien (z. B. „Arbeit", „Sport").

Der Wechsel wirkt sofort auf das Radar. Jeder Modus hat seine EIGENE
gespeicherte Auswahl; ein Wechsel überschreibt die andere Liste nicht. Beim
Zurückwechseln erscheint die vorherige Auswahl wieder.

### 2. Auswahl-Sheet

„Auswählen ›" öffnet ein `ModalBottomSheet` mit allen Einträgen des aktiven
Modus:

- **Alle** Aktivitäten/Kategorien, auch solche ohne Zeit im gezeigten
  Zeitraum. So lassen sich Achsen unabhängig von der aktuellen Woche
  festlegen.
- Sortierung nach Dauer absteigend (die häufigsten zuerst), danach Label.
- Pro Zeile: Icon im farbigen Kreis, Name, Dauer und Anteil am Zeitraum.
- Es sind **genau fünf** Auswahlen möglich, wenn mindestens fünf Einträge
  existieren; bei weniger Einträgen sind alle ausgewählt. Bei fünf
  ausgewählten Einträgen sind weitere Zeilen deaktiviert; ein ausgewählter
  Eintrag muss zuerst abgetippt werden (der Hinweis erklärt das).
- **Top 5** löscht die gespeicherte Auswahl und stellt den Default wieder her.
- **Übernehmen** speichert die Auswahl und schließt das Sheet.

### 3. Default

Ohne gespeicherte Auswahl zeigt das Radar die **fünf häufigsten Einträge mit
Zeit** (Dauer absteigend). Bewusst nur Einträge MIT Zeit: Ein frischer Nutzer
mit drei genutzten Aktivitäten soll drei sinnvolle Achsen sehen, nicht drei
Achsen plus zwei leere Katalog-Achsen. Gibt es gar keine Zeit, bleibt es bei
den ersten fünf Optionen (die Karte zeigt dann den „keine Zeit"-Hinweis).

### 4. Persistenz

SharedPreferences-Datei `aevum_insights` (dieselbe wie Perioden-Auswahl und
Ausklapp-Zustand):

| Key | Inhalt |
|---|---|
| `balance_mode` | `Activity` oder `Category` |
| `balance_activity_ids` | geordnete IDs, mit `\u001F` getrennt, max. 5 |
| `balance_category_ids` | geordnete IDs, mit `\u001F` getrennt, max. 5 |

Die Reihenfolge bleibt erhalten, damit die Achsen beim nächsten Öffnen stabil
stehen. Ein neues ViewModel (App-Neustart) liest Modus und beide Listen; der
Test `balanceModeAndSelectionSurviveANewViewModel` prüft genau das.

### 5. Stabile Achsen und Randfälle

- Eine gespeicherte Aktivität/Kategorie, die im gezeigten Zeitraum **0 Zeit**
  hat, bleibt als Achse mit 0 erhalten. Sonst würden die Achsen beim
  Durchblättern der Perioden springen.
- Gelöschte Aktivitäten/Kategorien fallen aus der Auswahl. Bleibt dadurch
  nichts übrig, greift wieder der Default. Eine vorhandene Auswahl wird NICHT
  aufgefüllt: wer bewusst drei Achsen wählt, bekommt drei.
- Das Radar braucht mindestens **3 Achsen** (geometrisch sinnvoll). Bei
  weniger zeigt die Karte einen Hinweis statt einer leeren Grafik; das
  Auswahl-Sheet bleibt erreichbar.
- Die Prozentwerte der Chips beziehen sich auf die Summe der AUSGEWÄHLTEN
  Achsen (sie summieren sich zu 100 %), nicht auf den ganzen Zeitraum. Die
  Karte vergleicht die gewählten Bereiche miteinander.

### 6. Architektur

- `BalanceMode` (Activity/Category) und `BalanceOption` (id, label, color,
  icon, duration, percent) in `InsightsAnalytics`.
- Neue `InsightsUiState`-Felder: `balanceAxes` (Radar), `balanceOptions`
  (Sheet), `balanceSelectedIds` (effektive Auswahl), `balanceMode`.
- `buildBalanceOptions` baut den Katalog aus `activityTypes`/`categories` und
  den aggregierten Slices; `resolveBalanceSelection` validiert die
  gespeicherten IDs; `buildBalanceAxes` rechnet die Achsen und ihre
  Prozentwerte.
- Das ViewModel hält `_balanceMode`, `_balanceActivityIds`,
  `_balanceCategoryIds` als eigene Flows und faltet sie mit dem bestehenden
  `_viewPrefs`-Paar zu `_uiPrefs` (das äußere `combine` bleibt beim Limit von
  fünf typisierten Flows).
- Das Sheet ist reiner UI-Zustand des Screens (`showBalancePicker`), wie der
  Perioden-Picker; gespeichert wird über die ViewModel-Handler.

## Dateien

- `app/src/main/java/.../ui/screens/insights/InsightsAnalytics.kt`
  (`BalanceMode`, `BalanceOption`, Achsen/Options-Aufbau, State-Felder)
- `app/src/main/java/.../ui/screens/insights/InsightsCharts.kt`
  (Modus-Chips, Auswahl-Sheet, Radar auf `axes`)
- `app/src/main/java/.../ui/screens/insights/InsightsScreen.kt`
  (Sheet-Zustand, Handler-Verdrahtung)
- `app/src/main/java/.../ui/screens/insights/InsightsViewModel.kt`
  (Flows, Persistenz, `setBalanceMode`, `saveBalanceSelection`,
  `resetBalanceSelection`)
- `app/src/main/res/values/strings_weekly_insights.xml` (EN)
- `app/src/main/res/values-de/strings_weekly_insights.xml` (DE)
- `app/src/test/java/.../InsightsBalanceSelectionTest.kt` (neu, 5 Tests)
- `app/src/test/java/.../InsightsChartsUiTest.kt` (+2 Tests)
- `app/src/test/java/.../InsightsWiringIntegrationTest.kt` (+1 Test)
- `app/build.gradle.kts` — 1.0.52 / versionCode 53
- `docs/CHANGELOG.md`, `docs/ROADMAP.md`

## Verifikation

### Unit-Tests

```
:app:testDebugUnitTest
1434 Tests, 0 Fehler, 0 Errors, 1 skipped
```

Neu: 8 Tests.

`InsightsBalanceSelectionTest` (5):

1. Default = die fünf häufigsten Aktivitäten mit Zeit; Prozentwerte 30/25/20/
   15/10 bei 6/5/4/3/2 h.
2. Gespeicherte Reihenfolge `t3, t6, t1` bleibt erhalten; `t6` hat 0 Zeit und
   bleibt als 0-Achse (40 %, 0 %, 60 %).
3. Eine gespeicherte Auswahl mit nur zwei Achsen wird nicht auf fünf
   aufgefüllt.
4. Ausschließlich gelöschte IDs → Default greift wieder.
5. Kategorie-Modus: Default sind die fünf häufigsten Kategorien; dieselben
   Prozentwerte.

`InsightsChartsUiTest` (+2):

1. Auswahl-Sheet öffnen, zwei fehlende Achsen ergänzen, „Übernehmen" liefert
   die fünf IDs in Anzeige-Reihenfolge an den Handler.
2. Der Kategorien-Chip meldet `BalanceMode.Category` an den Handler.

`InsightsWiringIntegrationTest` (+1):

1. `balanceModeAndSelectionSurviveANewViewModel`: Default-Top-5, Wechsel auf
   Kategorien, Auswahl `sport, work` speichern, ZWEITES ViewModel auf demselben
   App-Kontext → Modus und Reihenfolge kommen unverändert an; Reset stellt den
   Default wieder her. Die Test-Preferences werden davor und danach geleert.

### Visueller Check

Temporärer Robolectric-Screenshot der Balance-Karte (400 dp breit, Dark Theme,
deutsche Ressourcen), danach entfernt:

- Modus-Chips „Aktivitäten/Kategorien" und „Auswählen ›" passen in eine Zeile,
  nichts wird abgeschnitten.
- Radar mit fünf Achsen, Labels (Arbeit, Freizeit, Sport, Lesen, Soziales) und
  Chips vollständig sichtbar; Chips summieren sich zu 100 %.
- Kein Clipping, keine Überlappung.

### Build

```
:app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 40s
```

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Größe: 126.238.770 Bytes (120,4 MiB)
- SHA-256: `93067a7db8fca38aa07de05821a24c222bfd177d46e6c17a01da6fea47a52f23`
- `aapt dump badging`: `versionCode='53'`, `versionName='1.0.52-debug'`,
  `compileSdkVersion='36'`, `sdkVersion:'29'`
- `apksigner verify`: kanonischer Key
  `9c3055c4810b5cc2e548d8de8357344352fbe0e8` — Update ohne Deinstallation

### Was auf dem Gerät zu prüfen ist

Kein `adb`-Gerät und kein `/dev/kvm` — die Auswahl wurde nicht auf Hardware
durchgeklickt. Nach dem Update:

1. Insights → Balance: Standardmäßig müssen die fünf häufigsten Aktivitäten
   mit Zeit als Achsen erscheinen.
2. Auf „Kategorien" wechseln: Das Radar zeigt die fünf häufigsten Kategorien.
3. „Auswählen" öffnen, fünf beliebige Einträge wählen, „Übernehmen": Radar und
   Chips zeigen sofort die neue Auswahl.
4. App komplett schließen und neu öffnen: Modus und Auswahl müssen unverändert
   sein.
5. Zwischen Heute/Woche/Monat und mit ‹/› durch die Perioden blättern: Die
   Achsen bleiben gleich; nur die Werte ändern sich.
6. „Top 5" im Sheet: Default (häufigste Einträge mit Zeit) kommt zurück.
7. Eine der gewählten Aktivitäten in einer Periode ohne Zeit: Sie bleibt als
   0-Achse sichtbar.

## Bewusste Grenzen / Nicht Bestandteil

- **Genau fünf Achsen, wenn fünf Einträge existieren.** Wer weniger als fünf
  speichern möchte, kann das nicht über das Sheet; bei weniger als fünf
  vorhandenen Einträgen sind automatisch alle ausgewählt. Das entspricht dem
  Auftrag „5 Aktivitäten oder 5 Kategorien".
- **Keine Drag-and-drop-Reihenfolge:** Die Achsen-Reihenfolge ist die
  Reihenfolge im Auswahl-Sheet (nach Dauer sortiert) bzw. die gespeicherte
  Reihenfolge. Ein Umsortieren ist nicht Teil dieses Meilensteins.
- **Keine Suche im Auswahl-Sheet:** Die Liste ist nach Dauer sortiert und
  scrollbar. Bei sehr vielen Aktivitäten wäre eine Suche der nächste Schritt.
- **Pauschalen zählen nicht in die Radar-Werte.** Das Radar basiert auf
  erfasster Zeit (wie die bisherige Balance-Karte); Pauschalen bleiben im Hero
  und im Donut sichtbar.
- **Titel-Fallback:** Sessions ohne `activityTypeId` werden über ihren Titel
  gruppiert und sind als Achse wählbar. Wird der Titel später geändert, ist
  diese gespeicherte ID nicht mehr auffindbar; das Radar fällt dann auf den
  Default zurück.
- **Kein Geräte-Test:** siehe oben; Verifikation über 1434 Unit-/Robolectric-
  Tests plus temporären Screenshot.

# M30 — Bildschirmzeit-Ziel zeigt nicht mehr das Doppelte

**Status:** Abgeschlossen
**App-Version:** 1.0.50 (versionCode 51)
**Datum:** 2026-10-24
**Commit:** `7421af1c4070886aad12b7828b5d59fa6933a5e0`

## Auftrag

> „Da scheint es einen Bug zu geben bei den Zielen mit digital Balance. Ich
> habe ein automatisches Ziel mit der Quelle Bildschirmzeit und es soll unter
> 3 Stunden sein, und laut der Balance Seite habe ich heute 3 Stunden
> 7 Minuten, das passt auch mit der digital Balance App, aber das Ziel zeigt
> an 6 Stunden 8 Minuten Bildschirmzeit heute, und entsprechend groß ist auch
> der Balken im balkendiagramm.“

## Root Cause

Ein Ziel mit `sourceKind = DIGITAL` wurde in
`GoalProgressAnalytics.matchesSession` über die **Aktivitäts-ID** gematcht:

```kotlin
SOURCE_DIGITAL -> session.activityTypeId == "digital"
```

`StatisticsSessionSource.merge` entfernt zwar die Bildschirm-Aufzeichnung
(`sourceType = SCREEN_AUTO`) und fügt pro Tag **eine** künstliche
`DIGITAL_BALANCE`-Session mit der echten Bildschirmzeit ein. Es bleiben aber
alle anderen Sessions mit `activityTypeId = "digital"` erhalten — z. B.
App-Aufzeichnung (`APP_TRACKING`), PC-Sessions (`PC_REMOTE`) oder manuell
gestartete Digital-Blöcke. Diese wurden zusätzlich zur Balance-Session
summiert.

Die gemeldeten Zahlen sind exakt dieser Fall:

```
Balance:                  3 h 07 min = 187 min
zusätzlicher Digital-Block: 3 h 01 min = 181 min
Summe:                    6 h 08 min = 368 min
```

Der Regressionstest reproduziert vor dem Fix exakt `6.133333` (= 6 h 08 min)
und nach dem Fix `3.116666` (= 3 h 07 min).

## Fix

### 1. Digital-Ziel zählt nur die Messung

`GoalProgressAnalytics.matchesSession` akzeptiert für `SOURCE_DIGITAL` nur
noch Sessions mit

```kotlin
sourceType == ScreenStatisticsPolicy.SOURCE_DIGITAL_BALANCE
```

Damit ist der Zielwert per Konstruktion identisch mit der Digital-Balance-
Seite. Das gilt für die Karte, das Balkendiagramm und die Heatmap, weil alle
denselben Analyzer verwenden. Andere Digital-Sessions bleiben in der Timeline
sichtbar und zählen für Aktivitäts-/Kategorieziele weiter — nur nicht für ein
Bildschirmzeit-Ziel.

### 2. Minuten runden

`formatValue` schnitt den Minutenbruchteil ab: 3,1166 h wurden zu „3h 6m"
statt „3h 7m". Jetzt wird auf die volle Minute gerundet:

```kotlin
val totalMinutes = (value * 60f).roundToInt()
```

### 3. Test-Stabilität

Der M22-Test „laufende Session ohne endAt zählt bis jetzt" nahm an, dass
`now − 90 min` noch am selben Tag liegt. Zwischen 00:00 und 01:30 lag der
Start am Vortag, und der Test schlug ohne Codeänderung fehl. Er berechnet den
erwarteten Anteil jetzt aus dem tatsächlichen Tagesfenster.

## Dateien

- `app/src/main/java/.../domain/analytics/GoalProgressAnalytics.kt`
- `app/src/test/java/.../GoalDigitalSourceRegressionTest.kt` (neu, 3 Tests)
- `app/src/test/java/.../GoalProgressAnalyticsTest.kt` (Test-Stabilität)
- `app/build.gradle.kts` — 1.0.50 / versionCode 51
- `docs/CHANGELOG.md`, `docs/ROADMAP.md`

## Verifikation

### Red-Green-Nachweis

Vor dem Fix (nur der neue Regressionstest):

```
digital goal shows exactly the balance time despite extra digital recording FAILED
  expected: 3.1166666
  but was : 6.133333
digital history bar for today uses the balance value only FAILED
  expected: 3.1166666
  but was : 6.133333
```

Nach dem Fix: 3/3 Tests grün.

### Unit-Tests

```
:app:testDebugUnitTest
1417 Tests, 0 Fehler, 0 Errors, 1 skipped
```

Neu: 3 Tests in `GoalDigitalSourceRegressionTest`:

1. Digital-Ziel mit zusätzlicher `APP_TRACKING`-Digital-Session → 3 h 07 min
   statt 6 h 08 min.
2. `SCREEN_AUTO`-Aufzeichnung bleibt weiterhin ausgeschlossen.
3. Der heutige Verlaufsbalken nutzt nur die Balance-Zeit (`PC_REMOTE`-Fall).

### Build

```
:app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 43s
```

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Größe: 125.169.640 Bytes (119,4 MiB)
- SHA-256: `c343499729a81afe3fdc8e3b7af60a029e44992d228976a2914be22d10a364c1`
- `aapt dump badging`: `versionCode='51'`, `versionName='1.0.50-debug'`,
  `compileSdkVersion='36'`
- `apksigner verify`: kanonischer Key
  `9c3055c4810b5cc2e548d8de8357344352fbe0e8` — Update ohne Deinstallation

### Was auf dem Gerät zu prüfen ist

Kein `adb`-Gerät und kein `/dev/kvm` — die Zielkarte selbst wurde nicht auf
Hardware beobachtet. Nach dem Update:

1. Ziele → betroffenes Bildschirmzeit-Ziel öffnen.
2. Der Wert und der heutige Balken müssen jetzt der Digital-Balance-Seite
   entsprechen (im gemeldeten Fall 3 h 07 min statt 6 h 08 min).
3. Bei einer laufenden Bildschirmnutzung muss der Wert weiterhin im
   60-Sekunden-Takt wachsen.

## Bewusste Grenzen

- **Alt-Ziele ohne `sourceKind`** (nur `activityTypeId = "digital"`) behalten
  das bisherige Matching. Das gemeldete Ziel nutzt die neue Quelle
  „Bildschirmzeit" (`sourceKind = DIGITAL`) und ist damit abgedeckt.
- **Welcher Zusatzblock genau** die 3 h 01 min beigesteuert hat (App-, PC-
  oder manueller Digital-Block), lässt sich ohne Geräte-Datenbank nicht
  benennen. Der Fix schließt alle Nicht-Balance-Digital-Sessions aus, daher
  ist die konkrete Quelle für das Ergebnis nicht mehr relevant.

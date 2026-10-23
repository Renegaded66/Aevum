# M29 — Timeline-Lücke beim Anlegen einer Aktivität vorbelegen

**Status:** Abgeschlossen
**App-Version:** 1.0.49 (versionCode 50)
**Datum:** 2026-10-23
**Commit:** `bed36a357e74cb949436defa2381bfbbf8b2f508`

## Auftrag

> „Bei Aevum möchte ich die Funktion, wenn ich in der Timeline direkt unter
> einem Termin in einen freien Bereich klicke, und sich dann das neue Fenster
> zum hinzufügen einer Aktivität öffnet, dass dann dort die start und Zielzeit
> automatisch so ausgefüllt wird, dass sie genau angrenzend zwischen zwei
> aktivitäten ist, also ohne überlagerung den ganzen Zeitraum einnimmt. Aber
> nur wenn man halbwegs direkt unter einer Activity auf die Timeline klickt,
> und auch nur wenn es nicht die zuletzt aufgenommene Activity ist, weil dann
> gibt es ja keine nachfolgende die man als Ziel Zeitpunkt nutzen kann.“

## Verhalten

Ein Tap auf freie Zeit in der Tages-Timeline öffnet weiterhin den
Activity-Editor. Die Zeitfelder werden jetzt situational vorbelegt:

| Situation | Startzeit | Endzeit |
|---|---|---|
| Tap direkt unter einer Aufzeichnung, nächste Aufzeichnung vorhanden | Ende der oberen Aufzeichnung | Start der nächsten Aufzeichnung |
| Tap weiter als 24 dp unter dem Blockende | Tap-Zeit | Tap-Zeit + 1 h |
| Tap unter der letzten Aufzeichnung des Tages (keine nächste) | Tap-Zeit | Tap-Zeit + 1 h |
| Tap in einer Aufzeichnung / in überlappender Zeit | Tap-Zeit | Tap-Zeit + 1 h |
| Tap über der ersten Aufzeichnung | Tap-Zeit | Tap-Zeit + 1 h |

Die Lücke wird vollständig ausgefüllt — auch wenn sie mehrere Stunden lang
ist. Es gibt keine Überlagerung mit der oberen oder unteren Aufzeichnung.

### „Halbwegs direkt darunter“

Die Schwelle ist **24 dp unter dem sichtbaren Blockende**. Sie wird über
`pixelsPerHour` in Minuten umgerechnet und auf 5–90 Minuten begrenzt. Dadurch
fühlt sich die Trefferfläche bei jedem Zoom gleich groß an:

- Standard-Zoom (40 dp/h): 36 Minuten
- maximaler Zoom (180 dp/h): 8 Minuten → auf 5 begrenzt
- minimaler Zoom (18 dp/h): 80 Minuten

Ein Tap genau auf der Schwelle zählt noch als „direkt darunter“.

## Technische Umsetzung

### 1. Reine Lückenfunktion

Neu: `ui/screens/timeline/TimelineGap.kt`

`findGapBelowActivity(clickedMinute, sessions, maxDistanceBelowMinutes)`
arbeitet nur mit den sichtbaren Minutenoffsets des Tages und ist frei von
Compose/Android. Sie liefert `TimelineGapRange(startMinute, endMinute)` oder
`null`.

Regeln:

1. Der Tap muss in freier Zeit liegen (nicht in einem Block).
2. Obere Grenze ist die Aufzeichnung mit dem **spätesten Ende** oberhalb des
   Taps — nicht einfach die letzte in der Liste. Das verhindert Überlagerungen
   bei verschachtelten Blöcken.
3. Die Distanz zwischen diesem Ende und dem Tap darf die Schwelle nicht
   überschreiten.
4. Untere Grenze ist der **früheste Start** nach diesem Ende. Fehlt er, ist es
   die letzte Aufzeichnung → `null`.
5. Ein Block, der den Tap überdeckt, verhindert die Vorbelegung. Damit kann
   die berechnete Lücke keine laufende oder überlappende Aktivität schneiden.
6. Die End-Geometrie entspricht der Zeichnung: sehr kurze/auf 0 gefallene
   Enden werden als eine Minute breiter Block behandelt; Mitternachts-Sessions
   sind bereits auf 0..1440 geclippt.

### 2. Tap-Handler

`ZoomableDayTimeline` in `TimelineScreen.kt`:

- Der bestehende Hit-Test auf Blöcke bleibt unverändert. Nur wenn er keinen
  Block trifft, wird die Lücke gesucht.
- Die 24-dp-Schwelle wird aus der aktuellen Zoomstufe berechnet.
- `onCreateAt(startMinute, endMinute?)` gibt die Lücke an den Screen weiter.

### 3. Navigation

`AppDestination.ActivityCreate` hat jetzt den optionalen Query-Parameter
`end`:

```
activity/new/{date}?end={end}
```

`activityCreateRoute(startMillis, endMillis?)` baut die Route. Ohne Ende bleibt
die URL wie bisher (`activity/new/<millis>`); der optionale Parameter hat den
Default `-1`.

### 4. Editor

`ActivityEditorViewModel` liest `end` aus dem `SavedStateHandle`. Nur wenn der
Wert positiv und größer als die Startzeit ist, wird er übernommen; sonst gilt
weiterhin `Start + 1 h`. Plus-Button und Empty-State übergeben kein Ende und
behalten damit exakt ihr bisheriges Verhalten.

## Dateien

- **Neu:** `app/src/main/java/.../ui/screens/timeline/TimelineGap.kt`
- **Neu:** `app/src/test/java/.../TimelineGapRangeTest.kt` (13 Tests)
- **Neu:** `app/src/test/java/.../ActivityCreateRouteTest.kt` (2 Tests)
- `TimelineScreen.kt` — Tap-Handler, Callback-Signatur, Minuten→Millis
- `TimelineViewModels.kt` — `end`-Argument im Editor
- `AppDestination.kt` — Route + `activityCreateRoute`
- `AppNavHost.kt` — Navigation + optionales `end`-Argument
- `app/build.gradle.kts` — Version 1.0.49 / versionCode 50
- `docs/CHANGELOG.md`, `docs/ROADMAP.md`

## Verifikation

### Unit-Tests

```
:app:testDebugUnitTest
1414 Tests, 0 Fehler, 0 Errors, 1 skipped
```

Neu: 15 Tests.

`TimelineGapRangeTest` (13):

- direkte Lücke wird komplett gefüllt
- Schwelle exakt eingehalten
- zu weit darunter → Default
- letzte Aktivität → Default
- Tap in Aktivität → Default
- Tap über der ersten Aktivität → Default
- überlappender Block über dem Tap → Default
- spätestes Ende oberhalb des Taps gewinnt
- frühester Start nach der Lücke gewinnt
- Mitternachts-geclippte Aktivität als obere Grenze
- lange Lücke wird komplett gefüllt
- Schwelle 0 deaktiviert
- Tap außerhalb des Tages wird ignoriert

`ActivityCreateRouteTest` (2):

- Route ohne Ende bleibt unverändert
- Route mit Ende trägt `?end=<millis>`

### Build

```
:app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 40s
```

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Größe: 125.169.640 Bytes (119,4 MiB)
- SHA-256: `6bd62ed3078792d381e5eba71c758ee31d57b3a27a0906360c5976bd97b240b2`
- `aapt dump badging`: `versionCode='50'`, `versionName='1.0.49-debug'`,
  `compileSdkVersion='36'`
- `apksigner verify`: kanonischer Key
  `9c3055c4810b5cc2e548d8de8357344352fbe0e8` — Update ohne Deinstallation

### Was auf dem Gerät noch zu prüfen ist

Es ist kein Gerät per `adb` angeschlossen und `/dev/kvm` fehlt; die
Tap-Interaktion selbst ist daher **nicht auf Hardware beobachtet**. Die
Lückenlogik, die Route und der Build sind JVM-seitig bewiesen, der Compose-Tap
nicht.

Manueller Test:

1. Debug-APK installieren (gleiche Signatur, Update ohne Deinstallation).
2. Timeline → Tag-Ansicht → zwei Aufzeichnungen mit sichtbarer Lücke.
3. Direkt unter die obere Aufzeichnung tippen.
4. Editor muss Start = Ende der oberen, Ende = Start der unteren zeigen.
5. Unter die letzte Aufzeichnung tippen → Start = Tap-Zeit, Ende = +1 h.
6. Weiter unten in eine große Lücke tippen → weiterhin +1 h.

## Bewusste Grenzen

- **Geplante Kalender-Blöcke sind keine Grenzen.** Sie sind keine
  Aufzeichnungen; die Lücke wird aus den tatsächlich aufgenommenen
  Aktivitäten gebildet. Ein Plan im freien Bereich wird also nicht als obere
  oder untere Kante verwendet.
- **Nur der sichtbare Tag.** Die nächste Aufzeichnung am Folgetag ist auf der
  Tages-Timeline nicht sichtbar und kann deshalb nicht als Ziel dienen.
- **Liste- und Wochenansicht unverändert.** Die Lücken-Vorbelegung betrifft
  nur die Tages-Timeline, in der ein Tap auf freie Zeit eine neue Aktivität
  anlegt.

## Ergebnis

Die Zeitfelder im Editor sind bei einem Tap direkt unter einer Aufzeichnung
exakt auf die umgebende Lücke gesetzt. Es entsteht keine Überlagerung, die
Lücke wird vollständig gefüllt, und die letzte Aufzeichnung des Tages behält
den bisherigen +1h-Default.

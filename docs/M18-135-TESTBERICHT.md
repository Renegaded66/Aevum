# M18.135 — Automatisierte Tests des Kalender-Wiedereinstiegs (Integrationsebene)

Kanban **t_f15f4443** · Branch `hermes/t_f15f4443-calendar-tests` (Basis `323de63` = t_0bf5541e)
Status: **fertig und verifiziert** — 780 Unit-Tests grün (0 Failures), 4 Mutationstests belegt.
Produktionscode in diesem Auftrag **unangetastet** (nur Testdatei + Test-Dependency).

## Auftrag

> „Add unit/integration tests covering the fallback/resume behavior. Include these scenarios:
> calendar entry active -> car drive starts -> car drive stops -> calendar recording resumes;
> calendar entry expires during car drive -> no resume; multiple conflicts; no overlapping
> recordings. Follow existing test patterns; mock time, calendar, and recording services as needed."

## Was schon da war — und was hier neu ist

t_0bf5541e hat `CalendarResumeFallbackTest` (14 Tests) gebaut: Engine-Logik gegen
**handgeschriebene Repository-Fakes**. Damit ist die Entscheidungslogik bewiesen.

Die verbleibende Lücke: die beiden **SQL-Queries**, an denen die Wiedereinstiegs-EVIDENZ
hängt, wurden von den Fakes **nachgebaut** statt geprüft. Ein Fake, der nur nach
`source_type` filtert, wäre grün, während die echte Query soft-gelöschte oder noch
laufende Sessions mitzählt.

Neu: **`CalendarResumeRoomIntegrationTest` (13 Tests)** — Robolectric 4.14.1 +
echte Room-In-Memory-`AppDatabase` + echte Produktionsklassen in ihrer echten Verdrahtung
(`ActivityRepositoryImpl` → `ActivitySessionDao` → Room, `LiveActivityManager` → Repository).

Das erste Mal in diesem Projekt, dass die Kalender-Evidenz-Kette
**SQL → Repository → Engine → Session-Start** durchgehend gegen die echte DB läuft.

## Die 13 Tests

| Test | Prüft |
|---|---|
| `Termin wird nach der Autofahrt fortgesetzt - echte DB und echte Queries` | **Der gemeldete Fall** durchgehend: beide Beweis-Stufen aus echten Queries, Resume ankert bei JETZT, genau 2 Sessions, überlappungsfrei |
| `Termin endet waehrend der Fahrt - kein Wiedereinstieg` | Grenze exklusiv: Termin während der Fahrt abgelaufen → kein Resume |
| `mehrere Konflikte hintereinander - jeder Wiedereinstieg greift` | 3 Konflikte (Fahrt/Wanderung/Fahrt) → 4 Blöcke, 1 live, keine Duplikate |
| `Kalender-Bloecke ueberlappen nie und werden nie dupliziert` | Keine Überlappung, in der echten DB nachgerechnet |
| `Resume-Query liefert nur FINISHED Sessions - nicht laufende, nicht geloeschte` | `deleted_at IS NULL`, `session_status='FINISHED'`, `end_at NOT NULL` |
| `Resume-Query sortiert nach end_at DESC und respektiert das LIMIT` | Reihenfolge + LIMIT schneidet die ÄLTESTEN ab |
| `Verdraengungs-Query zaehlt keine Kalender-Session als Zeuge` | `source_type !=` — der Kalender darf seinen eigenen Nachfolger nicht als Fremd-Aufzeichnung deuten |
| `Verdraengungs-Query nutzt die Toleranzgrenzen inklusiv` | BETWEEN-Kanten exakt (beide Seiten) |
| `manueller Stop wird nicht wiederaufgenommen - auch mit echter DB` | **Menschliche Entscheidung bleibt**; Vorbedingung: regulärer Start unmöglich |
| `abgeschnittener Block allein genuegt nicht - spaeterer Fremd-Start ist kein Zeuge` | Diskriminator-Schärfe: Fahrt 60 s NACH der Schnitt berührt nichts (mit Gegenprobe exakt auf der Schnitt) |
| `QUEUE-Termin bleibt ohne Verdraengung nachholbar` | M18.132-Semantik unverändert, mit OVERRIDE-Gegenprobe |
| `jeder automatische Stop-Pfad stoesst den Kalender-Lauf an` | 7 Stop-Pfade × `restartNow` NACH dem Stop |
| `manuelle Stop-Pfade stoessen den Kalender-Lauf NICHT an` | Dashboard/SaveManual rufen den Scheduler nicht auf |

## Zeitachse — der wichtigste Unterschied zum Fake-Test

Szenarien liegen um `System.currentTimeMillis()` herum, **nicht** auf einer festen
Kalender-Achse. Grund (gemessen): `LiveActivityManager.stop()` stempelt
`endAt = System.currentTimeMillis()`, und Robolectric kann die **Wanduhr nicht
verschieben** — `SystemClock.setCurrentTimeMillis(ms)` wirkt nur auf Uptime-Clocks,
`System.currentTimeMillis()` bleibt unverändert. Auf einer festen Achse (15–18 Uhr am
19.09.2026) lägen diese Enden Jahre daneben: der abgeschnittene Block entstünde nie und
die Tests wären **grün und blind**.

Daraus die Modell-Regeln: der erste Lauf liegt innerhalb der 20-Minuten-Toleranz
(sonst greift der reguläre Start bewusst nicht), und für den Diskriminator beginnt der
Termin ≥ 20 min vor jetzt (sonst grün der Resume-Test aus dem falschen Grund).

## Verifikation

**Vollsuite:** `./gradlew :app:testDebugUnitTest --offline --rerun-tasks`
→ **780 Tests, 0 Failures, 0 Errors, 0 Skipped** (70 Suiten).
Kalender/LiveActivity-Nachbarsuiten: **175 → 188 Tests**, alle grün.

**Stabilität:** 5/5 aufeinanderfolgende Läufe der neuen Suite grün (nach dem Flake-Fix,
siehe unten).

**Mutationstests — 4/4 GEFANGEN** (belegt, dass die Tests wirklich greifen):

| Mutation | Ergebnis |
|---|---|
| 1. DAO-Resume-Query ohne `deleted_at`-Filter | `Resume-Query liefert nur FINISHED Sessions` FAILED |
| 2. `startAnchorMs(displaced)` → Termin-Beginn statt JETZT (= die zentrale Fehlerrichtung) | 4 Tests FAILED |
| 3. `hasForeignSessionStartingNear` fest `true` | 2 Tests FAILED (u. a. manueller Stop) |
| 4. `restartNow` im Walking-Watchdog entfernt | Wiring-Test FAILED |

Alle Mutationen wurden danach zurückgesetzt; `git diff --stat HEAD -- app/src/main/` ist leer
(Produktionscode bitgleich zum Ausgangsstand).

## Gefundener und behobener Flake

Erster Stabilitaetslauf: 1 von 5 rot —
`Überlappung zwischen … expected 1790083137948 but was 1790083137947`.

Ursache (per Row-Dump gemessen, nicht geraten): `live.stop()` und der `forceFinish` im
nahtlosen Wechsel stempeln `endAt = System.currentTimeMillis()`, während der Start der
nächsten Session ein vom **Aufrufer** gelesener Wert ist. Beim Testablauf (7 Sessions in
~50 ms) können sich diese Stempel um Millisekunden überholen. In der Produktion liegen
diese Ereignisse Sekunden bis Minuten auseinander — ein Test, der sie in 50 ms feuert,
modelliert einen Ablauf, den es nicht gibt.

Fix: das Assert prüft jetzt **zwei getrennte Zusicherungen** —
Kalender-Block gegen Kalender-Block **exakt** (Toleranz 0), über die ganze Sequenz mit
`WALLCLOCK_STAMP_TOLERANCE_MS = 50 ms`. Die Toleranz ist bewusst 3 Größenordnungen kleiner
als die geprüfte Fehlerrichtung (Minuten) — Mutationstest 2 zeigt: die echte Regression
wird weiterhin gefangen.

## Bewusst NICHT gemacht

- **Kein Produktionscode geändert.** Der Auftrag ist „Tests hinzufügen"; die Implementierung
  liegt in t_0bf5541e. Einzige Nicht-Test-Änderung: zwei `testImplementation`-Zeilen
  (Robolectric 4.14.1, androidx.test:core 1.6.1) in `app/build.gradle.kts`.
- **Keine Schema-Migration**, keine neue Query.
- **Keine Emulator-Tests** (kein KVM). Robolectric deckt die DB-/SQL-Seite auf der JVM ab;
  der manuelle Gerätetest (Termin anlegen → fahren → ankommen) steht weiterhin beim Nutzer.

## Nebenbefund (nicht angefasst)

`ActivityRecognitionWorker.kt:123` stoppt eine Fahrt-Session auf einem EXIT-Pfad und ruft
`CalendarAutoRunScheduler` **nicht** auf — sieht wie ein fehlender Stop-Pfad aus. Ist
**keiner**: der Worker wird nirgends enqueued (Superseded durch `DriveWorkers`), die Datei
ist toter Code. Deshalb bewusst nicht in die Wiring-Prüfung aufgenommen.

## Dateien

**Neu:** `app/src/test/java/com/d_drostes_apps/aevum/domain/calendar/CalendarResumeRoomIntegrationTest.kt` (13 Tests)
**Geändert:** `app/build.gradle.kts` (nur `testImplementation`-Zeilen)
**Unverändert:** alle Produktionsquellen

**Commits:** `a56fa05` (Suite), `c377708` (Flake-Fix) auf `hermes/t_f15f4443-calendar-tests`;
main-Repo unangetastet.

## Für den Nachfolger / Reviewer

- Kommando: `./gradlew :app:testDebugUnitTest --tests "com.d_drostes_apps.aevum.domain.calendar.*" --offline`
- `local.properties` mit `sdk.dir=/opt/android-sdk` muss existieren (gitignored).
- Neue Evidenz-Query? Muster: **erst** Fake-Test (Engine-Logik), **dann** Room-Test (SQL-Seite).
- Skill `aevum-calendar-authorun` ist um beide Lehren erweitert (Wanduhr-Falle, Robolectric-Setup,
  Zwei-Stufen-Überlappungs-Assert).

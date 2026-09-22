# M18.134 — Kalender-Aufzeichnung als Fallback (Resume nach Konflikt)

Kanban **t_0bf5541e** · Branch `hermes/t_0bf5541e-calendar-resume` · Basis `7c6827d` (Analyse-Branch t_61143053)
Status: **implementiert und verifiziert** — 767 Unit-Tests grün (0 Failures), Debug-APK gebaut (`versionCode 18` / `1.0.17-debug`).

## Auftrag

> „der Kalendereintrag soll immer laufen wenn nichts anderes läuft […] hätte die Aufzeichnung direkt
> nach stopp der Autofahrt weiter aufgezeichnet werden sollen, da der Kalendereintrag zeitlich auch dann noch aktiv war."

## Was geändert wurde

### 1. Engine: Wiedereinstiegs-Prädikat + Verdraengungs-Evidenz (`CalendarAutoRunEngine.kt`)

Neue reine Funktionen (JVM-testbar, kein Android):

| Funktion | Zweck |
|---|---|
| `shouldResumeAfterDisplacement(event, now, displaced)` | Fälligkeit des Wiedereinstiegs: Termin läuft **und** Verdrängung belegt |
| `displacedMarkers(matches, recentFinished)` | Kandidaten: abgeschnittene Termine **samt Schnittstelle** (`DisplacedMarker(eventId, cutAtMs)`) |
| `displacedEventIds(...)` | Nur die IDs — Bequemlichkeit über `displacedMarkers` |
| `isFallbackDue(match, now, ids)` | Einziger Zusammenlauf: regulär / QUEUE / Wiedereinstieg |
| `startAnchorMs(match, now, displaced)` | Startzeit-Anker: Wiedereinstieg bei JETZT, sonst Termin-Beginn (M18.70), QUEUE-Nachholer bei JETZT (M18.132) |

`pickStartCandidate(...)` bekam den Parameter `displacedEventIds: Collection<String> = emptyList()`
— rückwärtskompatibel, alle Bestandstests unverändert.

### 1b. Der Verdrängungs-BEWEIS (die eigentliche Hürde)

„Block abgeschnitten" ist noch **kein** Beweis für eine Verdrängung: derselbe Zustand entsteht,
wenn der **Nutzer selbst** stoppt, pausiert oder die Aktivität wechselt. Ohne Unterschied würde
der nächste Worker-Lauf eine menschliche Entscheidung 15 Minuten später stillschweigend umdrehen.

Der positive Nachweis nutzt den Fingerabdruck des M18.71-Trims: beim Fremd-Start schreibt
`LiveActivityManager.start()` das Ende der verdrängten Session **exakt** auf die Startzeit der
übernehmenden Session. Ein manueller Stop tut das nicht — er endet bei „jetzt", ohne dass dort
etwas beginnt.

```
Stufe 1: displacedMarkers(...)     → abgeschnittener Block + Schnittzeit cutAtMs
Stufe 2: hasForeignSessionStartingNear(atMs = cutAtMs, tolerance = 2 s)
                                   → begann dort eine Session, die NICHT vom Kalender stammt?
```

Nur wer **beide** Stufen besteht, kommt zurück. Die Toleranz (`DISPLACEMENT_WITNESS_TOLERANCE_MS
= 2 s`) deckt getrennte `System.currentTimeMillis()`-Aufrufe ab und bleibt trotzdem scharf genug,
um „manueller Stop" von „Fahrt begann eine Minute später" zu trennen.

**Beide** Zweige sind getestet und der Nachweis ist **mutationstest-geprüft**: mit deaktivierter
Stufe 2 fällt `manueller Stop wird nicht wiederaufgenommen` sofort (der Test war zuvor blind, weil
der Fake-`stop()` die reale Wanduhr benutzte — die Fake-Uhr `simulatedWallClockMs` zieht Enden
jetzt auf die Simulationsachse, und der Test prüft zusätzlich, dass der abgeschnittene Block
überhaupt existiert).

### 2. Worker: Fallback nur, wenn nichts läuft (`CalendarAutoRunWorker.kt`)

- Neuer Schritt `resumeEvidence(...)`: zweistufig wie oben; liefert **leer**, solange eine Session
  live ist (sonst Start/Stop-Ping-Pong gegen die laufende Fahrt); liest den Verlauf über
  `recentFinishedSessionsBySourceType(CALENDAR_AUTO, RESUME_EVIDENCE_LOOKBACK=8)`.
- `startSession(...)` nutzt `startAnchorMs` statt der zwei handgeschriebenen Fälle.
- `SOURCE_CALENDAR` ist jetzt eine Engine-Konstante (Domain-Schicht); der Worker verweist darauf.
- Lookback 8 statt 1: bei mehreren Konflikten hintereinander (Fahrt → Wanderung → Fahrt) kann die
  **jüngste** beendete Session zu einem bereits abgelaufenen Termin gehören — dann bliebe ein
  älterer, noch laufender Termin unversorgt.
- Zwei Log-Zeilen für die Diagnose: „Wiedereinstieg erkannt: verdrängte Termine […]" vs.
  „Abgeschnittene Termine ohne Verdrängungs-Nachweis (kein Wiedereinstieg): […]" (= manueller Stop).

### 3. Timing: Stop-Pfade stoßen den Kalender-Lauf sofort an

Ohne das kam der Wiedereinstieg erst beim nächsten 15-Minuten-Takt (Blocker 2 des Reports).
`CalendarAutoRunScheduler.restartNow(...)` ergänzt NACH `live.stop()`:

| Datei | Pfad |
|---|---|
| `DriveWorkers.kt:439` | DriveStopWorker (Google-EXIT) |
| `DriveWorkers.kt:671` | DriveWatchdogWorker (5-Min-Regel, **Haupt-Stop-Pfad**) |
| `WalkingWorkers.kt:213` / `:323` | Walking-Stop + Walking-Watchdog |
| `GeofenceTransitionProcessor.kt:361` | Geofence-Auto-Stop |
| `CurrentZoneProvider.kt:311` | Geofence-Auto-Stop (Direktpfad) |
| `AppTrackingService.kt:418` | App-Tracking-Ende |
| `PingTriggerWorker.kt:81` / `:114` | Ping-Session-Ende |
| `ScreenOffStopWorker.kt:73` | Screen-Auto-Ende |

Alle Aufrufe sind idempotent (REPLACE) und werden vom Worker selbst gegated (Feature-Schalter).

### 4. Datenzugriff

- `ActivitySessionDao.getRecentFinishedBySourceType(sourceType, limit)` — reine Query, **keine
  Schema-Änderung, keine Migration** (Index `source_type+start_at` existiert).
- `ActivitySessionDao.countForeignSessionsStartingBetween(...)` — der Verdrängungs-Beweis.
- Beide über `ActivityRepository` **mit Default-Body** (Bestandsverhalten): die acht Test-Fakes
  bleiben unverändert, nur die Produktion nutzt die echten Queries.
- `LiveActivityManager.recentFinishedSessionsBySourceType(...)` und
  `hasForeignSessionStartingNear(...)` als Zugänge für den Worker.

### 5. Version

`versionCode 17 → 18`, `versionName 1.0.16 → 1.0.17`.

## Verifikation

**Neue Tests** — `CalendarResumeFallbackTest` (14 Tests, echte `LiveActivityManager`/Engine/
MatchEngine gegen Fake-Repositories, Worker-Kern als Spiegel von `doWork()`):

| Test | Prüft |
|---|---|
| `Termin wird nach der Autofahrt fortgesetzt` | **Der gemeldete Fall** — Resume nach Fahrt-Ende, Anker bei JETZT |
| `Termin laeuft waehrend der Fahrt ab - keine Fortsetzung` | Grenze 18:00 exklusiv, kein Resume |
| `mehrere Konflikte hintereinander - jeder Wiedereinstieg greift` | 3 Konflikte (Fahrt/Wanderung/Fahrt), 3 Wiedereinstiege |
| `Kalender-Bloecke ueberlappen nie und werden nie dupliziert` | Block1 endet exakt am Fahrt-Beginn, Block2 ≥, genau 2 Sessions |
| `Doppelstart-Schutz verhindert zweite Session fuer denselben Termin` | `isAlreadyRunningFor` |
| `ohne Verdraengung kein Start mitten im Termin` | Diskriminator „Handy war aus" |
| `Verdraengungs-Evidenz braucht eine zum Termin gehoerende Session` | 5 Negativfälle (fremdes Fenster, vollständig gelaufen, fremde Quelle, …) |
| `nur der abgeschnittene Termin wird wiederaufgenommen` | Überlappende Termine — kein Mitreißen |
| `QUEUE-Termin bleibt ohne Verdraengung nachholbar` | M18.132 unverändert |
| `Wiedereinstieg ankert bei JETZT - kein Rueckdatieren` | `startAnchorMs` alle drei Fälle |
| `kein Wiedereinstieg solange eine fremde Session laeuft` | Flacker-Schutz |
| `manueller Stop wird nicht wiederaufgenommen` | **Menschliche Entscheidung bleibt** (Beweis-Stufe 2) |
| `Verdraengungs-Nachweis trennt Autofahrt von manuellem Stop` | Beweis positiv UND negativ |
| `Kandidat allein genuegt nicht - Marker liefern die Schnittstelle` | Zwei-Stufen-Modell explizit |

**Mutationstest (belegt, dass der Test wirklich greift):** Beweis-Stufe 2 temporär deaktiviert →
`manueller Stop wird nicht wiederaufgenommen` FAILED. Die erste Fassung dieses Tests war blind
(Fake-`stop()` nutzte die reale Wanduhr, der abgeschnittene Block existierte gar nicht); jetzt
prüft er erst die Existenz des Blocks und zieht Enden über `simulatedWallClockMs` auf die
Simulationsachse.

**Bestands-Repro angepasst** — `CalendarConflictResumeReproductionTest` (5 Tests): S1/S2/S3b
kodierten vorher das **Fehlverhalten** (`kein Resume`, `doesNotContain("CalendarAutoRunScheduler")`)
und prüfen jetzt die Soll-Semantik. S1 = gemeldeter Fall mit Resume, S2 = Verdrängung vs.
„nie gestartet", S3b = beide Drive-Stop-Pfade rufen den Scheduler (≥2 Treffer im Quelltext).

**Vollsuite:** `./gradlew :app:testDebugUnitTest --offline --rerun-tasks` → **767 Tests, 0 Failures,
0 Errors, 0 Skipped** (69 Suiten). Kalender/LiveActivity-Nachbarsuiten: 162 → 175 Tests, alle grün.

**Artefakt:** `:app:assembleDebug` erfolgreich. APK `versionCode 18`, `versionName 1.0.17-debug`
(117 MB); im Dex verifiziert: „Wiedereinstieg erkannt" vorhanden, `restartNow` referenziert.

**Grenze der Evidenz:** Kein Emulator verfügbar (kein KVM) — die stärkste Prüfung ist die
JVM-Simulation gegen die echten Produktionsklassen (skill-konformes Muster). Der manuelle
Gerätetest (Termin anlegen → fahren → ankommen) steht beim Nutzer aus.

## Bewusst NICHT gemacht

- **Manueller Stop resumiert nicht** — und zwar nicht durch Auslassen, sondern durch den
  Verdrängungs-Beweis (Stufe 2). `DashboardViewModel.stopLiveActivity`, `switchActivity` und
  `SaveManualActivityUseCase` rufen den Scheduler zusätzlich gar nicht erst an.
- **Fremde Aufzeichnungen werden nie angetastet** — die `foreignRunning`-Policy-Logik in
  `startSession` ist unverändert; der Resume greift nur bei `currentLive == null || !isLive`.
- **Keine Schema-Migration** — beide neuen Queries nutzen bestehende Indizes.
- **Kein UI** — der Auftrag ist Verhalten, nicht Darstellung. Eine Konfiguration gibt es nicht
  („läuft immer, wenn nichts anderes läuft" ist die gewünschte Semantik, kein Schalter).

## Offener Punkt für die Dokumentation (t_72236a9a)

Zu beschreiben: Kalender-Termine sind ein **Fallback mit Wiedereinstieg** — sie laufen, solange
nichts anderes aufzeichnet, und kommen nach jeder automatisch erkannten Aufzeichnung zurück,
solange der Termin noch läuft. Der Wiedereinstieg beginnt bei JETZT (nicht rückdatiert), ein
Termin endet nie vor seiner Zeit, und ein manueller Stop beendet ihn endgültig.

## Dateien

**Geändert (Produktion):**
`domain/calendar/CalendarAutoRunEngine.kt` (Kern) · `automation/calendar/CalendarAutoRunWorker.kt` ·
`domain/liveactivity/LiveActivityManager.kt` · `data/db/ActivitySessionDao.kt` ·
`data/repository/ActivityRepository.kt` · `data/repository/ActivityRepositoryImpl.kt` ·
`automation/activityrecognition/DriveWorkers.kt` · `automation/activityrecognition/WalkingWorkers.kt` ·
`automation/geofence/GeofenceTransitionProcessor.kt` · `automation/geofence/CurrentZoneProvider.kt` ·
`automation/apptracking/AppTrackingService.kt` · `automation/ping/PingTriggerWorker.kt` ·
`automation/screen/ScreenOffStopWorker.kt` · `app/build.gradle.kts`

**Geändert (Tests):** `domain/calendar/CalendarConflictResumeReproductionTest.kt`
**Neu (Tests):** `domain/calendar/CalendarResumeFallbackTest.kt`

## Für den Nachfolger-Auftrag „Dokumentation" (t_72236a9a) — was drüben stehen muss

Nutzer-Sicht: Kalender-Termine sind ein **Fallback mit Wiedereinstieg**.
1. Ein Termin läuft, solange nichts anderes aufzeichnet.
2. Wird er von einer automatisch erkannten Aufzeichnung verdrängt (Fahrt, Wanderung, Geofence,
   App-Tracking, Screen), kommt er danach **zurück** — sobald die andere Aufzeichnung endet und
   der Termin noch läuft.
3. Der Wiedereinstieg beginnt bei der aktuellen Uhrzeit, nicht rückdatiert (die Lücke bleibt
   ehrlich leer; „Zeit doppelt erfassen" gibt es nicht).
4. Ein Termin endet nie vor seiner Zeit.
5. **Ein manueller Stop ist endgültig.** Wer selbst stoppt, pausiert oder die Aktivität wechselt,
   bekommt keinen automatischen Neustart.
6. Es gibt **keine Konfiguration** dafür — das ist die Semantik, kein Schalter. Die bestehenden
   Policies (OVERRIDE / ONLY_IF_IDLE / QUEUE_IF_BUSY) bleiben unverändert und regeln nur, wer
   eine *laufende* fremde Aufzeichnung übernehmen darf.

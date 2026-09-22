# Kalender-Aufzeichnung: kein Resume nach Konflikt (Auto-Fahrt)

Kanban: **t_61143053** · Branch `hermes/t_61143053-calendar-conflict` · HEAD-Basis `8f6d1c5` (M18.133)
Status: **Root Cause belegt** — Repro: `app/src/test/java/.../domain/calendar/CalendarConflictResumeReproductionTest.kt` (5/5 Tests grün, gegen echte Produktionsklassen).

## Symptom (Auftrag)

Kalender-Termin läuft → Auto-Fahrt startet → Kalender-Aufzeichnung wird gestoppt → nach Fahrt-Ende **kein** Resume, obwohl der Termin zeitlich noch aktiv ist.

## Root Cause: zwei unabhängige Blocker

### Blocker 1 — Engine-Regel verbietet den Neustart mitten im Termin

`CalendarAutoRunEngine.shouldStart` (CalendarAutoRunEngine.kt:59-72) verwirft jeden Termin, dessen Beginn mehr als
`START_TOLERANCE_MS = 20 min` (Z. 30, Check Z. 68) zurückliegt. Das Fenster existiert, um verspätete Worker-Läufe
(WorkManager ist inexakt) und „Handy war aus" abzudecken. Es ist aber **blind dafür, dass der Termin vorher bereits
aufgezeichnet wurde und verdrängt wurde** — genau der gemeldete Fall. Folge: Nach 20 Minuten Terminlaufzeit ist ein
Neustart durch den Kalender unmöglich, egal wie oft der Worker läuft.

Einzige Ausnahme ist `shouldStartQueued` (Z. 88-89), erreichbar nur über die Policy `QUEUE_IF_BUSY`:
- Standard-Policy ist `OVERRIDE` (CalendarEventPickerScreen.kt:479-480; CalendarRule.kt:96 `defaultValue = "OVERRIDE"`).
- Regeln können QUEUE in der UI **gar nicht** wählen — der Regel-Editor kennt nur einen Toggle OVERRIDE/ONLY_IF_IDLE
  (CalendarRuleEditorDialog.kt:375-378).
- Das Datenmodell/der Picker kennt QUEUE (CalendarEventPickerScreen.kt:611), es greift also nur bei einzeln
  markierten Terminen und nur, wenn der Nutzer die dritte Option aktiv wählt.

### Blocker 2 — beim Fahrt-Ende wird der Kalender-Worker nicht angestoßen

Der Kalender-Worker ist ein selbst-erneuernder OneTimeWorkRequest mit `DEFAULT_MAX_DELAY_MS = 15 min`
(CalendarAutoRunWorker.kt:333; Reschedule nur am Ende des eigenen Laufs, Z. 150-153). Es gibt **keinen**
session-lifecycle-Hook: kein Codepfad reagiert darauf, dass eine fremde Aufzeichnung endet.

Nach dem Drive-Stop schedulen `DriveStopWorker` und `DriveWatchdogWorker` ausschließlich
`DriveEndGeofenceRestarter` (DriveWorkers.kt:439 und 671) — der kennt nur Geofences
(DriveEndGeofenceRestarter.kt:60-103) und startet nur eine **Geofence**-Session. Der Kalender-Worker wird an keiner
Stelle angestoßen. Selbst mit korrekter Engine-Regel käme der Resume also bis zu 15 Minuten zu spät; bei einem
Termin, der in diesem Fenster endet, gar nicht (der Stop-Watchdog greift ja korrekt).

### Interaktion: wer wen abschneidet

Der Drive-Start ruft `LiveActivityManager.start(..., startedAt = Cluster-Start)` (DriveWorkers.kt:267-272).
`start()` trimmt die laufende Kalender-Session über `trimOverlappingForNewSession`
(LiveActivityManager.kt:202-205, 337-370) + `LiveActivityOverlapResolver.resolve` (LiveActivityOverlapResolver.kt:48-123)
exakt bis zum neuen Start und setzt sie auf FINISHED. Wegen der rückdatierten Cluster-Startzeit (bis 15 Min zurück)
endet der Kalender-Block **vor** dem realen Fahrt-Beginn; nach dem Fahrt-Ende steht dort nichts mehr. Kein Overlap,
kein Datenverlust am Block selbst — aber der Rest des Termins bleibt leer.

## Repro (belegt)

`CalendarConflictResumeReproductionTest` — echte `LiveActivityManager`/`CalendarAutoRunEngine`/`CalendarMatchEngine`,
Worker-Kern als Spiegel von `doWork()`, feste Zeitachse, Termin 15:00-18:00:

| Szenario | Erwartung | Ergebnis |
|---|---|---|
| S1 | Fahrt 15:40-16:05, Lauf 16:05 → **kein** Resume (gemeldeter Fall) | reproduziert (grün) |
| S2 | Resume nur, wenn Fahrt-Ende **innerhalb** der 20-Min-Toleranz liegt (15:15 ja, 15:25 nein) | reproduziert |
| S3 | `QUEUE_IF_BUSY` resumed mitten im Termin (16:05, Startzeit = jetzt) — der Engine-Pfad heilt | reproduziert |
| S3b | Quelltext-Scan: `DriveWorkers.kt` enthält `DriveEndGeofenceRestarter.schedule`, aber **kein** `CalendarAutoRunScheduler` | reproduziert |
| S4 | Termin endet während der Fahrt → korrekt kein Resume (auch kein QUEUE-Nachholer) | reproduziert |

Nachbarsuiten unverändert grün: calendar.* und liveactivity.* (162 Tests, 0 Failures).

## Empfohlene Integration (für t_0bf5541e)

**A. Fallback-Semantik in der Engine (Kern).**
Resume braucht ein eigenes Prädikat neben `shouldStart`/`shouldStartQueued`, z. B.
`shouldResumeDisplaced(event, now, wasDisplaced) = now in [startAt, endAt) && wasDisplaced`.
`wasDisplaced` ist die fehlende Evidenz, die „verdrängt" von „Handy war aus" trennt:
letzte beendete `CALENDAR_AUTO`-Session (`ActivitySessionDao.getLastFinishedBySourceType`, Zeile 35-36 — existiert
schon; Vorbild `LiveActivityManager.lastAutoSessionEndMs`, Z. 319-321), deren `startAt` im Terminfenster liegt
(`findRelatedMatch`, Z. 127-149) **und** deren `endAt < event.endAt` → dieser Termin wurde abgeschnitten, nicht
beendet. Nur dann darf die 20-Min-Toleranz fallen.
Alternativ (einfacher, gröber): `QUEUE_IF_BUSY` als Default für Kalender-Matches — entspricht wörtlich der
Nutzer-Regel „läuft immer, wenn nichts anderes läuft", lässt aber auch einen 8-h-Termin beim Einschalten des Handys
mittendrin starten. Trade-off bewusst entscheiden.

**B. Trigger beim Konflikt-Ende (Timing).**
`CalendarAutoRunScheduler.restartNow(context)` (`CalendarAutoRunScheduler.kt:52-54`, sofortiger Lauf, REPLACE) in
allen Session-Stop-Pfaden ergänzen, mindestens: DriveWorkers.kt:439 (Google-EXIT) und :671 (Watchdog), sinnvoll auch
WalkingWorkers.kt:213/:323. Reihenfolge egal (REPLACE), aber **nach** `live.stop()` setzen.

**C. Fallback-Garantie ohne Overlap.**
Der Resume-Zweig darf nur greifen, wenn derzeit **keine** Live-Session existiert
(`currentLive == null || !currentLive.isLive`) — sonst würde er gegen die Fahrt kämpfen (die `foreignRunning`-Logik in
`startSession`, Z. 222-241, ist bewusst OVERRIDE/QUEUE-gesteuert und darf für den Resume nicht angefasst werden).
Zweite Verteidigungslinie bleibt das M18.71-Trimming in `start()`: es garantiert auch bei Race keine Überlappung.
Doppelstart-Schutz greift automatisch, weil die Resume-Session mit `startAt = jetzt` im Terminfenster liegt und
`isAlreadyRunningFor` (Z. 305-320) sie dann korrekt diesem Termin zuordnet.

**D. Startzeit des Resumes.**
M18.132-Konvention (Z. 264-277) ist „Nachholer startet bei JETZT, nicht rückdatiert" — ehrlich, lässt aber die Lücke
[Fahrt-Ende, Worker-Lauf] leer. Da das Fahrt-Ende eine **harte** Evidenzgrenze ist, wäre ein Anker auf
`lastForeignSessionEnd` (bzw. `max(fremdes Ende, jetzt - 1 min)`) datenqualitativ besser. Entscheidung dem
Implementierer überlassen; im Report nur als Option markiert.

**E. Risiko Flackern.**
Drive-Stop → Resume → Drive-Restart könnte pumpen. Der 3-Min-Restart-Cooldown der Fahrt (M18.84,
`isWithinDriveRestartCooldown`) dämpft das bereits; bei Bedarf Drossel nach dem Vorbild `GeofenceRestartThrottle`
(M18.121) wiederverwenden.

## Wichtige Dateien

- `automation/calendar/CalendarAutoRunWorker.kt` (doWork Z. 67-154, stopFinishedSession Z. 172-204, startSession Z. 207-292, isAlreadyRunningFor Z. 305-320)
- `domain/calendar/CalendarAutoRunEngine.kt` (START_TOLERANCE 30, shouldStart 59, shouldStartQueued 88, findRelatedMatch 127, shouldStop 163, pickStartCandidate 226)
- `domain/liveactivity/LiveActivityManager.kt` (start 184, trim 202/337, stop 291, lastAutoSessionEndMs 319)
- `domain/liveactivity/LiveActivityOverlapResolver.kt` (resolve 48)
- `automation/activityrecognition/DriveWorkers.kt` (Drive-Start 267, Stop 409/439, Watchdog-Stop 650/671)
- `automation/calendar/CalendarAutoRunScheduler.kt` (scheduleNext 33, restartNow 52)
- `data/db/ActivitySessionDao.kt` (getLastFinishedBySourceType 35)
- `ui/screens/calendar/CalendarEventPickerScreen.kt` (Default OVERRIDE 479), `CalendarRuleEditorDialog.kt` (377)

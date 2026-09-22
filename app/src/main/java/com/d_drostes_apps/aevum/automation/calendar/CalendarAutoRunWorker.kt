package com.d_drostes_apps.aevum.automation.calendar

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.data.repository.CalendarEventPinRepository
import com.d_drostes_apps.aevum.data.repository.CalendarRepository
import com.d_drostes_apps.aevum.domain.calendar.CalendarAutoRunEngine
import com.d_drostes_apps.aevum.domain.calendar.CalendarMatchEngine
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * M18.129: Startet und stoppt Aufzeichnungen an Kalender-Termingrenzen.
 *
 * ═════════════════════════════════════════════════════════════════════
 * DESIGN (siehe docs/M18-129-CALENDAR-DESIGN.md § 2.6)
 *
 * Der Worker läuft als EINER, selbst-erneuernder OneTimeWorkRequest —
 * NICHT als PeriodicWorkRequest. Grund: WorkManager verbietet Perioden
 * unter 15 Minuten mit einer IllegalArgumentException, und genau dieser
 * Fehler hat den Ping-Trigger monatelang stillgelegt (M18.62-FIX).
 * Selbst-Erneuerung erlaubt kurze Takte UND den gezielten Weckruf auf
 * die nächste Termingrenze.
 *
 * Der Worker liest AUSSCHLIESSLICH den lokalen Cache — der ContentResolver
 * wird nie angefasst (das macht der SyncWorker selten). Ein Lauf ist damit
 * eine indizierte Room-Query, kein IO.
 *
 * ── SCHUTZ VOR KOLLISIONEN ──
 * Aevum hat fünf konkurrierende Auto-Quellen (Geofence, Fahrt, Wanderung,
 * App-Tracking, Bildschirm). Der Kalender darf keine fremde Session
 * zerstören:
 *   - Beim Stop wird NUR eine Session mit sourceType == "CALENDAR_AUTO"
 *     beendet, die dieser Worker selbst gestartet hat (Muster von
 *     AppTrackingService).
 *   - Beim Start entscheidet die Regel: OVERRIDE (Standard) oder
 *     ONLY_IF_IDLE (konservativ).
 *
 * ── WATCHDOG ──
 * Läuft eine CALENDAR_AUTO-Session, wird IMMER geprüft, ob ihr Termin
 * vorbei ist — auch wenn kein neuer Termin ansteht. Ohne diesen Zweig
 * bliebe eine Session ewig laufen, wenn der Stop-Lauf verpasst wurde
 * (M18.61e: „Auto-Start impliziert Auto-Stop").
 */
class CalendarAutoRunWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun automationSettingsDao(): AutomationSettingsDao
        fun calendarRepository(): CalendarRepository
        fun liveActivityManager(): LiveActivityManager
        /** M18.131: manuell markierte Einzel-Termine. */
        fun calendarEventPinRepository(): CalendarEventPinRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val settings = try {
            deps.automationSettingsDao().getSettingsSync()
        } catch (e: Exception) {
            Log.e(TAG, "Settings nicht lesbar", e)
            return Result.success()
        }

        // ── GATE 1: Auto-Aufzeichnung aktiviert? ──────────────────────
        // Bewusst getrennt vom Sync-Schalter: der Nutzer kann den Kalender
        // nur LESEN (Vorschau) und trotzdem nicht aufzeichnen lassen.
        if (settings?.calendarAutoTrackingEnabled != true || settings.calendarSyncEnabled != true) {
            Log.d(TAG, "Kalender-Auto-Aufzeichnung deaktiviert — kein Reschedule")
            return Result.success()
        }

        val repo = deps.calendarRepository()
        val live = deps.liveActivityManager()
        val pinRepo = deps.calendarEventPinRepository()

        // ── GATE 2: Gibt es überhaupt etwas zu tun? ──────────────────
        // M18.131-ANPASSUNG: Früher brach der Lauf ab, wenn keine REGEL
        // existierte. Jetzt genügt eine manuell markierte Aufzeichnung —
        // sonst würde ein einzelner markierter Termin nie starten, solange
        // keine Regel angelegt ist (der häufigste Anwendungsfall des
        // Features: „ich will nur diesen einen Termin, keine Regel").
        val rules = try {
            repo.getEnabledRulesOnce()
        } catch (e: Exception) {
            Log.e(TAG, "Regeln nicht lesbar", e)
            return reschedule(applicationContext, DEFAULT_MAX_DELAY_MS)
        }

        // ── Termine aus dem CACHE (kein ContentResolver) ─────────────
        val now = System.currentTimeMillis()
        val horizonEnd = now + CACHE_HORIZON_MS
        val events = try {
            repo.getEventsInWindowOnce(now - CACHE_LOOKBACK_MS, horizonEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Cache nicht lesbar", e)
            return reschedule(applicationContext, DEFAULT_MAX_DELAY_MS)
        }

        // M18.131: Markierungen aus demselben Fenster. Ein Fehler hier darf
        // den Lauf nicht sprengen — dann greifen eben nur die Regeln.
        val pins = try {
            pinRepo.getInWindowOnce(now - CACHE_LOOKBACK_MS, horizonEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Markierte Termine nicht lesbar", e)
            emptyList()
        }

        if (rules.isEmpty() && pins.isEmpty()) {
            Log.d(TAG, "Keine aktiven Regeln und keine markierten Termine — kein Reschedule")
            return Result.success()
        }

        // EINE Auflösung für beide Quellen (Markierung schlägt Regel).
        val matches = CalendarMatchEngine.evaluateWithPins(rules, pins, events)

        // ── SCHRITT 1: Laufende Kalender-Session ggf. stoppen ────────
        // WICHTIG: VOR dem Start prüfen — sonst würde ein gerade
        // abgelaufener Termin den neuen Start sofort wieder abräumen.
        stopFinishedSession(live, matches, now)

        // ── SCHRITT 2: Fälligen Termin starten ───────────────────────
        val currentLive = live.liveSession.value

        // M18.134: Wiedereinstiegs-Evidenz — wurde ein Termin von einer
        // fremden Aufzeichnung abgeschnitten? Nur dann darf die
        // 20-Minuten-Start-Toleranz fallen (siehe displacedEventIds).
        val displacedEventIds = resumeEvidence(live, matches, currentLive)

        // Doppelstart-Schutz: Läuft bereits eine Kalender-Session, die
        // zu DIESEM Termin gehört (ihre Startzeit liegt in seinem
        // Fenster, siehe findRelatedMatch), ist nichts zu tun.
        val candidate = CalendarAutoRunEngine.pickStartCandidate(
            matches = matches,
            now = now,
            lastStartedEventId = null,
            displacedEventIds = displacedEventIds
        )?.takeIf { c -> !isAlreadyRunningFor(currentLive, c, matches) }

        if (candidate != null) {
            startSession(live, candidate, currentLive, now, displacedEventIds)
        }

        // ── SCHRITT 3: Nächsten Lauf auf die nächste Termingrenze legen
        return reschedule(
            applicationContext,
            CalendarAutoRunEngine.nextWakeUpDelayMs(events, System.currentTimeMillis(), DEFAULT_MAX_DELAY_MS)
        )
    }

    /**
     * Stoppt die eigene Kalender-Session, wenn ihr Termin vorbei ist.
     *
     * M18.132: Der zugehörige Termin wird über DIESEIN-Funktion
     * gefunden — die Session-Startzeit muss im [startAt, endAt]-Fenster
     * des Termins liegen (plus Typ). Die BIS M18.131 genutzte Suche über
     * Typ/Titel hatte einen echten Bug: Gehören zwei Terminen dieselbe
     * Aktivität (z. B. „Soziales" für Großeltern 15–18 und Kino 20–22),
     * ordnete `firstOrNull` die laufende Session dem ERSTEN Treffer zu
     * und stoppte sie am Ende des FALSCHEN Termins (12:00 statt 18:00).
     *
     * Fallback (Watchdog): Findet sich kein passender Termin mehr (z. B.
     * Termin wurde im Kalender gelöscht), wird anhand der Session-Laufzeit
     * entschieden — läuft sie länger als das Längen-Limit ohne
     * Terminbezug, wird sie ebenfalls beendet.
     */
    private suspend fun stopFinishedSession(
        live: LiveActivityManager,
        matches: List<com.d_drostes_apps.aevum.domain.calendar.CalendarMatch>,
        now: Long
    ) {
        val session = live.liveSession.value ?: return
        // Nur eigene Sessions — fremde Automatiken werden nie angefasst.
        if (session.sourceType != SOURCE_CALENDAR) return

        val relatedMatch = CalendarAutoRunEngine.findRelatedMatch(
            matches = matches,
            sessionStartAt = session.startAt,
            activityTypeId = session.activityTypeId
        )

        val shouldStop = if (relatedMatch != null) {
            CalendarAutoRunEngine.shouldStop(relatedMatch.event, now)
        } else {
            // Watchdog: Session ohne auffindbaren Termin (gelöscht/verschoben).
            // Läuft sie länger als das Maximum, wird sie beendet.
            now - session.startAt > ORPHAN_MAX_DURATION_MS
        }

        if (shouldStop) {
            try {
                live.stop()
                LiveActivityService.stop(applicationContext)
                Log.i(TAG, "Kalender-Session gestoppt: ${session.id} (Termin vorbei)")
            } catch (e: Exception) {
                Log.e(TAG, "Stop fehlgeschlagen", e)
            }
        }
    }

    /**
     * M18.134: Wiedereinstiegs-Evidenz — die `eventId`s der Termine, deren
     * Kalender-Session nachweislich von einer FREMDEN Aufzeichnung
     * abgeschnitten wurde (oder leer).
     *
     * ZWEI STUFEN, und die zweite ist der eigentliche Trick:
     *  1. Ein Kandidat muss eine abgeschnittene Kalender-Session haben
     *     ([CalendarAutoRunEngine.displacedMarkers]).
     *  2. Für diesen Schnittpunkt muss eine FREMDE Session existieren, die
     *     genau dort begann ([witnessesDisplacement]).
     *
     * Ohne Stufe 2 würde JEDER abgeschnittene Block eine Wiederaufnahme
     * auslösen — auch wenn der NUTZER selbst gestoppt, pausiert oder die
     * Aktivität gewechselt hat. Ein manueller Stop ist eine Entscheidung;
     * sie darf nicht 15 Minuten später von einem Worker umgedreht werden.
     * Der Auto-Trim schreibt dagegen das Ende der verdrängten Session exakt
     * auf die Startzeit der übernehmenden (M18.71) — dieser Fingerabdruck
     * ist der Beweis.
     *
     * Diese Funktion ist der EINZIGE Ort, an dem die Evidenz beschafft wird.
     * Sie liefert absichtlich leer, wenn gerade eine Session läuft: der
     * Wiedereinstieg ist ein FALLBACK („läuft, wenn nichts anderes läuft").
     *
     * Fehler dürfen den Lauf nicht sprengen: Ist eine Query nicht lesbar,
     * bleibt es beim Bestandsverhalten (kein Wiedereinstieg).
     */
    private suspend fun resumeEvidence(
        live: LiveActivityManager,
        matches: List<com.d_drostes_apps.aevum.domain.calendar.CalendarMatch>,
        currentLive: com.d_drostes_apps.aevum.data.model.ActivitySession?
    ): List<String> {
        if (currentLive != null && currentLive.isLive) return emptyList()
        val recent = try {
            live.recentFinishedSessionsBySourceType(
                CalendarAutoRunEngine.SOURCE_CALENDAR,
                RESUME_EVIDENCE_LOOKBACK
            )
        } catch (e: Exception) {
            Log.e(TAG, "Resume-Evidenz nicht lesbar", e)
            emptyList()
        }
        val markers = CalendarAutoRunEngine.displacedMarkers(matches, recent)
        if (markers.isEmpty()) return emptyList()

        val witnessed = markers.filter { marker ->
            try {
                live.hasForeignSessionStartingNear(
                    calendarSource = CalendarAutoRunEngine.SOURCE_CALENDAR,
                    atMs = marker.cutAtMs,
                    toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
                )
            } catch (e: Exception) {
                Log.e(TAG, "Verdrängungs-Nachweis fehlgeschlagen", e)
                false
            }
        }
        if (witnessed.isNotEmpty()) {
            Log.i(TAG, "Wiedereinstieg erkannt: verdrängte Termine ${witnessed.map { it.eventId }}")
        } else if (markers.isNotEmpty()) {
            // Wichtig fürs Debugging: abgeschnitten, aber ohne Fremd-Session
            // an der Schnittstelle → wahrscheinlich ein manueller Stop.
            Log.i(TAG, "Abgeschnittene Termine ohne Verdrängungs-Nachweis (kein Wiedereinstieg): ${markers.map { it.eventId }}")
        }
        return witnessed.map { it.eventId }
    }

    /** Startet die Session für den fälligen Termin. */
    private suspend fun startSession(
        live: LiveActivityManager,
        candidate: com.d_drostes_apps.aevum.domain.calendar.CalendarMatch,
        currentLive: com.d_drostes_apps.aevum.data.model.ActivitySession?,
        now: Long,
        displacedEventIds: Collection<String> = emptyList()
    ) {
        val typeId = candidate.activityTypeId ?: run {
            // M18.131: Gilt für beide Quellen — eine Regel mit gelöschter
            // Aktivität (ON DELETE SET NULL) genauso wie eine Markierung,
            // deren Aktivität entfernt wurde. Beide werden übersprungen
            // statt zu crashen (M18.51-Muster).
            Log.w(TAG, "Keine Aktivität zugeordnet (Regel oder Markierung) — übersprungen")
            return
        }

        val foreignRunning = currentLive != null &&
            currentLive.isLive &&
            currentLive.sourceType != SOURCE_CALENDAR

        if (foreignRunning) {
            // M18.132: QUEUE_IF_BUSY — der Termin wartet, bis die fremde
            // Session endet (der Worker läuft ja im 15-Min-Takt wieder
            // und pickStartCandidate holt ihn nach, solange der Termin
            // läuft). Fremde Sessions werden NIE angetastet.
            if (candidate.shouldQueueWhenBusy) {
                Log.d(TAG, "Fremde Session läuft (${currentLive?.sourceType}) — QUEUE-Termin '${candidate.event.title}' wartet")
                return
            }
            // M18.129: OVERRIDE/ONLY_IF_IDLE-Verhalten unverändert.
            if (!candidate.shouldOverrideRunning) {
                Log.d(TAG, "Fremde Session läuft (${currentLive?.sourceType}) — Regel ist ONLY_IF_IDLE, kein Start")
                return
            }
            Log.i(TAG, "Fremde Session läuft (${currentLive?.sourceType}) — OVERRIDE beendet sie zum Termin-Beginn")
        }

        // Auch die eigene Session nicht doppelt starten. Ausnahme
        // (M18.132): Der Kandidat ist ein ANDERER Termin als der, für den
        // die eigene Session läuft (isAlreadyRunningFor hat ihn durch-
        // gelassen), und seine Policy sagt OVERRIDE — dann übernimmt der
        // neue Termin. Beim Start trimmt LiveActivityManager die laufende
        // Session ohnehin bis zur neuen Startzeit zurück (M18.71), also
        // ist die Reihenfolge Stop→Start durch dasselbe System garantiert.
        if (currentLive != null && currentLive.isLive && currentLive.sourceType == SOURCE_CALENDAR) {
            if (!candidate.shouldOverrideRunning) {
                Log.d(TAG, "Eigene Kalender-Session läuft bereits — kein Doppelstart")
                return
            }
            Log.i(TAG, "Eigene Kalender-Session läuft für anderen Termin — OVERRIDE wechselt zum neuen Termin '${candidate.event.title}'")
        }

        try {
            // startedAt = der ECHTE Termin-Beginn, nicht "jetzt": der
            // Worker läuft evtl. ein paar Minuten verspätet, und der
            // Nutzer erwartet den Block ab 10:15 (M18.70-Muster:
            // rückwirkende Startzeit bei Vorlauf).
            //
            // M18.132-AUSNAHME: Ein QUEUE-Nachholer, der erst nach Ablauf
            // der regulären Start-Toleranz drankommt, startet bei JETZT —
            // nicht rückwirkend zum Termin-Beginn. Grund: in der Zeit vor
            // dem Freiwerden lief eine andere Aufzeichnung; eine rückwirkende
            // Startzeit würde die eigene Timeline belügen (Zeit doppelt
            // erfasst). Ehrlichkeit der Daten > optische Termin-Treue.
            //
            // M18.134: Dasselbe gilt für den WIEDEREINSTIEG nach einer
            // abgeschnittenen Session (startAnchorMs) — in der Lücke lief
            // die fremde Aufzeichnung, eine Rückdatierung würde sie
            // überlappen. Beide Anker-Regeln liegen in der Engine, damit
            // sie testbar sind und nicht an zwei Stellen driften.
            val displaced = candidate.event.eventId in displacedEventIds
            val sessionStart = CalendarAutoRunEngine.startAnchorMs(
                match = candidate,
                now = now,
                displaced = displaced
            )
            val session = live.start(
                activityTypeId = typeId,
                title = candidate.sessionTitle,
                note = null,
                sourceType = SOURCE_CALENDAR,
                startedAt = sessionStart
            )
            // Foreground-Service: der Timer muss im Hintergrund laufen
            // (Muster aller Auto-Quellen).
            LiveActivityService.start(applicationContext)
            Log.i(TAG, "Kalender-Auto-Start: '${candidate.event.title}' → Session ${session.id} (Typ $typeId)")
        } catch (e: Exception) {
            Log.e(TAG, "Auto-Start fehlgeschlagen", e)
        }
    }

    /**
     * Doppelstart-Schutz: Läuft bereits eine Kalender-Session, die zu
     * DIESEM Termin gehört?
     *
     * M18.132: DIESELBE Zuordnungsregel wie der Stop-Pfad — die Session-
     * Startzeit liegt im Terminfenster des Kandidaten ([findRelatedMatch]).
     * Das ersetzt die alte Heuristik (Typ gleich UND |start-Abstand| < 5
     * min): Letztere hätte einen QUEUE-Nachholer (gestartet zur
     * Freiwerdezeit, z. B. 16:30 statt 15:00) fälschlich als NEUEN
     * Termin eingestuft und doppelt gestartet.
     */
    private fun isAlreadyRunningFor(
        currentLive: com.d_drostes_apps.aevum.data.model.ActivitySession?,
        candidate: com.d_drostes_apps.aevum.domain.calendar.CalendarMatch,
        matches: List<com.d_drostes_apps.aevum.domain.calendar.CalendarMatch>
    ): Boolean {
        if (currentLive == null || !currentLive.isLive) return false
        if (currentLive.sourceType != SOURCE_CALENDAR) return false
        // Gehört die laufende Session zu DIESEM Kandidaten? Dann ist der
        // Kandidat bereits bedient. (Ein ANDERER, ebenfalls fälliger
        // Termin fällt hier nicht durch — für ihn ist das Ergebnis false,
        // und die OVERRIDE-Logik in startSession regelt den Wechsel.)
        val related = CalendarAutoRunEngine.findRelatedMatch(
            matches, currentLive.startAt, currentLive.activityTypeId
        )
        return related != null && related.event.eventId == candidate.event.eventId
    }

    /** Plant den nächsten Lauf (Selbst-Erneuerung). */
    private fun reschedule(context: Context, delayMs: Long): Result {
        CalendarAutoRunScheduler.scheduleNext(context, delayMs)
        return Result.success()
    }

    companion object {
        private const val TAG = "CalendarAutoRunWorker"
        const val WORK_NAME = "aevum_calendar_autorun"

        /** Obergrenze des Takts (WorkManager-freundlich, 15 min). */
        const val DEFAULT_MAX_DELAY_MS = 15L * 60 * 1000

        /**
         * Rückblick-Fenster für die Termin-Abfrage. Muss ≥ START_TOLERANCE
         * sein, sonst könnte ein gerade begonnener Termin übersehen werden.
         */
        const val CACHE_LOOKBACK_MS = 60L * 60 * 1000

        /** Vorausschau-Fenster (deckt die nächste Termingrenze ab). */
        const val CACHE_HORIZON_MS = 24L * 60 * 60 * 1000

        /** Not-Stop: Session ohne auffindbaren Termin läuft max. 8 h. */
        const val ORPHAN_MAX_DURATION_MS = 8L * 60 * 60 * 1000

        /**
         * M18.134: Wie viele beendete Kalender-Sessions für die
         * Wiedereinstiegs-Evidenz betrachtet werden.
         *
         * Warum mehr als eine: Bei mehreren Konflikten hintereinander
         * (Fahrt → Wanderung → Fahrt) liegen mehrere abgeschnittene
         * Sessions im Verlauf, und die jüngste kann zu einem bereits
         * beendeten Termin gehören. 8 deckt einen realistischen Tag ab
         * (eine Query über den Index source_type+start_at, kein Scan).
         */
        const val RESUME_EVIDENCE_LOOKBACK = 8

        /**
         * M18.134: SourceType aller vom Kalender gestarteten Sessions.
         *
         * Verweis auf die Engine-Konstante — die Evidenz-Logik
         * ([CalendarAutoRunEngine.displacedEventIds]) vergleicht die
         * Quelle, und beide Stellen müssen denselben Wert nutzen.
         */
        const val SOURCE_CALENDAR = CalendarAutoRunEngine.SOURCE_CALENDAR
    }
}

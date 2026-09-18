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

        // Doppelstart-Schutz: Läuft bereits eine Kalender-Session für
        // GENAU diesen Termin, ist nichts zu tun. Der Schutz läuft über
        // die Startzeit der Session, weil die Session selbst die
        // eventId nicht speichert (kein Schema-Eingriff in
        // activity_session nötig): eine laufende Kalender-Session, die
        // zu einem fälligen Termin passt, „konsumiert" ihn.
        val candidate = CalendarAutoRunEngine.pickStartCandidate(
            matches = matches,
            now = now,
            lastStartedEventId = null
        )?.takeIf { c -> !isAlreadyRunningFor(currentLive, c) }

        if (candidate != null) {
            startSession(live, candidate, currentLive)
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
     * Es wird der Termin gesucht, der ZUR LAUFENDEN SESSION gehört (über
     * den Titel/Typ), nicht irgendeiner — sonst könnte ein beliebiger
     * abgelaufener Termin die Session vorzeitig beenden.
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

        val relatedMatch = matches.firstOrNull { m ->
            // M18.131: activityTypeId kommt aus der Match-Quelle (Regel ODER
            // manuelle Markierung) — der direkte Zugriff auf m.rule würde
            // bei einer Markierung mit NPE abstürzen.
            val sameType = m.activityTypeId != null && m.activityTypeId == session.activityTypeId
            val sameTitle = m.sessionTitle != null && m.sessionTitle == session.title
            sameType || sameTitle
        }

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

    /** Startet die Session für den fälligen Termin. */
    private suspend fun startSession(
        live: LiveActivityManager,
        candidate: com.d_drostes_apps.aevum.domain.calendar.CalendarMatch,
        currentLive: com.d_drostes_apps.aevum.data.model.ActivitySession?
    ) {
        val typeId = candidate.activityTypeId ?: run {
            // M18.131: Gilt für beide Quellen — eine Regel mit gelöschter
            // Aktivität (ON DELETE SET NULL) genauso wie eine Markierung,
            // deren Aktivität entfernt wurde. Beide werden übersprungen
            // statt zu crashen (M18.51-Muster).
            Log.w(TAG, "Keine Aktivität zugeordnet (Regel oder Markierung) — übersprungen")
            return
        }

        // Kollisions-Regel: läuft etwas Fremdes und die Regel ist
        // konservativ, wird NICHT gestartet.
        val foreignRunning = currentLive != null &&
            currentLive.isLive &&
            currentLive.sourceType != SOURCE_CALENDAR
        if (foreignRunning && !candidate.shouldOverrideRunning) {
            Log.d(TAG, "Fremde Session läuft (${currentLive?.sourceType}) — Regel ist ONLY_IF_IDLE, kein Start")
            return
        }
        // Auch die eigene Session nicht doppelt starten.
        if (currentLive != null && currentLive.isLive && currentLive.sourceType == SOURCE_CALENDAR) {
            Log.d(TAG, "Eigene Kalender-Session läuft bereits — kein Doppelstart")
            return
        }

        try {
            // startedAt = der ECHTE Termin-Beginn, nicht "jetzt": der
            // Worker läuft evtl. ein paar Minuten verspätet, und der
            // Nutzer erwartet den Block ab 10:15 (M18.70-Muster:
            // rückwirkende Startzeit bei Vorlauf).
            val session = live.start(
                activityTypeId = typeId,
                title = candidate.sessionTitle,
                note = null,
                sourceType = SOURCE_CALENDAR,
                startedAt = candidate.event.startAt
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
     * diesem Termin gehört?
     *
     * Kriterien (beide tolerant, weil die Session keine eventId speichert):
     *  - gleicher ActivityType wie die Regel, UND
     *  - der Session-Start liegt innerhalb des Start-Fensters des Termins
     *    (also nicht früher als der Termin minus Toleranz).
     *
     * Damit wird derselbe Termin beim nächsten Worker-Lauf (≤ 15 min
     * später) nicht erneut gestartet, ohne dass ein Schema-Feld in
     * `activity_session` nötig wäre.
     */
    private fun isAlreadyRunningFor(
        currentLive: com.d_drostes_apps.aevum.data.model.ActivitySession?,
        candidate: com.d_drostes_apps.aevum.domain.calendar.CalendarMatch
    ): Boolean {
        if (currentLive == null || !currentLive.isLive) return false
        if (currentLive.sourceType != SOURCE_CALENDAR) return false
        val typeMatches = candidate.activityTypeId != null &&
            currentLive.activityTypeId == candidate.activityTypeId
        if (!typeMatches) return false
        // Gehört der Session-Start zu DIESEM Termin? (Startzeit der
        // Session wird beim Auto-Start auf den Termin-Beginn gesetzt.)
        val delta = kotlin.math.abs(currentLive.startAt - candidate.event.startAt)
        return delta < 5L * 60 * 1000
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
         * SourceType aller vom Kalender gestarteten Sessions. Wird in
         * AUTO_SOURCES aufgenommen, damit die Timeline sie als
         * automatische Aufzeichnung markiert.
         */
        const val SOURCE_CALENDAR = "CALENDAR_AUTO"
    }
}

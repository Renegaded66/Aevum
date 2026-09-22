package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.model.CalendarRuleType
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * t_61143053: REPRODUKTION des Kalender-Konflikts (Kanban-Symptom:
 * „Kalendereintrag wurde durch die Autofahrt gestoppt und danach NICHT
 * wieder weitergeführt, obwohl der Termin noch lief").
 *
 * Methode (Muster RideRecordingReproductionTest/DriveEndGeofenceRestartFlowTest):
 * ECHTE Produktionsklassen ([LiveActivityManager], [CalendarAutoRunEngine],
 * [CalendarMatchEngine]) gegen Fake-Repositories — kein Emulator nötig.
 * Der Worker-Kern ([calendarWorkerTick]) spiegelt exakt die Reihenfolge aus
 * CalendarAutoRunWorker.doWork() (Stop-Pfad → Kandidat → Start).
 *
 * Befund: Zwei unabhängige Blocker verhindern die Wiederaufnahme:
 *  1. Beim Fahrt-Ende wird der Kalender-Worker NICHT angestoßen — nur
 *     DriveEndGeofenceRestarter (nur Geofences). Nächster Kalender-Lauf:
 *     bis zu 15 Min später.
 *  2. [CalendarAutoRunEngine.shouldStart] verwirft jeden Termin, dessen
 *     Beginn > 20 Min zurückliegt (START_TOLERANCE_MS) — mitten im Termin
 *     ist ein Neustart damit unmöglich. Nur QUEUE_IF_BUSY umgeht das
 *     ([shouldStartQueued]) — die Standard-Policy ist aber OVERRIDE, und
 *     Regeln kennen QUEUE in der UI gar nicht.
 */
class CalendarConflictResumeReproductionTest {

    // ── Festes Zeitmodell: Termin 15:00–18:00 (Europe/Berlin, 19.09.2026) ──
    private val zone = java.time.ZoneId.of("Europe/Berlin")
    private val day = java.time.LocalDate.of(2026, 9, 19)

    private fun at(hour: Int, minute: Int = 0): Long =
        java.time.LocalDateTime.of(day, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private val eventStart = at(15)
    private val eventEnd = at(18)

    // ── Fakes (Muster DriveEndGeofenceRestartFlowTest) ──────────────────

    private class FakeActivityRepository : ActivityRepository {
        val live = MutableStateFlow<ActivitySession?>(null)
        val finished = mutableListOf<Pair<String, Long>>()
        val inserted = mutableListOf<ActivitySession>()

        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        /**
         * M18.134: Echte DAO-Semantik — `WHERE source_type = :t AND
         * session_status = 'FINISHED' AND end_at IS NOT NULL ORDER BY
         * end_at DESC LIMIT 1`. Wichtig für die Resume-Evidenz: die
         * Fakes halten Objekte zum INSERT-Zeitpunkt (endAt == null),
         * spätere Trims stehen nur in [finished] — deshalb wird hier das
         * (id, endAt)-Paar zurück auf die Session gelegt.
         */
        override suspend fun getLastFinishedBySourceType(sourceType: String): ActivitySession? {
            val byId = inserted.associateBy { it.id }
            return finished
                .mapNotNull { (id, endAt) -> byId[id]?.copy(endAt = endAt, sessionStatus = "FINISHED") }
                .filter { it.sourceType == sourceType }
                .maxByOrNull { it.endAt ?: Long.MIN_VALUE }
        }
        /** M18.134: Verlauf statt Einzeleintrag — für die Evidenz bei mehreren Konflikten. */
        override suspend fun getRecentFinishedBySourceType(sourceType: String, limit: Int): List<ActivitySession> {
            val byId = inserted.associateBy { it.id }
            return finished
                .mapNotNull { (id, endAt) -> byId[id]?.copy(endAt = endAt, sessionStatus = "FINISHED") }
                .filter { it.sourceType == sourceType }
                .sortedByDescending { it.endAt ?: Long.MIN_VALUE }
                .take(limit)
        }
        /** M18.134: Verdrängungs-Beweis — Fremd-Session am Schnittpunkt. */
        override suspend fun hasForeignSessionStartingNear(
            calendarSource: String,
            atMs: Long,
            toleranceMs: Long
        ): Boolean = inserted.any {
            it.sourceType != calendarSource &&
                it.startAt in (atMs - toleranceMs)..(atMs + toleranceMs)
        }
        override fun getCurrentActiveSession(): Flow<ActivitySession?> = flowOf(null)
        override fun getLiveSession(): Flow<ActivitySession?> = live
        override suspend fun updateStatus(id: String, status: String) {}
        override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) {}
        override suspend fun pauseSession(id: String, endAt: Long) {}
        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {
            finished.add(id to endAt)
            // Real-DAO-Semantik: session_status = 'FINISHED' → getLiveSession()
            // liefert die Session nicht mehr (Query filtert RUNNING/PAUSED).
            if (live.value?.id == id) live.value = null
        }
        override suspend fun updatePauseData(id: String, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?> = flowOf(null)
        override fun getById(id: String): Flow<ActivitySession?> = flowOf(live.value?.takeIf { it.id == id })
        override fun getByExternalId(externalId: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override suspend fun insert(session: ActivitySession) {
            inserted.add(session)
            if (session.isLive) live.value = session
        }
        override suspend fun insertWithTags(session: ActivitySession, tags: List<com.d_drostes_apps.aevum.data.model.Tag>) {}
        override suspend fun update(session: ActivitySession) {}
        override suspend fun softDelete(id: String, now: Long) {}
        override suspend fun delete(id: String) {}
        override suspend fun setManualQualityOverride(sessionId: String, score: Int?) {}
        override suspend fun setManualQualityOverrideForRange(start: Long, end: Long, score: Int?) {}
        override suspend fun insertTagMapping(mapping: com.d_drostes_apps.aevum.data.model.ActivitySessionTag) {}
        override fun getTagIdsForSession(sessionId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteTagMappings(sessionId: String) {}
        override suspend fun countSessionsByType(typeId: String): Int = 0
        override suspend fun countLiveSessionsByType(typeId: String): Int = 0
        override suspend fun reassignSessionsToType(typeId: String, fallbackTypeId: String, now: Long) {}
        override suspend fun hardDeleteSessionsByType(typeId: String) {}
    }

    private class FakeTypeRepository : ActivityTypeRepository {
        private fun type(id: String, name: String) = ActivityType(
            id = id, name = name, defaultCategoryId = null, isSystem = false,
            propertiesJson = null, positivityScore = 50, icon = "•", color = 0L
        )
        private val all = listOf(type("studium", "Studium"), type("driving", "Autofahren"))

        override fun getById(id: String): Flow<ActivityType?> =
            flowOf(all.firstOrNull { it.id == id })
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(all)
        override fun getFavorites(): Flow<List<ActivityType>> = flowOf(emptyList())
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun setPositivityScore(id: String, score: Int) {}
        override suspend fun setIcon(id: String, icon: String) {}
        override suspend fun setColor(id: String, color: Long) {}
        override suspend fun setCategory(id: String, categoryId: String?) {}
        override suspend fun insert(type: ActivityType) {}
        override suspend fun insertAll(types: List<ActivityType>) {}
        override suspend fun update(type: ActivityType) {}
        override suspend fun delete(typeId: String) {}
    }

    private class FakeTriggerRepository : TriggerEventRepository {
        val inserted = mutableListOf<TriggerEvent>()
        override fun getAll(): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getByGeofenceId(geofenceId: String): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getById(id: String): Flow<TriggerEvent?> = flowOf(null)
        override suspend fun insert(event: TriggerEvent) { inserted.add(event) }
        override suspend fun insertAll(events: List<TriggerEvent>) { inserted.addAll(events) }
        override suspend fun delete(id: String) {}
    }

    private fun event(
        title: String = "Vorlesung",
        id: String = "uni:1:$eventStart"
    ) = CalendarEventCache(
        eventId = id, calendarId = "uni", calendarName = "Uni", title = title,
        description = null, location = null, startAt = eventStart, endAt = eventEnd,
        allDay = false, attendees = null, syncedAt = 0L
    )

    private fun ruleMatch(policy: String = CalendarOverlapPolicy.OVERRIDE) = CalendarMatch(
        event = event(),
        rule = CalendarRule(
            id = "r1", name = "Studium",
            matchType = CalendarRuleType.TITLE_CONTAINS, matchValue = "Vorlesung",
            activityTypeId = "studium", overlapPolicy = policy
        )
    )

    private fun pinMatch(policy: String = CalendarOverlapPolicy.QUEUE_IF_BUSY) = CalendarMatch(
        event = event(),
        pin = CalendarEventPin(
            eventId = event().eventId, activityTypeId = "studium",
            eventStartAt = eventStart, eventEndAt = eventEnd, eventTitle = "Vorlesung",
            overlapPolicy = policy
        )
    )

    private suspend fun awaitLive(manager: LiveActivityManager, id: String?) {
        repeat(200) {
            if (manager.liveSession.value?.id == id) return
            delay(10)
        }
    }

    /**
     * Spiegel von CalendarAutoRunWorker.doWork() (Schritt 1–2):
     * Stop-Pfad → Resume-Evidenz → Kandidat wählen (Doppelstart-Schutz) →
     * starten. Rückgabe: die gestartete Session oder null (= kein Start).
     */
    private suspend fun calendarWorkerTick(
        live: LiveActivityManager,
        matches: List<CalendarMatch>,
        now: Long,
        repo: FakeActivityRepository
    ): ActivitySession? {
        // SCHRITT 1 (stopFinishedSession): nur eigene Sessions.
        val session = live.liveSession.value
        if (session != null && session.sourceType == "CALENDAR_AUTO") {
            val related = CalendarAutoRunEngine.findRelatedMatch(matches, session.startAt, session.activityTypeId)
            val shouldStop = if (related != null) {
                CalendarAutoRunEngine.shouldStop(related.event, now)
            } else {
                now - session.startAt > 8L * 60 * 60 * 1000
            }
            if (shouldStop) {
                live.stop()
                awaitLive(live, null)
            }
        }

        // SCHRITT 2: Resume-Evidenz (resumeEvidence) — nur wenn nichts läuft.
        // Nutzt den ECHTEN LiveActivityManager-Zugang (M18.134) statt einer
        // Test-Kopie: der Datenweg DAO → Manager → Engine ist damit mitgetestet.
        // Zweistufig: (1) abgeschnittener Block, (2) Fremd-Session am Schnitt.
        val currentLive = live.liveSession.value
        val displacedEventIds = if (currentLive != null && currentLive.isLive) {
            emptyList()
        } else {
            CalendarAutoRunEngine.displacedMarkers(
                matches,
                live.recentFinishedSessionsBySourceType(
                    "CALENDAR_AUTO",
                    CalendarAutoRunEngine.RESUME_EVIDENCE_LOOKBACK
                )
            ).filter { marker ->
                live.hasForeignSessionStartingNear(
                    calendarSource = "CALENDAR_AUTO",
                    atMs = marker.cutAtMs,
                    toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
                )
            }.map { it.eventId }
        }

        // SCHRITT 2b: Kandidat + Doppelstart-Schutz (isAlreadyRunningFor).
        val candidate = CalendarAutoRunEngine
            .pickStartCandidate(matches, now, lastStartedEventId = null, displacedEventIds = displacedEventIds)
            ?.takeIf { c ->
                val related = currentLive?.let {
                    CalendarAutoRunEngine.findRelatedMatch(matches, it.startAt, it.activityTypeId)
                }
                !(currentLive != null && currentLive.isLive &&
                    currentLive.sourceType == "CALENDAR_AUTO" &&
                    related != null && related.event.eventId == c.event.eventId)
            } ?: return null

        if (currentLive != null && currentLive.isLive && currentLive.sourceType != "CALENDAR_AUTO" &&
            !candidate.shouldQueueWhenBusy && !candidate.shouldOverrideRunning
        ) return null

        // Startzeit-Regel aus startSession() (M18.132 + M18.134).
        val displaced = candidate.event.eventId in displacedEventIds
        val sessionStart = CalendarAutoRunEngine.startAnchorMs(candidate, now, displaced)
        val started = live.start(
            activityTypeId = candidate.activityTypeId ?: return null,
            title = candidate.sessionTitle,
            sourceType = "CALENDAR_AUTO",
            startedAt = sessionStart
        )
        awaitLive(live, started.id)
        return started
    }

    /** Die Fahrt übernimmt (DriveStartWorker → live.start, rückdatiert auf Cluster-Start). */
    private suspend fun driveStart(live: LiveActivityManager, clusterStart: Long): ActivitySession {
        val s = live.start(
            activityTypeId = "driving", title = "Autofahren",
            sourceType = "ACTIVITY_RECOGNITION_AUTO", startedAt = clusterStart
        )
        awaitLive(live, s.id)
        return s
    }

    // ────────────────────────────────────────────────────────────────────
    // SZENARIO 1 — der gemeldete Fall (OVERRIDE, Standard-Policy)
    // Termin 15:00–18:00; Fahrt 15:40–16:05; Termin läuft noch.
    // M18.134: Der Termin MUSS wieder einsteigen (vorher: kein Resume).
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 Kalender-Session wird vom Fahrt-Start abgeschnitten und nach der Fahrt wieder aufgenommen`() = runTest {
        val repo = FakeActivityRepository()
        val live = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        // 15:00 — Kalender startet (startedAt = Termin-Beginn).
        val cal = calendarWorkerTick(live, listOf(ruleMatch()), eventStart, repo)
        assertThat(cal).isNotNull()
        assertThat(cal!!.sourceType).isEqualTo("CALENDAR_AUTO")
        assertThat(cal.startAt).isEqualTo(eventStart)

        // 15:40 — Autofahrt erkannt (Cluster-Start 15:37): start() trimmt
        // die Kalender-Session exakt bis zum Fahrt-Beginn.
        driveStart(live, clusterStart = at(15, 37))
        assertThat(repo.finished.map { it.first }).containsExactly(cal.id)
        assertThat(repo.finished.first().second).isEqualTo(at(15, 37))

        // 16:05 — Fahrt endet (DriveStopWorker: live.stop()).
        live.stop()
        awaitLive(live, null)

        // Der Stop-Pfad stößt den Kalender-Lauf an (S3b) → Lauf JETZT:
        // Termin läuft noch und wurde nachweislich abgeschnitten → Resume.
        val resumed = calendarWorkerTick(live, listOf(ruleMatch()), at(16, 5), repo)
        assertThat(resumed).isNotNull()
        assertThat(resumed!!.sourceType).isEqualTo("CALENDAR_AUTO")
        assertThat(resumed.activityTypeId).isEqualTo("studium")
        // Ehrlichkeit der Daten: Der Wiedereinstieg startet bei JETZT —
        // in der Lücke lief die Fahrt, eine Rückdatierung würde sie
        // überlappen (M18.134-Anker-Regel).
        assertThat(resumed.startAt).isEqualTo(at(16, 5))
        // Keine Überlappung: der neue Block beginnt nach dem Fahrt-Beginn
        // und nach dem Ende des abgeschnittenen Blocks.
        assertThat(resumed.startAt).isAtLeast(at(15, 37))
        assertThat(repo.inserted.count { it.sourceType == "CALENDAR_AUTO" }).isEqualTo(2)
        // Der Termin ist jetzt bis 18:00 versorgt (Ende kommt vom Stop-Pfad).
        assertThat(live.liveSession.value?.id).isEqualTo(resumed.id)
    }

    // ────────────────────────────────────────────────────────────────────
    // SZENARIO 2 — der Diskriminator: Die 20-Minuten-Toleranz fällt NUR
    // bei nachgewiesener Verdrängung, nicht für einen nie gestarteten
    // Termin („Handy war aus").
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 Toleranz faellt nur bei Verdraengung - sonst kein Resume mitten im Termin`() = runTest {
        // (a) VERDRÄNGT: Termin lief schon, Fahrt schnitt ihn ab.
        val repo = FakeActivityRepository()
        val live = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        calendarWorkerTick(live, listOf(ruleMatch()), eventStart, repo)
        driveStart(live, clusterStart = at(15, 2))
        live.stop()
        awaitLive(live, null)

        // 15:25 — 25 Min nach Termin-Beginn (Toleranz vorbei), aber die
        // Vorgänger-Session endete um 15:02 < 18:00 → abgeschnitten.
        val resumed = calendarWorkerTick(live, listOf(ruleMatch()), at(15, 25), repo)
        assertThat(resumed).isNotNull()
        assertThat(resumed!!.startAt).isEqualTo(at(15, 25))

        // (b) NIE GESTARTET: kein Wiedereinstieg mitten im Termin. Ohne
        // jede Vorgänger-Session ist „mehr als 20 Min nach Beginn" das
        // Signal für „Handy war aus / Termin verpasst" — unverändert
        // kein nachträglicher Start.
        val fresh = FakeActivityRepository()
        val liveFresh = LiveActivityManager(fresh, FakeTypeRepository(), FakeTriggerRepository())
        assertThat(calendarWorkerTick(liveFresh, listOf(ruleMatch()), at(15, 25), fresh)).isNull()
        assertThat(liveFresh.liveSession.value).isNull()
        // Gegenprobe innerhalb der Toleranz: der reguläre Start greift
        // weiterhin (Bestandsverhalten unverändert).
        assertThat(calendarWorkerTick(liveFresh, listOf(ruleMatch()), at(15, 15), fresh)).isNotNull()
    }

    // ────────────────────────────────────────────────────────────────────
    // SZENARIO 3 — QUEUE_IF_BUSY heilt den Engine-Pfad, aber NICHT das
    // fehlende Timing (Fahrt-Ende stößt keinen Lauf an).
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 QUEUE-Policy resumed mitten im Termin - aber erst beim naechsten Lauf`() = runTest {
        val repo = FakeActivityRepository()
        val live = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        val queueMatch = pinMatch(CalendarOverlapPolicy.QUEUE_IF_BUSY)

        val first = calendarWorkerTick(live, listOf(queueMatch), eventStart, repo)
        assertThat(first).isNotNull()
        driveStart(live, clusterStart = at(15, 37))
        live.stop()
        awaitLive(live, null)

        // Ein Lauf JETZT (16:05, Termin läuft) holt den Termin nach —
        // QUEUE umgeht die 20-Min-Toleranz (shouldStartQueued).
        val resumed = calendarWorkerTick(live, listOf(queueMatch), at(16, 5), repo)
        assertThat(resumed).isNotNull()
        assertThat(resumed!!.sourceType).isEqualTo("CALENDAR_AUTO")
        // Ehrlichkeit der Daten: Nachholer startet bei JETZT, nicht rückdatiert.
        assertThat(resumed.startAt).isEqualTo(at(16, 5))

        // Keine Überlappung, kein Duplikat in der DB: die erste Session
        // endet exakt am Fahrt-Beginn, die zweite startet danach.
        assertThat(repo.finished).contains(first!!.id to at(15, 37))
        assertThat(repo.inserted.count { it.sourceType == "CALENDAR_AUTO" }).isEqualTo(2)
        assertThat(resumed.startAt).isAtLeast(at(15, 37))
    }

    @Test
    fun `S3b Beim Fahrt-Ende selbst stoesst der Stop-Pfad den Kalender-Lauf an`() {
        // M18.134: Der Befund aus t_61143053 war, dass DriveStopWorker/
        // DriveWatchdogWorker nach dem Stop nur DriveEndGeofenceRestarter
        // schedulen — der Kalender-Worker wurde nie angestoßen (Resume
        // erst beim nächsten 15-Min-Takt). Der Fix verlangt den Anstoß:
        // beide Stop-Pfade rufen jetzt CalendarAutoRunScheduler.restartNow.
        val worker = java.io.File(
            "src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition/DriveWorkers.kt"
        )
        if (!worker.exists()) return // Test läuft aus dem App-Modul
        val src = worker.readText()
        assertThat(src).contains("DriveEndGeofenceRestarter.schedule")
        assertThat(src).contains("CalendarAutoRunScheduler")
        // Beide Stop-Pfade (Google-EXIT + Watchdog) — nicht nur einer.
        assertThat(src.split("CalendarAutoRunScheduler").size - 1).isAtLeast(2)
    }

    // ────────────────────────────────────────────────────────────────────
    // SZENARIO 4 — Termin endet WÄHREND der Fahrt: kein Resume (korrekt).
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 Termin laeuft waehrend der Fahrt ab - kein Resume`() = runTest {
        val repo = FakeActivityRepository()
        val live = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        calendarWorkerTick(live, listOf(ruleMatch()), eventStart, repo)
        driveStart(live, clusterStart = at(17, 30))
        live.stop()
        awaitLive(live, null)

        // 18:10 — Termin vorbei.
        assertThat(calendarWorkerTick(live, listOf(ruleMatch()), at(18, 10), repo)).isNull()
        // Auch der QUEUE-Nachholer ist dann nicht mehr fällig.
        assertThat(calendarWorkerTick(live, listOf(pinMatch()), at(18, 10), repo)).isNull()
        assertThat(CalendarAutoRunEngine.shouldStartQueued(event(), at(18, 10))).isFalse()
    }
}

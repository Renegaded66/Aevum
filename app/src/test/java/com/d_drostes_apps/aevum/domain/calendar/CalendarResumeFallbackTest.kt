package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M18.134 (Kanban t_0bf5541e): FALLBACK-SEMANTIK der Kalender-Aufzeichnung.
 *
 * Der Auftrag wörtlich: „der Kalendereintrag soll immer laufen, wenn nichts
 * anderes läuft" — inklusive der Wiederaufnahme NACH einem Konflikt (im
 * gemeldeten Fall: Auto-Fahrt mitten im Termin).
 *
 * Abgedeckt (Auftrags-Szenarien wörtlich):
 *  1. Termin läuft → Fahrt startet → Fahrt endet → Termin wird fortgesetzt.
 *  2. Termin läuft während der Fahrt ab → KEINE Fortsetzung.
 *  3. Mehrere Konflikte nacheinander → jeder Wiedereinstieg greift.
 *  4. Keine Überlappung / kein Duplikat — weder zeitlich noch in der DB.
 *
 * Methode: ECHTE Produktionsklassen ([LiveActivityManager], [CalendarAutoRunEngine],
 * [CalendarMatchEngine]) gegen Fake-Repositories — kein Emulator. Der
 * Worker-Kern spiegelt exakt CalendarAutoRunWorker.doWork() (Stop-Pfad →
 * Resume-Evidenz → Kandidat → Start), die Evidenz kommt über den ECHTEN
 * Manager-Zugang (recentFinishedSessionsBySourceType).
 */
class CalendarResumeFallbackTest {

    // ── Festes Zeitmodell: Termin 15:00–18:00 (Europe/Berlin, 19.09.2026) ──
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 9, 19)

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private val eventStart = at(15)
    private val eventEnd = at(18)

    // ── Fakes (Muster DriveEndGeofenceRestartFlowTest / Overlap-Test) ────

    private class FakeActivityRepository : ActivityRepository {
        val live = MutableStateFlow<ActivitySession?>(null)
        val finished = mutableListOf<Pair<String, Long>>()
        val inserted = mutableListOf<ActivitySession>()

        var simulatedWallClockMs: Long? = null

        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())

        /** Echte DAO-Semantik: FINISHED + end_at NOT NULL, jüngstes Ende zuerst. */
        override suspend fun getLastFinishedBySourceType(sourceType: String): ActivitySession? =
            finishedSessions(sourceType).firstOrNull()

        override suspend fun getRecentFinishedBySourceType(sourceType: String, limit: Int): List<ActivitySession> =
            finishedSessions(sourceType).take(limit)

        /**
         * M18.134: Verdrängungs-BEWEIS (echte DAO-Semantik über ALLE
         * Sessions): hat an [atMs] eine Fremd-Session begonnen?
         * Genau das hinterlässt der M18.71-Trim beim Fremd-Start — ein
         * manueller Stop hinterlässt dort nichts.
         */
        override suspend fun hasForeignSessionStartingNear(
            calendarSource: String,
            atMs: Long,
            toleranceMs: Long
        ): Boolean = inserted.any {
            it.sourceType != calendarSource &&
                it.startAt in (atMs - toleranceMs)..(atMs + toleranceMs)
        }

        private fun finishedSessions(sourceType: String): List<ActivitySession> {
            val byId = inserted.associateBy { it.id }
            return finished
                .mapNotNull { (id, endAt) ->
                    byId[id]?.copy(endAt = normalizeEnd(endAt), sessionStatus = "FINISHED")
                }
                .filter { it.sourceType == sourceType }
                .sortedByDescending { it.endAt ?: Long.MIN_VALUE }
        }

        /**
         * Simulations-Uhr für WANDUHR-Enden (siehe [simulatedWallClockMs]).
         *
         * `LiveActivityManager.stop()` setzt `endAt = System.currentTimeMillis()`.
         * In einem Test mit fester Zeitachse (Termin 15–18 Uhr im Jahr 2026)
         * läge das Jahre später — ein „abgeschnittener" Block entstünde nie
         * und die Evidenz-Prüfung wäre blind. Ist die Uhr gesetzt, werden
         * Enden, die NICHT auf der Simulationsachse liegen (also echte
         * Wanduhr-Werte), auf sie gezogen. Der M18.71-Trim übergibt dagegen
         * einen expliziten Zeitpunkt (den Fremd-Start) — der bleibt unberührt.
         */
        fun normalizeEnd(endAt: Long): Long {
            val simulated = simulatedWallClockMs ?: return endAt
            val plausibleWindow = simulated - 2L * 60 * 60 * 1000..simulated + 2L * 60 * 60 * 1000
            return if (endAt in plausibleWindow) endAt else simulated
        }

        override fun getCurrentActiveSession(): Flow<ActivitySession?> = flowOf(null)
        override fun getLiveSession(): Flow<ActivitySession?> = live
        override suspend fun updateStatus(id: String, status: String) {}
        override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) {}
        override suspend fun pauseSession(id: String, endAt: Long) {}

        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {
            finished.add(id to endAt)
            // Live-Query filtert RUNNING/PAUSED → beendete Session ist nicht mehr live.
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
        private val all = listOf(
            type("studium", "Studium"),
            type("driving", "Autofahren"),
            type("walking", "Spazieren")
        )

        override fun getById(id: String): Flow<ActivityType?> = flowOf(all.firstOrNull { it.id == id })
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

    // ── Test-Welt ───────────────────────────────────────────────────────

    private class World {
        val repo = FakeActivityRepository()
        val live = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
    }

    private fun event(
        title: String = "Vorlesung",
        startAt: Long = at(15),
        endAt: Long = at(18),
        id: String = "uni:1:$startAt"
    ) = CalendarEventCache(
        eventId = id, calendarId = "uni", calendarName = "Uni", title = title,
        description = null, location = null, startAt = startAt, endAt = endAt,
        allDay = false, attendees = null, syncedAt = 0L
    )

    /** Termin per Regel erfasst — Standard-Policy OVERRIDE (der gemeldete Fall). */
    private fun ruleMatch(
        startAt: Long = eventStart,
        endAt: Long = eventEnd,
        id: String = "uni:1:$startAt",
        activityTypeId: String = "studium",
        policy: String = CalendarOverlapPolicy.OVERRIDE,
        priority: Int = 0
    ) = CalendarMatch(
        event = event(startAt = startAt, endAt = endAt, id = id),
        rule = CalendarRule(
            id = "r1", name = "Studium",
            matchType = CalendarRuleType.TITLE_CONTAINS, matchValue = "Vorlesung",
            activityTypeId = activityTypeId, overlapPolicy = policy, priority = priority
        )
    )

    private suspend fun awaitLive(manager: LiveActivityManager, id: String?) {
        repeat(200) {
            if (manager.liveSession.value?.id == id) return
            delay(10)
        }
    }

    /**
     * Spiegel von CalendarAutoRunWorker.doWork(): Stop-Pfad →
     * Resume-Evidenz (echter Manager-Zugang) → Kandidat → Start.
     */
    private suspend fun calendarTick(
        world: World,
        matches: List<CalendarMatch>,
        now: Long
    ): ActivitySession? {
        val live = world.live
        // Wanduhr-Endpfade (manueller Stop) auf die Simulationsachse ziehen.
        world.repo.simulatedWallClockMs = now

        // SCHRITT 1 — eigene Session stoppen, wenn ihr Termin vorbei ist.
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

        // SCHRITT 2 — Resume-Evidenz, nur wenn nichts läuft (Fallback-Semantik).
        // Zweistufig wie im Worker: (1) abgeschnittener Block, (2) Fremd-Session
        // genau an der Schnittstelle — der positive Verdrängungs-Beweis.
        val currentLive = live.liveSession.value
        val displaced = if (currentLive != null && currentLive.isLive) {
            emptyList()
        } else {
            CalendarAutoRunEngine.displacedMarkers(
                matches,
                live.recentFinishedSessionsBySourceType(
                    CalendarAutoRunEngine.SOURCE_CALENDAR,
                    CalendarAutoRunEngine.RESUME_EVIDENCE_LOOKBACK
                )
            ).filter { marker ->
                live.hasForeignSessionStartingNear(
                    calendarSource = CalendarAutoRunEngine.SOURCE_CALENDAR,
                    atMs = marker.cutAtMs,
                    toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
                )
            }.map { it.eventId }
        }

        // SCHRITT 3 — Kandidat + Doppelstart-Schutz.
        val candidate = CalendarAutoRunEngine
            .pickStartCandidate(matches, now, lastStartedEventId = null, displacedEventIds = displaced)
            ?.takeIf { c ->
                val related = currentLive?.let {
                    CalendarAutoRunEngine.findRelatedMatch(matches, it.startAt, it.activityTypeId)
                }
                !(currentLive != null && currentLive.isLive &&
                    currentLive.sourceType == "CALENDAR_AUTO" &&
                    related != null && related.event.eventId == c.event.eventId)
            } ?: return null

        // Fremd-Session läuft und die Policy verbietet den Start → nichts tun.
        if (currentLive != null && currentLive.isLive && currentLive.sourceType != "CALENDAR_AUTO" &&
            !candidate.shouldQueueWhenBusy && !candidate.shouldOverrideRunning
        ) return null

        val startAt = CalendarAutoRunEngine.startAnchorMs(
            match = candidate,
            now = now,
            displaced = candidate.event.eventId in displaced
        )
        val started = live.start(
            activityTypeId = candidate.activityTypeId ?: return null,
            title = candidate.sessionTitle,
            sourceType = "CALENDAR_AUTO",
            startedAt = startAt
        )
        awaitLive(live, started.id)
        return started
    }

    /**
     * Fremd-Quelle übernimmt (rückdatiert auf ihren Cluster-Start), wie der
     * Drive-Start: `live.start(...)` trimmt die laufende Kalender-Session
     * exakt bis zum Fremd-Beginn — das ist gleichzeitig die Evidenz, auf die
     * [CalendarAutoRunEngine.displacedMarkers] sein Urteil stützt.
     */
    private suspend fun foreignStart(
        world: World,
        sourceType: String,
        activityTypeId: String,
        clusterStart: Long
    ): ActivitySession {
        val s = world.live.start(
            activityTypeId = activityTypeId, title = "Fremd",
            sourceType = sourceType, startedAt = clusterStart
        )
        awaitLive(world.live, s.id)
        return s
    }

    private suspend fun foreignStop(world: World) {
        world.live.stop()
        awaitLive(world.live, null)
    }

    /**
     * Alle Kalender-Blöcke als (start, end)-Paare, chronologisch — inklusive
     * des AKTUELL laufenden (dessen Ende noch null ist, siehe
     * `FakeActivityRepository.inserted`-Pitfall: die Fakes halten Objekte
     * zum INSERT-Zeitpunkt).
     */
    private fun calendarBlocks(
        repo: FakeActivityRepository,
        live: LiveActivityManager,
        now: Long
    ): List<Pair<Long, Long>> {
        val byId = repo.inserted.associateBy { it.id }
        val finishedBlocks = repo.finished
            .mapNotNull { (id, endAt) ->
                byId[id]?.takeIf { it.sourceType == "CALENDAR_AUTO" }?.let { it.startAt to endAt }
            }
        val liveBlock = live.liveSession.value
            ?.takeIf { it.sourceType == "CALENDAR_AUTO" }
            ?.let { it.startAt to now }
        return (finishedBlocks + listOfNotNull(liveBlock)).sortedBy { it.first }
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. Der gemeldete Fall: Fahrt mitten im Termin → Termin läuft weiter
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Termin wird nach der Autofahrt fortgesetzt`() = runTest {
        val w = World()

        // 15:00 Auto-Start (startedAt = Termin-Beginn).
        val cal = calendarTick(w, listOf(ruleMatch()), eventStart)
        assertThat(cal).isNotNull()
        assertThat(cal!!.startAt).isEqualTo(eventStart)

        // 15:40 Fahrt übernimmt (Cluster-Start 15:37): Termin-Session wird
        // exakt bis zum Fahrt-Beginn gekürzt (LiveActivityManager-Trimming).
        foreignStart(w, "ACTIVITY_RECOGNITION_AUTO", "driving", at(15, 37))
        assertThat(w.repo.finished.first().second).isEqualTo(at(15, 37))

        // 16:05 Fahrt endet → Kalender-Lauf wird angestoßen (Stop-Pfad-Wiring).
        foreignStop(w)
        val resumed = calendarTick(w, listOf(ruleMatch()), at(16, 5))

        assertThat(resumed).isNotNull()
        assertThat(resumed!!.activityTypeId).isEqualTo("studium")
        assertThat(resumed.sourceType).isEqualTo("CALENDAR_AUTO")
        // Wiedereinstieg startet bei JETZT (in der Lücke lief die Fahrt).
        assertThat(resumed.startAt).isEqualTo(at(16, 5))
        assertThat(w.live.liveSession.value?.id).isEqualTo(resumed.id)
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. Termin endet WÄHREND der Fahrt → keine Fortsetzung
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Termin laeuft waehrend der Fahrt ab - keine Fortsetzung`() = runTest {
        val w = World()
        calendarTick(w, listOf(ruleMatch()), eventStart)

        // Fahrt 17:30–18:10 (Termin-Ende 18:00 fällt in die Fahrt).
        foreignStart(w, "ACTIVITY_RECOGNITION_AUTO", "driving", at(17, 30))
        foreignStop(w)

        // Nach dem Termin-Ende ist der Wiedereinstieg nicht mehr fällig.
        assertThat(calendarTick(w, listOf(ruleMatch()), at(18, 10))).isNull()
        assertThat(w.live.liveSession.value).isNull()
        // Und auch das Prädikat selbst sagt Nein (Grenze exklusiv).
        assertThat(
            CalendarAutoRunEngine.shouldResumeAfterDisplacement(event(), at(18), displaced = true)
        ).isFalse()
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. Mehrere Konflikte nacheinander → jeder Wiedereinstieg greift
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `mehrere Konflikte hintereinander - jeder Wiedereinstieg greift`() = runTest {
        val w = World()
        val blocks = mutableListOf<ActivitySession?>()

        blocks += calendarTick(w, listOf(ruleMatch()), eventStart)        // 15:00
        foreignStart(w, "ACTIVITY_RECOGNITION_AUTO", "driving", at(15, 30))
        foreignStop(w)
        blocks += calendarTick(w, listOf(ruleMatch()), at(15, 50))        // 1. Wiedereinstieg

        foreignStart(w, "WALKING_AUTO", "walking", at(16, 10))
        foreignStop(w)
        blocks += calendarTick(w, listOf(ruleMatch()), at(16, 30))        // 2. Wiedereinstieg

        foreignStart(w, "ACTIVITY_RECOGNITION_AUTO", "driving", at(16, 50))
        foreignStop(w)
        blocks += calendarTick(w, listOf(ruleMatch()), at(17, 10))        // 3. Wiedereinstieg

        assertThat(blocks.filterNotNull()).hasSize(4)
        // Jeder Wiedereinstieg startet bei JETZT und nicht rückdatiert.
        assertThat(blocks[1]!!.startAt).isEqualTo(at(15, 50))
        assertThat(blocks[2]!!.startAt).isEqualTo(at(16, 30))
        assertThat(blocks[3]!!.startAt).isEqualTo(at(17, 10))
        // Danach läuft genau EINE Session (kein Doppelstart, kein Rest).
        // Achtung: `inserted` hält die Objekte zum INSERT-Zeitpunkt (alle
        // RUNNING) — die Live-Aussage kommt deshalb aus dem StateFlow.
        assertThat(w.live.liveSession.value?.id).isEqualTo(blocks[3]!!.id)
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. Keine Überlappung und kein Duplikat
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Kalender-Bloecke ueberlappen nie und werden nie dupliziert`() = runTest {
        val w = World()
        calendarTick(w, listOf(ruleMatch()), eventStart)
        foreignStart(w, "ACTIVITY_RECOGNITION_AUTO", "driving", at(15, 37))
        foreignStop(w)
        val resumedAt = at(16, 5)
        calendarTick(w, listOf(ruleMatch()), resumedAt)

        val blocks = calendarBlocks(w.repo, w.live, now = resumedAt)
        assertThat(blocks).hasSize(2)
        // Block 1 endet exakt am Fahrt-Beginn, Block 2 beginnt danach.
        assertThat(blocks[0].second).isEqualTo(at(15, 37))
        assertThat(blocks[1].first).isAtLeast(blocks[0].second)
        assertThat(blocks[1].first).isEqualTo(resumedAt)
        // Kein Doppelstart: genau zwei Kalender-Sessions in der DB, und
        // der Wiedereinstieg ist die einzige laufende.
        assertThat(w.repo.inserted.count { it.sourceType == "CALENDAR_AUTO" }).isEqualTo(2)
        assertThat(w.live.liveSession.value?.startAt).isEqualTo(resumedAt)
        assertThat(w.live.liveSession.value?.sourceType).isEqualTo("CALENDAR_AUTO")
    }

    @Test
    fun `Doppelstart-Schutz verhindert zweite Session fuer denselben Termin`() = runTest {
        val w = World()
        val first = calendarTick(w, listOf(ruleMatch()), eventStart)
        // Zweiter Lauf, ohne dass etwas dazwischenkam: derselbe Termin ist
        // bereits bedient → kein zweiter Block (isAlreadyRunningFor).
        val second = calendarTick(w, listOf(ruleMatch()), at(15, 10))
        assertThat(second).isNull()
        assertThat(w.repo.inserted.count { it.sourceType == "CALENDAR_AUTO" }).isEqualTo(1)
        assertThat(w.live.liveSession.value?.id).isEqualTo(first!!.id)
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. Der Diskriminator: Wiedereinstieg NUR nach Verdrängung
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `ohne Verdraengung kein Start mitten im Termin`() = runTest {
        val w = World()
        // Termin 15:00–18:00, nichts lief vorher. Ein Lauf um 16:05 ist
        // „Handy war aus / Termin verpasst" — unverändert kein Start.
        assertThat(calendarTick(w, listOf(ruleMatch()), at(16, 5))).isNull()
        assertThat(w.live.liveSession.value).isNull()
        assertThat(w.repo.inserted).isEmpty()
    }

    @Test
    fun `Verdraengungs-Evidenz braucht eine zum Termin gehoerende Session`() {
        val e = event()
        // (a) Session eines ANDEREN Termins (Fenster passt nicht) → keine Evidenz.
        assertThat(
            CalendarAutoRunEngine.displacedEventIds(
                listOf(ruleMatch()),
                listOf(session(startAt = at(12), endAt = at(12, 30), typeId = "studium"))
            )
        ).isEmpty()
        // (b) Session lief bis zum Termin-Ende (nicht abgeschnitten) → keine Evidenz.
        assertThat(
            CalendarAutoRunEngine.displacedEventIds(
                listOf(ruleMatch()),
                listOf(session(startAt = eventStart, endAt = eventEnd, typeId = "studium"))
            )
        ).isEmpty()
        // (c) Abgeschnittene Session im Terminfenster → Evidenz.
        assertThat(
            CalendarAutoRunEngine.displacedEventIds(
                listOf(ruleMatch()),
                listOf(session(startAt = eventStart, endAt = at(15, 37), typeId = "studium"))
            )
        ).containsExactly(e.eventId)
        // (d) Fremde Quelle im Fenster ist KEIN Beweis.
        assertThat(
            CalendarAutoRunEngine.displacedEventIds(
                listOf(ruleMatch()),
                listOf(session(startAt = eventStart, endAt = at(15, 37), typeId = "studium", source = "ACTIVITY_RECOGNITION_AUTO"))
            )
        ).isEmpty()
        // (e) Session endet VOR dem Termin-Beginn (anderer Tag/Block) → keine Evidenz.
        assertThat(
            CalendarAutoRunEngine.displacedEventIds(
                listOf(ruleMatch()),
                listOf(session(startAt = at(9), endAt = at(10), typeId = "studium"))
            )
        ).isEmpty()
    }

    @Test
    fun `nur der abgeschnittene Termin wird wiederaufgenommen`() {
        // Überlappende Termine: A (15–18) wurde abgeschnitten, B (16–20)
        // lief nie. B darf NICHT mitgerissen werden.
        val a = ruleMatch(startAt = at(15), endAt = at(18), id = "ev:A", priority = 1)
        val b = ruleMatch(startAt = at(16), endAt = at(20), id = "ev:B", priority = 0)
        val displaced = CalendarAutoRunEngine.displacedEventIds(
            listOf(a, b),
            listOf(session(startAt = at(15), endAt = at(15, 37), typeId = "studium"))
        )
        assertThat(displaced).containsExactly("ev:A")

        // 16:25 — beide Termine laufen; A ist über die Verdrängung fällig,
        // B weder regulär (25 Min > 20-Min-Toleranz) noch über Fallback.
        val now = at(16, 25)
        assertThat(CalendarAutoRunEngine.isFallbackDue(a, now, displaced)).isTrue()
        assertThat(CalendarAutoRunEngine.isFallbackDue(b, now, displaced)).isFalse()
        assertThat(CalendarAutoRunEngine.pickStartCandidate(listOf(b), now, null, displaced)).isNull()
        // Der abgeschnittene Termin gewinnt, wenn beide im Feld sind.
        assertThat(CalendarAutoRunEngine.pickStartCandidate(listOf(a, b), now, null, displaced))
            .isSameInstanceAs(a)
    }

    @Test
    fun `QUEUE-Termin bleibt ohne Verdraengung nachholbar`() {
        // Regressionsschutz: Die bestehende QUEUE-Semitik ist unverändert —
        // auch ohne jede Vorgänger-Session (das war M18.132).
        val queued = ruleMatch(
            startAt = at(15), endAt = at(18), activityTypeId = "studium",
            policy = CalendarOverlapPolicy.QUEUE_IF_BUSY
        )
        val now = at(16, 30)
        assertThat(CalendarAutoRunEngine.isFallbackDue(queued, now, emptyList())).isTrue()
        assertThat(CalendarAutoRunEngine.pickStartCandidate(listOf(queued), now, null, emptyList()))
            .isSameInstanceAs(queued)
        // Gegenprobe: OVERRIDE ohne Verdrängung bleibt unverändert gesperrt.
        assertThat(CalendarAutoRunEngine.isFallbackDue(ruleMatch(), now, emptyList())).isFalse()
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. Anker-Regel + Wache gegen Flackern
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Wiedereinstieg ankert bei JETZT - kein Rueckdatieren`() {
        val m = ruleMatch()
        val now = at(16, 5)
        // Wiedereinstieg → jetzt (sonst Überlappung mit der Fahrt).
        assertThat(CalendarAutoRunEngine.startAnchorMs(m, now, displaced = true)).isEqualTo(now)
        // Regulärer Start → Termin-Beginn (M18.70-Muster).
        assertThat(CalendarAutoRunEngine.startAnchorMs(m, now, displaced = false)).isEqualTo(eventStart)
        // QUEUE-Nachholer jenseits der Toleranz → jetzt (M18.132).
        val queued = ruleMatch(policy = CalendarOverlapPolicy.QUEUE_IF_BUSY)
        assertThat(CalendarAutoRunEngine.startAnchorMs(queued, now, displaced = false)).isEqualTo(now)
    }

    @Test
    fun `kein Wiedereinstieg solange eine fremde Session laeuft`() = runTest {
        val w = World()
        calendarTick(w, listOf(ruleMatch()), eventStart)
        foreignStart(w, "ACTIVITY_RECOGNITION_AUTO", "driving", at(15, 37))

        // Während die Fahrt läuft, darf der Kalender NICHT zurückkommen —
        // sonst Start/Stop-Ping-Pong gegen die laufende Aufzeichnung.
        assertThat(calendarTick(w, listOf(ruleMatch()), at(15, 50))).isNull()
        assertThat(w.live.liveSession.value?.activityTypeId).isEqualTo("driving")
        assertThat(w.repo.inserted.count { it.sourceType == "CALENDAR_AUTO" }).isEqualTo(1)
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. Die Grenze: MANUELLER Stop wird nicht automatisch umgedreht
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `manueller Stop wird nicht wiederaufgenommen`() = runTest {
        val w = World()
        calendarTick(w, listOf(ruleMatch()), eventStart)

        // Der Nutzer stoppt SELBST um 15:30 (Dashboard/Notification).
        // Der Stop setzt endAt = Wanduhr; die Fake-Uhr zieht ihn auf 15:30 —
        // also GENAU dorthin, wo der reguläre Stop-Pfad ihn setzen würde.
        w.repo.simulatedWallClockMs = at(15, 30)
        w.live.stop()
        awaitLive(w.live, null)

        // Vorbedingung: es GIBT einen abgeschnittenen Block (sonst wäre der
        // Test blind — genau dieses Loch hatte die erste Fassung).
        val markers = CalendarAutoRunEngine.displacedMarkers(
            listOf(ruleMatch()),
            w.live.recentFinishedSessionsBySourceType("CALENDAR_AUTO", 8)
        )
        assertThat(markers).hasSize(1)
        assertThat(markers.first().cutAtMs).isEqualTo(at(15, 30))

        // Der Termin läuft weiter, aber an der Schnittstelle begann KEINE
        // Fremd-Session → kein Wiedereinstieg. Ohne diese Grenze würde der
        // nächste Worker-Lauf die Entscheidung des Nutzers stillschweigend
        // umdrehen.
        assertThat(calendarTick(w, listOf(ruleMatch()), at(15, 45))).isNull()
        assertThat(w.live.liveSession.value).isNull()
        assertThat(w.repo.inserted.count { it.sourceType == "CALENDAR_AUTO" }).isEqualTo(1)
    }

    @Test
    fun `Verdraengungs-Nachweis trennt Autofahrt von manuellem Stop`() = runTest {
        // (a) AUTOMATISCH verdrängt: Fremd-Session beginnt exakt am Schnitt.
        val auto = World()
        calendarTick(auto, listOf(ruleMatch()), eventStart)
        foreignStart(auto, "ACTIVITY_RECOGNITION_AUTO", "driving", at(15, 37))
        assertThat(
            auto.live.hasForeignSessionStartingNear(
                "CALENDAR_AUTO", atMs = at(15, 37),
                toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
            )
        ).isTrue()

        // (b) MANUELL gestoppt: an der Schnittstelle (15:30) begann nichts.
        val manual = World()
        calendarTick(manual, listOf(ruleMatch()), eventStart)
        manual.live.stop()
        awaitLive(manual.live, null)
        assertThat(
            manual.live.hasForeignSessionStartingNear(
                "CALENDAR_AUTO", atMs = at(15, 30),
                toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
            )
        ).isFalse()
    }

    @Test
    fun `Kandidat allein genuegt nicht - Marker liefern die Schnittstelle`() {
        // Stufe 1 (Kandidat): abgeschnittener Block → Marker mit Schnittzeit.
        val markers = CalendarAutoRunEngine.displacedMarkers(
            listOf(ruleMatch()),
            listOf(session(startAt = eventStart, endAt = at(15, 37), typeId = "studium"))
        )
        assertThat(markers).hasSize(1)
        assertThat(markers.first().eventId).isEqualTo(event().eventId)
        assertThat(markers.first().cutAtMs).isEqualTo(at(15, 37))
    }

    private fun session(
        startAt: Long,
        endAt: Long,
        typeId: String,
        source: String = "CALENDAR_AUTO"
    ) = ActivitySession(
        id = "s-${startAt}-${source}",
        title = "Studium",
        activityTypeId = typeId,
        startAt = startAt,
        endAt = endAt,
        sourceType = source,
        sessionStatus = "FINISHED"
    )
}

package com.d_drostes_apps.aevum.domain.calendar

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.model.CalendarRuleType
import com.d_drostes_apps.aevum.data.repository.ActivityRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepositoryImpl
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M18.135 (Kanban t_f15f4443): INTEGRATIONSTESTS des Kalender-Wiedereinstiegs
 * gegen die ECHTE Room-Datenbank — die zweite Evidenzstufe.
 *
 * WARUM DIESE SUITE NEBEN CalendarResumeFallbackTest EXISTIERT:
 * Jene Suite (t_0bf5541e, 14 Tests) fährt den Worker-Kern gegen
 * HANDGESCHRIEBENE Repository-Fakes. Damit ist die Engine-Logik bewiesen —
 * aber die beiden SQL-Queries, an denen die Evidenz hängt, werden von den
 * Fakes NACHGEBAUT statt geprüft:
 *
 *   - `getRecentFinishedBySourceType`: WHERE-Klausel (deleted_at IS NULL,
 *     session_status = 'FINISHED', end_at IS NOT NULL), ORDER BY end_at DESC,
 *     LIMIT. Ein Fake, der nur nach `source_type` filtert, bliebe grün,
 *     während die echte Query soft-gelöschte oder laufende Sessions mitzählt.
 *   - `countForeignSessionsStartingBetween`: der Verdrängungs-BEWEIS. Die
 *     BETWEEN-Grenzen entscheiden, ob „Automatik hat übernommen" von „Nutzer
 *     hat selbst gestoppt" getrennt wird.
 *
 * Zusätzlich laufen hier die ECHTEN Produktionsklassen in ihrer echten
 * Verdrahtung ([ActivityRepositoryImpl] → [ActivitySessionDao] → Room,
 * [LiveActivityManager] → Repository) — die Kette
 * Beweis-Query → Engine → Session-Start also einmal durchgehend.
 *
 * ZEITACHSE — der wichtigste Unterschied zum Fake-Test:
 * Die Szenarien liegen um `System.currentTimeMillis()` herum. Grund:
 * `LiveActivityManager.stop()` stempelt `endAt = System.currentTimeMillis()`,
 * und Robolectric kann die Wanduhr NICHT verschieben
 * (`SystemClock.setCurrentTimeMillis` wirkt nur auf Uptime-Clocks).
 * Mit einer festen Kalender-Achse (wie im Fake-Test: 15–18 Uhr am 19.09.2026)
 * läge dieses Ende Jahre daneben — der abgeschnittene Block entstünde nie und
 * die Tests wären BLIND.
 *
 * Daraus folgt die Modell-Regel: der ERSTE Lauf muss innerhalb der
 * 20-Minuten-Start-Toleranz ([CalendarAutoRunEngine.START_TOLERANCE_MS])
 * liegen (sonst greift der reguläre Start bewusst nicht), und
 * Fremd-Automatiken starten rückdatiert VOR „jetzt", während ihr Ende durch
 * den echten Stop auf „jetzt" fällt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarResumeRoomIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var live: LiveActivityManager

    private val MIN = 60_000L
    private val SEC = 1_000L

    /**
     * Granularität der Wanduhr-Stempel, siehe [assertNoOverlap].
     *
     * 50 ms ist bewusst KLEIN gegen die geprüfte Fehlerrichtung (Minuten)
     * und groß genug, dass die Millisekunden-Stempel von `stop()`/
     * `forceFinish` nicht zufällig aus der Reihe fallen. Der Wert ist der
     * einzige Kompromiss dieser Suite und steht deshalb hier oben sichtbar.
     */
    private val WALLCLOCK_STAMP_TOLERANCE_MS = 50L

    /** Simulations-„JETZT" = reale Wanduhr (siehe Klassenkommentar). */
    private fun realNow(): Long = System.currentTimeMillis()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        val repo = ActivityRepositoryImpl(db.activitySessionDao(), db.rawDetectionEventDao())
        val types = ActivityTypeRepositoryImpl(db.activityTypeDao())
        val triggers = TriggerEventRepositoryImpl(db.triggerEventDao())
        live = LiveActivityManager(repo, types, triggers, ApplicationProvider.getApplicationContext())

        // FK-Ziele: die Aktivitaeten, die die Regeln referenzieren.
        // runBlocking (kein runTest): @Before ist keine Coroutine, und die
        // Seeds sind reine Vorbedingung ohne Zeitbezug.
        kotlinx.coroutines.runBlocking {
            db.activityTypeDao().insert(ActivityType(id = "studium", name = "Studium", isSystem = false))
            db.activityTypeDao().insert(ActivityType(id = "driving", name = "Autofahren", isSystem = false))
            db.activityTypeDao().insert(ActivityType(id = "walking", name = "Spazieren", isSystem = false))
        }
    }

    @After
    fun tearDown() = db.close()

    // ── Test-Welt ───────────────────────────────────────────────────────

    private fun event(
        startAt: Long,
        endAt: Long,
        id: String = "uni:1:$startAt"
    ) = CalendarEventCache(
        eventId = id, calendarId = "uni", calendarName = "Uni", title = "Vorlesung",
        description = null, location = null, startAt = startAt, endAt = endAt,
        allDay = false, attendees = null, syncedAt = startAt
    )

    private fun ruleMatch(
        event: CalendarEventCache,
        activityTypeId: String = "studium",
        policy: String = CalendarOverlapPolicy.OVERRIDE
    ) = CalendarMatch(
        event = event,
        rule = CalendarRule(
            id = "r1", name = "Studium", matchType = CalendarRuleType.TITLE_CONTAINS,
            matchValue = "Vorlesung", activityTypeId = activityTypeId,
            overlapPolicy = policy, priority = 0
        )
    )

    /**
     * Auf eine Zustandsänderung der Live-Session warten — mit ECHTER Zeit.
     * `liveSession` ist ein StateFlow aus `stateIn(Dispatchers.IO, Eagerly)`,
     * und die Upstream-Quelle ist ein Room-Flow: beides läuft auf echten
     * Threads. Ein virtuelles `delay` (runTest) wäre hier wertlos — es
     * vergeht keine reale Zeit, der Collector käme nie dran.
     */
    private fun awaitLive(id: String?) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (live.liveSession.value?.id == id) return
            Thread.sleep(5)
        }
        throw AssertionError("liveSession war nicht '$id', sondern ${live.liveSession.value?.id}")
    }

    /** Kalender-Session starten (wie CalendarAutoRunWorker.startSession). */
    private suspend fun startCalendar(match: CalendarMatch, at: Long): ActivitySession {
        val s = live.start(
            activityTypeId = match.activityTypeId!!,
            title = match.sessionTitle,
            sourceType = CalendarAutoRunEngine.SOURCE_CALENDAR,
            startedAt = at
        )
        awaitLive(s.id)
        return s
    }

    /**
     * Fremd-Automatik startet (rückdatiert auf ihren Cluster-Start) — wie der
     * Drive-Start. `LiveActivityManager.start` trimmt die laufende
     * Kalender-Session exakt bis zu diesem Start (M18.71).
     */
    private suspend fun foreignStart(sourceType: String, typeId: String, at: Long): ActivitySession {
        val s = live.start(activityTypeId = typeId, title = "Fremd", sourceType = sourceType, startedAt = at)
        awaitLive(s.id)
        return s
    }

    /** Fremd-Automatik endet (DriveStopWorker/Watchdog → live.stop()). */
    private suspend fun foreignStop() {
        live.stop()
        awaitLive(null)
    }

    /**
     * Spiegel von CalendarAutoRunWorker.doWork(): Stop-Pfad →
     * Resume-Evidenz (ECHTE Queries über LiveActivityManager) →
     * Kandidat → Start. Nur die Reihenfolge ist kopiert; die Evidenz kommt
     * vollständig aus der echten DB.
     */
    private suspend fun calendarTick(matches: List<CalendarMatch>, now: Long): ActivitySession? {
        // SCHRITT 1 — eigene Session stoppen, wenn ihr Termin vorbei ist.
        val session = live.liveSession.value
        if (session != null && session.sourceType == CalendarAutoRunEngine.SOURCE_CALENDAR) {
            val related = CalendarAutoRunEngine.findRelatedMatch(matches, session.startAt, session.activityTypeId)
            val shouldStop = if (related != null) {
                CalendarAutoRunEngine.shouldStop(related.event, now)
            } else {
                now - session.startAt > 8L * 60 * MIN
            }
            if (shouldStop) {
                live.stop()
                awaitLive(null)
            }
        }

        // SCHRITT 2 — Resume-Evidenz, nur wenn nichts läuft (Fallback-Semantik).
        val currentLive = live.liveSession.value
        val displaced = if (currentLive != null && currentLive.isLive) {
            emptyList()
        } else {
            val recent = live.recentFinishedSessionsBySourceType(
                CalendarAutoRunEngine.SOURCE_CALENDAR,
                CalendarAutoRunEngine.RESUME_EVIDENCE_LOOKBACK
            )
            CalendarAutoRunEngine.displacedMarkers(matches, recent)
                .filter { marker ->
                    live.hasForeignSessionStartingNear(
                        calendarSource = CalendarAutoRunEngine.SOURCE_CALENDAR,
                        atMs = marker.cutAtMs,
                        toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
                    )
                }
                .map { it.eventId }
        }

        // SCHRITT 3 — Kandidat + Doppelstart-Schutz + Start.
        val candidate = CalendarAutoRunEngine
            .pickStartCandidate(matches, now, lastStartedEventId = null, displacedEventIds = displaced)
            ?: return null

        if (currentLive != null && currentLive.isLive) {
            val related = CalendarAutoRunEngine.findRelatedMatch(
                matches, currentLive.startAt, currentLive.activityTypeId
            )
            if (currentLive.sourceType == CalendarAutoRunEngine.SOURCE_CALENDAR &&
                related != null && related.event.eventId == candidate.event.eventId
            ) return null
            if (currentLive.sourceType != CalendarAutoRunEngine.SOURCE_CALENDAR &&
                !candidate.shouldQueueWhenBusy && !candidate.shouldOverrideRunning
            ) return null
        }

        val startAt = CalendarAutoRunEngine.startAnchorMs(
            match = candidate,
            now = now,
            displaced = candidate.event.eventId in displaced
        )
        return startCalendar(candidate, startAt)
    }

    /** Alle Kalender-Sessions aus der DB (mit echtem Ende). */
    private suspend fun calendarSessions(): List<ActivitySession> =
        db.activitySessionDao().getAllNonDeletedOnce()
            .filter { it.sourceType == CalendarAutoRunEngine.SOURCE_CALENDAR }

    /**
     * Kleine ECHTE Pause zwischen zwei Test-Schritten.
     *
     * WARUM NÖTIG (und warum das keine Verschleierung ist):
     * `LiveActivityManager.stop()` stempelt `endAt = System.currentTimeMillis()`.
     * Werden mehrere Sessions innerhalb derselben Millisekunde angelegt und
     * beendet, können die Stempel in der DB um wenige Millisekunden
     * auseinanderlaufen (Start der nächsten vor Ende der vorherigen). In der
     * Produktion liegen diese Ereignisse Sekunden bis Minuten auseinander —
     * ein Test, der sieben Sessions in 50 ms feuert, modelliert also einen
     * Ablauf, den es nicht gibt. Die Pause stellt die reale Zeitordnung her,
     * OHNE die geprüfte Zusicherung abzuschwächen (die Überlappungsfreiheit
     * wird weiterhin exakt, ohne Toleranz, geprüft).
     */
    private fun step() = Thread.sleep(20)

    /** Alle nicht-gelöschten Sessions als (start, end)-Paare, chronologisch. */
    private suspend fun allBlocks(): List<Pair<Long, Long>> =
        db.activitySessionDao().getAllNonDeletedOnce()
            .mapNotNull { s -> s.endAt?.let { s.startAt to it } }
            .sortedBy { it.first }

    /**
     * Überlappungsfreiheit über alle Blöcke.
     *
     * ZWEI STUFEN, weil zwei verschiedene Zusicherungen zu prüfen sind:
     *
     *  - Kalender-Block gegen Kalender-Block: EXAKT (Toleranz 0),
     *    siehe [assertCalendarBlocksDoNotOverlap].
     *  - Über die ganze Sequenz hinweg: mit [WALLCLOCK_STAMP_TOLERANCE_MS].
     *    Die Toleranz ist KEINE Verschleierung, sondern die Granularität der
     *    Wanduhr-Stempel: `live.stop()` und der `forceFinish` im nahtlosen
     *    Wechsel ([LiveActivityOverlapResolver] wertet `newStart == existingEnd`
     *    ausdrücklich als KEINE Überlappung) stempeln
     *    `endAt = System.currentTimeMillis()`, während der Start der nächsten
     *    Session ein vom AUFRUFER gelesener Wert ist. Beide liegen dann
     *    wenige Millisekunden auseinander und können sich um genau diese
     *    Millisekunden überholen.
     *
     * Warum die Toleranz die Prüfung nicht entwertet: echte Regressionen sind
     * um GRÖSSENORDNUNGEN größer. Der Wiedereinstieg, der statt bei JETZT beim
     * Termin-Beginn ankert (die zentrale Fehlerrichtung dieses Auftrags),
     * erzeugt eine Überlappung von MINUTEN — das ist per Mutationstest belegt
     * (siehe Bericht) und wird von dieser Prüfung weiterhin gefangen.
     */
    private fun assertNoOverlap(
        blocks: List<Pair<Long, Long>>,
        toleranceMs: Long = WALLCLOCK_STAMP_TOLERANCE_MS
    ) {
        blocks.zipWithNext().forEach { (a, b) ->
            assertWithMessage("Überlappung zwischen $a und $b (Toleranz ${toleranceMs}ms)")
                .that(b.first).isAtLeast(a.second - toleranceMs)
        }
    }

    /** Die Kalender-Blöcke untereinander — hier gilt Überlappungsfreiheit EXAKT. */
    private suspend fun assertCalendarBlocksDoNotOverlap() {
        calendarSessions()
            .mapNotNull { s -> s.endAt?.let { s.startAt to it } }
            .sortedBy { it.first }
            .let { assertNoOverlap(it, toleranceMs = 0L) }
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. Der gemeldete Fall — gegen die echte DB
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Termin wird nach der Autofahrt fortgesetzt - echte DB und echte Queries`() = runTest {
        val now = realNow()
        val eventStart = now - 10 * MIN
        val eventEnd = now + 120 * MIN
        val match = ruleMatch(event(eventStart, eventEnd))

        // Regulärer Start (innerhalb der 20-Minuten-Toleranz).
        val cal = calendarTick(listOf(match), now)
        assertThat(cal).isNotNull()
        assertThat(cal!!.startAt).isEqualTo(eventStart)

        // Fahrt übernimmt rückdatiert auf ihren Cluster-Start (5 min her).
        val driveStart = now - 5 * MIN
        step()
        foreignStart("ACTIVITY_RECOGNITION_AUTO", "driving", driveStart)

        // BEWEIS-STUFE 1 (echte SQL): die Kalender-Session steht als FINISHED
        // mit end_at == Fahrt-Beginn in der DB.
        val finished = live.recentFinishedSessionsBySourceType(
            CalendarAutoRunEngine.SOURCE_CALENDAR, CalendarAutoRunEngine.RESUME_EVIDENCE_LOOKBACK
        )
        assertThat(finished.map { it.id }).containsExactly(cal.id)
        assertThat(finished.first().endAt).isEqualTo(driveStart)

        val markers = CalendarAutoRunEngine.displacedMarkers(listOf(match), finished)
        assertThat(markers.map { it.eventId }).containsExactly(match.event.eventId)
        assertThat(markers.first().cutAtMs).isEqualTo(driveStart)

        // BEWEIS-STUFE 2 (echte SQL): an der Schnittstelle begann eine FREMD-Session.
        assertThat(
            live.hasForeignSessionStartingNear(
                CalendarAutoRunEngine.SOURCE_CALENDAR,
                atMs = driveStart,
                toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
            )
        ).isTrue()

        // Fahrt endet (Stop-Pfad stößt den Kalender-Lauf an) → Wiedereinstieg.
        step()
        foreignStop()
        step()
        val resumeNow = realNow()
        val resumed = calendarTick(listOf(match), resumeNow)

        assertThat(resumed).isNotNull()
        assertThat(resumed!!.sourceType).isEqualTo(CalendarAutoRunEngine.SOURCE_CALENDAR)
        assertThat(resumed.activityTypeId).isEqualTo("studium")
        // Wiedereinstieg ankert bei JETZT: in der Lücke lief die Fahrt,
        // eine Rückdatierung würde sie überlappen.
        assertThat(resumed.startAt).isEqualTo(resumeNow)
        assertThat(resumed.startAt).isAtLeast(driveStart)

        // Genau ZWEI Kalender-Sessions — kein Duplikat.
        assertThat(calendarSessions()).hasSize(2)
        assertCalendarBlocksDoNotOverlap()
        assertNoOverlap(allBlocks())
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. Termin endet während der Fahrt → keine Fortsetzung
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Termin endet waehrend der Fahrt - kein Wiedereinstieg`() = runTest {
        val now = realNow()
        // Termin läuft bereits ab: Beginn 40 min her, Ende 5 min her.
        val eventStart = now - 40 * MIN
        val eventEnd = now - 5 * MIN
        val e = event(eventStart, eventEnd)
        val match = ruleMatch(e)

        // Der Start-Lauf fand PÜNKTLICH statt (Termin-Beginn).
        val cal = calendarTick(listOf(match), eventStart)
        assertThat(cal).isNotNull()
        assertThat(cal!!.startAt).isEqualTo(eventStart)

        // Fahrt übernimmt 20 min nach Termin-Beginn.
        foreignStart("ACTIVITY_RECOGNITION_AUTO", "driving", eventStart + 20 * MIN)
        foreignStop()

        // Nach dem Termin-Ende ist der Wiedereinstieg nicht fällig.
        assertThat(calendarTick(listOf(match), realNow())).isNull()
        assertThat(live.liveSession.value).isNull()
        // Grenze exklusiv — auch das Prädikat selbst sagt Nein.
        assertThat(
            CalendarAutoRunEngine.shouldResumeAfterDisplacement(e, eventEnd, displaced = true)
        ).isFalse()
        // Es blieb bei der einen (abgeschnittenen) Session.
        assertThat(calendarSessions()).hasSize(1)
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. Mehrere Konflikte hintereinander → jeder Wiedereinstieg greift
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `mehrere Konflikte hintereinander - jeder Wiedereinstieg greift`() = runTest {
        val now = realNow()
        val eventStart = now - 10 * MIN
        val eventEnd = now + 120 * MIN
        val match = ruleMatch(event(eventStart, eventEnd))
        val blocks = mutableListOf<ActivitySession?>()

        blocks += calendarTick(listOf(match), now)                    // 1. Start (Termin-Beginn)
        step()
        foreignStart("ACTIVITY_RECOGNITION_AUTO", "driving", now - 5 * MIN)
        step()
        foreignStop()
        step()
        blocks += calendarTick(listOf(match), realNow())              // Wiedereinstieg 1

        step()
        foreignStart("WALKING_AUTO", "walking", realNow())
        step()
        foreignStop()
        step()
        blocks += calendarTick(listOf(match), realNow())              // Wiedereinstieg 2

        step()
        foreignStart("ACTIVITY_RECOGNITION_AUTO", "driving", realNow())
        step()
        foreignStop()
        step()
        blocks += calendarTick(listOf(match), realNow())              // Wiedereinstieg 3

        assertThat(blocks.filterNotNull()).hasSize(4)
        assertThat(blocks[0]!!.startAt).isEqualTo(eventStart)
        // Jeder Wiedereinstieg startet bei JETZT (nicht rückdatiert) — also
        // strikt nach dem Beginn des jeweils abgeschnittenen Vorgängerblocks.
        assertThat(blocks[1]!!.startAt).isAtLeast(now - 5 * MIN)
        assertThat(blocks[2]!!.startAt).isAtLeast(blocks[1]!!.startAt)
        assertThat(blocks[3]!!.startAt).isAtLeast(blocks[2]!!.startAt)

        // Keine Überlappung: Kalender-Blöcke untereinander EXAKT …
        assertCalendarBlocksDoNotOverlap()
        // … und über alle Sessions hinweg (Wanduhr-Stempel-Granularität,
        // siehe [assertNoOverlap] und [WALLCLOCK_STAMP_TOLERANCE_MS]).
        assertNoOverlap(allBlocks())

        // LIVE-Aussage aus dem StateFlow (nicht aus `inserted`).
        assertThat(live.liveSession.value?.id).isEqualTo(blocks[3]!!.id)

        // Echte DB: 4 Kalender-Sessions, kein Duplikat, genau eine läuft.
        val sessions = calendarSessions()
        assertThat(sessions).hasSize(4)
        assertThat(sessions.count { it.isLive }).isEqualTo(1)
        assertThat(sessions.map { it.id }.toSet()).hasSize(4)
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. Keine Überlappung — in der echten DB nachgerechnet
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Kalender-Bloecke ueberlappen nie und werden nie dupliziert`() = runTest {
        val now = realNow()
        val match = ruleMatch(event(now - 10 * MIN, now + 120 * MIN))
        calendarTick(listOf(match), now)

        val driveStart = now - 5 * MIN
        step()
        foreignStart("ACTIVITY_RECOGNITION_AUTO", "driving", driveStart)
        step()
        foreignStop()
        step()
        val resumeNow = realNow()
        calendarTick(listOf(match), resumeNow)

        val sessions = calendarSessions().sortedBy { it.startAt }
        assertThat(sessions).hasSize(2)
        // Block 1 endet exakt am Fahrt-Beginn …
        assertThat(sessions[0].endAt).isEqualTo(driveStart)
        // … Block 2 beginnt danach (keine Überlappung).
        assertThat(sessions[1].startAt).isAtLeast(sessions[0].endAt!!)
        assertThat(sessions[1].startAt).isEqualTo(resumeNow)

        assertCalendarBlocksDoNotOverlap()
        assertNoOverlap(allBlocks())
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. Die echten SQL-Queries selbst (der Kern dieser Suite)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `Resume-Query liefert nur FINISHED Sessions - nicht laufende, nicht geloeschte`() = runTest {
        val now = realNow()
        val dao = db.activitySessionDao()
        // (a) laufende Kalender-Session (end_at NULL, RUNNING) → NIE Evidenz.
        dao.insert(
            ActivitySession(
                id = "cal-running", title = "Studium", activityTypeId = "studium",
                startAt = now - 10 * MIN, endAt = null, sourceType = "CALENDAR_AUTO",
                sessionStatus = "RUNNING"
            )
        )
        assertThat(dao.getRecentFinishedBySourceType("CALENDAR_AUTO", 8)).isEmpty()

        // (b) beendet, aber soft-gelöscht → NIE Evidenz (deleted_at IS NULL).
        dao.insert(
            ActivitySession(
                id = "cal-deleted", title = "Studium", activityTypeId = "studium",
                startAt = now - 10 * MIN, endAt = now - 5 * MIN, sourceType = "CALENDAR_AUTO",
                sessionStatus = "FINISHED", deletedAt = now
            )
        )
        assertThat(dao.getRecentFinishedBySourceType("CALENDAR_AUTO", 8)).isEmpty()

        // (c) beendet und gültig → Evidenz.
        dao.insert(
            ActivitySession(
                id = "cal-ok", title = "Studium", activityTypeId = "studium",
                startAt = now - 10 * MIN, endAt = now - 2 * MIN, sourceType = "CALENDAR_AUTO",
                sessionStatus = "FINISHED"
            )
        )
        assertThat(dao.getRecentFinishedBySourceType("CALENDAR_AUTO", 8).map { it.id })
            .containsExactly("cal-ok")

        // Gegenprobe der Repo-Schicht (Default-Body → echte DAO-Query).
        assertThat(
            live.recentFinishedSessionsBySourceType("CALENDAR_AUTO", 8).map { it.id }
        ).containsExactly("cal-ok")
    }

    @Test
    fun `Resume-Query sortiert nach end_at DESC und respektiert das LIMIT`() = runTest {
        val now = realNow()
        val dao = db.activitySessionDao()
        // Drei abgeschnittene Kalender-Blocks; Einfüge-Reihenfolge != End-Reihenfolge.
        listOf(30L, 10L, 20L).forEach { minutesAgo ->
            dao.insert(
                ActivitySession(
                    id = "cal-$minutesAgo", title = "Studium", activityTypeId = "studium",
                    startAt = now - 60 * MIN, endAt = now - minutesAgo * MIN,
                    sourceType = "CALENDAR_AUTO", sessionStatus = "FINISHED"
                )
            )
        }
        assertThat(dao.getRecentFinishedBySourceType("CALENDAR_AUTO", 8).map { it.id })
            .containsExactly("cal-10", "cal-20", "cal-30").inOrder()
        // LIMIT schneidet die ÄLTESTEN ab — der Verlauf ist absteigend.
        assertThat(dao.getRecentFinishedBySourceType("CALENDAR_AUTO", 2).map { it.id })
            .containsExactly("cal-10", "cal-20").inOrder()
    }

    @Test
    fun `Verdraengungs-Query zaehlt keine Kalender-Session als Zeuge`() = runTest {
        val now = realNow()
        val dao = db.activitySessionDao()
        val cut = now - 10 * MIN
        // Kalender-Sessions an der Schnittstelle dürfen NICHT als Zeuge
        // zählen — sonst deutete der Kalender seinen eigenen Nachfolger als
        // „Fremd-Aufzeichnung" und drehte einen manuellen Stop um.
        listOf("cal-a", "cal-b").forEach { id ->
            dao.insert(
                ActivitySession(
                    id = id, title = "Studium", activityTypeId = "studium",
                    startAt = cut, endAt = cut + 5 * MIN, sourceType = "CALENDAR_AUTO",
                    sessionStatus = "FINISHED"
                )
            )
        }
        assertThat(
            dao.countForeignSessionsStartingBetween("CALENDAR_AUTO", cut - 2 * SEC, cut + 2 * SEC)
        ).isEqualTo(0)

        // Eine echte Fremd-Session an derselben Stelle → Zeuge.
        dao.insert(
            ActivitySession(
                id = "drive", title = "Autofahren", activityTypeId = "driving",
                startAt = cut, endAt = null, sourceType = "ACTIVITY_RECOGNITION_AUTO",
                sessionStatus = "RUNNING"
            )
        )
        assertThat(
            dao.countForeignSessionsStartingBetween("CALENDAR_AUTO", cut - 2 * SEC, cut + 2 * SEC)
        ).isEqualTo(1)
    }

    @Test
    fun `Verdraengungs-Query nutzt die Toleranzgrenzen inklusiv`() = runTest {
        val now = realNow()
        val dao = db.activitySessionDao()
        val cut = now - 10 * MIN
        val tol = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
        // Genau AUF der Grenze (cut + tol) → noch Zeuge (BETWEEN ist inklusiv).
        dao.insert(
            ActivitySession(
                id = "edge-in", title = "Autofahren", activityTypeId = "driving",
                startAt = cut + tol, endAt = null, sourceType = "ACTIVITY_RECOGNITION_AUTO",
                sessionStatus = "RUNNING"
            )
        )
        assertThat(
            dao.countForeignSessionsStartingBetween("CALENDAR_AUTO", cut - tol, cut + tol)
        ).isEqualTo(1)

        // Fenster um 1 ms verschoben → nur noch die ältere Kante passt nicht mehr.
        assertThat(
            dao.countForeignSessionsStartingBetween("CALENDAR_AUTO", cut - tol, cut - 1)
        ).isEqualTo(0)
        // Und exakt die Gegenkante: start_at == cut - tol muss treffen.
        dao.insert(
            ActivitySession(
                id = "edge-out", title = "Autofahren", activityTypeId = "driving",
                startAt = cut - tol, endAt = null, sourceType = "ACTIVITY_RECOGNITION_AUTO",
                sessionStatus = "RUNNING"
            )
        )
        assertThat(
            dao.countForeignSessionsStartingBetween("CALENDAR_AUTO", cut - tol, cut - 1)
        ).isEqualTo(1)
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. Der Diskriminator — mit echter Persistenz
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `manueller Stop wird nicht wiederaufgenommen - auch mit echter DB`() = runTest {
        // Der Termin beginnt 40 min VOR jetzt: der reguläre 20-Minuten-Start
        // ist damit vorbei — ein Wiederanlauf kann hier NUR über die
        // Verdrängungs-Evidenz entstehen. Genau das macht den Test scharf:
        // ohne die Beweis-Stufe 2 würde der Worker den manuellen Stop 15 min
        // später stillschweigend umdrehen.
        val now = realNow()
        val eventStart = now - 40 * MIN
        val eventEnd = now + 60 * MIN
        val match = ruleMatch(event(eventStart, eventEnd))

        // Der Start-Lauf fand PÜNKTLICH statt (Termin-Beginn).
        val cal = calendarTick(listOf(match), eventStart)
        assertThat(cal).isNotNull()
        val sessionId = cal!!.id

        // Nutzer stoppt SELBST (Dashboard/Notification → live.stop()).
        step()
        live.stop()
        awaitLive(null)

        // Der abgeschnittene Block existiert (sonst wäre der Test blind) …
        val finished = live.recentFinishedSessionsBySourceType(
            CalendarAutoRunEngine.SOURCE_CALENDAR, CalendarAutoRunEngine.RESUME_EVIDENCE_LOOKBACK
        )
        assertThat(finished.map { it.id }).containsExactly(sessionId)
        val markers = CalendarAutoRunEngine.displacedMarkers(listOf(match), finished)
        assertThat(markers).hasSize(1)

        // … aber an der Schnittstelle begann KEINE Fremd-Session → kein Resume.
        assertThat(
            live.hasForeignSessionStartingNear(
                CalendarAutoRunEngine.SOURCE_CALENDAR,
                atMs = markers.first().cutAtMs,
                toleranceMs = CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
            )
        ).isFalse()
        // Vorbedingung des Tests: der reguläre Start ist nicht mehr möglich.
        assertThat(
            CalendarAutoRunEngine.shouldStart(match.event, realNow())
        ).isFalse()

        step()
        assertThat(calendarTick(listOf(match), realNow())).isNull()
        assertThat(live.liveSession.value).isNull()
        assertThat(calendarSessions()).hasSize(1)
    }

    @Test
    fun `abgeschnittener Block allein genuegt nicht - spaeterer Fremd-Start ist kein Zeuge`() = runTest {
        // Die Schärfe des Diskriminators gegen die ECHTE SQL: der Block wurde
        // abgeschnitten, und es begann danach eine Fahrt — aber nicht AN der
        // Schnittstelle. Das darf NICHT als Verdrängung durchgehen.
        val now = realNow()
        val dao = db.activitySessionDao()
        // Termin: Beginn 30 min her (regulärer Start also NICHT mehr möglich),
        // Ende in 60 min.
        val eventStart = now - 30 * MIN
        val eventEnd = now + 60 * MIN
        val match = ruleMatch(event(eventStart, eventEnd))
        val cut = now - 2 * MIN

        // (a) Der Kalender-Block endet 2 min vor jetzt (abgeschnitten).
        dao.insert(
            ActivitySession(
                id = "cal-cut", title = "Studium", activityTypeId = "studium",
                startAt = eventStart, endAt = cut, sourceType = "CALENDAR_AUTO",
                sessionStatus = "FINISHED"
            )
        )
        // (b) Eine Fahrt begann 60 s NACH der Schnittstelle (außerhalb der 2-s-Toleranz).
        dao.insert(
            ActivitySession(
                id = "drive-late", title = "Autofahren", activityTypeId = "driving",
                startAt = cut + 60 * SEC, endAt = cut + 90 * SEC,
                sourceType = "ACTIVITY_RECOGNITION_AUTO", sessionStatus = "FINISHED"
            )
        )

        val markers = CalendarAutoRunEngine.displacedMarkers(
            listOf(match),
            live.recentFinishedSessionsBySourceType(CalendarAutoRunEngine.SOURCE_CALENDAR, 8)
        )
        assertThat(markers).hasSize(1) // Kandidat existiert …
        assertThat(
            live.hasForeignSessionStartingNear(
                CalendarAutoRunEngine.SOURCE_CALENDAR, cut,
                CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
            )
        ).isFalse() // … aber ohne Zeugen.
        // Termin läuft noch — trotzdem KEIN Start (30 min > 20-min-Toleranz).
        assertThat(calendarTick(listOf(match), realNow())).isNull()

        // GEGENPROBE: dieselbe Lage, aber die Fahrt begann EXAKT am Schnitt.
        val now2 = realNow()
        val cut2 = now2 - 2 * MIN
        val dao2 = db.activitySessionDao()
        dao2.insert(
            ActivitySession(
                id = "cal-cut2", title = "Studium", activityTypeId = "studium",
                startAt = eventStart, endAt = cut2, sourceType = "CALENDAR_AUTO",
                sessionStatus = "FINISHED"
            )
        )
        dao2.insert(
            ActivitySession(
                id = "drive-on-cut", title = "Autofahren", activityTypeId = "driving",
                startAt = cut2, endAt = cut2 + 30 * SEC,
                sourceType = "ACTIVITY_RECOGNITION_AUTO", sessionStatus = "FINISHED"
            )
        )
        assertThat(
            live.hasForeignSessionStartingNear(
                CalendarAutoRunEngine.SOURCE_CALENDAR, cut2,
                CalendarAutoRunEngine.DISPLACEMENT_WITNESS_TOLERANCE_MS
            )
        ).isTrue()
        val resumed = calendarTick(listOf(match), realNow())
        assertThat(resumed).isNotNull()
        assertThat(resumed!!.startAt).isAtLeast(cut2)
    }

    @Test
    fun `QUEUE-Termin bleibt ohne Verdraengung nachholbar`() = runTest {
        // Regressionsschutz der M18.132-Semantik mit echter DB: der Nachholer
        // braucht KEINE Vorgänger-Session. Der Termin begann vor 60 min —
        // jenseits der 20-Minuten-Toleranz, also nur über QUEUE erreichbar.
        val now = realNow()
        val eventStart = now - 60 * MIN
        val eventEnd = now + 60 * MIN

        // GEGENPROBE ZUERST (noch keine Session, keine Evidenz): derselbe
        // Termin mit OVERRIDE startet NICHT — „Handy war aus" bleibt
        // unverändert kein Start.
        val overrideMatch = ruleMatch(event(eventStart, eventEnd, id = "uni:2:$eventStart"))
        assertThat(calendarTick(listOf(overrideMatch), realNow())).isNull()
        assertThat(live.liveSession.value).isNull()

        // Und jetzt QUEUE: der Termin ist nachholbar.
        val queueMatch = ruleMatch(event(eventStart, eventEnd), policy = CalendarOverlapPolicy.QUEUE_IF_BUSY)
        val queuedAt = realNow()
        val s = calendarTick(listOf(queueMatch), queuedAt)
        assertThat(s).isNotNull()
        // Nachholer startet bei JETZT (nicht rückdatiert zum Termin-Beginn).
        assertThat(s!!.startAt).isEqualTo(queuedAt)
        assertThat(live.liveSession.value?.id).isEqualTo(s.id)
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. Stop-Pfad-Verdrahtung (Struktur-Check, Muster der Nachbarsuiten)
    // ════════════════════════════════════════════════════════════════════

    private fun mainDir(): File =
        File("src/main/java/com/d_drostes_apps/aevum").takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum")

    /**
     * Jede Automatik, die eine Session stoppt, muss den Kalender-Lauf
     * anstoßen — sonst kommt der Wiedereinstieg bis zu 15 min zu spät.
     * Der Test koppelt die beiden Mengen aneinander: in DERSELBEN Datei
     * müssen mindestens so viele `CalendarAutoRunScheduler`-Nennungen stehen
     * wie es Stop-Pfade gibt, und der Anstoß muss NACH dem Stop stehen.
     */
    @Test
    fun `jeder automatische Stop-Pfad stoesst den Kalender-Lauf an`() {
        val expected = mapOf(
            "automation/activityrecognition/DriveWorkers.kt" to 2,
            "automation/activityrecognition/WalkingWorkers.kt" to 2,
            "automation/geofence/GeofenceTransitionProcessor.kt" to 1,
            "automation/geofence/CurrentZoneProvider.kt" to 1,
            "automation/apptracking/AppTrackingService.kt" to 1,
            "automation/ping/PingTriggerWorker.kt" to 2,
            "automation/screen/ScreenOffStopWorker.kt" to 1
        )

        expected.forEach { (rel, stopCount) ->
            val f = File(mainDir(), rel)
            assertWithMessage("Quelldatei fehlt: $rel").that(f.exists()).isTrue()
            val src = f.readText()

            val restarts = src.split("CalendarAutoRunScheduler").size - 1
            assertWithMessage("$rel: zu wenige Anstöße ($restarts, erwartet >= $stopCount)")
                .that(restarts).isAtLeast(stopCount)

            // Reihenfolge wie in der Produktion: erst beenden, dann neu bewerten.
            val stopIdx = src.indexOf(".stop()")
            val restartIdx = src.indexOf("CalendarAutoRunScheduler")
            if (stopIdx >= 0 && restartIdx >= 0) {
                assertWithMessage("$rel: Anstoß steht VOR dem Stop")
                    .that(restartIdx).isGreaterThan(stopIdx)
            }
        }
    }

    @Test
    fun `manuelle Stop-Pfade stoessen den Kalender-Lauf NICHT an`() {
        // Menschliche Entscheidungen dürfen nicht automatisch umgedreht
        // werden — dort darf die Verdrahtung gar nicht erst existieren.
        val manual = listOf(
            "ui/screens/dashboard/DashboardViewModel.kt",
            "domain/activity/SaveManualActivityUseCase.kt"
        )
        manual.forEach { rel ->
            val f = File(mainDir(), rel)
            assertWithMessage("Quelldatei fehlt: $rel").that(f.exists()).isTrue()
            assertWithMessage("$rel ruft unerwartet CalendarAutoRunScheduler")
                .that(f.readText().contains("CalendarAutoRunScheduler")).isFalse()
        }
    }
}

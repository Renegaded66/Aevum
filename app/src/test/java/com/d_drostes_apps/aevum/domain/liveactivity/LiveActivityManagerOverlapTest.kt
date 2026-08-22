package com.d_drostes_apps.aevum.domain.liveactivity

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * M18.71: Überlappende Aktivitäten — Integrationstest des Live-Start-Pfads.
 *
 * [LiveActivityManager.start] darf die bestehende Live-Session bei einer
 * neuen Aufzeichnung nicht mehr pauschal mit endAt=jetzt beenden, sondern
 * nur im überlappenden Zeitraum überschreiben:
 *   (a) neue startet rückwirkend → alte endet exakt am neuen Start
 *   (c) keine Session wird gelöscht
 * Ohne Zeit-Überlappung (z. B. PAUSED-Session, die vor dem neuen Start
 * endete) wird die alte Session weiterhin beendet (Status-Mechanik),
 * damit nie zwei Live-Sessions existieren.
 */
class LiveActivityManagerOverlapTest {

    private class FakeActivityRepository : ActivityRepository {
        val live = MutableStateFlow<ActivitySession?>(null)
        val finished = mutableListOf<Pair<String, Long>>() // (id, endAt)
        val inserted = mutableListOf<ActivitySession>()
        val deleted = mutableListOf<String>()

        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getCurrentActiveSession(): Flow<ActivitySession?> = flowOf(null)
        override fun getLiveSession(): Flow<ActivitySession?> = live
        override suspend fun updateStatus(id: String, status: String) {}
        override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) {}
        override suspend fun pauseSession(id: String, endAt: Long) {}
        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {
            finished.add(id to endAt)
            val current = live.value
            if (current?.id == id) {
                live.value = current.copy(
                    sessionStatus = "FINISHED",
                    endAt = endAt,
                    totalPausedMs = totalPausedMs,
                    pauseSegmentsJson = pauseSegmentsJson
                )
            }
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
        override suspend fun softDelete(id: String, now: Long) { deleted.add(id) }
        override suspend fun delete(id: String) {}
        override suspend fun insertTagMapping(mapping: com.d_drostes_apps.aevum.data.model.ActivitySessionTag) {}
        override fun getTagIdsForSession(sessionId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteTagMappings(sessionId: String) {}
        override suspend fun countSessionsByType(typeId: String): Int = 0
        override suspend fun countLiveSessionsByType(typeId: String): Int = 0
        override suspend fun reassignSessionsToType(typeId: String, fallbackTypeId: String, now: Long) {}
        override suspend fun hardDeleteSessionsByType(typeId: String) {}
    }

    private class FakeTypeRepository : ActivityTypeRepository {
        private val work = ActivityType(
            id = "work",
            name = "Arbeit",
            defaultCategoryId = null,
            isSystem = false,
            propertiesJson = null,
            positivityScore = 50,
            icon = "•",
            color = 0L
        )
        override fun getById(id: String): Flow<ActivityType?> = flowOf(if (id == "work") work else null)
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(listOf(work))
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
        override fun getAll(): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getByGeofenceId(geofenceId: String): Flow<List<TriggerEvent>> = flowOf(emptyList())
        override fun getById(id: String): Flow<TriggerEvent?> = flowOf(null)
        override suspend fun insert(event: TriggerEvent) {}
        override suspend fun insertAll(events: List<TriggerEvent>) {}
        override suspend fun delete(id: String) {}
    }

    private fun liveSession(
        id: String = "old",
        startAt: Long,
        endAt: Long? = null,
        status: String = "RUNNING"
    ) = ActivitySession(
        id = id,
        title = "Alte Aktivität",
        categoryId = null,
        activityTypeId = "work",
        startAt = startAt,
        endAt = endAt,
        timezoneId = "UTC",
        sourceType = "LIVE",
        sessionStatus = status
    )

    /** Wartet, bis der stateIn des Managers den Wert aus dem Fake übernommen hat. */
    private suspend fun awaitLive(manager: LiveActivityManager, id: String) {
        repeat(200) {
            if (manager.liveSession.value?.id == id) return
            delay(10)
        }
    }

    // ── Regel (a) über den Manager: rückwirkender Start ──

    @Test
    fun `rueckwirkender Start kuerzt laufende Session exakt auf den neuen Start`() = runTest {
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        // Screen-Aufzeichnung: Digital-Session läuft seit 100_000,
        // neue Aufzeichnung startet rückwirkend bei 400_000 (M18.70-Vorlauf).
        repo.live.value = liveSession(id = "old", startAt = 100_000L)
        awaitLive(manager, "old")

        val started = manager.start(
            activityTypeId = "work",
            title = "Digital",
            sourceType = "SCREEN_AUTO",
            startedAt = 400_000L
        )

        // Alte Session endet exakt am Start der neuen — NICHT erst „jetzt".
        assertThat(repo.finished).containsExactly("old" to 400_000L)
        // Regel (c): keine Session wird gelöscht.
        assertThat(repo.deleted).isEmpty()
        // Neue Session mit rückwirkender Startzeit ist drin.
        assertThat(repo.inserted.map { it.id }).contains(started.id)
        assertThat(started.startAt).isEqualTo(400_000L)
        assertThat(started.sessionStatus).isEqualTo("RUNNING")
    }

    // ── Ohne Zeit-Überlappung: alte Session wird trotzdem beendet ──

    @Test
    fun `ohne Zeit-Ueberlappung wird die pausierte Session wie bisher beendet`() = runTest {
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        // PAUSED-Session: Aufzeichnung endete bereits bei 200_000
        // (M18.62: Pause = Session-Split). Neue Session startet „jetzt" —
        // es gibt keine Zeit-Überlappung, aber die alte Live-Session muss
        // trotzdem beendet werden (nie zwei Live-Sessions).
        repo.live.value = liveSession(id = "old", startAt = 100_000L, endAt = 200_000L, status = "PAUSED")
        awaitLive(manager, "old")

        manager.start(activityTypeId = "work", title = "Neu")

        // Beendet mit dem Pause-Zeitpunkt (M18.62-Semantik bleibt erhalten).
        assertThat(repo.finished).containsExactly("old" to 200_000L)
        assertThat(repo.deleted).isEmpty()
        assertThat(repo.inserted).hasSize(1)
    }

    // ── Start ohne vorherige Session ──

    @Test
    fun `Start ohne bestehende Session funktioniert unveraendert`() = runTest {
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        val started = manager.start(activityTypeId = "work", title = "Erste")

        assertThat(repo.finished).isEmpty()
        assertThat(repo.deleted).isEmpty()
        assertThat(repo.inserted).hasSize(1)
        assertThat(started.id).isEqualTo(repo.inserted[0].id)
    }
}

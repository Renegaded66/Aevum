package com.d_drostes_apps.aevum.automation.geofence

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
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
 * M18.114: End-to-End-Flow des Geofence-Re-Enter nach Fahrt-Ende
 * (Kanban t_d6639d07).
 *
 * Abbildung der Worker-Sequenz nach einem Drive-Session-Stop:
 *   1. Drive-Session läuft (ACTIVITY_RECOGNITION_AUTO, "driving").
 *   2. DriveStopWorker/DriveWatchdogWorker stoppen sie (live.stop()).
 *   3. Der letzte GPS-Fix liegt in einem Geofence mit
 *      autoStartActivityTypeId → Resolver: Restart.
 *   4. LiveActivityManager.start() legt die neue Geofence-Session an
 *      (sourceType GEOFENCE_AUTO — exakt wie der ENTER-Pfad).
 *
 * Negative Fälle: Session läuft weiter (Kollision) → kein Start.
 */
class DriveEndGeofenceRestartFlowTest {

    private class FakeActivityRepository : ActivityRepository {
        val live = MutableStateFlow<ActivitySession?>(null)
        val finished = mutableListOf<Pair<String, Long?>>()
        val inserted = mutableListOf<ActivitySession>()
        var lastFinishedAuto: ActivitySession? = null

        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override suspend fun getLastFinishedBySourceType(sourceType: String): ActivitySession? =
            lastFinishedAuto?.takeIf { it.sourceType == sourceType }
        override fun getCurrentActiveSession(): Flow<ActivitySession?> = flowOf(null)
        override fun getLiveSession(): Flow<ActivitySession?> = live
        override suspend fun updateStatus(id: String, status: String) {}
        override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) {}
        override suspend fun pauseSession(id: String, endAt: Long) {}
        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {
            finished.add(id to endAt)
            val current = live.value
            if (current?.id == id) {
                live.value = null // Drive-Ende: keine Live-Session mehr
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
        private val fitness = ActivityType(
            id = "fitness",
            name = "Fitness",
            defaultCategoryId = "sport",
            isSystem = false,
            propertiesJson = null,
            positivityScore = 50,
            icon = "•",
            color = 0L
        )
        override fun getById(id: String): Flow<ActivityType?> = flowOf(if (id == "fitness") fitness else null)
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(listOf(fitness))
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

    private val gymZone = PlaceGeofence(
        id = "gym",
        name = "Gym",
        latitude = 51.5136,
        longitude = 7.4653,
        radiusMeters = 120f,
        autoStartActivityTypeId = "fitness",
        enabled = true,
        deletedAt = null
    )

    private fun driveSession(startAt: Long) = ActivitySession(
        id = "drive-1",
        title = "Autofahren",
        categoryId = "transport",
        activityTypeId = "driving",
        startAt = startAt,
        endAt = null,
        timezoneId = "UTC",
        sourceType = "ACTIVITY_RECOGNITION_AUTO",
        createdBy = "ACTIVITY_RECOGNITION_AUTO",
        sessionStatus = "RUNNING"
    )

    private suspend fun awaitLive(manager: LiveActivityManager, id: String?) {
        repeat(200) {
            if (manager.liveSession.value?.id == id) return
            delay(10)
        }
    }

    /** Die Worker-Sequenz nach dem Drive-Stop: live.stop() → Resolver → ggf. start(). */
    private suspend fun driveEndCheck(
        live: LiveActivityManager
    ): GeofenceDriveEndResolver.Decision {
        // (DriveWorker-Pfad) Fahrt-Session beenden:
        val driving = live.liveSession.value
        if (driving != null && driving.sourceType == "ACTIVITY_RECOGNITION_AUTO") {
            live.stop()
        }
        awaitLive(live, null)

        // (DriveEndGeofenceRestarter-Pfad) Resolver-Entscheidung mit dem
        // letzten GPS-Fix (Fix 30s alt, 20m genau — im Zentrum der Zone).
        val now = System.currentTimeMillis()
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(gymZone),
            fixLat = 51.5137,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = now - 30_000L,
            nowMs = now,
            liveSessionTypeId = live.liveSession.value?.activityTypeId,
            liveSessionIsLive = live.liveSession.value?.isLive == true
        )
        if (decision is GeofenceDriveEndResolver.Decision.Restart) {
            // Wie DriveEndGeofenceRestarter.doWork():
            live.start(
                activityTypeId = decision.type,
                title = null,
                sourceType = "GEOFENCE_AUTO",
                sourceTriggerId = null
            )
        }
        return decision
    }

    // ── AK 4: Geofence-Activity startet nach dem Fahrt-Ende neu ──

    @Test
    fun `Fahrt endet - Geofence-Activity startet automatisch neu`() = runTest {
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())

        // 1) Geofence-Activity lief, Fahrt hat sie übernommen (Override).
        repo.live.value = driveSession(startAt = 100_000L)
        awaitLive(manager, "drive-1")

        // 2) Fahrt endet (Watchdog/EXIT) + Re-Enter-Check: User steht
        //    noch im Gym.
        val decision = driveEndCheck(manager)

        // 3) Restart entschieden + neue Geofence-Session läuft.
        assertThat(decision).isEqualTo(
            GeofenceDriveEndResolver.Decision.Restart("gym", "fitness")
        )
        // Nach dem start() muss der stateIn-Flow die neue Session
        // übernommen haben (awaitLive wie nach jedem DB-Write).
        awaitLive(manager, repo.inserted.lastOrNull()?.id ?: "")
        val restarted = manager.liveSession.value
        assertThat(restarted).isNotNull()
        assertThat(restarted!!.activityTypeId).isEqualTo("fitness")
        assertThat(restarted.sourceType).isEqualTo("GEOFENCE_AUTO")
        assertThat(restarted.sessionStatus).isEqualTo("RUNNING")
        // Die alte Fahrt-Session wurde sauber beendet (ein finish-Eintrag).
        assertThat(repo.finished.map { it.first }).containsExactly("drive-1")
        // Genau eine neue Session — keine Duplikate.
        assertThat(repo.inserted).hasSize(1)
    }

    // ── AK 5: User hat die Zone verlassen → kein Restart ──

    @Test
    fun `Fahrt endet ausserhalb der Zone - keine neue Session`() = runTest {
        val repo = FakeActivityRepository()
        val manager = LiveActivityManager(repo, FakeTypeRepository(), FakeTriggerRepository())
        repo.live.value = driveSession(startAt = 100_000L)
        awaitLive(manager, "drive-1")

        // Fahrt beenden (wie oben), aber der Fix liegt ~1 km entfernt.
        manager.stop()
        awaitLive(manager, null)
        val now = System.currentTimeMillis()
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = listOf(gymZone),
            fixLat = 51.5136 + 0.009,
            fixLon = 7.4653,
            fixAccuracyM = 20f,
            fixAtMs = now - 30_000L,
            nowMs = now,
            liveSessionTypeId = null,
            liveSessionIsLive = false
        )

        assertThat(decision).isEqualTo(GeofenceDriveEndResolver.Decision.NoRestart)
        assertThat(manager.liveSession.value).isNull()
        assertThat(repo.inserted).isEmpty()
    }
}
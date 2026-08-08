package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.domain.automation.CandidateBatchResult
import com.d_drostes_apps.aevum.domain.automation.ReviewCandidateUseCase
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivitySessionChangeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID

/**
 * M12.2: Regression-Tests für die Timeline- und Auto-Accept-Pipeline.
 *
 * Diese Tests sind absichtlich klein und deterministisch — sie sichern
 * die Kern-Invariants ab, ohne die gesamte Datenbank zu instanziieren.
 */
class M12RegressionTest {

    /**
     * M12.2: AUTO_SOURCES enthält alle automatischen Quellen.
     * Timeline / LiveActivity-Card / Foreground-Notification prüfen
     * genau diese Konstante — wenn hier ein SourceType fehlt, wird er
     * in der UI nicht als "Auto" markiert.
     */
    @Test
    fun autoSourcesContainsAllAutomaticTypes() {
        assertThat(com.d_drostes_apps.aevum.ui.screens.timeline.AUTO_SOURCES).containsExactly(
            "GEOFENCE_AUTO",
            "HEALTH_SLEEP_AUTO",
            "ACTIVITY_RECOGNITION_AUTO"
        )
    }

    /**
     * M12.2: Timeline-Default-Zoom liegt im erlaubten Bereich.
     * Beim Wechsel von enum- zu float-basiertem Zoom wurden die
     * MIN/MAX-Grenzen neu kalibriert.
     */
    @Test
    fun timelineZoomIsWithinBounds() {
        val def = com.d_drostes_apps.aevum.ui.screens.timeline.TimelineUiState.DEFAULT_PIXELS_PER_HOUR
        val min = com.d_drostes_apps.aevum.ui.screens.timeline.TimelineUiState.MIN_PIXELS_PER_HOUR
        val max = com.d_drostes_apps.aevum.ui.screens.timeline.TimelineUiState.MAX_PIXELS_PER_HOUR
        assertThat(def).isAtLeast(min)
        assertThat(def).isAtMost(max)
    }

    /**
     * M12.2: Sleep-Candidate mit hoher Konfidenz wird von acceptAuto akzeptiert.
     * Wir testen den Pfad über einen Fake-UseCase mit gemockten Repositories.
     */
    @Test
    fun sleepCandidateWithHighConfidenceIsAutoAccepted() = runBlocking {
        val repo = FakeActivityRepository()
        val candRepo = FakeCandidateRepository()
        val changeRepo = FakeChangeRepository()
        val useCase = ReviewCandidateUseCase(candRepo, repo, changeRepo)

        val sleepCandidate = candidate(
            id = "sleep-1",
            activityTypeId = "sleep",
            confidence = 0.88f
        )
        candRepo.pending.add(sleepCandidate)

        val result = useCase.acceptAuto(listOf(sleepCandidate))

        assertThat(result.accepted).isEqualTo(1)
        // M12.2: Health-Sleep bekommt expliziten sourceType für die UI-Heuristik.
        val saved = repo.inserted.first()
        assertThat(saved.sourceType).isEqualTo("HEALTH_SLEEP_AUTO")
        assertThat(saved.activityTypeId).isEqualTo("sleep")
    }

    /**
     * M12.2: Driving-Candidate mit hoher Konfidenz wird akzeptiert und
     * bekommt ACTIVITY_RECOGNITION_AUTO als SourceType.
     */
    @Test
    fun drivingCandidateWithHighConfidenceIsAutoAccepted() = runBlocking {
        val repo = FakeActivityRepository()
        val candRepo = FakeCandidateRepository()
        val changeRepo = FakeChangeRepository()
        val useCase = ReviewCandidateUseCase(candRepo, repo, changeRepo)

        val driveCandidate = candidate(
            id = "drive-1",
            activityTypeId = "driving",
            confidence = 0.82f
        )
        candRepo.pending.add(driveCandidate)

        val result = useCase.acceptAuto(listOf(driveCandidate))

        assertThat(result.accepted).isEqualTo(1)
        val saved = repo.inserted.first()
        assertThat(saved.sourceType).isEqualTo("ACTIVITY_RECOGNITION_AUTO")
        assertThat(saved.activityTypeId).isEqualTo("driving")
    }

    /**
     * M12.2: Niedrig-confidente Candidates werden NICHT auto-accepted.
     * Schwellwert liegt bei 0.70 (SAFE_CONFIDENCE_THRESHOLD).
     */
    @Test
    fun lowConfidenceCandidateIsNotAutoAccepted() = runBlocking {
        val repo = FakeActivityRepository()
        val candRepo = FakeCandidateRepository()
        val changeRepo = FakeChangeRepository()
        val useCase = ReviewCandidateUseCase(candRepo, repo, changeRepo)

        val lowConfSleep = candidate(
            id = "sleep-low",
            activityTypeId = "sleep",
            confidence = 0.55f // unter Schwellwert
        )
        candRepo.pending.add(lowConfSleep)

        val result = useCase.acceptAuto(listOf(lowConfSleep))

        assertThat(result.accepted).isEqualTo(0)
        assertThat(repo.inserted).isEmpty()
    }

    /**
     * M12.2: Manueller accept() bleibt unverändert.
     * Wichtig: Die alte `accept(id)`-API darf sich nicht geändert haben.
     */
    @Test
    fun manualAcceptStillUsesDefaultSourceType() = runBlocking {
        val repo = FakeActivityRepository()
        val candRepo = FakeCandidateRepository()
        val changeRepo = FakeChangeRepository()
        val useCase = ReviewCandidateUseCase(candRepo, repo, changeRepo)

        val c = candidate(
            id = "manual-1",
            activityTypeId = "work",
            confidence = 0.9f
        )
        candRepo.pending.add(c)

        val result = useCase.accept(c.id)
        assertThat(result).isInstanceOf(com.d_drostes_apps.aevum.domain.automation.CandidateReviewResult.Accepted::class.java)
        // M12.2: Manuelle Accepts behalten CONFIRMED_CANDIDATE — keine Auto-Markierung.
        assertThat(repo.inserted.first().sourceType).isEqualTo("CONFIRMED_CANDIDATE")
    }

    // ============================================================
    // Fakes
    // ============================================================

    private fun candidate(id: String, activityTypeId: String?, confidence: Float): ActivityCandidate =
        ActivityCandidate(
            id = id,
            suggestedTitle = "Test $activityTypeId",
            suggestedCategoryId = if (activityTypeId == "sleep") "sleep" else "transport",
            activityTypeId = activityTypeId,
            startAt = 1_000L,
            endAt = 2_000L,
            confidence = confidence,
            status = "PENDING",
            reason = "Test",
            createdBy = "TEST",
            createdAt = 0L
        )

    private class FakeActivityRepository : ActivityRepository {
        val inserted = mutableListOf<ActivitySession>()
        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getCurrentActiveSession(): Flow<ActivitySession?> = flowOf(null)
        override fun getLiveSession(): Flow<ActivitySession?> = flowOf(null)
        override suspend fun updateStatus(id: String, status: String) {}
        override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) {}
        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override suspend fun updatePauseData(id: String, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?> = flowOf(null)
        override fun getById(id: String): Flow<ActivitySession?> = flowOf(null)
        override suspend fun insert(session: ActivitySession) { inserted.add(session) }
        override suspend fun insertWithTags(session: ActivitySession, tags: List<com.d_drostes_apps.aevum.data.model.Tag>) { inserted.add(session) }
        override suspend fun update(session: ActivitySession) {}
        override suspend fun softDelete(id: String, now: Long) {}
        override suspend fun delete(id: String) {}
        override suspend fun insertTagMapping(mapping: com.d_drostes_apps.aevum.data.model.ActivitySessionTag) {}
        override fun getTagIdsForSession(sessionId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteTagMappings(sessionId: String) {}
        // M18.50: Activity löschen — Test-Fake ergänzt.
        override suspend fun countSessionsByType(typeId: String): Int = 0
        override suspend fun countLiveSessionsByType(typeId: String): Int = 0
        override suspend fun reassignSessionsToType(typeId: String, fallbackTypeId: String, now: Long) {}
        override suspend fun hardDeleteSessionsByType(typeId: String) {}
    }

    private class FakeCandidateRepository : ActivityCandidateRepository {
        val pending = mutableListOf<ActivityCandidate>()
        override fun getById(id: String): Flow<ActivityCandidate?> = flowOf(pending.firstOrNull { it.id == id })
        override fun getByStatus(status: String): Flow<List<ActivityCandidate>> = flowOf(pending.filter { it.status == status })
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivityCandidate>> = flowOf(emptyList())
        override fun getByResolvedSession(sessionId: String): Flow<ActivityCandidate?> = flowOf(null)
        override suspend fun insert(candidate: ActivityCandidate) { pending.add(candidate) }
        override suspend fun insertAll(candidates: List<ActivityCandidate>) { pending.addAll(candidates) }
        override suspend fun update(candidate: ActivityCandidate) {
            val idx = pending.indexOfFirst { it.id == candidate.id }
            if (idx >= 0) pending[idx] = candidate
        }
        override suspend fun delete(id: String) { pending.removeAll { it.id == id } }
    }

    private class FakeChangeRepository : ActivitySessionChangeRepository {
        override fun getBySessionId(sessionId: String): Flow<List<com.d_drostes_apps.aevum.data.model.ActivitySessionChange>> = flowOf(emptyList())
        override fun getByTypeAndDateRange(type: String, start: Long, end: Long): Flow<List<com.d_drostes_apps.aevum.data.model.ActivitySessionChange>> = flowOf(emptyList())
        override fun getBySourceCandidateId(candidateId: String): Flow<List<com.d_drostes_apps.aevum.data.model.ActivitySessionChange>> = flowOf(emptyList())
        override suspend fun insert(change: com.d_drostes_apps.aevum.data.model.ActivitySessionChange) {}
        override suspend fun insertAll(changes: List<com.d_drostes_apps.aevum.data.model.ActivitySessionChange>) {}
    }
}

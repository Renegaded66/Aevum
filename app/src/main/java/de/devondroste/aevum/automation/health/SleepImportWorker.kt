package de.devondroste.aevum.automation.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.domain.automation.ReviewCandidateUseCase
import de.devondroste.aevum.domain.health.HealthConnectManager
import kotlinx.coroutines.flow.first

/**
 * M9.2/M12.2: Periodic import of sleep sessions from Health Connect.
 *
 * - M9.2: Verbindet HealthConnectManager mit der Candidate-Pipeline.
 * - M12.2: Auto-Accept — importierte Sleep-Candidates werden direkt zu
 *   Sessions (mit sourceType = HEALTH_SLEEP_AUTO), wenn die Confidence
 *   ≥ SAFE_CONFIDENCE_THRESHOLD (0.70) liegt.
 *
 * Damit läuft Schlaf komplett über die gleiche Live-Session-Architektur
 * wie Geofence-Auto-Starts. Kein zweites System. Die Sleep-Session
 * erscheint in Timeline + Dashboard + Insights ohne extra Review.
 *
 * Uses Hilt EntryPointAccessors to fetch dependencies without
 * requiring a custom WorkManager Configuration.Provider.
 */
class SleepImportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun healthConnectManager(): HealthConnectManager
        fun candidateRepository(): ActivityCandidateRepository
        fun reviewCandidateUseCase(): ReviewCandidateUseCase
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        val healthConnectManager = deps.healthConnectManager()
        val candidateRepository = deps.candidateRepository()
        val reviewCandidateUseCase = deps.reviewCandidateUseCase()

        if (!healthConnectManager.isAvailable()) {
            return Result.success()
        }
        if (!healthConnectManager.hasSleepPermission()) {
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val start = now - 24L * 60 * 60 * 1000
        val end = now

        val imported = try {
            healthConnectManager.importSleepSessions(start, end)
        } catch (e: Exception) {
            return Result.retry()
        }

        if (imported.isEmpty()) return Result.success()

        val existing = candidateRepository.getByStatus(AutomationConstants.CANDIDATE_STATUS_PENDING).first()
        val existingExternalIds = existing.mapNotNull { it.sourceCandidateId }.toSet()

        val newCandidates = imported.filter { it.sourceCandidateId !in existingExternalIds }
        if (newCandidates.isEmpty()) return Result.success()

        candidateRepository.insertAll(newCandidates)

        // M12.2: Auto-Accept Schlaf-Candidates direkt zu Sessions.
        // Damit läuft die Schlaf-Erkennung durch die gleiche Pipeline
        // wie Geofence-Trigger und wird in der Timeline als "Auto" markiert.
        // Niedrig-confidente Imports (< 0.70) bleiben als Candidate für den Review-Inbox.
        reviewCandidateUseCase.acceptAuto(newCandidates)

        return Result.success()
    }

    /**
     * M12.2: Helfer, damit Unit-Tests die Auto-Accept-Entscheidung prüfen können
     * ohne Health Connect zu involvieren.
     */
    @Suppress("unused")
    internal fun shouldAutoAccept(candidate: ActivityCandidate): Boolean {
        return candidate.status == "PENDING" && candidate.confidence >= 0.70f && candidate.activityTypeId == "sleep"
    }
}

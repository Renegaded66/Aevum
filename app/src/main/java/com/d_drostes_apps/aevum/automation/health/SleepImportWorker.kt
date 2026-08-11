package com.d_drostes_apps.aevum.automation.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.automation.model.AutomationConstants
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.domain.automation.ReviewCandidateUseCase
import com.d_drostes_apps.aevum.domain.health.HealthConnectManager
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
        // M18.45-FIX (Root Cause "Schlaf doppelt aufgezeichnet"):
        // Das Duplikat kam vom Health-Connect-Import: Er deduplizierte
        // NUR gegen PENDING-Candidates. Nach dem Auto-Accept (Status
        // ACCEPTED) war der Candidate aus dem Filter gefallen, und der
        // nächste Import legte eine zweite, identische Session an.
        // Jetzt wird auch gegen bestehende Sessions dedupliziert.
        fun activityRepository(): com.d_drostes_apps.aevum.data.repository.ActivityRepository
        // M18.63-CRITICAL (Root Cause "Garmin-Schlaf wird ~10x
        // synchronisiert"): Das sleepSource-Gate — Health-Connect-Schlaf
        // darf NUR importieren, wenn der User Health Connect als Quelle
        // gewählt hat. Vorher importierte der Worker IMMER (sobald die
        // HC-Permission existierte), auch bei Quelle "garmin" — ein
        // zweiter, unabhängiger Duplikat-Pfad neben dem Garmin-Import.
        fun automationSettingsDao(): com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        val healthConnectManager = deps.healthConnectManager()
        val candidateRepository = deps.candidateRepository()
        val reviewCandidateUseCase = deps.reviewCandidateUseCase()
        // M18.45: Repository für den Duplikat-Dedup gegen Sessions.
        val activityRepository = deps.activityRepository()

        // M18.63: sleepSource-Gate. Wenn der User eine andere Schlaf-
        // Quelle gewählt hat (garmin/screen/none), ist dieser Import
        // ein No-Op — sonst entstehen Duplikate parallel zum
        // Garmin-Import (der die gleiche Nacht einträgt).
        val sleepSource = try {
            deps.automationSettingsDao().getSettingsSync()?.sleepSource
        } catch (e: Exception) {
            null
        }
        if (sleepSource != "health_connect") {
            android.util.Log.d(TAG, "sleepSource = ${sleepSource ?: "?"} — Health-Connect-Import ist No-Op")
            return Result.success()
        }

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

        // M18.45: Bestands-Bereinigung — bereits entstandene Duplikate
        // entfernen (softDelete), BEVOR der neue Import prüft. Kriterium:
        // zwei Sleep-Sessions mit identischer startAt+endAt (exakte
        // Duplikate durch den alten PENDING-only-Dedup). Die ältere
        // (frühestes createdAt) bleibt, die jüngere wird verworfen.
        try {
            val cleanupStart = System.currentTimeMillis() - 3L * 24 * 3_600_000L
            val cleanupEnd = System.currentTimeMillis() + 24L * 3_600_000L
            val sleepSessions = activityRepository.getOverlappingRange(cleanupStart, cleanupEnd)
                .first().filter { it.activityTypeId == "sleep" && it.deletedAt == null }
            val seen = mutableMapOf<Pair<Long, Long>, String>() // (start,end) -> sessionId
            sleepSessions.sortedBy { it.createdAt ?: 0L }.forEach { session ->
                val key = (session.startAt to (session.endAt ?: 0L))
                val existingId = seen[key]
                if (existingId != null) {
                    android.util.Log.d(
                        TAG,
                        "Duplikat-Sleep-Session entfernt: ${session.id} (behalte $existingId)"
                    )
                    activityRepository.softDelete(session.id, System.currentTimeMillis())
                } else {
                    seen[key] = session.id
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Bestands-Bereinigung fehlgeschlagen (nicht blockierend): ${e.message}")
        }

        // M18.45-FIX: Breiter Dedup — gegen ALLE Candidates (nicht nur
        // PENDING!) UND gegen bestehende Sessions. Das schließt die Lücke,
        // durch die nach Auto-Accept (ACCEPTED) ein Duplikat entstand.
        val windowStart = imported.minOfOrNull { it.startAt } ?: start
        val windowEnd = imported.maxOfOrNull { it.endAt } ?: end

        // 1) Alle Candidates im Fenster (jeder Status).
        val existingCandidates = candidateRepository.getByDateRange(
            windowStart - 24L * 3_600_000L,
            windowEnd + 24L * 3_600_000L
        ).first()
        val existingCandidateIds = existingCandidates.mapNotNull { it.sourceCandidateId }.toSet()

        // 2) Bestehende Sleep-Sessions im Fenster (≥30 Min Überlappung =
        //    bereits erfasst).
        val existingSessions = activityRepository.getOverlappingRange(
            windowStart - 24L * 3_600_000L,
            windowEnd + 24L * 3_600_000L
        ).first().filter { it.activityTypeId == "sleep" && it.deletedAt == null }
        val overlapToleranceMs = 30L * 60 * 1000

        val newCandidates = imported.filter { candidate ->
            // Achtung (M18.45-Reflexion): `importedIds` NICHT als Dedup
            // verwenden — jeder Importierte ist per Definition in seiner
            // eigenen Liste; das würde ALLE Imports als Duplikat verwerfen.
            val idKnown = candidate.sourceCandidateId in existingCandidateIds
            val sessionKnown = existingSessions.any { existing ->
                val overlap = minOf(candidate.endAt, existing.endAt ?: Long.MAX_VALUE) -
                    maxOf(candidate.startAt, existing.startAt)
                overlap > overlapToleranceMs
            }
            !idKnown && !sessionKnown
        }
        if (newCandidates.isEmpty()) {
            android.util.Log.d(TAG, "Alle ${imported.size} Importe bereits erfasst (Candidates/Sessions) — kein Duplikat")
            return Result.success()
        }

        candidateRepository.insertAll(newCandidates)

        // M12.2: Auto-Accept Schlaf-Candidates direkt zu Sessions.
        // Damit läuft die Schlaf-Erkennung durch die gleiche Pipeline
        // wie Geofence-Trigger und wird in der Timeline als "Auto" markiert.
        // M18.58: acceptAutoDirect — Schlaf aus Health Connect wird IMMER
        // direkt eingetragen (User-Wunsch: "sobald Daten verfügbar sind,
        // direkt ohne vorherige Bestätigung in die Timeline eingetragen").
        // Das Confidence-Gate (0.70) entfällt für Schlaf-Imports.
        reviewCandidateUseCase.acceptAutoDirect(newCandidates)

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

    companion object {
        private const val TAG = "SleepImportWorker"
    }
}

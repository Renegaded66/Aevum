package com.d_drostes_apps.aevum.automation.garmin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.garmin.GarminApiClient
import com.d_drostes_apps.aevum.data.garmin.GarminRemoteActivity
import com.d_drostes_apps.aevum.data.model.GarminActivity
import com.d_drostes_apps.aevum.data.model.GarminDailySummary
import com.d_drostes_apps.aevum.data.repository.GarminRepository
import com.d_drostes_apps.aevum.domain.garmin.GarminImportUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * M18.58: Garmin Connect Sync-Worker.
 *
 * Holt von der Aevum-Garmin-Bridge (Server):
 *   1. Tageszusammenfassung (Schritte, Distanz, Kalorien) → Kachel-Daten
 *   2. Schlaf (letzte 7 Nächte) → Sleep-Sessions direkt in die Timeline
 *      (nur wenn sleepSource == "garmin")
 *   3. Aktivitäten (letzte 20, z.B. Joggen) → Timeline mit Zeitraum-
 *      Überschreibung via [GarminImportUseCase]
 *
 * Läuft als periodischer Worker (30 min) + manuell aus den Einstellungen.
 */
class GarminSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun garminApiClient(): GarminApiClient
        fun garminRepository(): GarminRepository
        fun garminImportUseCase(): GarminImportUseCase
        // M18.58: Schlaf-Import (sleepSource-Gate)
        fun automationSettingsDao(): com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
        fun activityRepository(): com.d_drostes_apps.aevum.data.repository.ActivityRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val api = deps.garminApiClient()
        val repo = deps.garminRepository()
        val importUseCase = deps.garminImportUseCase()
        val settingsDao = deps.automationSettingsDao()
        val activityRepository = deps.activityRepository()

        // Bridge erreichbar?
        val status = api.getStatus()
        if (!status.connected) {
            android.util.Log.w(TAG, "Garmin-Bridge nicht verbunden: ${status.error}")
            return Result.retry()
        }

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)

        // 1) Tageszusammenfassung (heute + gestern für die Kacheln)
        try {
            val todayData = api.getToday(today.toString())
            if (todayData != null) {
                repo.upsertSummary(
                    GarminDailySummary(
                        date = todayData.date,
                        steps = todayData.steps,
                        distanceMeters = todayData.distanceMeters,
                        calories = todayData.totalCalories,
                        activeCalories = todayData.activeCalories,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            val yesterday = api.getToday(today.minusDays(1).toString())
            if (yesterday != null) {
                repo.upsertSummary(
                    GarminDailySummary(
                        date = yesterday.date,
                        steps = yesterday.steps,
                        distanceMeters = yesterday.distanceMeters,
                        calories = yesterday.totalCalories,
                        activeCalories = yesterday.activeCalories,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Tageszusammenfassung fehlgeschlagen: ${e.message}")
        }

        // 2) Schlaf (letzte 7 Nächte) — nur wenn Garmin die Schlaf-Quelle ist.
        try {
            val settings = settingsDao.get().first()
            if (settings?.sleepSource == "garmin") {
                importSleep(api, activityRepository, today)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Schlaf-Import fehlgeschlagen: ${e.message}")
        }

        // 3) Aktivitäten → Timeline (Zeitraum-Überschreibung)
        try {
            val activities = api.getActivities(limit = 20)
            if (activities.isNotEmpty()) {
                val mapped = activities.map { it.toEntity() }
                val imported = importUseCase.importActivities(mapped)
                if (imported > 0) {
                    android.util.Log.i(TAG, "$imported Garmin-Aktivitäten importiert")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Aktivitäts-Import fehlgeschlagen: ${e.message}")
        }

        api.lastSyncAt = System.currentTimeMillis()
        return Result.success()
    }

    /**
     * M18.58: Garmin-Schlaf DIREKT in die Timeline (kein Review).
     * Dedup gegen bestehende Sleep-Sessions (≥30 min Überlappung).
     */
    private suspend fun importSleep(
        api: GarminApiClient,
        repo: com.d_drostes_apps.aevum.data.repository.ActivityRepository,
        today: LocalDate
    ) {
        for (i in 1..7) {
            val day = today.minusDays(i.toLong())
            val sleep = api.getSleep(day.toString()) ?: continue

            val existing = repo.getOverlappingRange(
                sleep.startGmtMs - 30L * 60 * 1000,
                sleep.endGmtMs + 30L * 60 * 1000
            ).first().any { it.activityTypeId == "sleep" && it.deletedAt == null }

            if (existing) continue

            val now = System.currentTimeMillis()
            val session = com.d_drostes_apps.aevum.data.model.ActivitySession(
                id = UUID.randomUUID().toString(),
                title = "Schlaf",
                categoryId = null,
                activityTypeId = "sleep",
                startAt = sleep.startGmtMs,
                endAt = sleep.endGmtMs,
                sourceType = "GARMIN_SLEEP_AUTO",
                createdBy = "GARMIN_IMPORT",
                updatedBy = null,
                sourceCandidateId = null,
                confidence = 1.0f,
                isUserEdited = false,
                createdAt = now,
                updatedAt = now,
                revision = 1
            )
            repo.insert(session)
            android.util.Log.i(TAG, "Garmin-Schlaf ${day} importiert (${sleep.sleepTimeSeconds}s)")
        }
    }

    /** M18.58: Bridge-DTO → Garmin-Entity. */
    private fun GarminRemoteActivity.toEntity(): GarminActivity {
        // start_gmt z.B. "2026-08-08 15:57:49" (Garmin GMT, keine Zulu-Suffixe)
        val startEpoch = parseGarminTimestamp(startGmt)
        return GarminActivity(
            id = UUID.randomUUID().toString(),
            externalId = activityId,
            activityType = type,
            title = name,
            startAt = startEpoch,
            endAt = startEpoch + (durationSeconds * 1000L).toLong(),
            distanceMeters = distanceMeters,
            calories = calories,
            importedAt = System.currentTimeMillis(),
            sessionId = null
        )
    }

    companion object {
        private const val TAG = "GarminSyncWorker"

        /**
         * Parst Garmin-Zeitstempel "2026-08-08 15:57:49" (GMT) in epochMillis.
         * Fallback: jetzt.
         */
        fun parseGarminTimestamp(raw: String): Long {
            return try {
                val dt = java.time.LocalDateTime.parse(
                    raw.trim(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                )
                dt.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}

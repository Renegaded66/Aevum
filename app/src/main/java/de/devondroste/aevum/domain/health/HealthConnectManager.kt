package de.devondroste.aevum.domain.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.repository.DetectionEventRepository
import de.devondroste.aevum.data.repository.RawSourceEventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Reads sleep sessions from Health Connect and feeds them into Aevum's pipeline:
 * RawSourceEvent → DetectionEvent → ActivityCandidate.
 *
 * M8: Sleep only. All data stays local.
 */
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rawSourceRepository: RawSourceEventRepository,
    private val detectionRepository: DetectionEventRepository
) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun isAvailable(): Boolean {
        return try {
            HealthConnectClient.getOrCreate(context)
            true
        } catch (_: Exception) { false }
    }

    suspend fun hasSleepPermission(): Boolean {
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(SLEEP_PERMISSIONS)
        } catch (_: Exception) { false }
    }

    suspend fun requestSleepPermissions(): Set<String> {
        return try {
            // Permission request is handled via ActivityResultContract in the UI layer.
            // Here we just check what's granted.
            client.permissionController.getGrantedPermissions()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * Reads sleep records from Health Connect and converts them to ActivityCandidates.
     */
    suspend fun importSleepSessions(start: Long, end: Long): List<ActivityCandidate> {
        if (!hasSleepPermission()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(start),
                        Instant.ofEpochMilli(end)
                    )
                )
                val response = client.readRecords(request)
                response.records.mapNotNull { record -> sleepToCandidate(record) }
            } catch (_: Exception) { emptyList() }
        }
    }

    private suspend fun sleepToCandidate(record: SleepSessionRecord): ActivityCandidate? {
        val now = System.currentTimeMillis()
        val zoneId = ZoneId.systemDefault().id
        val startTime = record.startTime.toEpochMilli()
        val endTime = record.endTime.toEpochMilli()
        val durationMs = endTime - startTime
        val hours = durationMs / 3_600_000
        val mins = (durationMs % 3_600_000) / 60_000
        val durationStr = if (mins > 0) "${hours}h ${mins}m" else "${hours}h"

        // M11: Dedup via externalId (Health Connect metadata.id). Wenn bereits
        // ein RawSourceEvent mit dieser externalId existiert, überspringen.
        val externalId = record.metadata.id.take(100)
        val existing = rawSourceRepository.getBySourceAndExternalId("health_connect", externalId).first()
        if (existing != null) return null

        // RawSourceEvent
        val rawId = UUID.randomUUID().toString()
        val raw = RawSourceEvent(
            id = rawId,
            sourceId = "health_connect",
            externalId = record.metadata.id.take(100),
            eventType = "HEALTH_SLEEP_SESSION",
            observedAt = now,
            startAt = startTime,
            endAt = endTime,
            timezoneId = zoneId,
            payloadJson = "{\"durationMs\":$durationMs,\"title\":\"${record.title ?: "Schlaf"}\"}"
        )
        rawSourceRepository.insert(raw)

        // DetectionEvent
        val detectionId = UUID.randomUUID().toString()
        val detection = DetectionEvent(
            id = detectionId,
            rawEventId = rawId,
            sourceId = "health_connect",
            kind = "HEALTH_SLEEP",
            startAt = startTime,
            endAt = endTime,
            confidence = SLEEP_CONFIDENCE,
            metadataJson = "{\"durationMs\":$durationMs}"
        )
        detectionRepository.insert(detection)

        // ActivityCandidate
        return ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = "Schlaf ($durationStr)",
            suggestedCategoryId = "sleep",
            activityTypeId = "sleep",
            startAt = startTime,
            endAt = endTime,
            confidence = SLEEP_CONFIDENCE,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Health Connect: ${record.title ?: "Schlaf"} ($durationStr). Bitte prüfen.",
            createdBy = "HEALTH_CONNECT_V1",
            createdAt = now,
            sourceCandidateId = rawId
        )
    }

    companion object {
        const val SLEEP_CONFIDENCE = 0.88f
        val SLEEP_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        )
    }
}

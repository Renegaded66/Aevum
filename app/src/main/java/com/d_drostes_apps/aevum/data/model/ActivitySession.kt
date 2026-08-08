package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "activity_session",
    indices = [
        Index("start_at"),
        Index("end_at"),
        Index(value = ["category_id", "start_at"]),
        Index(value = ["activity_type_id", "start_at"]),
        Index(value = ["source_type", "start_at"]),
        Index(value = ["deleted_at", "start_at"]),
        Index("source_candidate_id"),
        Index("supersedes_session_id"),
        Index("session_status")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivityCandidate::class,
            parentColumns = ["id"],
            childColumns = ["source_candidate_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivitySession::class,
            parentColumns = ["id"],
            childColumns = ["supersedes_session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ActivitySession(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    @ColumnInfo(name = "timezone_id") val timezoneId: String = "UTC",
    val description: String? = null,
    @ColumnInfo(name = "source_type") val sourceType: String = "MANUAL",
    @ColumnInfo(name = "created_by") val createdBy: String = "MANUAL",
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "source_candidate_id") val sourceCandidateId: String? = null,
    @ColumnInfo(name = "source_trigger_id") val sourceTriggerId: String? = null,
    @ColumnInfo(name = "supersedes_session_id") val supersedesSessionId: String? = null,
    val confidence: Float = 1.0f,
    @ColumnInfo(name = "is_user_edited") val isUserEdited: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val revision: Int = 1,
    @ColumnInfo(name = "origin_device_id") val originDeviceId: String? = null,
    // M9: Live Activity Recording
    @ColumnInfo(name = "session_status", defaultValue = "FINISHED") val sessionStatus: String = "FINISHED",
    @ColumnInfo(name = "total_paused_ms", defaultValue = "0") val totalPausedMs: Long = 0L,
    @ColumnInfo(name = "current_pause_started_at") val currentPauseStartedAt: Long? = null,
    @ColumnInfo(name = "pause_segments_json") val pauseSegmentsJson: String? = null,
    @ColumnInfo(name = "note") val note: String? = null
) : Serializable {
    /** Is this session currently running or paused (i.e. not finished)? */
    val isLive: Boolean get() = sessionStatus == "RUNNING" || sessionStatus == "PAUSED"
    val isRunning: Boolean get() = sessionStatus == "RUNNING"
    val isPaused: Boolean get() = sessionStatus == "PAUSED"

    /** Effective paused ms including current pause if paused right now. */
    fun effectivePausedMs(now: Long = System.currentTimeMillis()): Long {
        val base = totalPausedMs
        val current = if (isPaused && currentPauseStartedAt != null) (now - currentPauseStartedAt) else 0L
        return base + current
    }

    /** Total wall-clock duration from start to end (or now). */
    fun totalDurationMs(now: Long = System.currentTimeMillis()): Long {
        val end = endAt ?: now
        return (end - startAt).coerceAtLeast(0L)
    }

    /** Active (non-paused) duration. */
    fun activeDurationMs(now: Long = System.currentTimeMillis()): Long {
        return (totalDurationMs(now) - effectivePausedMs(now)).coerceAtLeast(0L)
    }
}
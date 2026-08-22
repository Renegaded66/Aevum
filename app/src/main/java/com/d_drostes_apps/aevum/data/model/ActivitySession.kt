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
        Index("session_status"),
        // M18.64: Stabile externe Identität (z.B. Garmin-Schlaf-Nacht).
        // Ermöglicht idempotente Imports: gleiche externalId = gleicher
        // Datensatz → UPDATE statt Insert (kein Duplikat bei wiederholtem
        // Sync, auch wenn Garmin die Zeiten nachträglich ändert).
        Index("external_id")
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
    // M18.64: Stabile externe Identität für idempotente Imports.
    // Garmin-Schlaf: "garmin_sleep_<Aufwach-Tag>" — Garmin ändert die
    // Schlafzeiten nachträglich, aber die Nacht-Identität bleibt stabil.
    @ColumnInfo(name = "external_id") val externalId: String? = null,
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
    @ColumnInfo(name = "note") val note: String? = null,
    // AEVUM-3: Manuelle Güte-Anpassung pro Aufzeichnung. Override (0..100)
    // für den Positivity-Score DIESER Session — gilt nur, solange die
    // Session existiert (am nächsten Tag starten neue Sessions ohne
    // Override → die automatische Berechnung gilt wieder).
    @ColumnInfo(name = "manual_quality_override") val manualQualityOverride: Int? = null
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

    /**
     * M18.62-FIX: Aktive (nicht-pausierte) Dauer innerhalb eines Zeitfensters.
     *
     * VORHER wurde in Dashboard/Timeline/Kalender/Insights überall
     * `(Ende - Start)` gerechnet und die Pause komplett ignoriert — die
     * Notification war die EINZIGE Stelle mit korrektem `activeMs`. Dadurch
     * zeigte die Timeline/Dashboard die Gesamt-Wanduhrzeit von Start bis
     * Ziel statt nur der tatsächlich aufgezeichneten Intervalle.
     *
     * Diese Funktion ist die zentrale Berechnung für ALLE Anzeigen:
     *  - Fenster-Clipping (Mitternachts-Sessions wie gehabt)
     *  - Abzug der Pausen-Segmente ([pauseSegmentsJson]), die in den
     *    Fensterausschnitt fallen
     *  - Laufende Pause (PAUSED + currentPauseStartedAt) zählt bis [now]
     *  - Fallback ohne Segment-Daten: akkumulierte [totalPausedMs] abziehen
     *    (exakt für Sessions, die vollständig im Fenster liegen; Näherung
     *    für Mitternachts-Sessions — dort sind Pausen aber untypisch)
     */
    fun activeDurationInWindow(
        windowStart: Long,
        windowEnd: Long,
        now: Long = System.currentTimeMillis()
    ): Long {
        val sessionEnd = endAt ?: minOf(now, windowEnd)
        val clipStart = startAt.coerceAtLeast(windowStart)
        val clipEnd = sessionEnd.coerceAtMost(windowEnd)
        val raw = (clipEnd - clipStart).coerceAtLeast(0L)
        if (raw <= 0L) return 0L

        val segments = parsePauseSegments()
        val pausedInWindow = if (segments.isNotEmpty()) {
            var acc = 0L
            segments.forEach { (segStart, segEnd) ->
                val overlap = minOf(segEnd, clipEnd) - maxOf(segStart, clipStart)
                if (overlap > 0L) acc += overlap
            }
            // Laufende Pause: Segment noch nicht abgeschlossen
            if (isPaused && currentPauseStartedAt != null) {
                val pauseEnd = minOf(now, clipEnd)
                val overlap = pauseEnd - maxOf(currentPauseStartedAt, clipStart)
                if (overlap > 0L) acc += overlap
            }
            acc
        } else {
            // Fallback: Gesamt-Pausenzeit abziehen
            effectivePausedMs(now)
        }
        return (raw - pausedInWindow).coerceAtLeast(0L)
    }

    /**
     * M18.62-FIX: Pausen-Segmente aus [pauseSegmentsJson] parsen.
     * Format: `[{"s": <startMs>, "e": <endMs>}, ...]`.
     * Robust gegen fehlende/korrupte Daten (→ leere Liste).
     */
    fun parsePauseSegments(): List<Pair<Long, Long>> {
        val json = pauseSegmentsJson ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val s = o.optLong("s")
                val e = o.optLong("e")
                if (e > s) s to e else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
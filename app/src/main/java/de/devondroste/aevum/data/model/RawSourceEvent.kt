package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "raw_source_event",
    indices = [
        Index(value = ["source_id", "external_id"], unique = true),
        Index(value = ["source_id", "observed_at"]),
        Index("processed_at")
    ],
    foreignKeys = [
        ForeignKey(
            entity = DataSource::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RawSourceEvent(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "external_id") val externalId: String? = null,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "start_at") val startAt: Long? = null,
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    @ColumnInfo(name = "timezone_id") val timezoneId: String? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 1,
    @ColumnInfo(name = "ingested_at") val ingestedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "processed_at") val processedAt: Long? = null
) : Serializable
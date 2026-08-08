package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "raw_detection_event",
    indices = [Index(value = ["source", "occurred_at"]), Index("processed_at")]
)
data class RawDetectionEvent(
    @PrimaryKey val id: String,
    val source: String,
    val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "processed_at") val processedAt: Long? = null
) : Serializable

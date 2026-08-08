package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.io.Serializable

@Entity(
    tableName = "activity_session_tag",
    primaryKeys = ["session_id", "tag_id"],
    indices = [Index("tag_id")],
    foreignKeys = [
        ForeignKey(
            entity = ActivitySession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ActivitySessionTag(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "tag_id") val tagId: String
) : Serializable

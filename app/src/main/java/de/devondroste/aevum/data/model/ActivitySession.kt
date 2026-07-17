package de.devondroste.aevum.data.model

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
        Index(value = ["status", "start_at"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ActivitySession(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "category_id", index = true) val categoryId: String?,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    val description: String? = null,
    val source: String = "MANUAL",
    val confidence: Float = 1.0f,
    val status: String = "CANDIDATE",
    @ColumnInfo(name = "is_user_edited") val isUserEdited: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable

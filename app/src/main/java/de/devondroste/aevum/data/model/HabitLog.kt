package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "habit_log",
    indices = [Index(value = ["habit_id", "date"]), Index("source_session_id")],
    foreignKeys = [
        ForeignKey(entity = Habit::class, parentColumns = ["id"], childColumns = ["habit_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ActivitySession::class, parentColumns = ["id"], childColumns = ["source_session_id"], onDelete = ForeignKey.SET_NULL)
    ]
)
data class HabitLog(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    val date: String,
    val status: String,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String? = null
) : Serializable

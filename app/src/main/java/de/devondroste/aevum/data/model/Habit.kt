package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "habit",
    indices = [Index("active"), Index("category_id")],
    foreignKeys = [
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.SET_NULL)
    ]
)
data class Habit(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "frequency_type") val frequencyType: String,
    @ColumnInfo(name = "target_count") val targetCount: Int,
    @ColumnInfo(name = "target_minutes") val targetMinutes: Int? = null,
    val active: Boolean = true
) : Serializable

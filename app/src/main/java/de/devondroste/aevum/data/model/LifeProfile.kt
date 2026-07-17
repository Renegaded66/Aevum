package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "life_profile")
data class LifeProfile(
    @PrimaryKey val id: String = "default",
    @ColumnInfo(name = "birth_date") val birthDate: String? = null,
    @ColumnInfo(name = "life_expectancy_years") val lifeExpectancyYears: Int? = null,
    @ColumnInfo(name = "ideal_week_json") val idealWeekJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable

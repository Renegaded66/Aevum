package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "activity_type",
    indices = [Index("default_category_id"), Index("is_system")],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["default_category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ActivityType(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: String? = null,
    @ColumnInfo(name = "is_system") val isSystem: Boolean = true,
    @ColumnInfo(name = "properties_json") val propertiesJson: String? = null
) : Serializable
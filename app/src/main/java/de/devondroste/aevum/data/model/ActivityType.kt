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
    @ColumnInfo(name = "properties_json") val propertiesJson: String? = null,
    // M9.2: Favorites — simple boolean, no priority, no order, no groups
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
    // M18: Positivitäts-Score 0..100. 50 = neutral, 100 = sehr gut,
    // 0 = sehr schlecht. Wird im Dashboard als "Zeitqualität" gewichtet.
    // Default 50 ist bewusst neutral — keine Wertung ohne User-Entscheidung.
    @ColumnInfo(name = "positivity_score", defaultValue = "50") val positivityScore: Int = 50,
    // M18.12: Icon (Emoji oder Icon-Key) + custom Farbe (ARGB-Int).
    // Jede Aktivität bekommt ein Icon, das der User custom färben kann.
    // Default: neutrales Symbol + Primärfarbe.
    @ColumnInfo(name = "icon", defaultValue = "•") val icon: String = "•",
    @ColumnInfo(name = "color", defaultValue = "0") val color: Long = 0L
) : Serializable

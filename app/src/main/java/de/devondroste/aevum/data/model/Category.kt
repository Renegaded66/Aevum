package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "category",
    indices = [Index("sort_order")]
)
data class Category(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val icon: String,
    @ColumnInfo(name = "is_system") val isSystem: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
) : Serializable

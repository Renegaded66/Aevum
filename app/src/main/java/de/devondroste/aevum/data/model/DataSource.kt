package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "data_source",
    indices = [Index("type")]
)
data class DataSource(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "permission_state") val permissionState: String = "UNKNOWN",
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "config_json") val configJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable
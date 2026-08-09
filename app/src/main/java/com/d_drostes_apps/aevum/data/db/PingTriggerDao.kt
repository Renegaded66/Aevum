package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.PingTrigger
import kotlinx.coroutines.flow.Flow

@Dao
interface PingTriggerDao {
    @Query("SELECT * FROM ping_trigger ORDER BY createdAt ASC")
    fun getAll(): Flow<List<PingTrigger>>

    @Query("SELECT * FROM ping_trigger WHERE enabled = 1")
    suspend fun getAllEnabled(): List<PingTrigger>

    @Query("SELECT * FROM ping_trigger WHERE id = :id")
    suspend fun getById(id: String): PingTrigger?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trigger: PingTrigger)

    @Query("UPDATE ping_trigger SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM ping_trigger WHERE id = :id")
    suspend fun delete(id: String)
}

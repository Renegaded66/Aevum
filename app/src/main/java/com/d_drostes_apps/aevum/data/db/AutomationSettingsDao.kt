package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationSettingsDao {
    @Query("SELECT * FROM automation_settings WHERE id = 'default'")
    fun get(): Flow<AutomationSettings?>

    @Query("SELECT * FROM automation_settings WHERE id = 'default'")
    suspend fun getSettingsSync(): AutomationSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AutomationSettings)
}
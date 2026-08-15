package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AppTrackingEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface AppTrackingEntryDao {
    @Query("SELECT * FROM app_tracking_entry ORDER BY package_name")
    fun getAll(): Flow<List<AppTrackingEntry>>

    @Query("SELECT * FROM app_tracking_entry WHERE package_name = :packageName")
    suspend fun getByPackageOnce(packageName: String): AppTrackingEntry?

    @Query("SELECT * FROM app_tracking_entry WHERE enabled = 1")
    suspend fun getEnabledOnce(): List<AppTrackingEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AppTrackingEntry)

    @Query("DELETE FROM app_tracking_entry WHERE package_name = :packageName")
    suspend fun delete(packageName: String)
}

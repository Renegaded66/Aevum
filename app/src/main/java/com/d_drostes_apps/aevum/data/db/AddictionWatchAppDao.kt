package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AddictionWatchApp
import kotlinx.coroutines.flow.Flow

/** M27: Auswahl der Apps, die der Sucht-Detektor beobachtet. */
@Dao
interface AddictionWatchAppDao {
    @Query("SELECT * FROM addiction_watch_app ORDER BY sort_order, app_label COLLATE NOCASE")
    fun getAll(): Flow<List<AddictionWatchApp>>

    @Query("SELECT * FROM addiction_watch_app ORDER BY sort_order, app_label COLLATE NOCASE")
    suspend fun getAllOnce(): List<AddictionWatchApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: AddictionWatchApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<AddictionWatchApp>)

    @Query("DELETE FROM addiction_watch_app WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM addiction_watch_app")
    suspend fun maxSortOrder(): Int
}

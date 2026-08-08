package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface DataSourceDao {
    @Query("SELECT * FROM data_source WHERE id = :id")
    fun getById(id: String): Flow<DataSource?>

    @Query("SELECT * FROM data_source WHERE type = :type")
    fun getByType(type: String): Flow<DataSource?>

    @Query("SELECT * FROM data_source WHERE enabled = 1 ORDER BY name")
    fun getEnabled(): Flow<List<DataSource>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: DataSource)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<DataSource>)

    @Query("UPDATE data_source SET last_sync_at = :now, updated_at = :now WHERE id = :id")
    suspend fun updateLastSync(id: String, now: Long)
}
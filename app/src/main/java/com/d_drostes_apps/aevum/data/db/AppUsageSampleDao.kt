package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AppUsageSample
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageSampleDao {
    @Query("SELECT * FROM app_usage_sample ORDER BY start_at DESC")
    fun getAll(): Flow<List<AppUsageSample>>

    @Query("SELECT * FROM app_usage_sample WHERE start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByDateRange(start: Long, end: Long): Flow<List<AppUsageSample>>

    @Query("SELECT * FROM app_usage_sample WHERE package_name = :pkg ORDER BY start_at DESC")
    fun getByPackage(pkg: String): Flow<List<AppUsageSample>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: AppUsageSample)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<AppUsageSample>)

    @Query("DELETE FROM app_usage_sample WHERE start_at < :cutoff")
    suspend fun deleteOld(cutoff: Long)
}
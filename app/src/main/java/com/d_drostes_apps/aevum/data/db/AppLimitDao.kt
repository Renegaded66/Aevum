package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AppLimit
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLimitDao {
    @Query("SELECT * FROM app_limit ORDER BY package_name")
    fun getAll(): Flow<List<AppLimit>>

    @Query("SELECT * FROM app_limit WHERE package_name = :packageName")
    fun getByPackage(packageName: String): Flow<AppLimit?>

    @Query("SELECT * FROM app_limit WHERE package_name = :packageName")
    suspend fun getByPackageOnce(packageName: String): AppLimit?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(limit: AppLimit)

    @Query("DELETE FROM app_limit WHERE package_name = :packageName")
    suspend fun delete(packageName: String)
}

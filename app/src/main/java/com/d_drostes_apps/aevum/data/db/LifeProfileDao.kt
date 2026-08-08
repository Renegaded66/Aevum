package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.LifeProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface LifeProfileDao {
    @Query("SELECT * FROM life_profile WHERE id = 'default'")
    fun getDefault(): Flow<LifeProfile?>

    @Query("SELECT * FROM life_profile WHERE id = :id")
    fun getById(id: String): Flow<LifeProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: LifeProfile)

    @Query("UPDATE life_profile SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)
}
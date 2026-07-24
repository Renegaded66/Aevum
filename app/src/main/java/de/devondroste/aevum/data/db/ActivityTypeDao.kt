package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.devondroste.aevum.data.model.ActivityType
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityTypeDao {
    @Query("SELECT * FROM activity_type WHERE id = :id")
    fun getById(id: String): Flow<ActivityType?>

    @Query("SELECT * FROM activity_type WHERE is_system = 1 ORDER BY name")
    fun getSystemTypes(): Flow<List<ActivityType>>

    @Query("SELECT * FROM activity_type ORDER BY name")
    fun getAll(): Flow<List<ActivityType>>

    // M9.2: Favorites
    @Query("SELECT * FROM activity_type WHERE is_favorite = 1 ORDER BY name")
    fun getFavorites(): Flow<List<ActivityType>>

    @Query("UPDATE activity_type SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(type: ActivityType)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(types: List<ActivityType>)

    @Update
    suspend fun update(type: ActivityType)
}
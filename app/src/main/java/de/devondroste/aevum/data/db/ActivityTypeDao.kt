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

    // M18: Positivitäts-Score (0-100) direkt setzen — kein @Update nötig,
    // das würde das ganze Objekt laden/vergleichen.
    @Query("UPDATE activity_type SET positivity_score = :score WHERE id = :id")
    suspend fun setPositivityScore(id: String, score: Int)

    // M18.12: Icon + custom Farbe setzen.
    @Query("UPDATE activity_type SET icon = :icon WHERE id = :id")
    suspend fun setIcon(id: String, icon: String)

    @Query("UPDATE activity_type SET color = :color WHERE id = :id")
    suspend fun setColor(id: String, color: Long)

    // M18.17: Kategorie einer Aktivität zuweisen (null = keine).
    @Query("UPDATE activity_type SET default_category_id = :categoryId WHERE id = :id")
    suspend fun setCategory(id: String, categoryId: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(type: ActivityType)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(types: List<ActivityType>)

    @Update
    suspend fun update(type: ActivityType)
}
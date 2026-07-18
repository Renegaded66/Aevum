package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.devondroste.aevum.data.model.Goal
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Query("SELECT * FROM goal WHERE status = 'ACTIVE' ORDER BY start_at")
    fun getActive(): Flow<List<Goal>>

    @Query("SELECT * FROM goal ORDER BY start_at DESC")
    fun getAll(): Flow<List<Goal>>

    @Query("SELECT * FROM goal WHERE status = :status ORDER BY start_at DESC")
    fun getByStatus(status: String): Flow<List<Goal>>

    @Query("SELECT * FROM goal WHERE id = :id")
    fun getById(id: String): Flow<Goal?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: Goal)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(goals: List<Goal>)

    @Update
    suspend fun update(goal: Goal)

    @Query("DELETE FROM goal WHERE id = :id")
    suspend fun delete(id: String)
}
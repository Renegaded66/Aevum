package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.devondroste.aevum.data.model.Habit
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habit WHERE active = 1 ORDER BY title")
    fun getActive(): Flow<List<Habit>>

    @Query("SELECT * FROM habit ORDER BY title")
    fun getAll(): Flow<List<Habit>>

    @Query("SELECT * FROM habit WHERE id = :id")
    fun getById(id: String): Flow<Habit?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habit: Habit)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(habits: List<Habit>)

    @Update
    suspend fun update(habit: Habit)

    @Query("DELETE FROM habit WHERE id = :id")
    suspend fun delete(id: String)
}
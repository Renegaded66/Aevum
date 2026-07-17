package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.devondroste.aevum.data.model.HabitLog
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitLogDao {
    @Query("SELECT * FROM habit_log WHERE habit_id = :habitId ORDER BY date DESC")
    fun getByHabit(habitId: String): Flow<List<HabitLog>>

    @Query("SELECT * FROM habit_log WHERE date = :date")
    fun getByDate(date: String): Flow<List<HabitLog>>

    @Query("SELECT * FROM habit_log WHERE habit_id = :habitId AND date = :date")
    fun getByHabitAndDate(habitId: String, date: String): Flow<HabitLog?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: HabitLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<HabitLog>)

    @Update
    suspend fun update(log: HabitLog)

    @Query("DELETE FROM habit_log WHERE habit_id = :habitId AND date = :date")
    suspend fun deleteByHabitAndDate(habitId: String, date: String)

    @Query("DELETE FROM habit_log WHERE id = :id")
    suspend fun delete(id: String)
}
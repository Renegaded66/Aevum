package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.AllowanceAccumulationDay
import de.devondroste.aevum.data.model.DailyAllowance
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyAllowanceDao {
    @Query("SELECT * FROM daily_allowance ORDER BY name")
    fun getAll(): Flow<List<DailyAllowance>>

    @Query("SELECT * FROM daily_allowance WHERE enabled = 1")
    suspend fun getEnabled(): List<DailyAllowance>

    @Query("SELECT * FROM daily_allowance WHERE id = :id")
    suspend fun getById(id: String): DailyAllowance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(allowance: DailyAllowance)

    @Query("UPDATE daily_allowance SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("DELETE FROM daily_allowance WHERE id = :id")
    suspend fun delete(id: String)

    // Accumulation table queries
    @Query("SELECT * FROM allowance_accumulation_day WHERE date = :date")
    suspend fun getAccumulationForDate(date: String): List<AllowanceAccumulationDay>

    @Query("SELECT * FROM allowance_accumulation_day WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getAccumulationInRange(startDate: String, endDate: String): List<AllowanceAccumulationDay>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccumulation(accumulation: AllowanceAccumulationDay)
}

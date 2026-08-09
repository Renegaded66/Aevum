package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.AllowanceDayOverride
import com.d_drostes_apps.aevum.data.model.DailyAllowance
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

    // M18.37-FIX (Root Cause): Beim Loeschen einer Pauschale muessen auch
    // ihre Accumulations weg — sonst zaehlt eine geloeschte + neu erstellte
    // Pauschale doppelt (alte Accumulation bleibt in der DB).
    @Query("DELETE FROM allowance_accumulation_day WHERE allowance_id = :allowanceId")
    suspend fun deleteAccumulationsForAllowance(allowanceId: String)

    // M18.60: Pro-Tag-Overrides — der Tageswert einer Pauschale kann
    // fuer einen einzelnen Tag abweichen (User: "Pauschalzeiten einmalig
    // anpassen, z.B. wenn man an einem Tag mehr/weniger Zeit gebraucht
    // hat"). Der Override gewinnt gegen den Pauschalen-Wert beim Lesen,
    // die Pauschale selbst bleibt unveraendert.
    @Query("SELECT * FROM allowance_day_override WHERE date = :date")
    suspend fun getOverridesForDate(date: String): List<AllowanceDayOverride>

    // M18.60-CRASH-FIX 3: Flow-Version fuer die Tages-Navigation.
    // Der Dashboard-combine muss Overrides pro Tag FRISCH abonnieren
    // (flatMapLatest) — sonst bleiben die Daten beim Tag-Wechsel stehen.
    @Query("SELECT * FROM allowance_day_override WHERE date = :date")
    fun getOverridesForDateFlow(date: String): Flow<List<AllowanceDayOverride>>

    @Query("SELECT * FROM allowance_day_override WHERE date = :date AND allowance_id = :allowanceId")
    suspend fun getOverride(date: String, allowanceId: String): AllowanceDayOverride?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverride(override: AllowanceDayOverride)

    @Query("DELETE FROM allowance_day_override WHERE date = :date AND allowance_id = :allowanceId")
    suspend fun deleteOverride(date: String, allowanceId: String)

    // Accumulation table queries
    @Query("SELECT * FROM allowance_accumulation_day WHERE date = :date")
    suspend fun getAccumulationForDate(date: String): List<AllowanceAccumulationDay>

    @Query("SELECT * FROM allowance_accumulation_day WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getAccumulationInRange(startDate: String, endDate: String): List<AllowanceAccumulationDay>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccumulation(accumulation: AllowanceAccumulationDay)
}

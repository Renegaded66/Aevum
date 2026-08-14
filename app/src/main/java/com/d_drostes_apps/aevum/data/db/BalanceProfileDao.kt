package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.BalanceProfile
import com.d_drostes_apps.aevum.data.model.BalanceProfileApp
import kotlinx.coroutines.flow.Flow

@Dao
interface BalanceProfileDao {

    @Query("SELECT * FROM balance_profile ORDER BY created_at ASC")
    fun getAll(): Flow<List<BalanceProfile>>

    @Query("SELECT * FROM balance_profile WHERE is_active = 1 LIMIT 1")
    fun getActive(): Flow<BalanceProfile?>

    @Query("SELECT * FROM balance_profile WHERE id = :id")
    suspend fun getById(id: String): BalanceProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: BalanceProfile)

    @Query("UPDATE balance_profile SET is_active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE balance_profile SET is_active = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Query("DELETE FROM balance_profile WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM balance_profile_app WHERE profile_id = :profileId")
    suspend fun deleteApps(profileId: String)

    @Query("SELECT package_name FROM balance_profile_app WHERE profile_id = :profileId")
    suspend fun getAppPackages(profileId: String): List<String>

    @Query("SELECT * FROM balance_profile_app WHERE profile_id = :profileId")
    fun getAppsFlow(profileId: String): Flow<List<BalanceProfileApp>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: BalanceProfileApp)

    @Query("DELETE FROM balance_profile_app WHERE profile_id = :profileId AND package_name = :packageName")
    suspend fun removeApp(profileId: String, packageName: String)

    // M18.66-FIX14: Zeitplan-Felder updaten
    @Query("""UPDATE balance_profile SET 
        schedule_enabled = :enabled, 
        schedule_days = :days, 
        schedule_start_minute = :startMin, 
        schedule_end_minute = :endMin 
        WHERE id = :id""")
    suspend fun updateSchedule(id: String, enabled: Boolean, days: Int, startMin: Int, endMin: Int)

    // M18.66-FIX14: Alle Profile mit aktiviertem Zeitplan laden (für Worker)
    @Query("SELECT * FROM balance_profile WHERE schedule_enabled = 1")
    suspend fun getScheduledProfiles(): List<BalanceProfile>
}

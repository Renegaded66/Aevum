package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.GarminActivity
import com.d_drostes_apps.aevum.data.model.GarminDailySummary
import kotlinx.coroutines.flow.Flow

/**
 * M18.58: DAO für Garmin-Daten (Tageszusammenfassung + Aktivitäten).
 */
@Dao
interface GarminDao {

    // ── Tageszusammenfassung ──────────────────────────────────────

    @Query("SELECT * FROM garmin_daily_summary WHERE date = :date")
    fun getByDate(date: String): Flow<GarminDailySummary?>

    @Query("SELECT * FROM garmin_daily_summary WHERE date >= :start ORDER BY date")
    fun getFrom(start: String): Flow<List<GarminDailySummary>>

    @Query("SELECT * FROM garmin_daily_summary WHERE date >= :start AND date <= :end ORDER BY date")
    fun getByDateRange(start: String, end: String): Flow<List<GarminDailySummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: GarminDailySummary)

    // ── Aktivitäten ───────────────────────────────────────────────

    @Query("SELECT * FROM garmin_activity WHERE external_id = :externalId LIMIT 1")
    suspend fun getByExternalId(externalId: String): GarminActivity?

    @Query("SELECT * FROM garmin_activity WHERE start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByRange(start: Long, end: Long): Flow<List<GarminActivity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActivity(activity: GarminActivity)
}

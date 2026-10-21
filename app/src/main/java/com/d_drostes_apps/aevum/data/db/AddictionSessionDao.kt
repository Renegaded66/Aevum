package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AddictionSession
import kotlinx.coroutines.flow.Flow

/** M27: Persistierte Vordergrund-Sitzungen der beobachteten Apps. */
@Dao
interface AddictionSessionDao {
    @Query("SELECT * FROM addiction_session WHERE start_at >= :startAt ORDER BY start_at")
    fun getSince(startAt: Long): Flow<List<AddictionSession>>

    @Query("SELECT * FROM addiction_session WHERE start_at >= :startAt ORDER BY start_at")
    suspend fun getSinceOnce(startAt: Long): List<AddictionSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<AddictionSession>)

    @Query("DELETE FROM addiction_session WHERE start_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM addiction_session WHERE package_name = :packageName")
    suspend fun deleteForPackage(packageName: String)
}

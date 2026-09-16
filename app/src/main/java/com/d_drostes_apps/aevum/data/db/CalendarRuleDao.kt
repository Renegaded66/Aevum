package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.CalendarRule
import kotlinx.coroutines.flow.Flow

/**
 * M18.129: DAO für die Kalender-Regeln.
 *
 * Sortierung: `priority DESC, name` — die Regel-Liste in den Einstellungen
 * zeigt damit die wichtigste zuerst, und bei Mehrfach-Treffern ist die
 * Reihenfolge exakt die, die die Engine zum Auflösen nutzt.
 */
@Dao
interface CalendarRuleDao {

    @Query("SELECT * FROM calendar_rule ORDER BY priority DESC, name ASC")
    fun getAll(): Flow<List<CalendarRule>>

    @Query("SELECT * FROM calendar_rule ORDER BY priority DESC, name ASC")
    suspend fun getAllOnce(): List<CalendarRule>

    @Query("SELECT * FROM calendar_rule WHERE enabled = 1 ORDER BY priority DESC, name ASC")
    suspend fun getEnabledOnce(): List<CalendarRule>

    @Query("SELECT * FROM calendar_rule WHERE id = :id")
    suspend fun getByIdOnce(id: String): CalendarRule?

    @Query("SELECT COUNT(*) FROM calendar_rule")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CalendarRule)

    @Query("DELETE FROM calendar_rule WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE calendar_rule SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)
}

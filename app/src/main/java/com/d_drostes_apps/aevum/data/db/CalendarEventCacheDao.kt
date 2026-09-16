package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import kotlinx.coroutines.flow.Flow

/**
 * M18.129: DAO für den Kalender-Termin-Cache.
 *
 * Der Auto-Start-Worker liest ausschließlich über [getInWindowOnce]
 * (eine indizierte Bereichs-Query) — er fasst den ContentResolver nie an.
 */
@Dao
interface CalendarEventCacheDao {

    /**
     * Termine, die das Fenster [from, to] schneiden.
     * Bedingung: start < to UND end > from (echter Overlap, nicht
     * Containment) — ein Termin, der gestern begann und heute endet,
     * gehört dazu.
     */
    @Query("SELECT * FROM calendar_event_cache WHERE start_at < :to AND end_at > :from ORDER BY start_at ASC")
    suspend fun getInWindowOnce(from: Long, to: Long): List<CalendarEventCache>

    /** Reaktive Variante für die Timeline-Vorschau (7 Tage). */
    @Query("SELECT * FROM calendar_event_cache WHERE start_at < :to AND end_at > :from ORDER BY start_at ASC")
    fun getInWindow(from: Long, to: Long): Flow<List<CalendarEventCache>>

    /**
     * M18.129: ALLE gecachten Termine als Flow.
     *
     * Bewusst OHNE Datums-Parameter: eine Flow-Query mit Zeit-Parametern
     * friert diese beim Subscribe ein (M18.43-Lektion — „Room-Query mit
     * Datums-Parameter friert beim Subscribe ein"). Da der Sync-Worker den
     * Cache ohnehin auf sein Fenster beschneidet, ist „alles" exakt
     * richtig und über Mitternacht hinweg korrekt.
     */
    @Query("SELECT * FROM calendar_event_cache ORDER BY start_at ASC")
    fun getAll(): Flow<List<CalendarEventCache>>

    @Query("SELECT MAX(synced_at) FROM calendar_event_cache")
    suspend fun getLastSyncedAt(): Long?

    @Query("SELECT COUNT(*) FROM calendar_event_cache")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM calendar_event_cache")
    fun countFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<CalendarEventCache>)

    /**
     * Entfernt Termine, die vor [before] enden — hält den Cache klein,
     * ohne die 7-Tage-Vorschau zu beschädigen.
     */
    @Query("DELETE FROM calendar_event_cache WHERE end_at < :before")
    suspend fun deleteEndedBefore(before: Long)

    @Query("DELETE FROM calendar_event_cache")
    suspend fun clear()
}

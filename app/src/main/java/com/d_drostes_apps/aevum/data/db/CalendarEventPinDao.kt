package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import kotlinx.coroutines.flow.Flow

/**
 * M18.131: DAO für die manuell markierten Einzel-Termine.
 *
 * Der Auto-Start-Worker liest über [getActiveOnce] (eine Query, kein
 * ContentResolver) — dieselbe M18.104-Philosophie wie beim Termin-Cache.
 */
@Dao
interface CalendarEventPinDao {

    /** Alle Markierungen (reaktiv, für die Einstellungs-Liste). */
    @Query("SELECT * FROM calendar_event_pin ORDER BY event_start_at ASC")
    fun getAll(): Flow<List<CalendarEventPin>>

    @Query("SELECT * FROM calendar_event_pin ORDER BY event_start_at ASC")
    suspend fun getAllOnce(): List<CalendarEventPin>

    @Query("SELECT * FROM calendar_event_pin WHERE event_id = :eventId")
    suspend fun getByIdOnce(eventId: String): CalendarEventPin?

    /**
     * Markierungen im Zeitfenster — echte Überlappung, damit ein Termin,
     * der gestern begann und heute endet, mitgeliefert wird (gleiche
     * Semantik wie [CalendarEventCacheDao.getInWindowOnce]).
     */
    @Query("SELECT * FROM calendar_event_pin WHERE event_start_at < :to AND event_end_at > :from ORDER BY event_start_at ASC")
    suspend fun getInWindowOnce(from: Long, to: Long): List<CalendarEventPin>

    @Query("SELECT COUNT(*) FROM calendar_event_pin")
    fun countFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pin: CalendarEventPin)

    @Query("DELETE FROM calendar_event_pin WHERE event_id = :eventId")
    suspend fun deleteById(eventId: String)

    /** Entfernt alle Markierungen, deren Termin vor [before] endete. */
    @Query("DELETE FROM calendar_event_pin WHERE event_end_at < :before")
    suspend fun deleteEndedBefore(before: Long)

    /**
     * Entfernt Markierungen, deren [CalendarEventCache]-Eintrag nicht mehr
     * existiert (Termin im Kalender gelöscht oder verschoben).
     *
     * Der Aufrufer übergibt die aktuell gültigen Cache-Schlüssel; was nicht
     * dabei ist, ist verwaist. Bewusst als explizite Löschliste statt eines
     * Subselects: die Cache-Tabelle ist bei jedem Sync kurzzeitig im
     * Umbau, ein Subselect könnte dort einen halbfertigen Zustand sehen.
     *
     * `now` schützt eine LIEGENDE Aufzeichnung: hat ein Termin gerade
     * begonnen und der Nutzer löscht ihn im Kalender, während Aevum
     * aufzeichnet, bleibt seine Markierung bis zum Terminende erhalten.
     * Ohne diesen Schutz wäre die Stopp-Information weg und die Session
     * liefe bis zum 8-Stunden-Watchdog weiter — die Markierung trägt als
     * einziges verbliebenes Objekt die ursprüngliche Endzeit.
     */
    @Query("DELETE FROM calendar_event_pin WHERE event_id NOT IN (:validEventIds) AND (:now >= event_end_at OR :now < event_start_at)")
    suspend fun deleteOrphans(validEventIds: List<String>, now: Long)

    /** Entfernt alle Markierungen (z. B. wenn der Kalender-Sync abgeschaltet wird). */
    @Query("DELETE FROM calendar_event_pin")
    suspend fun clear()
}

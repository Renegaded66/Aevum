package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M18.129: Lokaler Cache der ausgelesenen Kalender-Termine.
 *
 * WARUM EIN CACHE:
 * Der [android.content.ContentResolver] des Kalenders ist teuer und
 * blockiert. Der Auto-Start-Worker läuft im Minutentakt — ihn bei jedem
 * Lauf den Kalender-Provider abfragen zu lassen wäre unnötiger Akku-
 * und IO-Verbrauch. Stattdessen:
 *
 *   SyncWorker (alle 6 h)  → liest den Kalender → schreibt in diesen Cache
 *   AutoRunWorker (Takt)   → liest NUR diesen Cache (eine Room-Query)
 *
 * M18.104-Lektion (Akku-Redesign): teure Operationen gehören in seltene
 * Bursts, nicht in den dauerhaften Takt.
 *
 * Die Tabelle ist ein reiner Spiegel — sie wird bei jedem Sync geleert und
 * neu befüllt. Kein FK auf activity_session: geplante Blöcke sind KEINE
 * Aufzeichnungen und dürfen niemals in Statistiken einfließen.
 */
@Entity(tableName = "calendar_event_cache")
data class CalendarEventCache(
    /**
     * Stabile, idempotente ID: "calendarId:eventId:instanceStart".
     *
     * WARUM NICHT die reine eventId: bei wiederkehrenden Terminen liefert
     * der Instances-Provider pro Vorkommen dieselbe eventId, aber einen
     * anderen instanceStart. Ohne den Start im Schlüssel würde eine
     * wöchentliche Vorlesung alle anderen Vorkommen überschreiben
     * (M18.64-Muster: externalId für idempotente Imports).
     */
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "calendar_id") val calendarId: String,
    @ColumnInfo(name = "calendar_name") val calendarName: String,
    val title: String,
    val description: String?,
    val location: String?,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    /** Ganztägige Termine: Kalender liefert 00:00–00:00, hier auf den Tag normalisiert. */
    @ColumnInfo(name = "all_day") val allDay: Boolean = false,
    /** Teilnehmer-E-Mails, kommagetrennt (für ATTENDEE_CONTAINS). */
    val attendees: String? = null,
    /** Zeitpunkt des letzten Syncs — für Debug + "Daten sind veraltet"-Hinweise. */
    @ColumnInfo(name = "synced_at") val syncedAt: Long = System.currentTimeMillis()
) : Serializable

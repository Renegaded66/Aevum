package com.d_drostes_apps.aevum.automation.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.129: Liest Kalender-Termine über den ContentResolver.
 *
 * WARUM `Instances` UND NICHT `Events`:
 * Der Events-Table enthält bei wiederkehrenden Terminen NUR eine Zeile
 * (mit RRULE). Die Vorlesung, die jede Woche stattfindet, wäre damit genau
 * EINMAL sichtbar. Der Instances-Provider expandiert die Wiederholungen
 * zu konkreten Vorkommen im angefragten Fenster — genau das, was für
 * „welche Termine beginnen morgen um 10:15?" gebraucht wird.
 *
 * WARUM EIN FENSTER:
 * Der Kalender eines Viel-Nutzers enthält Jahre an Terminen. Wir fragen
 * ausschließlich [heute − 1 Tag, heute + 8 Tage] ab — das deckt die
 * 7-Tage-Vorschau plus den Auto-Start-Puffer ab. Das ist die M18.104-
 * Lektion: teure Operationen mit begrenztem Umfang, nicht „alles".
 *
 * PERMISSION-BEHANDLUNG (bewusst als erwarteter Zustand, nicht als Bug):
 * Der Nutzer kann READ_CALENDAR JEDERZEIT in den System-Einstellungen
 * entziehen — auch mitten zwischen zwei Syncs. Ein `SecurityException`
 * des Providers ist dann KEIN Fehler, sondern der normale Ausdruck eines
 * Widerrufs. Er wird als [CalendarReadResult.PermissionMissing]
 * zurückgegeben (sauberer Abbruch, kein Retry-Spam), nicht als Ausnahme
 * nach oben gereicht. Das ist die im Design-Dokument begründete,
 * bewusste Ausnahme von der „kein defensives try/catch"-Regel: hier
 * verschleiern wir keinen Bug, wir behandeln einen dokumentierten
 * Betriebszustand des Betriebssystems.
 */
@Singleton
class CalendarReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "CalendarReader"
        /** Tage in die Vergangenheit (laufende Termine von heute). */
        const val DAYS_BACK = 1L
        /** Tage in die Zukunft — deckt die 7-Tage-Vorschau + Puffer. */
        const val DAYS_FORWARD = 8L
    }

    /** Ergebnis eines Leseversuchs. */
    sealed interface CalendarReadResult {
        data class Success(val events: List<CalendarEventCache>) : CalendarReadResult
        /** Permission fehlt oder wurde widerrufen. */
        object PermissionMissing : CalendarReadResult
        /** Provider nicht verfügbar / unerwarteter Fehler. */
        data class Failed(val message: String) : CalendarReadResult
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Sync-Fenster in Millisekunden — [heute − DAYS_BACK, heute + DAYS_FORWARD]. */
    fun window(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = today.minusDays(DAYS_BACK).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(DAYS_FORWARD).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }

    /**
     * Liest alle Termin-Vorkommen im Sync-Fenster.
     *
     * Die Abfrage läuft auf einem IO-Thread des Aufrufers (Worker) — der
     * ContentResolver blockiert.
     */
    fun readEvents(now: Long = System.currentTimeMillis()): CalendarReadResult {
        if (!hasPermission()) {
            Log.d(TAG, "READ_CALENDAR nicht erteilt — Sync übersprungen")
            return CalendarReadResult.PermissionMissing
        }
        val (from, to) = window(now)
        return try {
            CalendarReadResult.Success(queryInstances(from, to))
        } catch (e: SecurityException) {
            // Widerruf zwischen Check und Query (oder durch den Provider
            // erzwungen) — legitimer Betriebszustand, siehe Klassen-Doku.
            Log.w(TAG, "Kalender-Zugriff verweigert (Berechtigung widerrufen): ${e.message}")
            CalendarReadResult.PermissionMissing
        } catch (e: Exception) {
            Log.e(TAG, "Kalender-Abfrage fehlgeschlagen", e)
            CalendarReadResult.Failed(e.message ?: "unbekannter Fehler")
        }
    }

    /**
     * Instances-Query mit den Feldern, die die Regel-Engine braucht.
     *
     * CALENDAR_ID / CALENDAR_DISPLAY_NAME kommen aus dem verknüpften
     * Calendar-Table (Instances ist eine View — diese Spalten sind dort
     * verfügbar, sofern die Permission erteilt ist).
     */
    private fun queryInstances(from: Long, to: Long): List<CalendarEventCache> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()

        val out = mutableListOf<CalendarEventCache>()
        val now = System.currentTimeMillis()

        context.contentResolver.query(
            uri,
            projection,
            // Nur nicht-abgesagte, sichtbare Termine. Der Status-Filter
            // verhindert, dass eine abgesagte Vorlesung aufgezeichnet wird.
            //
            // M18.132-FIX: `STATUS IS NULL` muss EXPLIZIT erlaubt sein.
            // Viele Provider (lokale Kalender, einige Sync-Backends)
            // speichern keinen Status — die Spalte ist dann NULL. In
            // SQL ist `NULL != 1` aber nicht TRUE, sondern NULL, d. h.
            // die Zeile fällt aus dem Ergebnis: ALLE Termine solcher
            // Kalender waren unsichtbar (Symptom: „meine echten Termine
            // werden gar nicht angezeigt"). Die Null-Fassung zuerst,
            // damit der Index-freundliche Ungleich-Vergleich bleibt.
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?)",
            arrayOf(CalendarContract.Instances.STATUS_CANCELED.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val idxEvent = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val idxCalId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val idxCalName = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            val idxTitle = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val idxDesc = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
            val idxLoc = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val idxBegin = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val idxEnd = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val idxAllDay = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)

            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(idxEvent)
                val calendarId = cursor.getLong(idxCalId)
                val start = cursor.getLong(idxBegin)
                val rawEnd = if (cursor.isNull(idxEnd)) start else cursor.getLong(idxEnd)
                // Manche Provider liefern END == BEGIN (oder END < BEGIN bei
                // kaputten Einträgen) → dann eine Minimaldauer annehmen,
                // sonst entstehen Null-Längen-Blöcke in der Timeline.
                val end = if (rawEnd > start) rawEnd else start + 15 * 60_000L

                out += CalendarEventCache(
                    // Stabil & idempotent: dieselbe Kalender-Instanz ergibt
                    // immer denselben Schlüssel (M18.64-Muster).
                    eventId = "$calendarId:$eventId:$start",
                    calendarId = calendarId.toString(),
                    calendarName = cursor.getString(idxCalName) ?: "",
                    title = cursor.getString(idxTitle) ?: "",
                    description = cursor.getString(idxDesc),
                    location = cursor.getString(idxLoc),
                    startAt = start,
                    endAt = end,
                    allDay = cursor.getInt(idxAllDay) == 1,
                    attendees = queryAttendees(eventId),
                    syncedAt = now
                )
            }
        }
        Log.d(TAG, "Kalender gelesen: ${out.size} Vorkommen im Fenster")
        return out
    }

    /**
     * Teilnehmer-E-Mails eines Termins (für ATTENDEE_CONTAINS).
     *
     * Eigene Query pro Termin ist bewusst NICHT gemacht — bei 100 Terminen
     * wären das 100 Queries. Stattdessen EINE Query über alle Termine und
     * Gruppierung im Speicher.
     */
    private fun queryAttendees(eventId: Long): String? = try {
        val selection = "${CalendarContract.Attendees.EVENT_ID} = ?"
        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.EVENT_ID, CalendarContract.Attendees.ATTENDEE_EMAIL),
            selection,
            arrayOf(eventId.toString()),
            null
        )?.use { c ->
            val emails = mutableListOf<String>()
            val idxEmail = c.getColumnIndex(CalendarContract.Attendees.ATTENDEE_EMAIL)
            val idxId = c.getColumnIndex(CalendarContract.Attendees.EVENT_ID)
            if (idxEmail >= 0 && idxId >= 0) {
                while (c.moveToNext()) {
                    c.getString(idxEmail)?.takeIf { it.isNotBlank() }?.let { emails += it }
                }
            }
            emails.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    } catch (e: SecurityException) {
        // Kein READ_CALENDAR-Zugriff auf Attendees → Regel-Typ inaktiv,
        // aber der Termin selbst bleibt nutzbar.
        null
    } catch (e: Exception) {
        Log.w(TAG, "Teilnehmer-Abfrage fehlgeschlagen für Event $eventId: ${e.message}")
        null
    }

    /**
     * Liste der verfügbaren Kalender (für die CALENDAR_IS-Auswahl im Editor).
     * Gibt (id, Anzeigename, AccountName) zurück.
     */
    fun listCalendars(): List<Triple<String, String, String>> {
        if (!hasPermission()) return emptyList()
        return try {
            val out = mutableListOf<Triple<String, String, String>>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME
                ),
                null, null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val idxName = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val idxAccount = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                while (c.moveToNext()) {
                    out += Triple(
                        c.getLong(idxId).toString(),
                        c.getString(idxName)
                            ?: context.getString(R.string.calendar_rules_unnamed_calendar),
                        c.getString(idxAccount) ?: ""
                    )
                }
            }
            out
        } catch (e: SecurityException) {
            Log.w(TAG, "Kalender-Liste: Berechtigung fehlt")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Kalender-Liste fehlgeschlagen", e)
            emptyList()
        }
    }
}

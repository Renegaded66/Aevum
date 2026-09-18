package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M18.131: Ein EINZELNER Kalender-Termin, den der Nutzer manuell zur
 * Aufzeichnung freigegeben hat („Termin → Activity", ohne Regel).
 *
 * ═══════════════════════════════════════════════════════════════════════
 * WARUM EINE EIGENE TABELLE UND KEINE REGEL
 *
 * Regeln (calendar_rule) matchen über TITEL/BESCHREIBUNG/Kalender. Ein
 * Regel-Match trifft damit zwangsläufig JEDES Vorkommen: „Vorlesung" als
 * Regel erfasst auch die Vorlesung in drei Wochen. Für „diesen einen
 * Termin am Donnerstag" ist das das falsche Werkzeug — der Nutzer müsste
 * eine Regel erfinden, die genau einmal greift (Titel-Unikat, Zeitfenster,
 * Wochentag …) und sie danach wieder löschen. Genau das ist die
 * Fehlerquelle, die diese Tabelle beseitigt: die Zuordnung hängt an der
 * TERMIN-INSTANZ, nicht an einem Textmuster.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * DER SCHLÜSSEL — UND WARUM ER DIE QUELLE ALLER RANDFÄLLE IST
 *
 * [eventId] ist derselbe stabile Instanz-Schlüssel, den der Cache benutzt:
 * `"<calendarId>:<eventId>:<instanceStart>"`. Er enthält den Startzeitpunkt
 * der konkreten Instanz — deshalb gilt:
 *
 *  - Wiederkehrender Termin (jede Woche): jede Woche eine ANDERE
 *    [eventId] → die Markierung trifft genau das eine Vorkommen. Das ist
 *    gewollt und der Hauptgrund für diesen Schlüssel.
 *  - Termin im Kalender GELÖSCHT → er verschwindet beim nächsten Sync aus
 *    dem Cache, die Markierung wird nie mehr ausgewertet und beim Aufräumen
 *    entfernt (siehe CalendarEventPinDao.pruneOrphans). Es wird also NICHTS
 *    mehr aufgezeichnet — genau wie vom Auftrag gefordert.
 *  - Termin VERSCHOBEN → der Kalender liefert einen neuen instanceStart,
 *    damit eine neue [eventId]. Die alte Markierung ist verwaist (und wird
 *    aufgeräumt), der verschobene Termin ist NICHT mehr markiert. Das ist
 *    die ehrliche Variante: Aevum zeichnet nicht heimlich zu neuen Zeiten
 *    auf, die der Nutzer nie bestätigt hat.
 *  - Termin INHALTLICH geändert (Titel/Zeit gleich) → [eventId] bleibt,
 *    die Markierung gilt weiter.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FK-VERHALTEN
 *
 * `activityTypeId` → ActivityType mit ON DELETE SET NULL (M18.50/51-Muster):
 * der Nutzer darf jeden Typ außer sleep/other löschen. Markierungen mit
 * null-Typ werden zur Laufzeit übersprungen und in der UI als „Aktivität
 * fehlt" markiert statt die App crashen zu lassen.
 *
 * BEWUSST KEIN FK auf calendar_event_cache: der Cache wird bei jedem Sync
 * geleert und neu befüllt (reiner Spiegel). Ein FK mit CASCADE würde die
 * Nutzer-Markierung bei jedem Sync mitlöschen — der schlimmste denkbare
 * Datenverlust hier. Deshalb: Markierung lebt unabhängig, das Aufräumen
 * verwaister Einträge ist ein expliziter, getesteter Schritt.
 */
@Entity(
    tableName = "calendar_event_pin",
    indices = [
        Index("activity_type_id"),   // FK-Spalte (Room-Pflicht)
        Index("event_start_at"),     // Für das Aufräumen abgelaufener Markierungen
        Index(value = ["event_id"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class CalendarEventPin(
    /** Instanz-Schlüssel: `"<calendarId>:<eventId>:<instanceStart>"` (PK). */
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    /** Die aufzuzeichnende Aktivität. Nullable wegen ON DELETE SET NULL. */
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    /**
     * Optionaler Session-Titel. Null = Name des ActivityType
     * (M18.66-FIX9-Muster: die Session heißt wie die Aktivität).
     */
    @ColumnInfo(name = "default_title") val defaultTitle: String? = null,
    /**
     * Kopie des Termin-Starts.
     *
     * WARUM NICHT DEN CACHE ALS QUELLE NUTZEN: der Sync leert den Cache
     * und schreibt ihn neu. Für das Aufräumen („Markierung zeigen, deren
     * Termin längst vorbei ist") und für den Fall, dass der Termin aus dem
     * Sync-Fenster fällt, während die Aufzeichnung noch läuft, braucht der
     * Pin seine eigenen Zeitangaben. Ohne sie könnte der Nutzer eine
     * Markierung weder sehen noch entfernen, nachdem ihr Termin aus dem
     * Cache gefallen ist.
     */
    @ColumnInfo(name = "event_start_at") val eventStartAt: Long,
    @ColumnInfo(name = "event_end_at") val eventEndAt: Long,
    /**
     * Titel-Kopie für die UI. Der Pin muss auch dann lesbar sein, wenn der
     * Termin nicht mehr im Cache steht (Sync-Fenster verlassen, Kalender
     * offline). Ohne diese Kopie stünde in der Liste ein leerer Eintrag.
     */
    @ColumnInfo(name = "event_title") val eventTitle: String,
    @ColumnInfo(name = "calendar_name") val calendarName: String = "",
    /**
     * Was passiert, wenn beim Termin-Start bereits eine Aufzeichnung läuft.
     * Gleiche Semantik wie [CalendarRule.overlapPolicy]: OVERRIDE beendet
     * die laufende Session, ONLY_IF_IDLE startet nur bei Leerlauf.
     */
    @ColumnInfo(name = "overlap_policy", defaultValue = "OVERRIDE")
    val overlapPolicy: String = CalendarOverlapPolicy.OVERRIDE,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable

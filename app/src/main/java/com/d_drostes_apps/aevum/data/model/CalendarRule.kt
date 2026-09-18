package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M18.129: KALENDER-INTEGRATION — eine Regel verbindet einen
 * Kalender-Termin mit einer Aevum-Activity.
 *
 * Das Kern-Dilemma: der Kalender kennt nur Titel + Beschreibung,
 * Aevum zeichnet Activity-Types auf. Statt unscharfem Fuzzy-Matching
 * (das bei "Vorlesung Analysis" scheitern würde) definiert der Nutzer
 * explizite Regeln:
 *
 *   WENN Titel ODER Beschreibung "Vorlesung" oder "Übung" enthält
 *   DANN Activity "Studium" aufzeichnen (Start = Termin-Start)
 *
 * Alle Felder sind im Editor einstellbar — nichts ist versteckt.
 *
 * FK-Verhalten: `activityTypeId` referenziert ActivityType mit
 * ON DELETE SET NULL (M18.50/51-Muster: der Nutzer darf jeden Typ außer
 * `sleep`/`other` löschen). Regeln mit null-Typ werden zur Laufzeit
 * übersprungen und in der Liste als "Aktivität fehlt" markiert statt
 * die App crashen zu lassen.
 */
@Entity(
    tableName = "calendar_rule",
    // Nur der FK-Index — Room verlangt für jede FK-Spalte einen Index.
    // KEIN zusätzlicher Index auf `enabled`: die Tabelle enthält eine
    // Handvoll Regeln, ein Index wäre reine Schema-Mismatch-Fläche ohne
    // Nutzen (M18.40/41-Lektion: jede deklarierte Struktur MUSS exakt in
    // der Migration existieren, sonst crasht Room beim DB-Öffnen).
    indices = [Index("activity_type_id")],
    foreignKeys = [
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class CalendarRule(
    @PrimaryKey val id: String,
    /** Anzeigename für die Regel-Liste (z.B. "Studium aus Kalender"). */
    val name: String,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
    /** Siehe [CalendarRuleType] — als String persistiert (Room-freundlich). */
    @ColumnInfo(name = "match_type") val matchType: String = CalendarRuleType.ANY_FIELD_CONTAINS,
    /**
     * Suchwert. Bei den *_CONTAINS-Typen: durch Komma/Semikolon getrennte
     * Wörter (ODER-Verknüpfung, sofern [requireAllWords] false ist).
     * Bei TITLE_REGEX: das Muster selbst.
     * Bei den übrigen Typen: unbenutzt (leer).
     */
    @ColumnInfo(name = "match_value", defaultValue = "") val matchValue: String = "",
    /**
     * Nur für [CalendarRuleType.CALENDAR_IS]: JSON-Array der Kalender-IDs.
     * Bewusst als String — keine zweite Tabelle für eine simple Auswahl.
     */
    @ColumnInfo(name = "match_calendar_ids") val matchCalendarIds: String? = null,
    @ColumnInfo(name = "case_sensitive", defaultValue = "0") val caseSensitive: Boolean = false,
    /** true = ALLE Wörter müssen vorkommen (UND), false = eines genügt (ODER). */
    @ColumnInfo(name = "require_all_words", defaultValue = "0") val requireAllWords: Boolean = false,
    /**
     * true = nur den Titel durchsuchen (Beschreibung ignorieren).
     * false (Default) = Titel + Beschreibung.
     */
    @ColumnInfo(name = "title_only", defaultValue = "0") val titleOnly: Boolean = false,
    /**
     * Die aufzuzeichnende Aktivität. Nullable wegen ON DELETE SET NULL —
     * eine Regel mit gelöschtem Typ wird zur Laufzeit übersprungen.
     */
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    /**
     * Optionaler Session-Titel. Null = der Name des ActivityType wird
     * verwendet (M18.66-FIX9-Muster: Activity-Name, nicht Termin-Titel).
     */
    @ColumnInfo(name = "default_title") val defaultTitle: String? = null,
    /** Termine kürzer als das hier werden ignoriert. 0 = kein Minimum. */
    @ColumnInfo(name = "min_duration_minutes", defaultValue = "0") val minDurationMinutes: Int = 0,
    /** Zeitfenster in Minuten seit Mitternacht. -1 = keine Grenze. */
    @ColumnInfo(name = "window_start_minute", defaultValue = "-1") val windowStartMinute: Int = -1,
    @ColumnInfo(name = "window_end_minute", defaultValue = "-1") val windowEndMinute: Int = -1,
    /** Bitmaske: Mo=1, Di=2, Mi=4, Do=8, Fr=16, Sa=32, So=64. 127 = alle. */
    @ColumnInfo(name = "weekday_mask", defaultValue = "127") val weekdayMask: Int = 0x7F,
    /**
     * [CalendarOverlapPolicy.OVERRIDE] = laufende Session wird beendet
     * (Aevum-Standard). [CalendarOverlapPolicy.ONLY_IF_IDLE] = startet nur,
     * wenn nichts anderes läuft (konservativ).
     */
    @ColumnInfo(name = "overlap_policy", defaultValue = "OVERRIDE") val overlapPolicy: String = CalendarOverlapPolicy.OVERRIDE,
    /** Bei mehreren Treffern gewinnt die Regel mit der höchsten Priorität. */
    @ColumnInfo(name = "priority", defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * M18.129: Die verfügbaren Regel-Typen.
 *
 * Bewusst als Konstanten-Objekt statt enum: Room speichert den String,
 * und die Engine (pure JVM) braucht keine Android-Abhängigkeit. Zusätzlich
 * macht es Bestands-Daten robust — ein unbekannter Wert führt zu
 * "trifft nichts" statt zu einem Deserialisierungs-Crash.
 */
object CalendarRuleType {
    /** Wörter in Titel ODER Beschreibung (Default, entspricht der Spec). */
    const val ANY_FIELD_CONTAINS = "ANY_FIELD_CONTAINS"
    /** Wörter nur im Titel. */
    const val TITLE_CONTAINS = "TITLE_CONTAINS"
    /** Wörter nur in der Beschreibung. */
    const val DESCRIPTION_CONTAINS = "DESCRIPTION_CONTAINS"
    /** Regulärer Ausdruck im Titel. */
    const val TITLE_REGEX = "TITLE_REGEX"
    /** Termin stammt aus einem bestimmten Kalender/Account. */
    const val CALENDAR_IS = "CALENDAR_IS"
    /** Teilnehmer (E-Mail) enthält einen Wert. */
    const val ATTENDEE_CONTAINS = "ATTENDEE_CONTAINS"
    /** Nur ganztägige Termine (Urlaub, Feiertag). */
    const val ALL_DAY_ONLY = "ALL_DAY_ONLY"

    val ALL: List<String> = listOf(
        ANY_FIELD_CONTAINS,
        TITLE_CONTAINS,
        DESCRIPTION_CONTAINS,
        TITLE_REGEX,
        CALENDAR_IS,
        ATTENDEE_CONTAINS,
        ALL_DAY_ONLY
    )

    /** Regel-Typen, die ein [CalendarRule.matchValue]-Eingabefeld brauchen. */
    fun needsMatchValue(type: String): Boolean = type != ALL_DAY_ONLY && type != CALENDAR_IS
}

/**
 * M18.129: Was passiert, wenn beim Termin-Start schon etwas läuft.
 *
 * M18.132: QUEUE_IF_BUSY ergänzt — die dritte, vom Auftrag geforderte
 * Option („beginnt, sobald keine Aufzeichnung mehr läuft"). Sie wird
 * als Wert in derselben String-Spalte gespeichert wie die anderen —
 * kein Schema-Eingriff, Bestands-Zeilen bleiben unverändert gültig.
 */
object CalendarOverlapPolicy {
    /** Beendet die laufende Session (Aevum-Standard bei Auto-Triggern). */
    const val OVERRIDE = "OVERRIDE"
    /** Startet nur, wenn gerade nichts aufgezeichnet wird. */
    const val ONLY_IF_IDLE = "ONLY_IF_IDLE"
    /**
     * M18.132: Startet den Termin nach, sobald keine Aufzeichnung mehr
     * läuft — auch noch mitten im Termin („Warteschlange"). Eine
     * laufende Fremd-Session wird dabei NIE angetastet.
     */
    const val QUEUE_IF_BUSY = "QUEUE_IF_BUSY"

    /** Alle gültigen Werte (für Validierung und UI-Reihenfolge). */
    val ALL: List<String> = listOf(OVERRIDE, ONLY_IF_IDLE, QUEUE_IF_BUSY)
}

package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "goal",
    indices = [
        Index(value = ["status", "period"]),
        Index("category_id"),
        Index("tag_id"),
        Index("activity_type_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class Goal(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "tag_id") val tagId: String? = null,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    /** AT_LEAST (mindestens) · AT_MOST (höchstens) · COUNT (Anzahl, M20). */
    val type: String = "DURATION",
    /** DAILY · WEEKLY · MONTHLY */
    val period: String = "DAILY",
    @ColumnInfo(name = "target_value") val targetValue: Float,
    /** MINUTES · HOURS · COUNT (M20) */
    @ColumnInfo(name = "target_unit") val targetUnit: String = "MINUTES",
    @ColumnInfo(name = "filter_json") val filterJson: String? = null,
    // ── M20: Ziel-Architektur ─────────────────────────────────────────
    // tracking_mode: "AUTO" (Fortschritt kommt aus Aufzeichnungen:
    // Aktivität/Kategorie/Digital/Aufgaben) oder "MANUAL" (Abhaken bzw.
    // Zählen über Check-ins, Tabelle goal_checkin).
    @ColumnInfo(name = "tracking_mode", defaultValue = "AUTO") val trackingMode: String = "AUTO",
    // schedule_days: Bitmaske Mo=bit0 … So=bit6; 127 = jeden Tag.
    // „Lernen täglich Mo–Fr" = 0b0011111 = 31. Bestimmt, an welchen Tagen
    // das Ziel FÄLLIG ist (Streaks, „frei"-Tage) und — bei Wochen-/
    // Monatszielen — welche Tage in den Zielwert zählen.
    @ColumnInfo(name = "schedule_days", defaultValue = "127") val scheduleDays: Int = 127,
    // source_kind: AUTO-Quelle — ACTIVITY_TYPE | CATEGORY | DIGITAL | TODOS.
    // null = Alt-Verhalten (Filter auf activity_type_id, exakt wie vor M20).
    @ColumnInfo(name = "source_kind") val sourceKind: String? = null,
    // accent: optionale Akzentfarbe des Ziels (Schlüssel der DesignTokens-
    // Palette, z. B. "gold", "eis", "feuer"); null = automatisch.
    @ColumnInfo(name = "accent") val accent: String? = null,
    @ColumnInfo(name = "start_at") val startAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    val status: String = "ACTIVE",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable

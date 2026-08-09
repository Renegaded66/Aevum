package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * M18.60: Tages-Override einer [DailyAllowance] — die Pauschale gilt
 * an einem bestimmten Tag mit ABWEICHENDER Minuten-Zahl.
 *
 * Beispiel: "Fertig machen" ist mit 30 min/Tag konfiguriert, aber am
 * 12.08. hat der User 55 min gebraucht. Der Override (date=2026-08-12,
 * minutes=55) überschreibt NUR für diesen Tag den Wert — die Pauschale
 * selbst bleibt unverändert.
 *
 * Der [MidnightAllowanceWorker] schreibt weiterhin die Normalwerte in
 * [AllowanceAccumulationDay]; beim Lesen (Dashboard/Insights) gewinnt
 * der Override. Ein Löschen des Overrides stellt den Pauschalen-Wert
 * für diesen Tag wieder her.
 */
@Entity(
    tableName = "allowance_day_override",
    primaryKeys = ["date", "allowance_id"],
    indices = [
        Index(value = ["date"]),
        Index(value = ["allowance_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = DailyAllowance::class,
            parentColumns = ["id"],
            childColumns = ["allowance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AllowanceDayOverride(
    /** ISO-LocalDate (yyyy-MM-dd) im Anwender-Timezone. */
    val date: String,
    @ColumnInfo(name = "allowance_id") val allowanceId: String,
    /** Abweichende Minuten für diesen Tag. */
    val minutes: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

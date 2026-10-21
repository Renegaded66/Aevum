package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * M27: Sucht-Detektor — vom Nutzer ausgewählte Apps, deren Nutzung
 * besonders genau beobachtet wird.
 *
 * Bewusst nur eine schlanke Auswahl-Tabelle: Die eigentlichen Messwerte
 * stecken in [AddictionSession]. Wird eine App entfernt, bleiben ihre
 * bereits erhobenen Sitzungen für den Verlauf erhalten (die Auswertung
 * filtert auf die aktuell ausgewählten Apps); erst die 400-Tage-Retention
 * löscht alte Zeilen.
 */
@Entity(tableName = "addiction_watch_app")
data class AddictionWatchApp(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
)

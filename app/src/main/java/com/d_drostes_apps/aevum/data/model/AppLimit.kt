package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M18.61: Digital Balance — Zeitlimit pro App.
 *
 * Jede App (packageName) kann ein tägliches Nutzungslimit haben.
 * Wird das Limit erreicht, sperrt Aevum die App (Sperr-Overlay).
 *
 * Ausnahmen (exceptionType):
 *  - NONE: keine Ausnahme — Limit gilt immer
 *  - ALWAYS_ALLOW: App ist vom Limit ausgenommen (nie sperren)
 *  - TIME_WINDOW: App ist nur im Zeitfenster [windowStartMin, windowEndMin]
 *    (Tagesminuten) gesperrt — außerhalb des Fensters gilt das Limit nicht
 */
@Entity(
    tableName = "app_limit",
    indices = [Index(value = ["package_name"], unique = true)]
)
data class AppLimit(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "limit_minutes") val limitMinutes: Int,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "exception_type") val exceptionType: String, // NONE | ALWAYS_ALLOW | TIME_WINDOW
    @ColumnInfo(name = "window_start_min") val windowStartMin: Int,
    @ColumnInfo(name = "window_end_min") val windowEndMin: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    companion object {
        const val EXCEPTION_NONE = "NONE"
        const val EXCEPTION_ALWAYS_ALLOW = "ALWAYS_ALLOW"
        const val EXCEPTION_TIME_WINDOW = "TIME_WINDOW"
    }
}

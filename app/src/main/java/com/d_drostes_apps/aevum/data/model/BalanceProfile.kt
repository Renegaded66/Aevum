package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M18.61f: Digital-Balance-Profil.
 *
 * Ein Profil bündelt Apps, die gesperrt werden sollen (z.B. "Lernen" →
 * alle Social-Media-Apps). Ist ein Profil aktiv, werden ALLE Apps des
 * Profils gesperrt — unabhängig von individuellen Limits.
 *
 * Die App-Zuordnung liegt in [BalanceProfileApp] (1:n).
 *
 * M18.66-FIX14: Zeitplan-Felder für automatische Aktivierung.
 * - scheduleEnabled: wenn true, wird das Profil automatisch nach
 *   Zeitplan aktiviert/deaktiviert (statt manueller Switch-Bedienung).
 * - scheduleDays: Bitmaske der Wochentage (1=Mo ... 7=So).
 *   0b1111100 = Mo-Fr, 0b1111111 = jeden Tag.
 * - scheduleStartMinute / scheduleEndMinute: Minuten seit Mitternacht.
 *   z.B. 420 = 07:00, 960 = 16:00.
 *   Wenn die aktuelle Zeit innerhalb des Fensters liegt UND der
 *   Wochentag in scheduleDays gesetzt ist → Profil aktiv.
 */
@Entity(
    tableName = "balance_profile",
    indices = [Index(value = ["name"], unique = true)]
)
data class BalanceProfile(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "icon") val icon: String,          // Emoji, z.B. "📚"
    @ColumnInfo(name = "color") val color: String,        // Hex, z.B. "#6366F1"
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    // M18.66-FIX14: Zeitplan für automatische Aktivierung
    @ColumnInfo(name = "schedule_enabled") val scheduleEnabled: Boolean = false,
    @ColumnInfo(name = "schedule_days") val scheduleDays: Int = 0,  // Bitmaske 1=Mo..7=So
    @ColumnInfo(name = "schedule_start_minute") val scheduleStartMinute: Int = 0,  // Minuten seit 00:00
    @ColumnInfo(name = "schedule_end_minute") val scheduleEndMinute: Int = 0       // Minuten seit 00:00
)

/**
 * M18.61f: App-Zuordnung eines Profils (packageName → Profil).
 */
@Entity(
    tableName = "balance_profile_app",
    indices = [Index(value = ["profile_id", "package_name"], unique = true)]
)
data class BalanceProfileApp(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "package_name") val packageName: String
)

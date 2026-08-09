package com.d_drostes_apps.aevum.domain.digital

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import com.d_drostes_apps.aevum.data.model.AppUsageSample
import com.d_drostes_apps.aevum.data.repository.AppUsageSampleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Collects digital balance data from Android UsageStatsManager.
 *
 * M8: Analytics-only. No candidates generated from digital data.
 * Reads: total screen time, per-app usage.
 */
class UsageStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appUsageRepository: AppUsageSampleRepository
) {
    private val usageStats by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    fun hasPermission(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val stats = usageStats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 1000,
                now
            )
            !stats.isNullOrEmpty()
        } catch (_: Exception) { false }
    }

    /**
     * M12.2: Open the screen where the user can grant Usage Access to Aevum.
     *
     * - Versucht zuerst den paket-spezifischen Usage-Access-Screen (manche Hersteller
     *   zeigen Aevum dort direkt, statt in der langen Liste).
     * - Fällt auf die generische Settings.ACTION_USAGE_ACCESS_SETTINGS zurück.
     * - Fällt ein zweites Mal auf ACTION_APPLICATION_DETAILS_SETTINGS zurück (App-Info),
     *   falls weder Usage-Screen verfügbar ist (z. B. Go-Edition ohne Usage-Access).
     *
     * Wichtig: Aevum muss im AndroidManifest unter
     * `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
     *   tools:ignore="ProtectedPermissions"/>` deklariert sein — sonst fehlt der
     *   Eintrag in der Liste. Wir nutzen tools:ignore, weil dies eine
     *   "Protected Permission" ist, die der User explizit gewährt.
     */
    fun openUsageAccessSettings() {
        val pm = context.packageManager

        // 1) Best effort: package-specific "App usage access" (OEM-spezifisch, AOSP ≥ 11).
        val packageSpecific = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
        if (packageSpecific.resolveActivity(pm) != null) {
            try {
                context.startActivity(packageSpecific)
                return
            } catch (_: Exception) { /* fallback */ }
        }

        // 2) Generischer Usage-Access-Screen (funktioniert auf Standard-AOSP-Geräten).
        val generic = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (generic.resolveActivity(pm) != null) {
            try {
                context.startActivity(generic)
                return
            } catch (_: Exception) { /* fallback */ }
        }

        // 3) Letzter Ausweg: App-Info-Seite öffnen, auf der der User zu den
        //    "Special access" -> "Usage access" navigieren kann.
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
        try {
            context.startActivity(appInfo)
        } catch (_: Exception) { /* nothing we can do */ }
    }

    /**
     * M13: Open the system settings directly on the activity-recognition permission
     * screen. The user is guided to Android Settings → Apps → Aevum → Permissions.
     */
    fun openActivityRecognitionSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        } catch (_: Exception) { /* nothing */ }
    }

    /**
     * Collects usage stats for a specific day and stores top apps.
     * Returns summary for dashboard display.
     */
    suspend fun collectDayUsage(date: LocalDate): DigitalDayStats {
        if (!hasPermission()) return DigitalDayStats.empty()

        return withContext(Dispatchers.IO) {
            val zoneId = ZoneId.systemDefault()
            val startOfDay = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

            val stats = usageStats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                endOfDay
            )

            if (stats.isNullOrEmpty()) return@withContext DigitalDayStats.empty()

            val appSamples = stats
                .filter { it.totalTimeInForeground > 1000 }
                .sortedByDescending { it.totalTimeInForeground }
                .map { stat ->
                    val label = try {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(stat.packageName, 0)
                        ).toString()
                    } catch (_: Exception) { stat.packageName }

                    AppUsageSample(
                        id = "${date}_${stat.packageName}",
                        packageName = stat.packageName,
                        appLabel = label,
                        startAt = stat.lastTimeUsed.takeIf { it > 0 } ?: startOfDay,
                        endAt = stat.lastTimeUsed.takeIf { it > 0 } ?: endOfDay,
                        durationMs = stat.totalTimeInForeground
                    )
                }
                .take(20)

            appUsageRepository.insertAll(appSamples)

            val totalMs = appSamples.sumOf { it.durationMs }
            val topApp = appSamples.firstOrNull()

            DigitalDayStats(
                date = date,
                totalScreenTimeMs = totalMs,
                appCount = appSamples.size,
                topAppName = topApp?.appLabel,
                topAppDurationMs = topApp?.durationMs ?: 0L
            )
        }
    }

    /**
     * M18.59-FIX (User: "Dashboard zeigt 2h 39 Bildschirmzeit, stimmt aber
     * nicht"): `totalTimeInForeground` kumuliert auf vielen Geräten über
     * mehrere Tage (OEM-Bug) und zählt auch Screen-off-Zeit (z.B. Musik-
     * Apps im Hintergrund). Die ehrliche Quelle ist die Event-API:
     * SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE liefern die echten
     * Screen-an-Phasen. Summe der Intervalle seit Mitternacht = echte
     * Bildschirmzeit heute.
     *
     * @return Bildschirmzeit seit Mitternacht in ms, oder null wenn keine
     * Permission / keine Events (dann nutzt der Aufrufer den Fallback).
     */
    fun screenTimeTodayMs(): Long? {
        return try {
            val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val zone = ZoneId.systemDefault()
            val startOfDay = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val events = mgr.queryEvents(startOfDay, now) ?: return null

            var total = 0L
            var screenOnAt: Long? = null
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        if (screenOnAt == null) screenOnAt = event.timeStamp
                    }
                    android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        screenOnAt?.let { on ->
                            total += (event.timeStamp - on).coerceAtLeast(0L)
                        }
                        screenOnAt = null
                    }
                }
            }
            // Screen ist gerade an → bis jetzt zählen
            screenOnAt?.let { on ->
                total += (now - on).coerceAtLeast(0L)
            }
            if (total <= 0L) null else total
        } catch (_: Exception) {
            null // keine Permission / OEM-Blockade — nie crashen
        }
    }

    /**
     * M13: Get top apps for a given day (returns the most-used ones).
     * Returns empty list if no permission.
     */
    suspend fun topAppsForDay(date: LocalDate, limit: Int = 5): List<AppUsageSample> {
        if (!hasPermission()) return emptyList()
        return withContext(Dispatchers.IO) {
            val zoneId = ZoneId.systemDefault()
            val startOfDay = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val stats = usageStats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                endOfDay
            )
            stats?.filter { it.totalTimeInForeground > 1000 }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.take(limit)
                ?.mapNotNull { stat ->
                    val label = try {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(stat.packageName, 0)
                        ).toString()
                    } catch (_: Exception) { stat.packageName }
                    AppUsageSample(
                        id = "${date}_${stat.packageName}",
                        packageName = stat.packageName,
                        appLabel = label,
                        startAt = stat.lastTimeUsed.takeIf { it > 0 } ?: startOfDay,
                        endAt = stat.lastTimeUsed.takeIf { it > 0 } ?: endOfDay,
                        durationMs = stat.totalTimeInForeground
                    )
                } ?: emptyList()
        }
    }
}

data class DigitalDayStats(
    val date: LocalDate = LocalDate.now(),
    val totalScreenTimeMs: Long = 0L,
    val appCount: Int = 0,
    val topAppName: String? = null,
    val topAppDurationMs: Long = 0L
) {
    companion object {
        fun empty() = DigitalDayStats()
    }

    val totalScreenTimeFormatted: String
        get() {
            val hours = totalScreenTimeMs / 3_600_000
            val minutes = (totalScreenTimeMs % 3_600_000) / 60_000
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }

    val topAppFormatted: String
        get() {
            if (topAppName == null) return "—"
            val mins = topAppDurationMs / 60_000
            return "$topAppName · ${mins}min"
        }
}

/**
 * M13: Permission helpers for UsageStats access.
 * "Protected permission" — must be granted via Settings, not at runtime.
 */
object UsageStatsPermission {
    fun isGranted(context: android.content.Context): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val mgr = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val stats = mgr.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 1000,
                now
            )
            !stats.isNullOrEmpty()
        } catch (_: Exception) { false }
    }

    /**
     * M18.61: Öffnet den Usage-Access-Settings-Screen (mit Fallbacks).
     */
    fun openSettings(context: android.content.Context) {
        try {
            val pm = context.packageManager
            val packageSpecific = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            if (packageSpecific.resolveActivity(pm) != null) {
                context.startActivity(packageSpecific)
                return
            }
            val generic = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (generic.resolveActivity(pm) != null) {
                context.startActivity(generic)
                return
            }
            val appInfo = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(appInfo)
        } catch (_: Exception) { /* nothing we can do */ }
    }
}

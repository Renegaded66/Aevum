package com.d_drostes_apps.aevum.data.garmin

import android.content.Context
import com.d_drostes_apps.aevum.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.58: HTTP-Client für die Aevum-Garmin-Bridge.
 *
 * Die Bridge (Flask auf dem Server, gleiche Maschine wie der
 * Calorie-Tracker) hält die Garmin-Connect-Session und liefert:
 *   GET /api/status       — verbunden?
 *   GET /api/today        — Schritte/Kalorien/Distanz (heute oder Datum)
 *   GET /api/sleep        — Schlaf (Start/Ende GMT, Dauer)
 *   GET /api/activities   — letzte Aktivitäten (Laufen, Radfahren, ...)
 *
 * Implementierung: pures HttpURLConnection (wie im Points Tracker) —
 * kein Retrofit/OkHttp-Overhead. Basis-URL aus BuildConfig, überschreibbar
 * über die DatenStore-Präferenz "garmin_bridge_url" (falls der
 * Cloudflare-Quick-Tunnel neu gestartet wird und eine neue URL bekommt).
 */
@Singleton
class GarminApiClient @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences("aevum_garmin", Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.GARMIN_BRIDGE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()

    /** M18.58: Letzter erfolgreicher Sync (ms) — für die Status-Karte. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    suspend fun getStatus(): GarminStatus = withContext(Dispatchers.IO) {
        val json = get("/api/status") ?: return@withContext GarminStatus(connected = false, error = "Keine Antwort")
        GarminStatus(
            connected = json.optBoolean("connected", false),
            error = json.optString("error").ifEmpty { null }
        )
    }

    suspend fun getToday(dateIso: String): GarminDayData? = withContext(Dispatchers.IO) {
        val json = get("/api/today?date=$dateIso") ?: return@withContext null
        if (json.has("error")) return@withContext null
        GarminDayData(
            date = json.optString("date", dateIso),
            steps = json.optInt("steps", 0),
            distanceMeters = json.optDouble("distance_m", 0.0),
            activeCalories = json.optInt("active_calories", 0),
            totalCalories = json.optInt("total_calories", 0)
        )
    }

    suspend fun getSleep(dateIso: String): GarminSleepData? = withContext(Dispatchers.IO) {
        val json = get("/api/sleep?date=$dateIso") ?: return@withContext null
        if (json.has("error")) return@withContext null
        val start = json.optLong("sleep_start_gmt", 0L)
        val end = json.optLong("sleep_end_gmt", 0L)
        if (start <= 0L || end <= start) return@withContext null
        GarminSleepData(
            date = json.optString("date", dateIso),
            startGmtMs = start,
            endGmtMs = end,
            sleepTimeSeconds = json.optLong("sleep_time_seconds", 0L),
            deepSeconds = json.optLong("deep_seconds", 0L),
            remSeconds = json.optLong("rem_seconds", 0L)
        )
    }

    suspend fun getActivities(limit: Int = 20): List<GarminRemoteActivity> = withContext(Dispatchers.IO) {
        val json = get("/api/activities?limit=$limit") ?: return@withContext emptyList()
        if (json.has("error")) return@withContext emptyList()
        val arr = json.optJSONArray("activities") ?: JSONArray()
        val result = mutableListOf<GarminRemoteActivity>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            result += GarminRemoteActivity(
                activityId = a.optString("activity_id"),
                name = a.optString("name", "Aktivität"),
                type = a.optString("type", "other"),
                startGmt = a.optString("start_gmt"),
                distanceMeters = a.optDouble("distance_m", 0.0),
                durationSeconds = a.optDouble("duration_s", 0.0),
                calories = a.optInt("calories", 0)
            )
        }
        result
    }

    private fun get(path: String): JSONObject? {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) {
                android.util.Log.w(TAG, "Garmin-Bridge $path → HTTP $code")
                return null
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            reader.forEachLine { sb.append(it) }
            JSONObject(sb.toString())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Garmin-Bridge $path fehlgeschlagen: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "GarminApiClient"
        private const val KEY_BASE_URL = "bridge_base_url"
        private const val KEY_LAST_SYNC = "last_sync_at"
    }
}

data class GarminStatus(
    val connected: Boolean,
    val error: String? = null
)

data class GarminDayData(
    val date: String,
    val steps: Int,
    val distanceMeters: Double,
    val activeCalories: Int,
    val totalCalories: Int
)

data class GarminSleepData(
    val date: String,
    val startGmtMs: Long,
    val endGmtMs: Long,
    val sleepTimeSeconds: Long,
    val deepSeconds: Long,
    val remSeconds: Long
)

data class GarminRemoteActivity(
    val activityId: String,
    val name: String,
    val type: String,
    val startGmt: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val calories: Int
)

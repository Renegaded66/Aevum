package com.d_drostes_apps.aevum.data.garmin

import android.content.Context
import com.d_drostes_apps.aevum.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.59: HTTP-Client für die Aevum-Garmin-Bridge (Multi-User).
 *
 * Jeder Nutzer authentifiziert sich mit SEINEN Garmin-Credentials:
 *   POST /api/connect    {email, password} → Login, Tokens pro user_id
 *   POST /api/disconnect                    → Tokens löschen
 *   GET  /api/status     ?user_id=          → verbunden?
 *   GET  /api/today      ?user_id=&date=    → Schritte/Kalorien/Distanz
 *   GET  /api/sleep      ?user_id=&date=    → Schlaf (Start/Ende GMT)
 *   GET  /api/activities ?user_id=&limit=   → letzte Aktivitäten
 *
 * Die user_id wird beim ersten Verbinden generiert (UUID) und lokal
 * gespeichert — die Bridge ordnet ihr die Garmin-Tokens zu. Das Passwort
 * wird NUR an die Bridge geschickt und dort nach dem Login verworfen
 * (kein Passwort-Speicher auf Server ODER Gerät).
 *
 * Jeder Request trägt den Header X-Aevum-Key (BuildConfig) — verhindert,
 * dass Fremde die Bridge als offenen Proxy missbrauchen.
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

    /** M18.59: Lokale Geräte-ID — identifiziert den Nutzer an der Bridge. */
    val userId: String
        get() {
            prefs.getString(KEY_USER_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, id).apply()
            return id
        }

    /** M18.58: Letzter erfolgreicher Sync (ms) — für die Status-Karte. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /**
     * M18.59: Garmin-Login über die Bridge. Das Passwort wird nur an die
     * Bridge geschickt (dort nach Login verworfen) und NIE lokal gespeichert.
     *
     * @return null bei Erfolg, sonst Fehlermeldung (deutsch, anzeigbar).
     */
    suspend fun connect(email: String, password: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        val json = post("/api/connect", body) ?: return@withContext "Bridge nicht erreichbar"
        if (json.optBoolean("connected", false)) {
            null
        } else {
            json.optString("error", "Verbindung fehlgeschlagen")
        }
    }

    /** M18.59: Garmin-Tokens auf der Bridge löschen. */
    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        val json = post("/api/disconnect", JSONObject()) ?: return@withContext false
        json.optBoolean("connected", false).not()
    }

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
            conn.setRequestProperty("X-Aevum-Key", BuildConfig.GARMIN_BRIDGE_KEY)
            conn.setRequestProperty("X-Aevum-User", userId)
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

    private fun post(path: String, body: JSONObject): JSONObject? {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000 // Login kann 30 s+ dauern (Garmin-WAF)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Aevum-Key", BuildConfig.GARMIN_BRIDGE_KEY)
            conn.setRequestProperty("X-Aevum-User", userId)
            val out: OutputStream = conn.outputStream
            out.write(body.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream))
            val sb = StringBuilder()
            reader.forEachLine { sb.append(it) }
            if (code !in 200..299) {
                android.util.Log.w(TAG, "Garmin-Bridge $path → HTTP $code: ${sb}")
                return null
            }
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
        private const val KEY_USER_ID = "user_id"
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

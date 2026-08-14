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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val directClient: DirectGarminClient
) {
    private val prefs by lazy {
        context.getSharedPreferences("aevum_garmin", Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.GARMIN_BRIDGE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()

    /**
     * M18.61g-FIX 2: URL-Self-Healing. Der Cloudflare-Quick-Tunnel rotiert
     * die URL — eine gespeicherte (tote) URL-Override lässt ALLE
     * Bridge-Aufrufe still fehlschlagen (Sync "erfolgreich", aber keine
     * Daten). Bei Verbindungsfehlern wird die gespeicherte URL verworfen
     * und auf die aktuelle BuildConfig-URL zurückgefallen.
     */
    fun resetBaseUrlIfStale() {
        if (prefs.contains(KEY_BASE_URL)) {
            prefs.edit().remove(KEY_BASE_URL).apply()
            android.util.Log.w("GarminApiClient", "Gespeicherte Bridge-URL verworfen — Fallback auf BuildConfig-URL")
        }
    }

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
     * M18.66-FIX11: Direkter Garmin-Login — kein Bridge-Server.
     * App spricht direkt mit sso.garmin.com → diauth.garmin.com.
     * @return null bei Erfolg, sonst Fehlermeldung (deutsch, anzeigbar).
     */
    suspend fun connect(email: String, password: String): String? = withContext(Dispatchers.IO) {
        when (val result = directClient.login(email, password)) {
            is DirectGarminClient.LoginResult.Success -> null
            is DirectGarminClient.LoginResult.NeedsMfa -> "MFA/2FA wird nicht unterstützt — bitte 2FA in Garmin Connect deaktivieren"
            is DirectGarminClient.LoginResult.Error -> result.message
        }
    }

    /** M18.66-FIX11: Direkter Disconnect — löscht lokale Token. */
    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        directClient.disconnect()
        true
    }

    suspend fun getStatus(): GarminStatus = withContext(Dispatchers.IO) {
        directClient.getStatus()
    }

    suspend fun getToday(dateIso: String): GarminDayData? = withContext(Dispatchers.IO) {
        directClient.getToday(dateIso)
    }

    /**
     * M18.65: [fresh]=true umgeht den Server-Cache (fragt Garmin direkt).
     * Der manuelle Sync nutzt das, damit die letzte Nacht nach dem
     * Aufwachen sofort mit den finalen Garmin-Daten aktualisiert wird
     * (Garmin korrigiert Schlafzeiten nachträglich; der Server-Cache
     * für "heute" ist nur 10 Min frisch).
     */
    suspend fun getSleep(dateIso: String, fresh: Boolean = false): GarminSleepData? = withContext(Dispatchers.IO) {
        directClient.getSleep(dateIso)
    }

    suspend fun getActivities(limit: Int = 20): List<GarminRemoteActivity> = withContext(Dispatchers.IO) {
        directClient.getActivities(limit)
    }

    private fun get(path: String): JSONObject? {
        val result = getWithUrl(path, baseUrl)
        // M18.66-FIX8: Self-Healing — wenn die gespeicherte URL nicht
        // erreichbar ist (Cloudflare-Tunnel hat rotiert), verwerfe sie
        // und retry mit der BuildConfig-URL. Vorher stand die App
        // jeden Tag auf "Keine Antwort", weil die alte URL tot war.
        if (result == null && prefs.contains(KEY_BASE_URL)) {
            android.util.Log.w(TAG, "Bridge mit gespeicherter URL fehlgeschlagen — retry mit BuildConfig-URL")
            resetBaseUrlIfStale()
            return getWithUrl(path, BuildConfig.GARMIN_BRIDGE_URL)
        }
        return result
    }

    private fun getWithUrl(path: String, urlBase: String): JSONObject? {
        val url = URL("${urlBase.trimEnd('/')}$path")
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

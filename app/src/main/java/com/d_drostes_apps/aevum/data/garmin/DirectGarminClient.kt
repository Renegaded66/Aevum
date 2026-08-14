package com.d_drostes_apps.aevum.data.garmin

import android.content.Context
import android.util.Base64
import android.util.Log
import com.d_drostes_apps.aevum.data.garmin.DirectGarminClient.LoginResult.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════════════════════════════
// M18.66-FIX11: DIREKTER GARMIN CONNECT CLIENT — KEIN BRIDGE-SERVER
//
// Vorher: App → Cloudflare Quick-Tunnel → Flask-Bridge → connect.garmin.com
// Problem: Quick-Tunnel URL rotiert täglich → "Bridge nicht erreichbar"
//
// Jetzt: App → direkt sso.garmin.com → diauth.garmin.com → connectapi.garmin.com
// Genau derselbe Flow wie die offizielle Garmin Connect Android-App.
// Kein Bridge-Server, kein Tunnel, keine URL-Rotation.
//
// Auth-Flow (portiert aus python-garminconnect/client.py):
// 1. POST sso.garmin.com/mobile/api/login → serviceTicketId
// 2. POST diauth.garmin.com/di-oauth2-service/oauth/token → DI Bearer token
// 3. GET connectapi.garmin.com/<endpoint> mit Authorization: Bearer
// 4. Token-Refresh: POST diauth.garmin.com mit refresh_token
//
// Token werden in SharedPreferences gespeichert (di_token, di_refresh_token,
// di_client_id, display_name). DI-Token ist ~1 Jahr gültig.
// ══════════════════════════════════════════════════════════════════════

@Singleton
class DirectGarminClient @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DirectGarminClient"
        private const val PREFS = "aevum_garmin_direct"
        private const val KEY_DI_TOKEN = "di_token"
        private const val KEY_DI_REFRESH = "di_refresh_token"
        private const val KEY_DI_CLIENT_ID = "di_client_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_CONNECTED_AT = "connected_at"

        // Garmin SSO endpoints (exakt wie python-garminconnect)
        private const val SSO_BASE = "https://sso.garmin.com"
        private const val SSO_LOGIN_URL = "$SSO_BASE/mobile/api/login"
        private const val SSO_SERVICE_URL = "https://mobile.integration.garmin.com/gcm/ios"
        private const val SSO_CLIENT_ID = "GCM_IOS_DARK"

        // DI OAuth2 token endpoint
        private const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        private const val DI_GRANT_TYPE = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"

        // DI client IDs (in Reihenfolge versuchen)
        private val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI"
        )

        // Connect API base
        private const val CONNECTAPI_BASE = "https://connectapi.garmin.com"

        // Native headers (wie die Garmin Connect App)
        private val NATIVE_HEADERS = mapOf(
            "User-Agent" to "GCM-Android-5.23",
            "X-Garmin-User-Agent" to "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
            "X-Garmin-Paired-App-Version" to "10861",
            "X-Garmin-Client-Platform" to "Android",
            "X-App-Ver" to "10861",
            "X-Lang" to "en",
            "X-GCExperience" to "GC5",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        // iOS Login User-Agent
        private const val IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ── Token-Storage ──────────────────────────────────────────────

    val isConnected: Boolean
        get() = prefs.getString(KEY_DI_TOKEN, null) != null

    val displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)

    val email: String?
        get() = prefs.getString(KEY_EMAIL, null)

    val lastConnectedAt: Long
        get() = prefs.getLong(KEY_CONNECTED_AT, 0L)

    private fun saveTokens(
        diToken: String,
        diRefresh: String?,
        diClientId: String?,
        displayName: String?,
        email: String?
    ) {
        prefs.edit().apply {
            putString(KEY_DI_TOKEN, diToken)
            if (diRefresh != null) putString(KEY_DI_REFRESH, diRefresh) else remove(KEY_DI_REFRESH)
            if (diClientId != null) putString(KEY_DI_CLIENT_ID, diClientId) else remove(KEY_DI_CLIENT_ID)
            if (displayName != null) putString(KEY_DISPLAY_NAME, displayName) else remove(KEY_DISPLAY_NAME)
            if (email != null) putString(KEY_EMAIL, email) else remove(KEY_EMAIL)
            putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
        }.apply()
        Log.d(TAG, "Tokens gespeichert (displayName=$displayName)")
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Token gelöscht — disconnected")
    }

    // ── Login ──────────────────────────────────────────────────────

    sealed class LoginResult {
        data object Success : LoginResult()
        data class Error(val message: String) : LoginResult()
        data object NeedsMfa : LoginResult()
    }

    /**
     * Login mit Email + Passwort. Speichert DI-Token bei Erfolg.
     * Flow: SSO Login → Service Ticket → DI Token Exchange.
     */
    suspend fun login(email: String, password: String): LoginResult {
        // 1) SSO Login → serviceTicketId
        val ticket = try {
            ssoLogin(email, password)
        } catch (e: MfaRequiredException) {
            return LoginResult.NeedsMfa
        } catch (e: AuthException) {
            return LoginResult.Error(e.message ?: "Login fehlgeschlagen")
        } catch (e: Exception) {
            Log.e(TAG, "SSO Login fehlgeschlagen: ${e.message}", e)
            return LoginResult.Error("Login fehlgeschlagen: ${e.message}")
        }

        if (ticket == null) {
            return LoginResult.Error("Kein Service-Ticket erhalten")
        }

        // 2) DI Token Exchange
        val diToken = try {
            exchangeServiceTicket(ticket)
        } catch (e: Exception) {
            Log.e(TAG, "DI Token Exchange fehlgeschlagen: ${e.message}", e)
            return LoginResult.Error("Token-Austausch fehlgeschlagen: ${e.message}")
        }

        // 3) Display Name abrufen (für API-Calls nötig)
        val name = try {
            fetchDisplayName(diToken.first)
        } catch (e: Exception) {
            Log.w(TAG, "DisplayName abrufen fehlgeschlagen: ${e.message}")
            null
        }

        // 4) Tokens speichern
        saveTokens(diToken.first, diToken.second, diToken.third, name, email)
        return LoginResult.Success
    }

    /**
     * SSO Login via sso.garmin.com/mobile/api/login (iOS app flow).
     * Gibt serviceTicketId zurück oder wirft MfaRequiredException.
     */
    private fun ssoLogin(email: String, password: String): String? {
        val url = URL("$SSO_LOGIN_URL?clientId=$SSO_CLIENT_ID&locale=en-US&service=$SSO_SERVICE_URL")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", IOS_UA)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", SSO_BASE)

            val body = JSONObject().apply {
                put("username", email)
                put("password", password)
                put("rememberMe", true)
                put("captchaToken", "")
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code == 429) {
                throw AuthException("Zu viele Login-Versuche — bitte später erneut versuchen")
            }

            val json = try { JSONObject(responseText) } catch (e: Exception) {
                throw AuthException("Login fehlgeschlagen (ungültige Antwort, HTTP $code)")
            }

            val respType = json.optJSONObject("responseStatus")?.optString("type") ?: ""

            when (respType) {
                "SUCCESSFUL" -> json.optString("serviceTicketId").takeIf { it.isNotBlank() }
                "MFA_REQUIRED" -> throw MfaRequiredException()
                "INVALID_USERNAME_PASSWORD" -> throw AuthException("Email oder Passwort falsch")
                else -> {
                    val errorCode = json.optJSONObject("error")?.optString("status-code")
                    if (errorCode == "429") {
                        throw AuthException("Zu viele Login-Versuche — bitte später erneut versuchen")
                    }
                    throw AuthException("Login fehlgeschlagen: $respType")
                }
            }
        } catch (e: MfaRequiredException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException("Netzwerkfehler: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Exchange service ticket for DI Bearer token.
     * POST diauth.garmin.com/di-oauth2-service/oauth/token
     */
    private fun exchangeServiceTicket(ticket: String): Triple<String, String?, String?> {
        for (clientId in DI_CLIENT_IDS) {
            try {
                val url = URL(DI_TOKEN_URL)
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 30_000
                    conn.doOutput = true

                    val basicAuth = "Basic " + Base64.encodeToString(
                        "$clientId:".toByteArray(), Base64.NO_WRAP
                    )
                    conn.setRequestProperty("Authorization", basicAuth)
                    conn.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    NATIVE_HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                    val body = "client_id=$clientId&service_ticket=$ticket&grant_type=$DI_GRANT_TYPE&service_url=$SSO_SERVICE_URL"
                    conn.outputStream.use { it.write(body.toByteArray()) }

                    val code = conn.responseCode
                    if (code == 429) throw AuthException("DI Token Exchange rate limited")
                    if (code !in 200..299) {
                        Log.d(TAG, "DI exchange failed for $clientId: HTTP $code")
                        continue
                    }

                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val diToken = json.getString("access_token")
                    val diRefresh = json.optString("refresh_token", null)
                    val extractedClientId = extractClientIdFromJwt(diToken) ?: clientId
                    Log.d(TAG, "DI Token erhalten (clientId=$extractedClientId)")
                    return Triple(diToken, diRefresh, extractedClientId)
                } finally {
                    conn.disconnect()
                }
            } catch (e: AuthException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "DI exchange failed for $clientId: ${e.message}")
                continue
            }
        }
        throw AuthException("DI Token Exchange fehlgeschlagen für alle Client-IDs")
    }

    // ── Token Refresh ──────────────────────────────────────────────

    /**
     * Refresh DI token using stored refresh token.
     * Liefert true bei Erfolg, false bei Fehler.
     */
    private fun refreshDiToken(): Boolean {
        val refreshToken = prefs.getString(KEY_DI_REFRESH, null) ?: return false
        val clientId = prefs.getString(KEY_DI_CLIENT_ID, null) ?: return false

        return try {
            val url = URL(DI_TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                conn.doOutput = true

                val basicAuth = "Basic " + Base64.encodeToString(
                    "$clientId:".toByteArray(), Base64.NO_WRAP
                )
                conn.setRequestProperty("Authorization", basicAuth)
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Cache-Control", "no-cache")
                NATIVE_HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                val body = "grant_type=refresh_token&client_id=$clientId&refresh_token=$refreshToken"
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "Token refresh failed: HTTP ${conn.responseCode}")
                    return false
                }

                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val newToken = json.getString("access_token")
                val newRefresh = json.optString("refresh_token", refreshToken)
                val newClientId = extractClientIdFromJwt(newToken) ?: clientId

                prefs.edit().apply {
                    putString(KEY_DI_TOKEN, newToken)
                    putString(KEY_DI_REFRESH, newRefresh)
                    putString(KEY_DI_CLIENT_ID, newClientId)
                }.apply()
                Log.d(TAG, "DI Token refreshed")
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh fehlgeschlagen: ${e.message}")
            false
        }
    }

    // ── Connect API Calls ──────────────────────────────────────────

    /**
     * GET connectapi.garmin.com/<path> mit Bearer Auth.
     * Refresh bei 401 automatisch.
     */
    private fun connectApi(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val token = prefs.getString(KEY_DI_TOKEN, null) ?: return null

        var result = connectApiCall(path, params, token)
        if (result == null) {
            // 401 → Token refresh → retry
            Log.d(TAG, "API call failed — versuche Token refresh")
            if (refreshDiToken()) {
                val newToken = prefs.getString(KEY_DI_TOKEN, null) ?: return null
                result = connectApiCall(path, params, newToken)
            }
        }
        return result
    }

    private fun connectApiCall(path: String, params: Map<String, String>, token: String): JSONObject? {
        val paramStr = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        } else ""
        val url = URL("$CONNECTAPI_BASE$path$paramStr")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            NATIVE_HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            if (code == 401) {
                Log.w(TAG, "Connect API $path → 401 (token expired)")
                return null
            }
            if (code !in 200..299) {
                Log.w(TAG, "Connect API $path → HTTP $code")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "Connect API $path fehlgeschlagen: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── High-Level API ─────────────────────────────────────────────

    /**
     * Display Name abrufen (wird für alle weiteren API-Calls benötigt).
     */
    private fun fetchDisplayName(token: String): String? {
        val url = URL("$CONNECTAPI_BASE/userprofile-service/socialProfile")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            NATIVE_HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optString("displayName").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Status: verbunden + letzter Sync.
     */
    fun getStatus(): GarminStatus {
        val token = prefs.getString(KEY_DI_TOKEN, null)
        if (token == null) return GarminStatus(connected = false, error = null)
        // Schneller Check: Token da → connected. API-Call bei Sync.
        return GarminStatus(connected = true, error = null)
    }

    /**
     * Today summary: steps, calories, distance.
     * GET /usersummary-service/usersummary/daily/<displayName>?calendarDate=YYYY-MM-DD
     */
    fun getToday(dateIso: String): GarminDayData? {
        val name = displayName ?: run {
            // Versuche display_name nachzuholen
            val token = prefs.getString(KEY_DI_TOKEN, null) ?: return null
            fetchDisplayName(token)?.let { prefs.edit().putString(KEY_DISPLAY_NAME, it).apply() }
            prefs.getString(KEY_DISPLAY_NAME, null) ?: return null
        }

        val json = connectApi(
            "/usersummary-service/usersummary/daily/$name",
            mapOf("calendarDate" to dateIso)
        ) ?: return null

        return GarminDayData(
            date = json.optString("calendarDate", dateIso),
            steps = json.optInt("totalSteps", 0),
            distanceMeters = json.optDouble("totalDistanceMeters", 0.0),
            activeCalories = json.optInt("activeCalories", 0),
            totalCalories = json.optInt("totalKilocalories", 0)
        )
    }

    /**
     * Sleep data: start, end, duration, deep, rem.
     * GET /wellness-service/wellness/dailySleepData/<displayName>?date=YYYY-MM-DD
     */
    fun getSleep(dateIso: String): GarminSleepData? {
        val name = displayName ?: return null

        val json = connectApi(
            "/wellness-service/wellness/dailySleepData/$name",
            mapOf("date" to dateIso, "nonSleepBufferMinutes" to "60")
        ) ?: return null

        val daily = json.optJSONObject("dailySleepDTO") ?: return null
        return GarminSleepData(
            date = dateIso,
            startGmtMs = daily.optLong("sleepStartTimestampGMT", 0),
            endGmtMs = daily.optLong("sleepEndTimestampGMT", 0),
            sleepTimeSeconds = daily.optInt("sleepTimeSeconds", 0).toLong(),
            deepSeconds = daily.optInt("deepSleepSeconds", 0).toLong(),
            remSeconds = daily.optInt("remSleepSeconds", 0).toLong()
        )
    }

    /**
     * Activities list.
     * GET /activitylist-service/activities/search/activities?start=0&limit=N
     * Garmin API gibt direkt ein JSON-Array zurück (kein Wrapper-Object).
     */
    fun getActivities(limit: Int = 20): List<GarminRemoteActivity> {
        val paramStr = "?start=0&limit=$limit"
        val url = URL("$CONNECTAPI_BASE/activitylist-service/activities/search/activities$paramStr")
        val token = prefs.getString(KEY_DI_TOKEN, null) ?: return emptyList()

        val responseText = try {
            apiGetRaw(url, token)
        } catch (e: Exception) {
            // Retry mit Token-Refresh
            if (refreshDiToken()) {
                val newToken = prefs.getString(KEY_DI_TOKEN, null) ?: return emptyList()
                apiGetRaw(url, newToken)
            } else {
                return emptyList()
            }
        }

        val arr = try { org.json.JSONArray(responseText) } catch (e: Exception) { return emptyList() }
        val result = mutableListOf<GarminRemoteActivity>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            result.add(GarminRemoteActivity(
                activityId = a.optLong("activityId", 0).toString(),
                name = a.optString("activityName", ""),
                type = a.optJSONObject("activityType")?.optString("typeKey", "") ?: "",
                startGmt = a.optString("startTimeGMT", ""),
                distanceMeters = a.optDouble("distance", 0.0),
                durationSeconds = a.optDouble("duration", 0.0),
                calories = a.optInt("calories", 0)
            ))
        }
        return result
    }

    private fun apiGetRaw(url: URL, token: String): String {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            NATIVE_HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Connect API ${url.path} → HTTP $code")
                throw Exception("HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── JWT Helpers ────────────────────────────────────────────────

    private fun extractClientIdFromJwt(token: String): String? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(payload).optString("client_id").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private class MfaRequiredException : Exception()
    private class AuthException(message: String) : Exception(message)
}
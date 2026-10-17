package com.d_drostes_apps.aevum.data.pc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP-Client für den Aevum PC-Tracker (docs/PC-TRACKER-PROTOCOL.md, Abschnitt 3).
 * Bewusst ohne zusätzliche Bibliothek: HttpURLConnection + org.json sind in
 * Android enthalten — die App bleibt schlank und der Vertrag ist klein.
 */
@Singleton
class PcTrackerClient @Inject constructor() {

    /** Für Tests anpassbar. */
    internal var connectTimeoutMs: Int = 3_000
    internal var readTimeoutMs: Int = 8_000

    data class PcInfo(
        val app: String,
        val version: String,
        val deviceId: String,
        val name: String,
        val apiPort: Int,
        val pairingActive: Boolean,
        val trackingPaused: Boolean
    )

    data class PairToken(val token: String, val deviceId: String, val name: String)

    /** Eine Aktivität, die an den PC gespiegelt wird (Regel-Dropdown dort). */
    data class ActivitySync(
        val key: String,
        val name: String,
        val icon: String,
        val categoryKey: String
    )

    data class PcSession(
        val id: String,
        val program: String,
        val activityKey: String,
        val activityName: String,
        val categoryKey: String,
        val icon: String,
        val startAt: Long,
        val endAt: Long,
        val timezoneId: String
    )

    data class SessionsResponse(
        val serverTime: Long,
        val deviceId: String,
        val deviceName: String,
        val sessions: List<PcSession>
    )

    /** HTTP-Fehler mit dem Fehlercode des Protokolls (z.B. invalid_code). */
    class PcHttpException(
        val statusCode: Int,
        val errorCode: String?,
        val retryAfterSeconds: Int?
    ) : IOException("HTTP $statusCode ($errorCode)")

    suspend fun fetchInfo(host: String, port: Int): PcInfo = withContext(Dispatchers.IO) {
        val (_, body) = request("GET", "${base(host, port)}/api/info", null, null)
        val json = JSONObject(body)
        PcInfo(
            app = json.optString("app"),
            version = json.optString("version"),
            deviceId = json.optString("deviceId"),
            name = json.optString("name"),
            apiPort = json.optInt("apiPort", port),
            pairingActive = json.optBoolean("pairingActive", false),
            trackingPaused = json.optBoolean("trackingPaused", false)
        )
    }

    suspend fun pair(host: String, port: Int, code: String, clientName: String): PairToken =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("code", code)
                .put("clientName", clientName)
                .toString()
            val (_, body) = request("POST", "${base(host, port)}/api/pair", payload, null)
            val json = JSONObject(body)
            PairToken(
                token = json.optString("token"),
                deviceId = json.optString("deviceId"),
                name = json.optString("name")
            )
        }

    suspend fun fetchSessions(
        host: String,
        port: Int,
        token: String,
        sinceMs: Long,
        limit: Int = 200,
        clientName: String = ""
    ): SessionsResponse = withContext(Dispatchers.IO) {
        val url = "${base(host, port)}/api/sessions?since=$sinceMs&limit=$limit"
        val headers = buildMap {
            put("X-Aevum-Token", token)
            if (clientName.isNotBlank()) {
                put("X-Aevum-Client", clientName)
            }
        }
        val (_, body) = request("GET", url, null, headers)
        val json = JSONObject(body)
        val device = json.optJSONObject("device")
        val sessions = json.optJSONArray("sessions") ?: return@withContext SessionsResponse(
            serverTime = json.optLong("serverTime", System.currentTimeMillis()),
            deviceId = device?.optString("id").orEmpty(),
            deviceName = device?.optString("name").orEmpty(),
            sessions = emptyList()
        )
        val list = buildList {
            for (i in 0 until sessions.length()) {
                val o = sessions.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val endAt = o.optLong("endAt")
                if (id.isEmpty() || endAt <= 0) {
                    continue
                }
                // Nur abgeschlossene Sessions sind importierbar.
                add(
                    PcSession(
                        id = id,
                        program = o.optString("program"),
                        activityKey = o.optString("activityKey"),
                        activityName = o.optString("activityName"),
                        categoryKey = o.optString("categoryKey"),
                        icon = o.optString("icon"),
                        startAt = o.optLong("startAt"),
                        endAt = endAt,
                        timezoneId = o.optString("timezoneId")
                    )
                )
            }
        }
        SessionsResponse(
            serverTime = json.optLong("serverTime", System.currentTimeMillis()),
            deviceId = device?.optString("id").orEmpty(),
            deviceName = device?.optString("name").orEmpty(),
            sessions = list
        )
    }

    /**
     * Spiegelt die Aktivitäten der App zum PC (Regeln-Dropdown dort). Der PC
     * ersetzt seinen Stand komplett — die App schickt bei jedem Sync alles.
     * Ein 404 bedeutet: ältere PC-Version ohne diesen Endpunkt → kein Fehler.
     */
    suspend fun pushActivities(
        host: String,
        port: Int,
        token: String,
        clientName: String,
        activities: List<ActivitySync>
    ): Boolean = withContext(Dispatchers.IO) {
        val array = JSONArray()
        activities.forEach { activity ->
            array.put(
                JSONObject()
                    .put("key", activity.key)
                    .put("name", activity.name)
                    .put("icon", activity.icon)
                    .put("categoryKey", activity.categoryKey)
            )
        }
        val payload = JSONObject()
            .put("clientName", clientName)
            .put("activities", array)
            .toString()
        try {
            request(
                "POST",
                "${base(host, port)}/api/activities",
                payload,
                mapOf("X-Aevum-Token" to token, "X-Aevum-Client" to clientName)
            )
            true
        } catch (e: PcHttpException) {
            if (e.statusCode == 404) false else throw e
        }
    }

    /**
     * PC 1.4.0: Live-Zustand der Handy-Aufzeichnung zum PC melden und den
     * ausstehenden Steuerbefehl abholen. Devon: „Also auf meinem handy zeichne
     * ich gerade studium auf. […] Es sollte es aber, auch mit buttons zum
     * stoppen und pausieren." — Der PC antwortet auf jede Meldung mit dem
     * ggf. wartenden Befehl; nur das Handy initiiert (Pull-Modell).
     *
     * Rückgabe: „pause" / „resume" / „stop" — oder null (nichts zu tun).
     */
    suspend fun relayLive(
        host: String,
        port: Int,
        token: String,
        clientName: String,
        session: LiveSessionSync?
    ): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("clientName", clientName)
        if (session != null) {
            payload.put(
                "session",
                JSONObject()
                    .put("sessionId", session.sessionId)
                    .put("activityKey", session.activityKey)
                    .put("activityName", session.activityName)
                    .put("icon", session.icon)
                    .put("categoryKey", session.categoryKey)
                    .put("startAtMs", session.startAtMs)
                    .put("isPaused", session.isPaused)
                    .put("blockEndAtMs", session.blockEndAtMs)
            )
        } else {
            payload.put("session", JSONObject.NULL)
        }
        val (_, body) = request(
            "POST",
            "${base(host, port)}/api/live",
            payload.toString(),
            mapOf("X-Aevum-Token" to token, "X-Aevum-Client" to clientName)
        )
        JSONObject(body).optString("command").ifBlank { null }
    }

    /** Momentaufnahme einer laufenden Aufzeichnung für den PC (Live-Kanal). */
    data class LiveSessionSync(
        val sessionId: String,
        val activityKey: String,
        val activityName: String,
        val icon: String,
        val categoryKey: String,
        val startAtMs: Long,
        val isPaused: Boolean,
        /** Bei Pause: Zeitpunkt, an dem der Aufzeichnungsblock endete (Session-Split). */
        val blockEndAtMs: Long
    )

    private fun base(host: String, port: Int) = "http://$host:$port"

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>?
    ): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers?.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw parseError(status, text)
            }
            return status to text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(status: Int, body: String): PcHttpException {
        var errorCode: String? = null
        var retryAfter: Int? = null
        runCatching {
            val json = JSONObject(body)
            errorCode = json.optString("error").ifBlank { null }
            if (json.has("retryAfterSeconds")) {
                retryAfter = json.optInt("retryAfterSeconds")
            }
        }
        return PcHttpException(status, errorCode, retryAfter)
    }
}

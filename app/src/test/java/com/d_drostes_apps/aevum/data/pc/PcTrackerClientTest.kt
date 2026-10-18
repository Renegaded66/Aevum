package com.d_drostes_apps.aevum.data.pc

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * HTTP-Vertrag gegen einen lokalen Mock-Server (MockWebServer):
 * Endpunkte, Header, Fehlerabbildung. Der echte PC-Server wird zusätzlich
 * Ende-zu-Ende gegen die Windows-App getestet.
 */
class PcTrackerClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PcTrackerClient

    private val host: String get() = server.hostName
    private val port: Int get() = server.port

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PcTrackerClient()
        client.connectTimeoutMs = 2_000
        client.readTimeoutMs = 2_000
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `fetchInfo parst die Antwort`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"app":"aevum-pc-tracker","protocol":1,"version":"1.0.0",
                    "deviceId":"dev-1","name":"Devons-PC","apiPort":47825,
                    "pairingActive":true,"trackingPaused":false,"retentionDays":365}"""
            )
        )

        val info = client.fetchInfo(host, port)

        assertThat(info.app).isEqualTo("aevum-pc-tracker")
        assertThat(info.name).isEqualTo("Devons-PC")
        assertThat(info.deviceId).isEqualTo("dev-1")
        assertThat(info.apiPort).isEqualTo(47825)
        assertThat(info.pairingActive).isTrue()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/api/info")
    }

    @Test
    fun `pair sendet POST mit Code und parst das Token`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"protocol":1,"token":"tok-123","deviceId":"dev-1","name":"Devons-PC"}"""
            )
        )

        val result = client.pair(host, port, "482913", "Aevum auf Pixel")

        assertThat(result.token).isEqualTo("tok-123")
        assertThat(result.deviceId).isEqualTo("dev-1")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/pair")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"code\":\"482913\"")
        assertThat(body).contains("\"clientName\":\"Aevum auf Pixel\"")
    }

    @Test
    fun `pair bildet 401 auf invalid_code ab`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_code"}"""))

        val failure = runCatching { client.pair(host, port, "000000", "Handy") }
            .exceptionOrNull() as PcTrackerClient.PcHttpException

        assertThat(failure.statusCode).isEqualTo(401)
        assertThat(failure.errorCode).isEqualTo("invalid_code")
        assertThat(failure.retryAfterSeconds).isNull()
    }

    @Test
    fun `pair bildet 429 mit Wartezeit ab`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":"rate_limited","retryAfterSeconds":60}""")
        )

        val failure = runCatching { client.pair(host, port, "000000", "Handy") }
            .exceptionOrNull() as PcTrackerClient.PcHttpException

        assertThat(failure.statusCode).isEqualTo(429)
        assertThat(failure.errorCode).isEqualTo("rate_limited")
        assertThat(failure.retryAfterSeconds).isEqualTo(60)
    }

    @Test
    fun `fetchSessions sendet das Token und filtert Unvollstaendiges`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"protocol":1,"serverTime":9999,"device":{"id":"dev-1","name":"Devons-PC"},
                    "sessions":[
                      {"id":"s1","program":"Minecraft","programKey":"minecraft.exe",
                       "activityKey":"gaming","activityName":"Zocken","categoryKey":"digital",
                       "icon":"🎮","startAt":1000,"endAt":2000,"timezoneId":"Europe/Berlin"},
                      {"id":"s2","program":"Steam","programKey":"steam.exe",
                       "activityKey":"gaming","activityName":"Zocken","categoryKey":"digital",
                       "icon":"🎮","startAt":3000,"endAt":4500,"timezoneId":"Europe/Berlin"},
                      {"id":"s3","program":"Laeuft noch","activityKey":"gaming","activityName":"Zocken",
                       "categoryKey":"digital","icon":"🎮","startAt":5000,"endAt":0,"timezoneId":"Europe/Berlin"}
                    ]}"""
            )
        )

        val response = client.fetchSessions(host, port, "tok-123", sinceMs = 1234)

        assertThat(response.serverTime).isEqualTo(9999)
        assertThat(response.deviceName).isEqualTo("Devons-PC")
        // s3 (ohne endAt) wird rausgefiltert — nur abgeschlossene Sessions.
        assertThat(response.sessions.map { it.id }).containsExactly("s1", "s2")
        assertThat(response.sessions.first().activityName).isEqualTo("Zocken")
        assertThat(response.sessions.first().timezoneId).isEqualTo("Europe/Berlin")

        val request = server.takeRequest()
        assertThat(request.getHeader("X-Aevum-Token")).isEqualTo("tok-123")
        assertThat(request.path).contains("since=1234")
    }

    @Test
    fun `fetchSessions bildet 401 auf unauthorized ab`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val failure = runCatching { client.fetchSessions(host, port, "schlechtes-token", 0) }
            .exceptionOrNull() as PcTrackerClient.PcHttpException

        assertThat(failure.statusCode).isEqualTo(401)
    }

    @Test
    fun `nicht erreichbarer Port wirft IOException`() = runTest {
        val free = ServerSocket(0).use { it.localPort }
        val failure = runCatching { client.fetchInfo("127.0.0.1", free) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `pushActivities sendet alle Aktivitaeten und meldet Erfolg`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"count":2}"""))

        val ok = client.pushActivities(
            host, port, "tok-123", "Aevum auf Pixel",
            listOf(
                PcTrackerClient.ActivitySync("gaming", "Zocken", "🎮", "leisure"),
                PcTrackerClient.ActivitySync("custom-1", "Neue Aktivität", "✨", "")
            )
        )

        assertThat(ok).isTrue()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/activities")
        assertThat(request.getHeader("X-Aevum-Token")).isEqualTo("tok-123")
        assertThat(request.getHeader("X-Aevum-Client")).isEqualTo("Aevum auf Pixel")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"key\":\"gaming\"")
        assertThat(body).contains("\"name\":\"Zocken\"")
        assertThat(body).contains("\"categoryKey\":\"leisure\"")
        assertThat(body).contains("Neue Aktivität")
    }

    @Test
    fun `pushActivities meldet false bei 404 (aeltere PC-Version)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))

        val ok = client.pushActivities(
            host, port, "tok-123", "Handy",
            listOf(PcTrackerClient.ActivitySync("gaming", "Zocken", "🎮", "leisure"))
        )

        assertThat(ok).isFalse()
    }

    // ── M22: Steuerbefehl-Kanal (/api/commands) ─────────────────────────

    @Test
    fun `fetchCommand parst einen Startbefehl`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"protocol":2,"serverTime":123,"command":"start","activityKey":"learning","issuedAt":120}"""
            )
        )

        val command = client.fetchCommand(host, port, "tok-123")

        assertThat(command).isNotNull()
        assertThat(command!!.command).isEqualTo("start")
        assertThat(command.activityKey).isEqualTo("learning")
        assertThat(command.issuedAt).isEqualTo(120)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/api/commands")
        assertThat(request.getHeader("X-Aevum-Token")).isEqualTo("tok-123")
    }

    @Test
    fun `fetchCommand liefert null ohne Befehl`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"protocol":2,"serverTime":123,"command":null,"activityKey":null,"issuedAt":0}"""
            )
        )

        assertThat(client.fetchCommand(host, port, "tok-123")).isNull()
    }
}

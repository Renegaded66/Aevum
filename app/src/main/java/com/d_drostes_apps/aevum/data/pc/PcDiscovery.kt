package com.d_drostes_apps.aevum.data.pc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** Ein im Heimnetz gefundener PC-Tracker. */
data class DiscoveredPc(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val pairingActive: Boolean
)

/**
 * UDP-Discovery im Heimnetz (siehe docs/PC-TRACKER-PROTOCOL.md, Abschnitt 2):
 * Die App sendet einen Broadcast an Port 47826, jeder laufende PC-Tracker
 * antwortet per Unicast mit Name + API-Port. So muss niemand IP-Adressen
 * abtippen — die manuelle Eingabe bleibt als Zweitweg.
 */
@Singleton
class PcDiscovery @Inject constructor() {

    /** Broadcast + kurzes Sammelfenster. Läuft nie auf dem Main-Thread. */
    suspend fun discover(timeoutMs: Int = 2500): List<DiscoveredPc> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredPc>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 350
            val request = """{"magic":"$DISCOVER_MAGIC","v":1}""".toByteArray(Charsets.UTF_8)

            broadcastAddresses().forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(request, request.size, address, DISCOVERY_PORT))
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val host = packet.address?.hostAddress ?: continue
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseAnnounce(text, host)?.let { pc -> found[pc.deviceId] = pc }
            }
        }
        found.values.toList()
    }

    /**
     * Antwort eines PCs parsen (reine Funktion, testbar). Null = keine gültige
     * Aevum-Antwort (fremder UDP-Verkehr im Netz wird ignoriert).
     */
    fun parseAnnounce(json: String, host: String): DiscoveredPc? {
        return try {
            val root = JSONObject(json)
            if (root.optString("magic") != ANNOUNCE_MAGIC) {
                return null
            }
            val port = root.optInt("apiPort", 0)
            val deviceId = root.optString("deviceId")
            if (port <= 0 || deviceId.isEmpty()) {
                return null
            }
            DiscoveredPc(
                deviceId = deviceId,
                name = root.optString("name").ifBlank { host },
                host = host,
                port = port,
                pairingActive = root.optBoolean("pairingActive", false)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Broadcast-Adresse 255.255.255.255 + die Broadcast-Adresse jedes IPv4-Interfaces. */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) {
                    return@forEach
                }
                nif.interfaceAddresses.forEach { ia ->
                    ia.broadcast?.let { result.add(it) }
                }
            }
        }
        return result.toList()
    }

    companion object {
        const val DISCOVER_MAGIC = "AEVUM-PC-DISCOVER"
        const val ANNOUNCE_MAGIC = "AEVUM-PC-ANNOUNCE"
        const val DISCOVERY_PORT = 47826
    }
}

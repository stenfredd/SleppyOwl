package ru.stenfredd.movies.core.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Finds a direct LAN address of the logged-in server, for streaming.
 *
 * At home the TV reached the server through its public HTTPS name: router hairpin, reverse proxy, and TLS decrypted
 * by the TV's CPU. That capped Exo at 40–115 Mbit/s and a 66 Mbit/s 4K remux rebuffered every
 * few seconds, while plain HTTP to the LAN address gives the TV ~240 Mbit/s (2026-10-13).
 *
 * The server's own `LocalAddress` is the public name too (it is configured that way), so the address comes from
 * Jellyfin's UDP discovery (port 7359): the reply's source IP, with the default HTTP port. It is only used if
 * `/System/Info/Public` there reports the same server id.
 */
internal object LocalServerDiscovery {
    private const val DISCOVERY_PORT = 7359
    private const val HTTP_PORT = 8096
    private const val TIMEOUT_MS = 1500
    private const val MESSAGE = "Who is JellyfinServer?"

    /** Server id from `/System/Info/Public` of [baseUrl], or null. */
    suspend fun serverId(baseUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(get("$baseUrl/System/Info/Public")).optString("Id").takeIf { it.isNotEmpty() } }
            .onFailure { Timber.d(it, "Server id not read from %s", baseUrl) }
            .getOrNull()
    }

    /** `http://<ip>:8096` of the server with [serverId] on the local network, or null. */
    suspend fun find(serverId: String): String? = withContext(Dispatchers.IO) {
        val candidates = runCatching { discover(serverId) }.onFailure { Timber.d(it, "Jellyfin discovery failed") }.getOrDefault(emptyList())
        candidates.firstOrNull { url -> serverId(url) == serverId }
    }

    private fun discover(serverId: String): List<String> {
        val found = mutableListOf<String>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = TIMEOUT_MS
            val message = MESSAGE.toByteArray()
            socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val reply = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                if (reply.optString("Id") != serverId) continue
                val host = packet.address.hostAddress ?: continue
                found += "http://$host:$HTTP_PORT"
                // The advertised address, if it happens to be a plain LAN URL, is worth a try as well.
                reply.optString("Address").takeIf { it.startsWith("http://") }?.let { found += it.trimEnd('/') }
                break
            }
        }
        return found.distinct()
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

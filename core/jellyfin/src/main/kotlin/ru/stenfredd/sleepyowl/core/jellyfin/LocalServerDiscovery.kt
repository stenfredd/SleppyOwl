package ru.stenfredd.sleepyowl.core.jellyfin

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
 * Finds Jellyfin servers on the local network: for the login screen, and the direct LAN address of the logged-in
 * server for streaming.
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

    /** A reply to the discovery broadcast. */
    class Reply(val id: String, val name: String, val lanUrl: String, val advertisedUrl: String?)

    /** Server id from `/System/Info/Public` of [baseUrl], or null. */
    suspend fun serverId(baseUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(get("$baseUrl/System/Info/Public")).optString("Id").takeIf { it.isNotEmpty() } }
            .onFailure { Timber.d(it, "Server id not read from %s", baseUrl) }
            .getOrNull()
    }

    /** `http://<ip>:8096` of the server with [serverId] on the local network, or null. */
    suspend fun find(serverId: String): String? = withContext(Dispatchers.IO) {
        val reply = discover(stopAt = serverId).firstOrNull { it.id == serverId } ?: return@withContext null
        // The advertised address, if it happens to be a plain LAN URL, is worth a try as well.
        listOfNotNull(reply.lanUrl, reply.advertisedUrl?.takeIf { it.startsWith("http://") }).distinct()
            .firstOrNull { url -> serverId(url) == serverId }
    }

    /** Every server that answers within [TIMEOUT_MS], one reply per server id. */
    suspend fun discoverAll(): List<Reply> = withContext(Dispatchers.IO) { discover(stopAt = null) }

    private fun discover(stopAt: String?): List<Reply> {
        val found = LinkedHashMap<String, Reply>()
        runCatching {
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
                    val id = reply.optString("Id").takeIf { it.isNotEmpty() } ?: continue
                    val host = packet.address.hostAddress ?: continue
                    found.getOrPut(id) {
                        Reply(id, reply.optString("Name"), "http://$host:$HTTP_PORT", reply.optString("Address").trimEnd('/').takeIf { it.startsWith("http") })
                    }
                    if (id == stopAt) break
                }
            }
        }.onFailure { Timber.d(it, "Jellyfin discovery failed") }
        return found.values.toList()
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

package ru.stenfredd.sleepyowl.playback.vlc

import android.os.SystemClock
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal HTTP/1.1 proxy on 127.0.0.1 that sits between libVLC and the server.
 *
 * libVLC's own HTTPS stack (GnuTLS) misbehaves behind reverse proxies: after the Range request to the
 * end of an MKV it reports "connection failed", every such seek stalls for ~10 s and the prefetch thread
 * sometimes spins forever. Here libVLC only ever talks plain HTTP to loopback, while the real request
 * goes through OkHttp (platform TLS with system/user CAs, connection pooling).
 *
 * Every response is sent with `Connection: close`, one request per socket. VLC opens a new connection on
 * each seek and closing that socket cancels the upstream call.
 *
 * The access token never appears in the loopback URL: `ApiKey` is stripped from the query and sent
 * upstream as an `Authorization` header instead. Loopback URLs carry a random route key so other apps on
 * the device can't use the proxy. The upstream path is preserved after the key, so relative URLs (HLS
 * playlists) resolve back through the proxy.
 *
 * Recently fetched bytes are kept in a [RecentBytesCache], see there why.
 */
internal class LoopbackProxy(private val client: OkHttpClient) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val routes = ConcurrentHashMap<String, Route>()
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val requestCounter = AtomicInteger()
    private val bytes = AtomicLong()
    private val cache = RecentBytesCache(CACHE_CAPACITY)

    @Volatile
    private var closed = false

    /** Bytes sent to libVLC since the proxy started. */
    val bytesTransferred: Long get() = bytes.get()
    val activeConnections: Int get() = connections.size

    init {
        Thread(::acceptLoop, "vlc-proxy-accept").apply { isDaemon = true }.start()
    }

    private class Route(val origin: HttpUrl, val token: String?)

    /** Loopback URL for [upstream]; non-HTTP URLs are returned unchanged. */
    fun localUrl(upstream: String): String {
        val url = upstream.toHttpUrlOrNull() ?: return upstream
        val tokenParams = url.queryParameterNames.filter { name -> TOKEN_PARAMS.any { it.equals(name, ignoreCase = true) } }
        val token = tokenParams.firstNotNullOfOrNull(url::queryParameter)
        val stripped = url.newBuilder().apply { tokenParams.forEach(::removeAllQueryParameters) }.build()
        val origin = HttpUrl.Builder().scheme(url.scheme).host(url.host).port(url.port).build()
        val key = synchronized(routes) {
            routes.entries.firstOrNull { it.value.origin == origin && it.value.token == token }?.key
                ?: newKey().also { routes[it] = Route(origin, token) }
        }
        val query = stripped.encodedQuery?.let { "?$it" }.orEmpty()
        return "http://127.0.0.1:${server.localPort}/$key${stripped.encodedPath}$query"
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        connections.forEach(Connection::abort)
        routes.clear()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!closed) Timber.w(e, "VLC proxy: accept failed")
                break
            }
            val connection = Connection(socket, requestCounter.incrementAndGet())
            connections += connection
            if (closed) {
                connection.abort()
                break
            }
            Thread(connection, "vlc-proxy-${connection.id}").apply { isDaemon = true }.start()
        }
    }

    private inner class Connection(private val socket: Socket, val id: Int) : Runnable {
        @Volatile
        private var call: Call? = null

        /** What this connection did, for the log line written when it ends. */
        private var summary = "?"

        fun abort() {
            call?.cancel()
            runCatching { socket.close() }
        }

        override fun run() {
            val started = SystemClock.elapsedRealtime()
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = HEADER_TIMEOUT_MS
                serve(BufferedInputStream(socket.getInputStream()), socket.getOutputStream())
            } catch (e: IOException) {
                // Normal when VLC drops the connection on seek or the engine is released.
                summary += " — прервано: ${e.message}"
            } finally {
                call?.cancel()
                runCatching { socket.close() }
                connections -= this
                Timber.d("VLC proxy #%d %s (%d ms)", id, summary, SystemClock.elapsedRealtime() - started)
            }
        }

        private fun serve(input: InputStream, output: OutputStream) {
            val requestLine = input.readHttpLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readHttpLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            val range = headers["range"]
            summary = "$method ${range ?: "весь файл"}"

            if (method != "GET" && method != "HEAD") {
                summary += " → 405"
                output.writeHead(405, "Method Not Allowed", NO_BODY)
                return
            }
            val upstream = upstreamUrl(target)
            if (upstream == null) {
                summary += " → 404"
                output.writeHead(404, "Not Found", NO_BODY)
                return
            }

            val cacheKey = upstream.url.toString()
            val rangeStart = range?.let(OPEN_RANGE::matchEntire)?.groupValues?.get(1)?.toLong()
            val meta = cache.meta(cacheKey)
            if (method == "GET" && rangeStart != null && meta != null && rangeStart < meta.totalLength) {
                serveThroughCache(output, upstream, headers, cacheKey, rangeStart, meta)
                return
            }

            val response = execute(upstream, method, range, headers)
            if (response == null) {
                output.writeHead(502, "Bad Gateway", NO_BODY)
                return
            }
            response.use {
                val body = response.body
                val passHeaders = PASS_HEADERS.mapNotNull { name -> response.header(name)?.let { name to it } }.toMutableList()
                if (passHeaders.none { it.first == "Content-Length" } && body.contentLength() >= 0 && method == "GET") {
                    passHeaders += "Content-Length" to body.contentLength().toString()
                }
                summary += " → ${response.code} ${response.header("Content-Range").orEmpty()}"
                output.writeHead(response.code, response.message.ifEmpty { "Status" }, passHeaders)
                if (method == "HEAD") return

                // Only byte-addressable responses can be cached.
                val contentRange = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
                val (offset, total) = when {
                    response.code == 206 && contentRange != null ->
                        contentRange.groupValues[1].toLong() to contentRange.groupValues[2].toLong()
                    response.code == 200 && body.contentLength() >= 0 -> 0L to body.contentLength()
                    else -> null to null
                }
                if (offset != null && total != null) {
                    cache.putMeta(cacheKey, RecentBytesCache.Meta(total, passHeaders.filter { it.first !in RANGE_HEADERS }))
                }
                pump(body.byteStream(), output, cacheKey, offset)
            }
        }

        /** Answers `Range: bytes=start-` from the cache, then fetches whatever is left from the server. */
        private fun serveThroughCache(
            output: OutputStream,
            upstream: Upstream,
            headers: Map<String, String>,
            cacheKey: String,
            start: Long,
            meta: RecentBytesCache.Meta,
        ) {
            val total = meta.totalLength
            summary += " → 206 через кэш"
            output.writeHead(
                206,
                "Partial Content",
                meta.headers + listOf("Content-Range" to "bytes $start-${total - 1}/$total", "Content-Length" to (total - start).toString()),
            )
            socket.soTimeout = 0
            val cached = cache.writeFrom(cacheKey, start, output)
            bytes.addAndGet(cached)
            summary += ", из кэша ${cached / 1024} КБ"
            val position = start + cached
            if (position >= total) {
                output.flush()
                return
            }
            // The head is already sent: on any upstream problem just drop the connection, VLC reconnects.
            val response = execute(upstream, "GET", "bytes=$position-", headers) ?: throw IOException("сервер недоступен")
            response.use {
                if (response.code != 206 || response.header("Content-Range")?.startsWith("bytes $position-") != true) {
                    throw IOException("сервер ответил ${response.code} ${response.header("Content-Range").orEmpty()}")
                }
                pump(response.body.byteStream(), output, cacheKey, position)
            }
        }

        private fun execute(upstream: Upstream, method: String, range: String?, headers: Map<String, String>): Response? {
            val request = Request.Builder().url(upstream.url).apply {
                if (method == "HEAD") head()
                range?.let { header("Range", it) }
                headers["user-agent"]?.let { header("User-Agent", it) }
                // Without this OkHttp transparently gunzips and drops Content-Length, which breaks seeking.
                header("Accept-Encoding", "identity")
                upstream.token?.let { header("Authorization", "MediaBrowser Token=\"$it\"") }
            }.build()
            val call = client.newCall(request).also { call = it }
            if (closed) call.cancel()
            return try {
                call.execute()
            } catch (e: IOException) {
                summary += " → ошибка сервера: ${e.message}"
                null
            }
        }

        /** Copies the body to VLC; when [offset] is known, also keeps the bytes in [cache]. */
        private fun pump(source: InputStream, output: OutputStream, cacheKey: String, offset: Long?) {
            // Body reads are bounded by OkHttp's read timeout, not by the header timeout.
            socket.soTimeout = 0
            val buffer = ByteArray(COPY_BUFFER)
            val pending = ByteArray(CACHE_BLOCK)
            var pendingLength = 0
            var pendingOffset = offset ?: 0
            fun flushPending() {
                if (offset != null && pendingLength > 0) cache.put(cacheKey, pendingOffset, pending.copyOf(pendingLength))
                pendingOffset += pendingLength
                pendingLength = 0
            }
            var sent = 0L
            try {
                while (!closed) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    // Cache first: bytes VLC no longer wants (it hung up mid-write) are still valid.
                    if (offset != null) {
                        var i = 0
                        while (i < n) {
                            val chunk = minOf(n - i, CACHE_BLOCK - pendingLength)
                            System.arraycopy(buffer, i, pending, pendingLength, chunk)
                            pendingLength += chunk
                            i += chunk
                            if (pendingLength == CACHE_BLOCK) flushPending()
                        }
                    }
                    output.write(buffer, 0, n)
                    sent += n
                    bytes.addAndGet(n.toLong())
                }
                output.flush()
            } finally {
                flushPending()
                summary += ", с сервера ${sent / 1024} КБ"
            }
        }
    }

    private class Upstream(val url: HttpUrl, val token: String?)

    private fun upstreamUrl(target: String): Upstream? {
        if (!target.startsWith("/")) return null
        val keyEnd = target.indexOfAny(charArrayOf('/', '?'), startIndex = 1).let { if (it < 0) target.length else it }
        val route = routes[target.substring(1, keyEnd)] ?: return null
        val rest = target.substring(keyEnd)
        val queryStart = rest.indexOf('?')
        val path = (if (queryStart < 0) rest else rest.substring(0, queryStart)).ifEmpty { "/" }
        val query = if (queryStart < 0) null else rest.substring(queryStart + 1)
        val url = runCatching { route.origin.newBuilder().encodedPath(path).encodedQuery(query).build() }.getOrNull() ?: return null
        return Upstream(url, route.token)
    }

    private fun newKey(): String {
        val raw = ByteArray(16).also(random::nextBytes)
        return raw.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BACKLOG = 16
        const val HEADER_TIMEOUT_MS = 30_000
        const val COPY_BUFFER = 64 * 1024
        const val MAX_LINE = 16 * 1024
        const val CACHE_BLOCK = 256 * 1024
        const val CACHE_CAPACITY = 32L * 1024 * 1024
        val OPEN_RANGE = Regex("""bytes=(\d+)-""")
        val CONTENT_RANGE = Regex("""bytes (\d+)-\d+/(\d+)""")
        val RANGE_HEADERS = setOf("Content-Length", "Content-Range")
        val TOKEN_PARAMS = listOf("ApiKey", "api_key")
        val PASS_HEADERS = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")
        val NO_BODY = listOf("Content-Length" to "0")
        val random = SecureRandom()

        fun InputStream.readHttpLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.removeSuffix("\r").toString()
                if (sb.length >= MAX_LINE) throw IOException("HTTP line too long")
                sb.append(c.toChar())
            }
        }

        fun OutputStream.writeHead(code: Int, message: String, headers: List<Pair<String, String>>) {
            val head = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
                for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            write(head.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }
}

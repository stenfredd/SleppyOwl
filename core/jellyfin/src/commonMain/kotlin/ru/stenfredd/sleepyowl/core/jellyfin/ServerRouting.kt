package ru.stenfredd.sleepyowl.core.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import ru.stenfredd.sleepyowl.core.common.Log
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Sends requests for the server's public address to its LAN address when one is known.
 *
 * API calls and images are built on the public address (it is also the image cache key, so the cache stays valid
 * at home and away); at home this interceptor swaps the origin to the plain-HTTP LAN address, which skips the router
 * hairpin, the reverse proxy and TLS on the TV's weak CPU. If the LAN address can't be connected to, the request goes
 * to the public address and [onLocalFailed] lets the session look for the server again.
 */
class ServerRouting internal constructor() : Interceptor {
    private class Route(val public: HttpUrl, val local: HttpUrl)

    @Volatile
    private var route: Route? = null

    internal var onLocalFailed: (() -> Unit)? = null

    val localUrl: String? get() = route?.local?.toString()?.trimEnd('/')

    internal fun set(publicUrl: String?, localUrl: String?) {
        val public = publicUrl?.toHttpUrlOrNull()
        val local = localUrl?.toHttpUrlOrNull()
        route = if (public != null && local != null) Route(public, local) else null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val r = route ?: return chain.proceed(request)
        val local = rewrite(request.url, r) ?: return chain.proceed(request)
        return try {
            // A LAN address that isn't there (away from home) must fail fast, not after the default 10 s.
            chain.withConnectTimeout(LOCAL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).proceed(request.newBuilder().url(local).build())
        } catch (e: IOException) {
            // Only failures to connect are safe to repeat: the request never reached the server.
            if (!e.isConnectFailure() || chain.call().isCanceled()) throw e
            Log.w("LAN address %s unreachable (%s), using %s", r.local, e.javaClass.simpleName, r.public)
            if (route === r) route = null
            onLocalFailed?.invoke()
            chain.proceed(request)
        }
    }

    private fun rewrite(url: HttpUrl, r: Route): HttpUrl? {
        if (url.host != r.public.host || url.port != r.public.port || url.scheme != r.public.scheme) return null
        val prefix = r.public.encodedPath.trimEnd('/')
        val path = url.encodedPath
        if (!path.startsWith(prefix)) return null
        val tail = path.removePrefix(prefix)
        val base = r.local.toString().trimEnd('/')
        val query = url.encodedQuery?.let { "?$it" }.orEmpty()
        return "$base$tail$query".toHttpUrlOrNull()
    }

    private fun IOException.isConnectFailure() = this is ConnectException || this is NoRouteToHostException ||
        this is UnknownHostException || (this is SocketTimeoutException && message?.contains("connect", ignoreCase = true) == true)

    private companion object {
        const val LOCAL_CONNECT_TIMEOUT_MS = 1500
    }
}

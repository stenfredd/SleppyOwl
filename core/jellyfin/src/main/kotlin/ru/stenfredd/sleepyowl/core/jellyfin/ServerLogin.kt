package ru.stenfredd.sleepyowl.core.jellyfin

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.QuickConnectDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** A Jellyfin server to log in to. */
data class Server(
    val id: String,
    val name: String,
    /** Address the account is saved with: the public name when the server has one that works, else the LAN address. */
    val url: String,
    /** Direct LAN address, when the server was found on the local network. */
    val localUrl: String? = null,
    val version: String? = null,
    /** Round trip of `/System/Info/Public`, ms; null when not measured. */
    val pingMs: Long? = null,
) {
    /** Where login requests go: the LAN address at home (no hairpin, no TLS on the TV's CPU). */
    val requestUrl: String get() = localUrl ?: url
}

/** A user the server shows on its login screen (`/Users/Public`). */
data class PublicUser(val id: UUID, val name: String, val hasPassword: Boolean)

/** A Quick Connect request: [code] is typed (or scanned) on a device where the user is logged in. */
data class QuickConnectCode(val code: String, val secret: String)

/** The server forgot a Quick Connect request (it expires after a few minutes); start a new one. */
class QuickConnectExpiredException : Exception("Quick Connect request expired")

/**
 * Everything the login screens need before there is an account: servers on the local network, a server by its
 * address, its public users, password and Quick Connect login. A successful login becomes the current account of
 * [JellyfinSession].
 */
@Singleton
class ServerLogin @Inject constructor(private val session: JellyfinSession) {

    /** Servers on the local network with version and ping; empty when there are none (or no network). */
    suspend fun discover(): List<Server> = coroutineScope {
        LocalServerDiscovery.discoverAll().map { reply ->
            async {
                val lan = probe(reply.lanUrl) ?: return@async null
                if (lan.id != reply.id) return@async null
                // The address the server advertises (its public name, if configured) is saved with the account so that
                // it also works away from home; the LAN address is used while at home.
                val public = reply.advertisedUrl?.takeIf { it != reply.lanUrl && it.startsWith("https://") }
                    ?.let { url -> probe(url)?.takeIf { it.id == reply.id }?.url }
                lan.copy(name = lan.name.ifEmpty { reply.name }, url = public ?: lan.url, localUrl = reply.lanUrl)
            }
        }.awaitAll().filterNotNull()
    }

    /** Version and ping of the server at [url], or null when it doesn't answer as a Jellyfin server. */
    suspend fun probe(url: String): Server? = withContext(Dispatchers.IO) {
        runCatching {
            val api = client(url, quick = true).systemApi
            val info = api.getPublicSystemInfo().content
            // The first request also opened the connection (and TLS); the ping is a second one on it.
            val started = SystemClock.elapsedRealtime()
            api.getPublicSystemInfo()
            Server(
                id = checkNotNull(info.id),
                name = info.serverName.orEmpty(),
                url = url,
                version = info.version,
                pingMs = SystemClock.elapsedRealtime() - started,
            )
        }.onFailure { Timber.d("No Jellyfin server at %s: %s", url, it.toString()) }.getOrNull()
    }

    /**
     * The server at an address typed by the user: `192.168.1.10`, `movies.example.com`, `https://…:8920/jellyfin`.
     * Tries the usual schemes and ports (SDK address candidates) and picks the best answer.
     * Throws [ServerNotFoundException] when nothing answers.
     */
    suspend fun find(input: String): Server = withContext(Dispatchers.IO) {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) throw ServerNotFoundException()
        val best = session.discovery.getRecommendedServers(text)
            .filter { it.score != RecommendedServerInfoScore.BAD && it.systemInfo.isSuccess }
            .minWithOrNull(compareBy({ it.score.ordinal }, { it.responseTime }))
            ?: throw ServerNotFoundException()
        val info = best.systemInfo.getOrThrow()
        Server(
            id = info.id ?: best.address,
            name = info.serverName.orEmpty(),
            url = best.address.trimEnd('/'),
            version = info.version,
            pingMs = best.responseTime,
        )
    }

    /** Users listed on the server's login screen; empty when the server hides them. */
    suspend fun publicUsers(server: Server): List<PublicUser> = withContext(Dispatchers.IO) {
        client(server.requestUrl).userApi.getPublicUsers().content.map {
            PublicUser(it.id, it.name.orEmpty(), it.hasPassword ?: true)
        }
    }

    /** Logs in and makes the account current. Throws [InvalidStatusException] 401 on a wrong name or password. */
    suspend fun login(server: Server, userName: String, password: String) {
        val result = withContext(Dispatchers.IO) {
            client(server.requestUrl).authenticationApi.authenticateUserByName(userName.trim(), password).content
        }
        session.signedIn(server, result)
    }

    /** Starts a Quick Connect request, or returns null when Quick Connect is turned off on the server. */
    suspend fun startQuickConnect(server: Server): QuickConnectCode? = withContext(Dispatchers.IO) {
        val api = client(server.requestUrl).authenticationApi
        if (!api.getQuickConnectEnabled().content) return@withContext null
        val result = api.initiateQuickConnect().content
        QuickConnectCode(result.code, result.secret)
    }

    /** Whether the request was approved; throws [QuickConnectExpiredException] once the server has dropped it. */
    suspend fun isQuickConnectApproved(server: Server, secret: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client(server.requestUrl).authenticationApi.getQuickConnectState(secret).content.authenticated
        } catch (e: InvalidStatusException) {
            if (e.status == 404) throw QuickConnectExpiredException() else throw e
        }
    }

    /** Finishes an approved Quick Connect request: logs in and makes the account current. */
    suspend fun loginQuickConnect(server: Server, secret: String) {
        val result = withContext(Dispatchers.IO) {
            client(server.requestUrl).authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret)).content
        }
        session.signedIn(server, result)
    }

    /** A client without a token, with this device's identity (a Quick Connect token is bound to the device id). */
    private fun client(url: String, quick: Boolean = false): ApiClient =
        session.loginClient(url, if (quick) PROBE_OPTIONS else LOGIN_OPTIONS)

    private companion object {
        val PROBE_OPTIONS = HttpClientOptions(connectTimeout = 2.seconds, requestTimeout = 3.seconds, socketTimeout = 3.seconds)
        val LOGIN_OPTIONS = HttpClientOptions(connectTimeout = 5.seconds, requestTimeout = 15.seconds, socketTimeout = 15.seconds)
    }
}

class ServerNotFoundException : Exception("Server not found")

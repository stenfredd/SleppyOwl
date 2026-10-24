package ru.stenfredd.sleepyowl.core.jellyfin

import android.content.Context
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.discovery.DiscoveryService
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticationResult
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SessionInfo(
    val accountId: String,
    val serverId: String,
    val serverUrl: String,
    val userId: UUID,
    val userName: String,
    val accessToken: String,
)

/**
 * Holds the authenticated [ApiClient] of the current account and the accounts saved on the device.
 *
 * All HTTP goes through one [OkHttpClient] with [ServerRouting]: at home the requests addressed to the server's public
 * name go to its LAN address instead (found by [LocalServerDiscovery], remembered per account).
 */
@Singleton
class JellyfinSession @Inject constructor(
    @ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
    private val routing: ServerRouting,
) {
    private val store = AccountStore(context)
    private val jellyfin = createJellyfin {
        context = this@JellyfinSession.context
        clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
        apiClientFactory = OkHttpFactory(httpClient)
    }
    private val mutex = Mutex()

    private val _session = MutableStateFlow<SessionInfo?>(null)
    val session: StateFlow<SessionInfo?> = _session.asStateFlow()

    private val _restored = MutableStateFlow(false)
    /** True once [restore] has run: before that a null [session] means "not read yet", not "signed out". */
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    /** Saved accounts, most recently used first. */
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _userDataChanges = MutableSharedFlow<UUID>(extraBufferCapacity = 16)
    /** Items whose watch state, progress or favourite flag just changed on the server (playback stopped, marked …). */
    val userDataChanges: SharedFlow<UUID> = _userDataChanges.asSharedFlow()

    lateinit var api: ApiClient
        private set

    val deviceId: String get() = api.deviceInfo.id

    @Volatile
    private var localCheckedAt = 0L
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        routing.onLocalFailed = {
            localCheckedAt = 0
            refreshLocalAddress()
        }
    }

    /** Direct LAN address of the current server, or null (away from home, or not found yet). */
    val localUrl: String? get() = routing.localUrl

    /**
     * Looks for the server on the local network in the background (at most every [LOCAL_RECHECK_MS]). Requests use
     * whatever is known at that moment and never wait for it; the address found is remembered for the next start.
     */
    fun refreshLocalAddress() {
        val s = _session.value ?: return
        val now = SystemClock.elapsedRealtime()
        if (localCheckedAt != 0L && now - localCheckedAt < LOCAL_RECHECK_MS) return
        localCheckedAt = now
        backgroundScope.launch {
            val found = LocalServerDiscovery.find(s.serverId)
            if (_session.value?.accountId != s.accountId) return@launch
            if (found != routing.localUrl) Timber.i("Server traffic: %s", found ?: "через ${s.serverUrl}")
            routing.set(s.serverUrl, found)
            mutex.withLock {
                if (_accounts.value.firstOrNull { it.id == s.accountId }?.localUrl != found) {
                    _accounts.value = store.update(s.accountId) { list -> list.map { if (it.id == s.accountId) it.copy(localUrl = found) else it } }
                }
            }
        }
    }

    /** Restores the current account. Returns false when the user has to log in. */
    suspend fun restore(): Boolean = mutex.withLock {
        val saved = store.read()
        api = jellyfin.createApi(deviceInfo = deviceInfo(saved.deviceId ?: newDeviceId()))
        var accounts = saved.accounts
        var currentId = saved.currentId
        if (accounts.isEmpty()) migrateLegacy()?.let { account ->
            accounts = listOf(account)
            currentId = account.id
        }
        _accounts.value = accounts.sortedByDescending { it.lastUsedAt }
        val current = accounts.firstOrNull { it.id == currentId }
        current?.let(::activate)
        _restored.value = true
        current != null
    }

    /** Saves the account of a successful login (see [ServerLogin]) and makes it current. */
    internal suspend fun signedIn(server: Server, result: AuthenticationResult) = mutex.withLock {
        val token = checkNotNull(result.accessToken) { "Сервер не вернул токен" }
        val user = checkNotNull(result.user) { "Сервер не вернул пользователя" }
        val account = Account(
            serverId = result.serverId ?: user.serverId ?: server.id,
            serverName = server.name,
            serverUrl = server.url,
            userId = user.id,
            userName = user.name.orEmpty(),
            accessToken = token,
            localUrl = server.localUrl,
            lastUsedAt = System.currentTimeMillis(),
        )
        save(account)
        activate(account)
    }

    internal val discovery: DiscoveryService get() = jellyfin.discovery

    /** A client for the login screens: no token, this device's identity, its own timeouts. */
    internal fun loginClient(baseUrl: String, options: HttpClientOptions): ApiClient =
        jellyfin.createApi(baseUrl = baseUrl, deviceInfo = api.deviceInfo, httpClientOptions = options)

    /** Makes a saved account current (profile switch); its token is reused, no password needed. */
    suspend fun switchTo(accountId: String) = mutex.withLock {
        val account = _accounts.value.first { it.id == accountId }.copy(lastUsedAt = System.currentTimeMillis())
        save(account)
        activate(account)
    }

    /** Signs out of the current account (its token is revoked on the server, if it answers) and forgets it on this device. */
    suspend fun logout() = mutex.withLock {
        val current = _session.value ?: return@withLock
        revoke { api }
        _accounts.value = store.update(currentId = null) { list -> list.filterNot { it.id == current.accountId } }
        api.update(accessToken = null)
        routing.set(null, null)
        _session.value = null
    }

    /** Forgets another saved account on this device; its token is revoked on the server, if it answers. */
    suspend fun forget(accountId: String) = mutex.withLock {
        val account = _accounts.value.firstOrNull { it.id == accountId } ?: return@withLock
        if (account.id == _session.value?.accountId) return@withLock
        revoke { jellyfin.createApi(baseUrl = account.serverUrl, accessToken = account.accessToken, deviceInfo = api.deviceInfo) }
        _accounts.value = store.update(_session.value?.accountId) { list -> list.filterNot { it.id == accountId } }
            .sortedByDescending { it.lastUsedAt }
    }

    /** Ends the token's session on the server (it disappears from the server's device list); a silent server is not waited for long. */
    private suspend fun revoke(client: () -> ApiClient) {
        runCatching { withTimeout(REVOKE_TIMEOUT_MS) { withContext(Dispatchers.IO) { client().sessionApi.reportSessionEnded() } } }
            .onFailure { Timber.w(it, "Token not revoked on the server") }
    }

    /** The SDK reads response bodies on the calling thread, so every call must leave the main thread. */
    suspend fun <T> call(block: suspend ApiClient.() -> T): T = withContext(Dispatchers.IO) { api.block() }

    fun requireSession(): SessionInfo = checkNotNull(_session.value) { "Нет активной сессии" }

    /** Tells screens that [itemId]'s user data changed on the server, so cached lists (resume, next up …) reload. */
    fun notifyUserDataChanged(itemId: UUID) {
        _userDataChanges.tryEmit(itemId)
    }

    /**
     * Absolute URL for a server-relative path or a URL on the server, with the token as `ApiKey` (engines can't send
     * headers). Media goes to the LAN address when one is known; engines use their own HTTP stacks, so the address is
     * substituted here rather than by [ServerRouting].
     */
    fun authorizedUrl(pathOrUrl: String): String {
        val s = requireSession()
        val base = routing.localUrl ?: s.serverUrl
        val absolute = when {
            pathOrUrl.startsWith(s.serverUrl) -> base + pathOrUrl.removePrefix(s.serverUrl)
            pathOrUrl.startsWith("http") -> pathOrUrl
            else -> base + pathOrUrl
        }
        if (absolute.contains("ApiKey=", ignoreCase = true) || absolute.contains("api_key=")) return absolute
        val separator = if ('?' in absolute) '&' else '?'
        return "$absolute${separator}${ApiClient.QUERY_ACCESS_TOKEN}=${s.accessToken}"
    }

    private fun activate(account: Account) {
        api.update(baseUrl = account.serverUrl, accessToken = account.accessToken)
        // The LAN address from last time is used right away; a wrong one costs one fast failed connect.
        routing.set(account.serverUrl, account.localUrl)
        _session.value = SessionInfo(account.id, account.serverId, account.serverUrl, account.userId, account.userName, account.accessToken)
        localCheckedAt = 0
        refreshLocalAddress()
    }

    private suspend fun save(account: Account) {
        _accounts.value = store.update(account.id) { list -> (listOf(account) + list.filterNot { it.id == account.id }) }
            .sortedByDescending { it.lastUsedAt }
    }

    /** The prototype kept one session in separate keys; turn it into the first saved account. */
    private suspend fun migrateLegacy(): Account? {
        val legacy = store.legacySession() ?: return null
        val serverId = LocalServerDiscovery.serverId(legacy.url) ?: legacy.url
        val account = Account(serverId, "", legacy.url, legacy.userId, legacy.userName, legacy.token, lastUsedAt = System.currentTimeMillis())
        store.update(account.id) { listOf(account) }
        store.dropLegacySession()
        Timber.i("Migrated the saved session to account %s", account.id)
        return account
    }

    private suspend fun newDeviceId(): String {
        val id = UUID.randomUUID().toString()
        store.setDeviceId(id)
        return id
    }

    private fun deviceInfo(id: String) = DeviceInfo(id = id, name = "${Build.MANUFACTURER} ${Build.MODEL}")

    private companion object {
        const val CLIENT_NAME = "SleepyOwl"
        const val CLIENT_VERSION = "0.1.0"
        const val LOCAL_RECHECK_MS = 10 * 60_000L
        const val REVOKE_TIMEOUT_MS = 3_000L
    }
}

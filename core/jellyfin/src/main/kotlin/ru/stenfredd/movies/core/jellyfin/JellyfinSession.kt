package ru.stenfredd.movies.core.jellyfin

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore by preferencesDataStore("jellyfin_session")

data class SessionInfo(
    val serverUrl: String,
    val userId: UUID,
    val userName: String,
    val accessToken: String,
)

/** Holds the single authenticated [ApiClient]; persists the session across restarts. */
@Singleton
class JellyfinSession @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val jellyfin = createJellyfin {
        context = this@JellyfinSession.context
        clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
    }

    private val _session = MutableStateFlow<SessionInfo?>(null)
    val session: StateFlow<SessionInfo?> = _session.asStateFlow()

    lateinit var api: ApiClient
        private set

    val deviceId: String get() = api.deviceInfo.id

    /** Direct LAN address of the same server for media streams, see [LocalServerDiscovery]; null away from home. */
    @Volatile
    private var localUrl: String? = null
    @Volatile
    private var localCheckedAt = 0L
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Looks for the server on the local network in the background (at most every [LOCAL_RECHECK_MS]); playback uses
     * whatever is known at that moment and never waits for it.
     */
    fun refreshLocalAddress() {
        val s = _session.value ?: return
        val now = SystemClock.elapsedRealtime()
        if (localCheckedAt != 0L && now - localCheckedAt < LOCAL_RECHECK_MS) return
        localCheckedAt = now
        discoveryScope.launch {
            val id = LocalServerDiscovery.serverId(s.serverUrl)
            val found = id?.let { LocalServerDiscovery.find(it) }
            if (found != localUrl) Timber.i("Media streams: %s", found ?: "через ${s.serverUrl}")
            localUrl = found
        }
    }

    /** Restores the saved session. Returns false when the user has to log in. */
    suspend fun restore(): Boolean {
        val prefs = context.sessionStore.data.first()
        api = jellyfin.createApi(deviceInfo = deviceInfo(prefs[KEY_DEVICE_ID] ?: newDeviceId()))
        val url = prefs[KEY_URL] ?: return false
        val token = prefs[KEY_TOKEN] ?: return false
        val userId = prefs[KEY_USER_ID]?.let(UUID::fromString) ?: return false
        api.update(baseUrl = url, accessToken = token)
        _session.value = SessionInfo(url, userId, prefs[KEY_USER_NAME].orEmpty(), token)
        refreshLocalAddress()
        return true
    }

    suspend fun login(serverUrl: String, userName: String, password: String) {
        val url = serverUrl.trim().trimEnd('/')
        api.update(baseUrl = url, accessToken = null)
        val result = call { authenticationApi.authenticateUserByName(userName, password).content }
        val token = checkNotNull(result.accessToken) { "Сервер не вернул токен" }
        val user = checkNotNull(result.user) { "Сервер не вернул пользователя" }
        api.update(accessToken = token)
        context.sessionStore.edit {
            it[KEY_URL] = url
            it[KEY_TOKEN] = token
            it[KEY_USER_ID] = user.id.toString()
            it[KEY_USER_NAME] = user.name.orEmpty()
        }
        _session.value = SessionInfo(url, user.id, user.name.orEmpty(), token)
        localUrl = null
        localCheckedAt = 0
        refreshLocalAddress()
    }

    suspend fun logout() {
        context.sessionStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_USER_ID)
        }
        api.update(accessToken = null)
        _session.value = null
    }

    /** The SDK reads response bodies on the calling thread, so every call must leave the main thread. */
    suspend fun <T> call(block: suspend ApiClient.() -> T): T = withContext(Dispatchers.IO) { api.block() }

    fun requireSession(): SessionInfo = checkNotNull(_session.value) { "Нет активной сессии" }

    /**
     * Absolute URL for a server-relative path or a URL on the server, with the token as `ApiKey` (engines can't send
     * headers). Media goes to the LAN address when one was found; API calls keep using [SessionInfo.serverUrl].
     */
    fun authorizedUrl(pathOrUrl: String): String {
        val s = requireSession()
        val base = localUrl ?: s.serverUrl
        val absolute = when {
            pathOrUrl.startsWith(s.serverUrl) -> base + pathOrUrl.removePrefix(s.serverUrl)
            pathOrUrl.startsWith("http") -> pathOrUrl
            else -> base + pathOrUrl
        }
        if (absolute.contains("ApiKey=", ignoreCase = true) || absolute.contains("api_key=")) return absolute
        val separator = if ('?' in absolute) '&' else '?'
        return "$absolute${separator}${ApiClient.QUERY_ACCESS_TOKEN}=${s.accessToken}"
    }

    private suspend fun newDeviceId(): String {
        val id = UUID.randomUUID().toString()
        context.sessionStore.edit { it[KEY_DEVICE_ID] = id }
        return id
    }

    private fun deviceInfo(id: String) = DeviceInfo(id = id, name = "${Build.MANUFACTURER} ${Build.MODEL}")

    private companion object {
        const val CLIENT_NAME = "Movies (prototype)"
        const val CLIENT_VERSION = "0.1.0"
        val KEY_URL = stringPreferencesKey("server_url")
        val KEY_TOKEN = stringPreferencesKey("access_token")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        const val LOCAL_RECHECK_MS = 10 * 60_000L
    }
}

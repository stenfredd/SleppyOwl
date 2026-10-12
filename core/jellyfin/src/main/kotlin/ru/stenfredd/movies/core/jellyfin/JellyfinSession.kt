package ru.stenfredd.movies.core.jellyfin

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import kotlinx.coroutines.withContext
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

    /** Restores the saved session. Returns false when the user has to log in. */
    suspend fun restore(): Boolean {
        val prefs = context.sessionStore.data.first()
        api = jellyfin.createApi(deviceInfo = deviceInfo(prefs[KEY_DEVICE_ID] ?: newDeviceId()))
        val url = prefs[KEY_URL] ?: return false
        val token = prefs[KEY_TOKEN] ?: return false
        val userId = prefs[KEY_USER_ID]?.let(UUID::fromString) ?: return false
        api.update(baseUrl = url, accessToken = token)
        _session.value = SessionInfo(url, userId, prefs[KEY_USER_NAME].orEmpty(), token)
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

    /** Absolute URL for a server-relative path, with the token as `ApiKey` (engines can't send headers). */
    fun authorizedUrl(pathOrUrl: String): String {
        val s = requireSession()
        val absolute = if (pathOrUrl.startsWith("http")) pathOrUrl else s.serverUrl + pathOrUrl
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
    }
}

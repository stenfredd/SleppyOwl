package ru.stenfredd.sleepyowl.core.jellyfin

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A user signed in on this device: server + user + token. Several can be saved; one is current. */
data class Account(
    val serverId: String,
    val serverName: String,
    /** Address the user entered (usually the public HTTPS name); API calls are addressed to it. */
    val serverUrl: String,
    val userId: UUID,
    val userName: String,
    val accessToken: String,
    /** Direct LAN address of the same server found last time, see [LocalServerDiscovery]; tried first on the next start. */
    val localUrl: String? = null,
    val lastUsedAt: Long = 0,
) {
    val id: String get() = accountId(serverId, userId)

    internal fun toJson(): JSONObject = JSONObject()
        .put("serverId", serverId)
        .put("serverName", serverName)
        .put("serverUrl", serverUrl)
        .put("userId", userId.toString())
        .put("userName", userName)
        .put("token", accessToken)
        .put("localUrl", localUrl ?: JSONObject.NULL)
        .put("lastUsedAt", lastUsedAt)

    internal companion object {
        fun fromJson(o: JSONObject) = Account(
            serverId = o.getString("serverId"),
            serverName = o.optString("serverName"),
            serverUrl = o.getString("serverUrl"),
            userId = UUID.fromString(o.getString("userId")),
            userName = o.optString("userName"),
            accessToken = o.getString("token"),
            localUrl = o.optString("localUrl").takeIf { it.isNotEmpty() && it != "null" },
            lastUsedAt = o.optLong("lastUsedAt"),
        )
    }
}

fun accountId(serverId: String, userId: UUID) = "$serverId/$userId"

private val Context.sessionStore by preferencesDataStore("jellyfin_session")

/** Saved accounts and the device id, in DataStore; the list is a small JSON array. */
internal class AccountStore(private val context: Context) {
    class Saved(val accounts: List<Account>, val currentId: String?, val deviceId: String?)

    suspend fun read(): Saved {
        val prefs = context.sessionStore.data.first()
        return Saved(parse(prefs), prefs[KEY_CURRENT], prefs[KEY_DEVICE_ID])
    }

    suspend fun setDeviceId(id: String) {
        context.sessionStore.edit { it[KEY_DEVICE_ID] = id }
    }

    /** Applies [change] to the saved list and sets the current account; returns the new list. */
    suspend fun update(currentId: String?, change: (List<Account>) -> List<Account>): List<Account> {
        var result = emptyList<Account>()
        context.sessionStore.edit { prefs ->
            result = change(parse(prefs))
            prefs[KEY_ACCOUNTS] = JSONArray(result.map { it.toJson() }).toString()
            if (currentId != null) prefs[KEY_CURRENT] = currentId else prefs.remove(KEY_CURRENT)
        }
        return result
    }

    /** The single session saved before accounts existed (prototype); null when there is none. */
    suspend fun legacySession(): LegacySession? {
        val prefs = context.sessionStore.data.first()
        return LegacySession(
            url = prefs[LEGACY_URL] ?: return null,
            token = prefs[LEGACY_TOKEN] ?: return null,
            userId = prefs[LEGACY_USER_ID]?.let(UUID::fromString) ?: return null,
            userName = prefs[LEGACY_USER_NAME].orEmpty(),
        )
    }

    suspend fun dropLegacySession() {
        context.sessionStore.edit { prefs -> listOf(LEGACY_URL, LEGACY_TOKEN, LEGACY_USER_ID, LEGACY_USER_NAME).forEach { prefs.remove(it) } }
    }

    class LegacySession(val url: String, val token: String, val userId: UUID, val userName: String)

    private fun parse(prefs: Preferences): List<Account> {
        val raw = prefs[KEY_ACCOUNTS] ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { runCatching { Account.fromJson(array.getJSONObject(it)) }.getOrNull() }
    }

    private companion object {
        val KEY_ACCOUNTS = stringPreferencesKey("accounts")
        val KEY_CURRENT = stringPreferencesKey("current_account")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val LEGACY_URL = stringPreferencesKey("server_url")
        val LEGACY_TOKEN = stringPreferencesKey("access_token")
        val LEGACY_USER_ID = stringPreferencesKey("user_id")
        val LEGACY_USER_NAME = stringPreferencesKey("user_name")
    }
}

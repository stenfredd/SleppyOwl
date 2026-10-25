package ru.stenfredd.sleepyowl.core.jellyfin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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

    internal fun toJson(): JsonObject = buildJsonObject {
        put("serverId", serverId)
        put("serverName", serverName)
        put("serverUrl", serverUrl)
        put("userId", userId.toString())
        put("userName", userName)
        put("token", accessToken)
        put("localUrl", localUrl)
        put("lastUsedAt", lastUsedAt)
    }

    internal companion object {
        fun fromJson(o: JsonObject): Account {
            fun string(key: String) = o[key]?.jsonPrimitive?.contentOrNull
            return Account(
                serverId = string("serverId")!!,
                serverName = string("serverName").orEmpty(),
                serverUrl = string("serverUrl")!!,
                userId = UUID.fromString(string("userId")),
                userName = string("userName").orEmpty(),
                accessToken = string("token")!!,
                localUrl = string("localUrl")?.takeIf { it.isNotEmpty() && it != "null" },
                lastUsedAt = o["lastUsedAt"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }
}

fun accountId(serverId: String, userId: UUID) = "$serverId/$userId"

/** Saved accounts and the device id, in DataStore; the list is a small JSON array. */
internal class AccountStore(private val sessionStore: DataStore<Preferences>) {
    class Saved(val accounts: List<Account>, val currentId: String?, val deviceId: String?)

    suspend fun read(): Saved {
        val prefs = sessionStore.data.first()
        return Saved(parse(prefs), prefs[KEY_CURRENT], prefs[KEY_DEVICE_ID])
    }

    suspend fun setDeviceId(id: String) {
        sessionStore.edit { it[KEY_DEVICE_ID] = id }
    }

    /** Applies [change] to the saved list and sets the current account; returns the new list. */
    suspend fun update(currentId: String?, change: (List<Account>) -> List<Account>): List<Account> {
        var result = emptyList<Account>()
        sessionStore.edit { prefs ->
            result = change(parse(prefs))
            prefs[KEY_ACCOUNTS] = JsonArray(result.map { it.toJson() }).toString()
            if (currentId != null) prefs[KEY_CURRENT] = currentId else prefs.remove(KEY_CURRENT)
        }
        return result
    }

    /** The single session saved before accounts existed (prototype); null when there is none. */
    suspend fun legacySession(): LegacySession? {
        val prefs = sessionStore.data.first()
        return LegacySession(
            url = prefs[LEGACY_URL] ?: return null,
            token = prefs[LEGACY_TOKEN] ?: return null,
            userId = prefs[LEGACY_USER_ID]?.let(UUID::fromString) ?: return null,
            userName = prefs[LEGACY_USER_NAME].orEmpty(),
        )
    }

    suspend fun dropLegacySession() {
        sessionStore.edit { prefs -> listOf(LEGACY_URL, LEGACY_TOKEN, LEGACY_USER_ID, LEGACY_USER_NAME).forEach { prefs.remove(it) } }
    }

    class LegacySession(val url: String, val token: String, val userId: UUID, val userName: String)

    private fun parse(prefs: Preferences): List<Account> {
        val raw = prefs[KEY_ACCOUNTS] ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { runCatching { Account.fromJson(it.jsonObject) }.getOrNull() }
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

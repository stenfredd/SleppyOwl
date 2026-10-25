package ru.stenfredd.sleepyowl.core.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import ru.stenfredd.sleepyowl.core.common.PreferenceStores
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession

/** Recent search queries, kept on the device per account, newest first. */
class SearchHistory(
    stores: PreferenceStores,
    private val session: JellyfinSession,
) {
    private val searchStore = stores["search"]

    private fun key() = stringPreferencesKey("recent:" + session.requireSession().accountId)

    fun recent(): Flow<List<String>> {
        val key = key()
        return searchStore.data.map { prefs -> prefs[key]?.let(::parse).orEmpty() }
    }

    /** Puts [query] first; the same query typed in another case replaces the old one. */
    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val key = key()
        searchStore.edit { prefs ->
            val list = listOf(q) + prefs[key]?.let(::parse).orEmpty().filterNot { it.equals(q, ignoreCase = true) }
            prefs[key] = JsonArray(list.take(LIMIT).map(::JsonPrimitive)).toString()
        }
    }

    private fun parse(raw: String): List<String> = runCatching {
        Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())

    private companion object {
        const val LIMIT = 8
    }
}

package ru.stenfredd.movies.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchStore by preferencesDataStore("search")

/** Recent search queries, kept on the device per account, newest first. */
@Singleton
class SearchHistory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: JellyfinSession,
) {
    private fun key() = stringPreferencesKey("recent:" + session.requireSession().accountId)

    fun recent(): Flow<List<String>> {
        val key = key()
        return context.searchStore.data.map { prefs -> prefs[key]?.let(::parse).orEmpty() }
    }

    /** Puts [query] first; the same query typed in another case replaces the old one. */
    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val key = key()
        context.searchStore.edit { prefs ->
            val list = listOf(q) + prefs[key]?.let(::parse).orEmpty().filterNot { it.equals(q, ignoreCase = true) }
            prefs[key] = JSONArray(list.take(LIMIT)).toString()
        }
    }

    private fun parse(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private companion object {
        const val LIMIT = 8
    }
}

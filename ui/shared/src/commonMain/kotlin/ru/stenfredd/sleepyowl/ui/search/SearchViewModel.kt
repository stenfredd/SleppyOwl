package ru.stenfredd.sleepyowl.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.data.LibraryRepository
import ru.stenfredd.sleepyowl.core.data.SearchHistory
import ru.stenfredd.sleepyowl.core.data.model.Person
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.PosterModel
import ru.stenfredd.sleepyowl.ui.components.RemoteImageSpec
import ru.stenfredd.sleepyowl.ui.model.CardMapper
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class PersonCardModel(val key: String, val name: String, val letters: String, val photo: RemoteImageSpec?)

/** Results of one library ("Фильмы 3"), or people. */
@Immutable
data class SearchGroup(val key: String, val title: String, val posters: List<PosterModel>, val people: List<PersonCardModel>) {
    val size: Int get() = posters.size + people.size
}

@Immutable
data class SearchState(
    val query: String = "",
    val latin: Boolean = false,
    val recentQueries: List<String> = emptyList(),
    val recentItems: List<PosterModel> = emptyList(),
    /** Null while the query is empty: the screen shows recent queries and items instead. */
    val results: List<SearchGroup>? = null,
    val searching: Boolean = false,
    val error: String? = null,
)

/**
 * Search tab: the query typed on the on-screen keyboard (or a physical one), results grouped by library plus people,
 * searched as the user types (after a short pause). Recent queries are kept on the device ([SearchHistory]):
 * a query is remembered when one of its results is opened.
 */
class SearchViewModel(
    private val library: LibraryRepository,
    private val history: SearchHistory,
    private val cards: CardMapper,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val targets = ConcurrentHashMap<String, Screen>()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { history.recent().collect { list -> _state.update { it.copy(recentQueries = list) } } }
        viewModelScope.launch {
            runCatching { library.latest().take(RECENT_ITEMS) }
                .onSuccess { items ->
                    val posters = withContext(Dispatchers.Default) {
                        items.map { item ->
                            targets[cards.key(RECENT_ROW, item)] = cards.target(item)
                            cards.poster(item, RECENT_ROW)
                        }
                    }
                    _state.update { it.copy(recentItems = posters) }
                }
                .onFailure { Log.w(it, "Recent items failed") }
        }
    }

    /** Where a card leads; opening a search result remembers the query. */
    fun open(key: String): Screen? {
        val query = _state.value.query
        if (_state.value.results != null && !key.startsWith(RECENT_ROW)) viewModelScope.launch { history.add(query) }
        return targets[key]
    }

    fun type(text: String) = setQuery(_state.value.query + text)

    fun backspace() = setQuery(_state.value.query.dropLast(1))

    fun clear() = setQuery("")

    fun pickRecent(query: String) = setQuery(query)

    fun toggleLayout() = _state.update { it.copy(latin = !it.latin) }

    private fun setQuery(query: String) {
        val q = query.take(MAX_LENGTH)
        if (q == _state.value.query) return
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(query = q, results = null, searching = false, error = null) }
            return
        }
        _state.update { it.copy(query = q, searching = true) }
        searchJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            search(q.trim())
        }
    }

    private suspend fun search(term: String) {
        try {
            val groups = coroutineScope {
                val people = async { library.searchPeople(term) }
                val byLibrary = library.views().map { view -> async { view to library.search(term, view) } }.awaitAll()
                withContext(Dispatchers.Default) {
                    byLibrary.filter { it.second.isNotEmpty() }.map { (view, items) ->
                        val row = "s/${view.id}"
                        SearchGroup(
                            row,
                            view.name,
                            items.map { item ->
                                targets[cards.key(row, item)] = cards.target(item)
                                cards.poster(item, row)
                            },
                            emptyList(),
                        )
                    } + listOfNotNull(people.await().takeIf { it.isNotEmpty() }?.let { list -> SearchGroup("s/people", "Люди", emptyList(), list.map(::personCard)) })
                }
            }
            _state.update { it.copy(results = groups, searching = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(e, "Search failed")
            _state.update { it.copy(results = emptyList(), searching = false, error = e.message ?: e.toString()) }
        }
    }

    private fun personCard(p: Person): PersonCardModel {
        val key = "s/people/${p.id}"
        targets[key] = Screen.Person(p.id)
        return PersonCardModel(
            key = key,
            name = p.name,
            letters = p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
            photo = cards.image(p.image, SearchLayout.PERSON_PHOTO, SearchLayout.PERSON_PHOTO),
        )
    }

    private companion object {
        const val RECENT_ROW = "recent"
        const val RECENT_ITEMS = 8
        const val TYPING_PAUSE_MS = 350L
        const val MAX_LENGTH = 60
    }
}

object SearchLayout {
    const val PERSON_PHOTO = 80f
    /** Left column: query field and keyboard. */
    const val KEYBOARD_W = 280
    /** The results column starts here, from the content's left edge. */
    const val RESULTS_START = 372
}

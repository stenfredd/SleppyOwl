package ru.stenfredd.sleepyowl.ui.favorites

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.core.data.LibraryRepository
import ru.stenfredd.sleepyowl.core.data.SessionCache
import ru.stenfredd.sleepyowl.core.data.WatchRepository
import ru.stenfredd.sleepyowl.core.data.model.ItemSummary
import ru.stenfredd.sleepyowl.core.data.model.LibraryView
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.PosterModel
import ru.stenfredd.sleepyowl.ui.model.CardMapper
import ru.stenfredd.sleepyowl.ui.model.plural
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** "Фильмы 3": a library with favourites, or everything ([libraryId] null). */
@Immutable
data class FavoriteFilter(val key: String, val libraryId: UUID?, val label: String, val count: Int)

@Immutable
data class FavoritesState(
    val loading: Boolean = true,
    val error: String? = null,
    /** "7 тайтлов". */
    val total: String = "",
    val filters: List<FavoriteFilter> = emptyList(),
    val selected: UUID? = null,
    val items: List<PosterModel> = emptyList(),
)

/**
 * Favourites tab: favourite movies and series of every library, filtered by library (the mockup's Всё / Фильмы /
 * Сериалы / Аниме are this server's libraries). Fetched per library with its own item type (fast on the server),
 * reloaded when watch state or favourites change.
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val watch: WatchRepository,
    private val cache: SessionCache,
    private val cards: CardMapper,
) : ViewModel() {
    private val _state = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    private var byLibrary: List<Pair<LibraryView, List<ItemSummary>>> = emptyList()
    private val targets = ConcurrentHashMap<String, Screen>()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { cache.invalidations.collect { load() } }
    }

    fun target(key: String): Screen? = targets[key]

    fun select(libraryId: UUID?) {
        if (libraryId == _state.value.selected) return
        viewModelScope.launch { publish(libraryId) }
    }

    private suspend fun load() {
        try {
            byLibrary = coroutineScope {
                library.views().map { view -> async { view to watch.favorites(view) } }.awaitAll()
            }
            publish(_state.value.selected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Favourites failed")
            if (_state.value.loading) _state.value = FavoritesState(loading = false, error = e.message ?: e.toString())
        }
    }

    private suspend fun publish(selected: UUID?) {
        val withItems = byLibrary.filter { it.second.isNotEmpty() }
        // A library whose last favourite was removed has no chip any more: back to everything.
        val filter = selected?.takeIf { id -> withItems.any { it.first.id == id } }
        val total = withItems.sumOf { it.second.size }
        val shown = withItems.filter { filter == null || it.first.id == filter }.flatMap { it.second }.sortedBy { it.name.lowercase() }
        val items = withContext(Dispatchers.Default) {
            shown.map { item ->
                targets[cards.key(ROW, item)] = cards.target(item)
                cards.kindPoster(item, ROW).copy(favorite = true)
            }
        }
        _state.update {
            FavoritesState(
                loading = false,
                total = "$total ${plural(total, "тайтл", "тайтла", "тайтлов")}",
                filters = listOf(FavoriteFilter(ALL_KEY, null, "Всё", total)) +
                    withItems.map { (view, list) -> FavoriteFilter("fav/${view.id}", view.id, view.name, list.size) },
                selected = filter,
                items = items,
            )
        }
    }

    companion object {
        const val ALL_KEY = "fav/all"
        private const val ROW = "fav"
    }
}

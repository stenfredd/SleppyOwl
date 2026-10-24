package ru.stenfredd.sleepyowl.ui.library

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.core.data.LibraryRepository
import ru.stenfredd.sleepyowl.core.data.SessionCache
import ru.stenfredd.sleepyowl.core.data.WatchRepository
import ru.stenfredd.sleepyowl.core.data.model.ItemKind
import ru.stenfredd.sleepyowl.core.data.model.ItemQuery
import ru.stenfredd.sleepyowl.core.data.model.ItemSort
import ru.stenfredd.sleepyowl.core.data.model.ItemSummary
import ru.stenfredd.sleepyowl.core.data.model.LibraryType
import ru.stenfredd.sleepyowl.core.data.model.LibraryView
import ru.stenfredd.sleepyowl.core.data.model.itemKinds
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.PosterModel
import ru.stenfredd.sleepyowl.ui.components.WideModel
import ru.stenfredd.sleepyowl.ui.model.CardMapper
import ru.stenfredd.sleepyowl.ui.model.daysAgo
import ru.stenfredd.sleepyowl.ui.model.plural
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** A library tile: name, "158 фильмов · 3 новинки" (once counted) and up to three poster URLs for its mosaic. */
@Immutable
data class LibraryTile(val key: String, val id: UUID, val name: String, val stats: String?, val dot: Color, val art: List<String>)

@Immutable
data class SortChip(val key: String, val sort: ItemSort, val label: String)

/** What the screen shows under the tiles for one library. */
@Immutable
data class LibraryContent(
    val libraryId: UUID,
    val continueItems: List<WideModel>,
    val latest: List<PosterModel>,
    val history: List<PosterModel>,
    /** "Все фильмы", "Все сериалы". */
    val allTitle: String,
    val allCount: Int?,
    val sort: ItemSort,
    val unwatched: Boolean,
    val grid: List<PosterModel>,
    /** The grid holds every matching item; false while more pages remain or the first one is loading. */
    val gridComplete: Boolean,
)

@Immutable
data class LibraryState(
    val loading: Boolean = true,
    val error: String? = null,
    val tiles: List<LibraryTile> = emptyList(),
    /** The library whose content is shown; follows focus on the tiles with a short delay. */
    val selected: UUID? = null,
    val content: LibraryContent? = null,
    val contentError: String? = null,
)

/**
 * The library tab: tiles of the user's libraries and, for the selected one, its continue watching, newest items,
 * watch history and the whole library as a grid (loaded page by page, sorted and filtered).
 *
 * Content of every library visited is kept, so moving between tiles shows it at once and refreshes it in the background.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val session: JellyfinSession,
    private val library: LibraryRepository,
    private val watch: WatchRepository,
    private val cache: SessionCache,
    private val cards: CardMapper,
    private val memory: LibraryMemory,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    val sortChips = listOf(
        SortChip("sort/name", ItemSort.Name, "А–Я"),
        SortChip("sort/added", ItemSort.DateAdded, "Добавлено"),
        SortChip("sort/year", ItemSort.Year, "Год"),
        SortChip("sort/rating", ItemSort.Rating, "Рейтинг"),
    )

    /** The loaded part of a library's grid. */
    private class Grid(val sort: ItemSort, val unwatched: Boolean, val items: List<ItemSummary>, val complete: Boolean)

    private val account = session.requireSession().accountId
    private var views: List<LibraryView> = emptyList()
    private val contents = HashMap<UUID, LibraryContent>()
    private val grids = HashMap<UUID, Grid>()
    /** Card key → where it leads, for every library loaded. */
    private val targets = ConcurrentHashMap<String, Screen>()
    private var selectJob: Job? = null
    private var loadJob: Job? = null
    private var pageJob: Job? = null

    init {
        viewModelScope.launch { loadViews() }
        viewModelScope.launch { cache.invalidations.collect { _state.value.selected?.let { load(it, refresh = true) } } }
    }

    fun target(key: String): Screen? = targets[key]

    /** Focus moved to a tile: show that library once focus stays there a moment (fast moves skip libraries). */
    fun focusLibrary(id: UUID) {
        selectJob?.cancel()
        if (id == _state.value.selected) return
        selectJob = viewModelScope.launch {
            delay(SELECT_DELAY_MS)
            select(id)
        }
    }

    fun setSort(sort: ItemSort) {
        val content = _state.value.content ?: return
        if (content.sort != sort) restartGrid(content.libraryId, sort, content.unwatched)
    }

    fun toggleUnwatched() {
        val content = _state.value.content ?: return
        restartGrid(content.libraryId, content.sort, !content.unwatched)
    }

    /** The grid shows its last cards: fetch the next page. */
    fun loadMore() {
        val id = _state.value.selected ?: return
        val grid = grids[id] ?: return
        if (grid.complete || pageJob?.isActive == true) return
        pageJob = viewModelScope.launch {
            try {
                val page = fetchGrid(id, grid.sort, grid.unwatched, grid.items.size, PAGE)
                if (grids[id] !== grid) return@launch
                grids[id] = Grid(grid.sort, grid.unwatched, grid.items + page, page.size < PAGE)
                publishGrid(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Library page failed")
            }
        }
    }

    private suspend fun loadViews() {
        try {
            views = library.views()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Libraries failed")
            _state.value = LibraryState(loading = false, error = e.message ?: e.toString())
            return
        }
        val selected = memory.library(account)?.takeIf { id -> views.any { it.id == id } } ?: views.firstOrNull()?.id
        _state.value = LibraryState(
            loading = false,
            tiles = views.mapIndexed { i, v -> LibraryTile(tileKey(v.id), v.id, v.name, null, TILE_DOTS[i % TILE_DOTS.size], emptyList()) },
            selected = selected,
        )
        selected?.let { load(it, refresh = false) }
        views.forEach { view -> viewModelScope.launch { tileStats(view) } }
    }

    private fun select(id: UUID) {
        memory.setLibrary(account, id)
        _state.update { it.copy(selected = id, content = contents[id], contentError = null) }
        load(id, refresh = false)
    }

    /** Count and newest items of a library for its tile. Both are shared with home through the cache. */
    private suspend fun tileStats(view: LibraryView) = coroutineScope {
        val total = async { runCatching { library.count(countQuery(view.id, unwatched = false)) }.getOrNull() }
        val latest = async { runCatching { library.latest(view.id) }.getOrNull().orEmpty() }
        val newCount = latest.await().count(cards::isNew)
        val stats = total.await()?.let { n ->
            val unit = when (view.type) {
                LibraryType.Movies -> plural(n, "фильм", "фильма", "фильмов")
                LibraryType.Shows -> plural(n, "сериал", "сериала", "сериалов")
                else -> plural(n, "тайтл", "тайтла", "тайтлов")
            }
            "$n $unit" + if (newCount > 0) " · $newCount ${plural(newCount, "новинка", "новинки", "новинок")}" else ""
        }
        val art = latest.await().take(3).mapNotNull { item ->
            val ref = (if (item.kind == ItemKind.Episode) item.seriesPrimary else null) ?: item.primary
            cards.image(ref, LibraryLayout.ART_POSTER_W, LibraryLayout.ART_POSTER_H)?.url
        }
        _state.update { s -> s.copy(tiles = s.tiles.map { if (it.id == view.id) it.copy(stats = stats, art = art) else it }) }
    }

    /**
     * Loads the rows and the first page(s) of the grid of library [id]. [refresh] (watch state changed) keeps the
     * grid's sort and reloads as many items as were loaded, so the focused card stays.
     */
    private fun load(id: UUID, refresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val view = views.firstOrNull { it.id == id } ?: return@launch
            val (sort, unwatched) = grids[id]?.let { it.sort to it.unwatched } ?: memory.sort(id)
            val loaded = if (refresh) grids[id]?.items?.size ?: 0 else 0
            try {
                coroutineScope {
                    val cont = async { watch.continueWatching(ROW_LIMIT, id) }
                    val latest = async { library.latest(id) }
                    val history = async { watch.history(0, HISTORY_FETCH, id).items }
                    val count = async { runCatching { library.count(countQuery(id, unwatched)) }.getOrNull() }
                    val limit = maxOf(PAGE, loaded)
                    val grid = fetchGrid(id, sort, unwatched, 0, limit)
                    grids[id] = Grid(sort, unwatched, grid, grid.size < limit)
                    val content = withContext(Dispatchers.Default) {
                        build(view, sort, unwatched, cont.await(), latest.await(), history.await(), grids[id]!!, count.await())
                    }
                    contents[id] = content
                    if (_state.value.selected == id) _state.update { it.copy(content = content, contentError = null) }
                }
                Timber.i("Library loaded in %d ms", SystemClock.elapsedRealtime() - started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Library content failed")
                if (_state.value.selected == id && contents[id] == null) _state.update { it.copy(contentError = e.message ?: e.toString()) }
            }
        }
    }

    private fun restartGrid(id: UUID, sort: ItemSort, unwatched: Boolean) {
        memory.setSort(id, sort, unwatched)
        pageJob?.cancel()
        val grid = Grid(sort, unwatched, emptyList(), complete = false)
        grids[id] = grid
        // The chips switch at once; the old cards stay until the new ones come (~0.1 s): an emptied grid would
        // shorten the screen and make it jump.
        contents[id]?.let { contents[id] = it.copy(sort = sort, unwatched = unwatched) }
        _state.update { it.copy(content = contents[id]) }
        pageJob = viewModelScope.launch {
            try {
                coroutineScope {
                    launch {
                        val count = runCatching { library.count(countQuery(id, unwatched)) }.getOrNull() ?: return@launch
                        val content = contents[id]?.takeIf { it.sort == sort && it.unwatched == unwatched } ?: return@launch
                        contents[id] = content.copy(allCount = count)
                        if (_state.value.selected == id) _state.update { it.copy(content = contents[id]) }
                    }
                    val page = fetchGrid(id, sort, unwatched, 0, PAGE)
                    if (grids[id] !== grid) return@coroutineScope
                    grids[id] = Grid(sort, unwatched, page, page.size < PAGE)
                    publishGrid(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Library grid failed")
            }
        }
    }

    private suspend fun publishGrid(id: UUID) {
        val grid = grids[id] ?: return
        val posters = withContext(Dispatchers.Default) { grid.items.map { gridPoster(id, it) } }
        val content = contents[id] ?: return
        val updated = content.copy(grid = posters, gridComplete = grid.complete)
        contents[id] = updated
        if (_state.value.selected == id) _state.update { it.copy(content = updated) }
    }

    private suspend fun fetchGrid(id: UUID, sort: ItemSort, unwatched: Boolean, start: Int, limit: Int): List<ItemSummary> =
        // Without the total: counting costs the server ~0.2 s per request, the count comes from [LibraryRepository.count].
        library.items(gridQuery(id, sort, unwatched), start, limit, withTotal = false).items

    private fun gridQuery(id: UUID, sort: ItemSort, unwatched: Boolean) = ItemQuery(
        parentId = id,
        kinds = kinds(id),
        sort = sort,
        descending = sort != ItemSort.Name,
        unplayedOnly = unwatched,
    )

    /** Same query as home's library count, so it is shared through the cache. */
    private fun countQuery(id: UUID, unwatched: Boolean) = ItemQuery(parentId = id, kinds = kinds(id), unplayedOnly = unwatched)

    private fun kinds(id: UUID) = views.firstOrNull { it.id == id }?.itemKinds ?: ItemQuery().kinds

    private fun build(
        view: LibraryView,
        sort: ItemSort,
        unwatched: Boolean,
        cont: List<ItemSummary>,
        latest: List<ItemSummary>,
        history: List<ItemSummary>,
        grid: Grid,
        count: Int?,
    ): LibraryContent {
        val id = view.id
        fun row(name: String) = "$id/$name"
        return LibraryContent(
            libraryId = id,
            continueItems = cont.map { item -> remember(row("cont"), item) { cards.wide(item, row("cont")) } },
            latest = latest.map { item -> remember(row("new"), item) { cards.poster(item, row("new")) } },
            // An episode stands for its series: one card per series, at its latest watched episode.
            history = history.distinctBy { it.seriesId ?: it.id }.take(ROW_LIMIT).map { item ->
                remember(row("hist"), item) { cards.poster(item, row("hist")).copy(caption = item.user.lastPlayedAt?.let(::daysAgo)) }
            },
            allTitle = when (view.type) {
                LibraryType.Movies -> "Все фильмы"
                LibraryType.Shows -> "Все сериалы"
                else -> "Всё"
            },
            allCount = count,
            sort = sort,
            unwatched = unwatched,
            grid = grid.items.map { gridPoster(id, it) },
            gridComplete = grid.complete,
        )
    }

    private fun gridPoster(id: UUID, item: ItemSummary): PosterModel {
        val row = "$id/all"
        return remember(row, item) {
            val poster = cards.poster(item, row)
            if (item.kind != ItemKind.Series) return@remember poster
            // A series: "3 сезона · ★ 8.2" instead of the year.
            val seasons = item.childCount?.takeIf { it > 0 }?.let { "$it ${plural(it, "сезон", "сезона", "сезонов")}" }
            poster.copy(caption = listOfNotNull(seasons, item.communityRating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null })
        }
    }

    private inline fun <T> remember(row: String, item: ItemSummary, card: () -> T): T {
        targets[cards.key(row, item)] = cards.target(item)
        return card()
    }

    companion object {
        fun tileKey(id: UUID) = "tile/$id"
        const val UNWATCHED_KEY = "filter/unwatched"

        private const val PAGE = 60
        private const val ROW_LIMIT = 20
        /** History comes as episodes; fetched with room for several episodes of one series. */
        private const val HISTORY_FETCH = 60
        private const val SELECT_DELAY_MS = 300L

        /** Tile dots in the mockup's order: blue, lilac, pink. */
        private val TILE_DOTS = listOf(AppColors.Blue, Color(0xFFB39DDB), AppColors.Pink)
    }
}

package ru.stenfredd.sleepyowl.ui.library

import ru.stenfredd.sleepyowl.core.data.model.ItemSort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the library tab was left at, for this run of the app: the selected library of each account and the grid's
 * sort and filter of each library. The tab's view model goes away when another tab opens.
 */
@Singleton
class LibraryMemory @Inject constructor() {
    private val libraries = ConcurrentHashMap<String, UUID>()
    private val sorts = ConcurrentHashMap<UUID, Pair<ItemSort, Boolean>>()

    fun library(account: String): UUID? = libraries[account]

    fun setLibrary(account: String, id: UUID) {
        libraries[account] = id
    }

    /** Sort and "unwatched only" of a library's grid. */
    fun sort(library: UUID): Pair<ItemSort, Boolean> = sorts[library] ?: (ItemSort.Name to false)

    fun setSort(library: UUID, sort: ItemSort, unwatched: Boolean) {
        sorts[library] = sort to unwatched
    }
}

/** Sizes (dp) shared by the layout and the image requests. */
internal object LibraryLayout {
    const val TILE_W = 260f
    const val TILE_H = 110f
    const val TILE_CORNER = 11f
    /** Posters of a tile's mosaic: 120×180 px in the mockup. */
    const val ART_POSTER_W = 60f
    const val ART_POSTER_H = 90f
    /** Grid: 6 posters of 220 px with 28 px between them, rows 36 px apart. */
    const val GRID_GAP = 14
    const val GRID_ROW_GAP = 18
}

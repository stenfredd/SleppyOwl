package ru.stenfredd.movies.core.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.personApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import ru.stenfredd.movies.core.data.SessionCache.Kind
import ru.stenfredd.movies.core.data.model.ItemDetails
import ru.stenfredd.movies.core.data.model.ItemKind
import ru.stenfredd.movies.core.data.model.ItemQuery
import ru.stenfredd.movies.core.data.model.ItemSort
import ru.stenfredd.movies.core.data.model.ItemSummary
import ru.stenfredd.movies.core.data.model.LibraryType
import ru.stenfredd.movies.core.data.model.LibraryView
import ru.stenfredd.movies.core.data.model.Page
import ru.stenfredd.movies.core.data.model.SearchResults
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Browsing the server: libraries, item lists, details, seasons and episodes, people, search. */
@Singleton
class LibraryRepository @Inject constructor(
    private val session: JellyfinSession,
    private val cache: SessionCache,
) {
    private val userId get() = session.requireSession().userId

    /** Video libraries of the user, in the server's order (music, photos, live TV are left out). */
    suspend fun views(): List<LibraryView> = cache.get("views", Kind.Stable) {
        session.call { userViewApi.getUserViews(userId = userId).content.items }
            .map { it.toLibraryView() }
            .filter { it.type != LibraryType.Other }
    }

    /**
     * Newest items, episodes grouped into their series (a series with new episodes comes as the series, or as the
     * episode itself when it is the only new one). Cheap on the server, unlike [items] sorted by date.
     */
    suspend fun latest(parentId: UUID? = null, limit: Int = 20): List<ItemSummary> = cache.get("latest:$parentId:$limit", Kind.UserData) {
        session.call {
            libraryApi.getLatestMedia(userId = userId, parentId = parentId, limit = limit, groupItems = true, fields = CARD_FIELDS).content
        }.map { it.toSummary() }
    }

    /** One page of a filtered, sorted list. The total is only counted when [withTotal] (the server pays ~0.2 s for it). */
    suspend fun items(query: ItemQuery, start: Int, limit: Int, withTotal: Boolean = true): Page<ItemSummary> =
        cache.get("items:$query:$start:$limit:$withTotal", Kind.UserData) {
            val result = session.call {
                libraryApi.getItems(
                    userId = userId,
                    parentId = query.parentId,
                    recursive = true,
                    includeItemTypes = query.kinds.mapNotNull { it.toBaseKind() },
                    sortBy = query.sort.toSortBy(),
                    sortOrder = listOf(if (query.descending) SortOrder.DESCENDING else SortOrder.ASCENDING),
                    filters = buildList {
                        if (query.unplayedOnly) add(ItemFilter.IS_UNPLAYED)
                        if (query.playedOnly) add(ItemFilter.IS_PLAYED)
                        if (query.favoritesOnly) add(ItemFilter.IS_FAVORITE)
                    },
                    genres = query.genre?.let { listOf(it) },
                    personIds = query.personId?.let { listOf(it) },
                    startIndex = start,
                    limit = limit,
                    fields = CARD_FIELDS,
                    enableTotalRecordCount = withTotal,
                ).content
            }
            Page(result.items.map { it.toSummary() }, start, if (withTotal) result.totalRecordCount else start + result.items.size)
        }

    /** Number of items matching [query]; kept longer than the lists, it changes rarely. */
    suspend fun count(query: ItemQuery): Int = cache.get("count:$query", Kind.Stable) {
        session.call {
            libraryApi.getItems(
                userId = userId,
                parentId = query.parentId,
                recursive = true,
                includeItemTypes = query.kinds.mapNotNull { it.toBaseKind() },
                filters = buildList {
                    if (query.unplayedOnly) add(ItemFilter.IS_UNPLAYED)
                    if (query.favoritesOnly) add(ItemFilter.IS_FAVORITE)
                },
                limit = 0,
                enableTotalRecordCount = true,
            ).content.totalRecordCount
        }
    }

    suspend fun details(id: UUID): ItemDetails = cache.get("details:$id", Kind.UserData) {
        session.call { libraryApi.getItem(itemId = id, userId = userId).content }.toDetails()
    }

    suspend fun seasons(seriesId: UUID): List<ItemSummary> = cache.get("seasons:$seriesId", Kind.UserData) {
        session.call { showApi.getSeasons(seriesId = seriesId, userId = userId, fields = CARD_FIELDS).content.items }.map { it.toSummary() }
    }

    /** Episodes of one season, or of the whole series when [seasonId] is null. */
    suspend fun episodes(seriesId: UUID, seasonId: UUID? = null): List<ItemSummary> = cache.get("episodes:$seriesId:$seasonId", Kind.UserData) {
        session.call {
            showApi.getEpisodes(seriesId = seriesId, userId = userId, seasonId = seasonId, fields = FEATURED_FIELDS).content.items
        }.map { it.toSummary() }
    }

    suspend fun similar(id: UUID, limit: Int = 12): List<ItemSummary> = cache.get("similar:$id:$limit", Kind.UserData) {
        session.call { libraryApi.getSimilarItems(itemId = id, userId = userId, limit = limit, fields = CARD_FIELDS).content.items }
            .map { it.toSummary() }
    }

    /** Movies, series and episodes matching [term], and people. Not cached: every keystroke is a new term. */
    suspend fun search(term: String, limit: Int = 60): SearchResults = coroutineScope {
        val items = async {
            session.call {
                libraryApi.getItems(
                    userId = userId,
                    searchTerm = term,
                    recursive = true,
                    includeItemTypes = listOf(ItemKind.Movie, ItemKind.Series, ItemKind.Episode).mapNotNull { it.toBaseKind() },
                    limit = limit,
                    fields = CARD_FIELDS,
                    enableTotalRecordCount = false,
                ).content.items
            }.map { it.toSummary() }
        }
        val people = async {
            session.call { personApi.getPersons(searchTerm = term, userId = userId, limit = PEOPLE_LIMIT).content.items }.map { it.toPerson() }
        }
        SearchResults(items.await(), people.await())
    }

    private fun ItemSort.toSortBy(): List<ItemSortBy> = when (this) {
        ItemSort.Name -> listOf(ItemSortBy.SORT_NAME)
        ItemSort.DateAdded -> listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME)
        ItemSort.Year -> listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME)
        ItemSort.Rating -> listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME)
        ItemSort.DatePlayed -> listOf(ItemSortBy.DATE_PLAYED)
        ItemSort.PremiereDate -> listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
        ItemSort.Random -> listOf(ItemSortBy.RANDOM)
    }

    private companion object {
        const val PEOPLE_LIMIT = 20
    }
}

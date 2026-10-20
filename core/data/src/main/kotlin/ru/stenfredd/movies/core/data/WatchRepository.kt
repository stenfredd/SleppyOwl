package ru.stenfredd.movies.core.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.MediaType
import ru.stenfredd.movies.core.data.SessionCache.Kind
import ru.stenfredd.movies.core.data.model.ItemKind
import ru.stenfredd.movies.core.data.model.ItemQuery
import ru.stenfredd.movies.core.data.model.ItemSort
import ru.stenfredd.movies.core.data.model.ItemSummary
import ru.stenfredd.movies.core.data.model.LibraryView
import ru.stenfredd.movies.core.data.model.Page
import ru.stenfredd.movies.core.data.model.itemKinds
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the user watches: continue watching, next up, history, favourites; marking watched and favourite. */
@Singleton
class WatchRepository @Inject constructor(
    private val session: JellyfinSession,
    private val cache: SessionCache,
    private val library: LibraryRepository,
) {
    private val userId get() = session.requireSession().userId

    /** Started movies and episodes, most recently watched first; of one library when [parentId] is given. */
    suspend fun resume(limit: Int = 20, parentId: UUID? = null): List<ItemSummary> = cache.get("resume:$limit:$parentId", Kind.UserData) {
        session.call {
            libraryApi.getResumeItems(userId = userId, parentId = parentId, limit = limit, mediaTypes = listOf(MediaType.VIDEO), fields = FEATURED_FIELDS).content.items
        }.map { it.toSummary() }
    }

    /** The next unwatched episode of each series in progress; of one library when [parentId] is given. */
    suspend fun nextUp(limit: Int = 20, parentId: UUID? = null): List<ItemSummary> = cache.get("nextup:$limit:$parentId", Kind.UserData) {
        session.call {
            showApi.getNextUp(userId = userId, parentId = parentId, limit = limit, fields = FEATURED_FIELDS, enableResumable = false, enableRewatching = false).content.items
        }.map { it.toSummary() }
    }

    /**
     * "Continue watching": started items first (most recent first), then the next episode of each series in progress
     * that isn't already there. Of one library when [parentId] is given.
     */
    suspend fun continueWatching(limit: Int = 20, parentId: UUID? = null): List<ItemSummary> = coroutineScope {
        val resume = async { resume(limit, parentId) }
        val nextUp = async { nextUp(limit, parentId) }
        val started = resume.await()
        val seriesStarted = started.mapNotNullTo(HashSet()) { it.seriesId }
        (started + nextUp.await().filter { it.seriesId !in seriesStarted && started.none { s -> s.id == it.id } }).take(limit)
    }

    /** Watched movies and episodes, most recent first; of one library when [parentId] is given. */
    suspend fun history(start: Int, limit: Int, parentId: UUID? = null): Page<ItemSummary> = library.items(
        ItemQuery(parentId = parentId, kinds = setOf(ItemKind.Movie, ItemKind.Episode), sort = ItemSort.DatePlayed, descending = true, playedOnly = true),
        start,
        limit,
        withTotal = false,
    )

    /** Favourite movies or series of library [view], by name. All of them: favourites are a short list. */
    suspend fun favorites(view: LibraryView): List<ItemSummary> = library.items(
        ItemQuery(parentId = view.id, kinds = view.itemKinds, favoritesOnly = true, sort = ItemSort.Name),
        0,
        FAVORITES_LIMIT,
        withTotal = false,
    ).items

    suspend fun setPlayed(id: UUID, played: Boolean) {
        session.call { if (played) userDataApi.markPlayedItem(itemId = id, userId = userId) else userDataApi.markUnplayedItem(itemId = id, userId = userId) }
        session.notifyUserDataChanged(id)
    }

    suspend fun setFavorite(id: UUID, favorite: Boolean) {
        session.call { if (favorite) userDataApi.markFavoriteItem(itemId = id, userId = userId) else userDataApi.unmarkFavoriteItem(itemId = id, userId = userId) }
        session.notifyUserDataChanged(id)
    }

    private companion object {
        const val FAVORITES_LIMIT = 500
    }
}

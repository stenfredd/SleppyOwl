package ru.stenfredd.movies.ui

import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import java.util.UUID
import javax.inject.Inject

class Library @Inject constructor(private val session: JellyfinSession) {
    private val api get() = session.api
    private val userId get() = session.requireSession().userId

    suspend fun views(): List<BaseItemDto> = session.call { userViewApi.getUserViews(userId = userId).content.items }

    suspend fun resume(): List<BaseItemDto> = session.call { libraryApi.getResumeItems(userId = userId, limit = 20).content.items }

    suspend fun children(parentId: UUID): List<BaseItemDto> = session.call {
        libraryApi.getItems(
        userId = userId,
        parentId = parentId,
        sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
        limit = 1000,
        ).content.items
    }

    suspend fun search(term: String): List<BaseItemDto> = session.call {
        libraryApi.getItems(
        userId = userId,
        searchTerm = term,
        recursive = true,
        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
        limit = 60,
        ).content.items
    }

    suspend fun latest(parentId: UUID? = null, limit: Int = 20): List<BaseItemDto> = session.call {
        libraryApi.getLatestMedia(userId = userId, parentId = parentId, limit = limit, groupItems = true).content
    }

    /** Newest movies and series of a library, with the total count. */
    suspend fun libraryItems(parentId: UUID, limit: Int = 20): Pair<List<BaseItemDto>, Int> = session.call {
        val r = libraryApi.getItems(
            userId = userId,
            parentId = parentId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            sortBy = listOf(ItemSortBy.DATE_CREATED),
            sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
            limit = limit,
        ).content
        r.items to r.totalRecordCount
    }

    /** Image of exactly the displayed size (px), so the TV only decodes. */
    fun imageUrl(id: UUID, type: ImageType, tag: String, widthPx: Int, heightPx: Int): String =
        api.imageApi.getItemImageUrl(id, type, fillWidth = widthPx, fillHeight = heightPx, quality = 90, tag = tag)

    suspend fun item(id: UUID): BaseItemDto = session.call { libraryApi.getItem(id, userId).content }

    fun posterUrl(item: BaseItemDto): String? {
        val tag = item.imageTags?.get(ImageType.PRIMARY) ?: return null
        return api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY, maxHeight = 360, tag = tag)
    }
}

val BaseItemDto.isPlayable get() = type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)

val BaseItemDto.label: String
    get() = when (type) {
        BaseItemKind.EPISODE -> listOfNotNull(
            seriesName,
            if (parentIndexNumber != null && indexNumber != null) "S%02dE%02d".format(parentIndexNumber, indexNumber) else null,
            name,
        ).joinToString(" · ")
        else -> listOfNotNull(name, productionYear?.toString()).joinToString(" · ")
    }

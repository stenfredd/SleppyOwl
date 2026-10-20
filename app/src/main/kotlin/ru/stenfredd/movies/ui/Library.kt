package ru.stenfredd.movies.ui

import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import java.util.UUID
import javax.inject.Inject

/** Plain item lists of the old prototype, still used for collections and folders ([ItemsScreen]). */
class Library @Inject constructor(private val session: JellyfinSession) {
    private val api get() = session.api
    private val userId get() = session.requireSession().userId

    suspend fun children(parentId: UUID): List<BaseItemDto> = session.call {
        libraryApi.getItems(
        userId = userId,
        parentId = parentId,
        sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
        limit = 1000,
        ).content.items
    }

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

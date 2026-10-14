package ru.stenfredd.movies.ui.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.stenfredd.movies.core.data.ImageUrls
import ru.stenfredd.movies.core.data.model.ImageRef
import ru.stenfredd.movies.core.data.model.ItemKind
import ru.stenfredd.movies.core.data.model.ItemSummary
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.FeaturedModel
import ru.stenfredd.movies.ui.components.PosterModel
import ru.stenfredd.movies.ui.components.RemoteImageSpec
import ru.stenfredd.movies.ui.components.WideModel
import ru.stenfredd.movies.ui.theme.AppDimens
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Turns server items into card models. Image URLs ask the server for exactly the card's size in pixels.
 * Card keys are "<row>/<item id>": the same item can appear in several rows, and focus is remembered per key.
 */
class CardMapper @Inject constructor(
    private val images: ImageUrls,
    @ApplicationContext context: Context,
) {
    private val density = context.resources.displayMetrics.density

    fun px(dp: Float) = (dp * density).roundToInt()

    fun key(row: String, item: ItemSummary) = "$row/${item.id}"

    fun poster(item: ItemSummary, row: String): PosterModel {
        // A single new episode comes as the episode itself: show it as its series, poster included.
        val episode = item.kind == ItemKind.Episode
        return PosterModel(
            key = key(row, item),
            title = (if (episode) item.seriesName else null) ?: item.name,
            caption = if (episode) {
                listOfNotNull(item.seasonNumber?.let { "S$it" }, item.episodeNumber?.let { "E$it" }).joinToString(" · ").ifEmpty { null }
            } else {
                listOfNotNull(item.year?.toString(), item.communityRating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null }
            },
            image = image(if (episode) item.seriesPrimary ?: item.primary else item.primary, AppDimens.POSTER_W.toFloat(), AppDimens.POSTER_H.toFloat()),
            badge = item.formatBadge(),
            progress = item.user.progress,
            isNew = item.isNew(),
            watched = item.user.played,
        )
    }

    fun wide(item: ItemSummary, row: String): WideModel = WideModel(
        key = key(row, item),
        title = item.seriesName ?: item.name,
        subtitle = listOfNotNull(item.episodeLabel() ?: item.year?.toString(), item.remaining()).joinToString(" · "),
        image = image(item.landscape(), AppDimens.WIDE_W.toFloat(), AppDimens.WIDE_H.toFloat()),
        progress = item.user.progress,
    )

    fun featured(item: ItemSummary, row: String): FeaturedModel {
        val isEpisode = item.kind == ItemKind.Episode
        return FeaturedModel(
            key = key(row, item),
            label = if (isEpisode) "СЕРИАЛ" else "ФИЛЬМ",
            title = item.seriesName ?: item.name,
            subtitle = if (isEpisode) {
                item.episodeLabel().orEmpty()
            } else {
                listOfNotNull(item.year?.toString(), item.genres.take(2).joinToString(", ").ifEmpty { null }).joinToString(" · ")
            },
            description = item.overview,
            image = image(item.backdrop ?: item.landscape(), FEATURED_W, FEATURED_H),
            progress = item.user.progress ?: 0f,
            remaining = item.remaining(),
        )
    }

    /** Where a card leads. Series and folders still open the old prototype list until their screens exist. */
    fun target(item: ItemSummary): Screen = when (item.kind) {
        ItemKind.Series, ItemKind.Season, ItemKind.BoxSet, ItemKind.Folder -> Screen.Items(item.id, item.name)
        else -> Screen.Detail(item.id)
    }

    private fun image(ref: ImageRef?, wDp: Float, hDp: Float): RemoteImageSpec? =
        ref?.let { RemoteImageSpec(images.url(it, px(wDp), px(hDp)), it.blurHash) }

    /** Episode still, else the backdrop or thumb of the item or its series. */
    private fun ItemSummary.landscape(): ImageRef? = (if (kind == ItemKind.Episode) primary else null) ?: backdrop ?: thumb

    private fun ItemSummary.remaining(): String? {
        val runtime = runtimeMs ?: return null
        val left = runtime * (1 - (user.progress ?: 0f)) / 60_000
        return "осталось ${left.roundToInt()} мин"
    }

    private fun ItemSummary.episodeLabel(): String? =
        if (kind == ItemKind.Episode && seasonNumber != null && episodeNumber != null) "S$seasonNumber · E$episodeNumber · $name" else null

    private fun ItemSummary.isNew(): Boolean = dateAddedAt?.let { System.currentTimeMillis() - it < NEW_MS } == true

    private fun ItemSummary.formatBadge(): String? {
        val w = videoWidth ?: return null
        return when {
            w >= 3200 -> "4K"
            w >= 1800 -> "1080p"
            w >= 1200 -> "720p"
            else -> null
        }
    }

    private companion object {
        /** "Новинка": added within the last 14 days. */
        const val NEW_MS = 14 * 24 * 3600_000L
        const val FEATURED_W = 410f
        const val FEATURED_H = 230.5f
    }
}

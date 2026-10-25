package ru.stenfredd.sleepyowl.ui.model

import ru.stenfredd.sleepyowl.core.data.ImageUrls
import ru.stenfredd.sleepyowl.core.data.model.ImageRef
import ru.stenfredd.sleepyowl.core.data.model.ItemKind
import ru.stenfredd.sleepyowl.core.data.model.ItemSummary
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.UiPlatform
import ru.stenfredd.sleepyowl.ui.components.FeaturedModel
import ru.stenfredd.sleepyowl.ui.components.PosterModel
import ru.stenfredd.sleepyowl.ui.components.RemoteImageSpec
import ru.stenfredd.sleepyowl.ui.components.WideModel
import ru.stenfredd.sleepyowl.ui.menu.WatchTarget
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import kotlin.math.roundToInt

/**
 * Turns server items into card models. Image URLs ask the server for exactly the card's size in pixels.
 * Card keys are "<row>/<item id>": the same item can appear in several rows, and focus is remembered per key.
 */
class CardMapper(
    private val images: ImageUrls,
    platform: UiPlatform,
) {
    private val density = platform.density

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
            isNew = isNew(item),
            watched = item.user.played,
            watch = watchTarget(item),
        )
    }

    /** Poster captioned "Фильм · 2021" / "Сериал · 2020": lists that mix movies and series. */
    fun kindPoster(item: ItemSummary, row: String): PosterModel = poster(item, row).copy(
        caption = listOfNotNull(
            when (item.kind) {
                ItemKind.Movie -> "Фильм"
                ItemKind.Series -> "Сериал"
                else -> null
            },
            item.year?.toString(),
        ).joinToString(" · ").ifEmpty { null },
    )

    fun wide(item: ItemSummary, row: String): WideModel = WideModel(
        key = key(row, item),
        title = item.seriesName ?: item.name,
        subtitle = listOfNotNull(item.episodeLabel() ?: item.year?.toString(), item.remaining()).joinToString(" · "),
        image = image(item.landscape(), AppDimens.WIDE_W.toFloat(), AppDimens.WIDE_H.toFloat()),
        progress = item.user.progress,
        watch = watchTarget(item),
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
            watch = watchTarget(item),
        )
    }

    /**
     * Where a card leads: a movie to its page, a series, season or episode to the series page (on that episode).
     * Collections and folders still open the old prototype list until the library screen exists.
     */
    fun target(item: ItemSummary): Screen = when (item.kind) {
        ItemKind.Series -> Screen.Series(item.id)
        ItemKind.Season -> item.seriesId?.let { Screen.Series(it) } ?: Screen.Items(item.id, item.name)
        ItemKind.Episode -> item.seriesId?.let { Screen.Series(it, item.id) } ?: Screen.Detail(item.id)
        ItemKind.BoxSet, ItemKind.Folder -> Screen.Items(item.id, item.name)
        else -> Screen.Detail(item.id)
    }

    /** What the card's long press marks watched: the item itself (a single new episode too, though it shows its series). */
    fun watchTarget(item: ItemSummary): WatchTarget? {
        val part = item.kind == ItemKind.Episode || item.kind == ItemKind.Season
        return when (item.kind) {
            ItemKind.Movie, ItemKind.Video, ItemKind.Episode, ItemKind.Season, ItemKind.Series -> WatchTarget(
                id = item.id,
                kind = item.kind,
                title = (if (part) item.seriesName else null) ?: item.name,
                subtitle = when (item.kind) {
                    ItemKind.Episode -> listOfNotNull(
                        item.seasonNumber?.let { "S$it" },
                        item.episodeNumber?.let { "E$it" },
                        item.name.takeIf { item.seriesName != null },
                    ).joinToString(" · ").ifEmpty { null }
                    ItemKind.Season -> item.name.takeIf { item.seriesName != null }
                    else -> item.year?.toString()
                },
                played = item.user.played,
                progress = item.user.progress,
                unplayed = item.user.unplayedCount.takeIf { item.kind == ItemKind.Series || item.kind == ItemKind.Season },
            )
            else -> null
        }
    }

    fun image(ref: ImageRef?, wDp: Float, hDp: Float): RemoteImageSpec? =
        ref?.let { RemoteImageSpec(images.url(it, px(wDp), px(hDp)), it.blurHash) }

    private fun ItemSummary.episodeLabel(): String? =
        if (kind == ItemKind.Episode && seasonNumber != null && episodeNumber != null) "S$seasonNumber · E$episodeNumber · $name" else null

    /** "Новинка": added within the last 14 days. */
    fun isNew(item: ItemSummary): Boolean = item.dateAddedAt?.let { System.currentTimeMillis() - it < NEW_MS } == true

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

/** Episode still, else the backdrop or thumb of the item or its series. Also the TV home screen's cards (`LauncherFeed`). */
fun ItemSummary.landscape(): ImageRef? = (if (kind == ItemKind.Episode) primary else null) ?: backdrop ?: thumb

/** "осталось 31 мин" from the item's progress. */
fun ItemSummary.remaining(): String? {
    val runtime = runtimeMs ?: return null
    val left = runtime * (1 - (user.progress ?: 0f)) / 60_000
    return "осталось ${left.roundToInt()} мин"
}

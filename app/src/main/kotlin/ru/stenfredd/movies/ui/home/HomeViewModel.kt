package ru.stenfredd.movies.ui.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import ru.stenfredd.movies.ui.Library
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.FeaturedModel
import ru.stenfredd.movies.ui.components.PosterModel
import ru.stenfredd.movies.ui.components.WideModel
import ru.stenfredd.movies.ui.theme.AppDimens
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.roundToInt

@Immutable
data class HomeRow(val key: String, val title: String, val count: Int, val items: List<PosterModel>)

@Immutable
data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val featured: FeaturedModel? = null,
    val continueItems: List<WideModel> = emptyList(),
    val continueCount: Int = 0,
    val rows: List<HomeRow> = emptyList(),
)

/**
 * Home screen data. Stage 1 version straight on top of [Library]; stage 2 moves loading into repositories.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: Library,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val density = context.resources.displayMetrics.density
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** Card key → where it leads. */
    private val targets = HashMap<String, Screen>()

    init { load() }

    fun target(key: String): Screen? = targets[key]

    private fun load() = viewModelScope.launch {
        val started = System.nanoTime()
        runCatching {
            coroutineScope {
                val resume = async { library.resume() }
                val latest = async { library.latest() }
                val views = library.views()
                val perView = views.map { v -> async { v to library.libraryItems(v.id) } }
                val resumeItems = resume.await()
                val rows = buildList {
                    val l = latest.await()
                    if (l.isNotEmpty()) add(HomeRow("latest", "Недавно добавлено", l.size, l.map { poster(it, "latest") }))
                    perView.awaitAll().forEach { (v, r) ->
                        val (items, total) = r
                        if (items.isNotEmpty()) add(HomeRow("lib-${v.id}", v.name.orEmpty(), total, items.map { poster(it, "lib-${v.id}") }))
                    }
                }
                HomeState(
                    loading = false,
                    featured = resumeItems.firstOrNull()?.let(::featured),
                    continueItems = resumeItems.drop(1).take(4).map(::wide),
                    continueCount = resumeItems.size,
                    rows = rows,
                )
            }
        }.onSuccess {
            _state.value = it
            Timber.i("Home loaded in %d ms", (System.nanoTime() - started) / 1_000_000)
        }.onFailure {
            Timber.w(it, "Home load failed")
            _state.value = HomeState(loading = false, error = it.message ?: it.toString())
        }
    }

    private fun px(dp: Float) = (dp * density).roundToInt()

    private fun poster(item: BaseItemDto, row: String): PosterModel {
        val key = "$row/${item.id}"
        targets[key] = item.target()
        // A single new episode comes as the episode itself: show it as its series, poster included.
        val episode = item.type == BaseItemKind.EPISODE
        val imageId = if (episode) item.seriesId else item.id
        val tag = if (episode) item.seriesPrimaryImageTag else item.imageTags?.get(ImageType.PRIMARY)
        return PosterModel(
            key = key,
            title = (if (episode) item.seriesName else null) ?: item.name.orEmpty(),
            caption = if (episode) {
                listOfNotNull(item.parentIndexNumber?.let { "S$it" }, item.indexNumber?.let { "E$it" }).joinToString(" · ").ifEmpty { null }
            } else {
                listOfNotNull(item.productionYear?.toString(), item.communityRating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null }
            },
            imageUrl = if (imageId != null && tag != null) library.imageUrl(imageId, ImageType.PRIMARY, tag, px(AppDimens.POSTER_W.toFloat()), px(AppDimens.POSTER_H.toFloat())) else null,
            badge = item.formatBadge(),
            progress = item.userData?.playedPercentage?.takeIf { it > 0 && item.type != BaseItemKind.SERIES }?.let { (it / 100).toFloat() },
            isNew = item.dateCreated?.isAfter(LocalDateTime.now().minusDays(NEW_DAYS)) == true,
            watched = item.userData?.played == true,
        )
    }

    private fun wide(item: BaseItemDto): WideModel {
        val key = "continue/${item.id}"
        targets[key] = Screen.Detail(item.id)
        return WideModel(
            key = key,
            title = item.seriesName ?: item.name.orEmpty(),
            subtitle = listOfNotNull(item.episodeLabel() ?: item.productionYear?.toString(), item.remaining()).joinToString(" · "),
            imageUrl = item.landscapeImage(px(AppDimens.WIDE_W.toFloat()), px(AppDimens.WIDE_H.toFloat())),
            progress = item.progress(),
        )
    }

    private fun featured(item: BaseItemDto): FeaturedModel {
        val key = "continue/${item.id}"
        targets[key] = Screen.Detail(item.id)
        val isEpisode = item.type == BaseItemKind.EPISODE
        return FeaturedModel(
            key = key,
            label = if (isEpisode) "СЕРИАЛ" else "ФИЛЬМ",
            title = item.seriesName ?: item.name.orEmpty(),
            subtitle = if (isEpisode) {
                item.episodeLabel().orEmpty()
            } else {
                listOfNotNull(item.productionYear?.toString(), item.genres?.take(2)?.joinToString(", ")?.ifEmpty { null }).joinToString(" · ")
            },
            description = item.overview,
            imageUrl = item.backdrop(px(410f), px(230.5f)) ?: item.landscapeImage(px(410f), px(230.5f)),
            progress = item.progress() ?: 0f,
            remaining = item.remaining(),
        )
    }

    private fun BaseItemDto.progress() = userData?.playedPercentage?.let { (it / 100).toFloat() }

    private fun BaseItemDto.remaining(): String? {
        val ticks = runTimeTicks ?: return null
        val left = ticks * (1 - (progress() ?: 0f)) / 600_000_000
        return "осталось ${left.roundToInt()} мин"
    }

    private fun BaseItemDto.episodeLabel(): String? =
        if (type == BaseItemKind.EPISODE && parentIndexNumber != null && indexNumber != null) "S$parentIndexNumber · E$indexNumber · $name" else null

    private fun BaseItemDto.backdrop(w: Int, h: Int): String? {
        backdropImageTags?.firstOrNull()?.let { return library.imageUrl(id, ImageType.BACKDROP, it, w, h) }
        val parent = parentBackdropItemId ?: return null
        return parentBackdropImageTags?.firstOrNull()?.let { library.imageUrl(parent, ImageType.BACKDROP, it, w, h) }
    }

    /** Episode still, else the item's or series' backdrop. */
    private fun BaseItemDto.landscapeImage(w: Int, h: Int): String? {
        if (type == BaseItemKind.EPISODE) imageTags?.get(ImageType.PRIMARY)?.let { return library.imageUrl(id, ImageType.PRIMARY, it, w, h) }
        return backdrop(w, h)
    }

    private fun BaseItemDto.formatBadge(): String? {
        val w = width ?: return null
        return when {
            w >= 3200 -> "4K"
            w >= 1800 -> "1080p"
            w >= 1200 -> "720p"
            else -> null
        }
    }

    private fun BaseItemDto.target(): Screen = when (type) {
        BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.BOX_SET, BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER -> Screen.Items(id, name.orEmpty())
        else -> Screen.Detail(id)
    }

    private companion object {
        const val NEW_DAYS = 14L
    }
}

package ru.stenfredd.movies.ui.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.movies.core.data.ImageUrls
import ru.stenfredd.movies.core.data.LibraryRepository
import ru.stenfredd.movies.core.data.SessionCache
import ru.stenfredd.movies.core.data.WatchRepository
import ru.stenfredd.movies.core.data.model.ItemDetails
import ru.stenfredd.movies.core.data.model.ItemKind
import ru.stenfredd.movies.core.data.model.MediaInfo
import ru.stenfredd.movies.core.data.model.PersonKind
import ru.stenfredd.movies.playback.api.StreamSelection
import ru.stenfredd.movies.playback.core.PlaybackPreferences
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.PosterModel
import ru.stenfredd.movies.ui.components.RemoteImageSpec
import ru.stenfredd.movies.ui.model.CardMapper
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** One audio or subtitle choice; [index] is the server's stream index, null = subtitles off. */
@Immutable
data class TrackOption(val key: String, val index: Int?, val label: String, val detail: String?)

@Immutable
data class CastModel(val key: String, val name: String, val role: String?, val letters: String, val image: RemoteImageSpec?)

/** Everything the movie (or episode) page shows, as display strings. */
@Immutable
data class DetailsModel(
    val id: UUID,
    /** "ФИЛЬМ", "СЕРИЯ · S1 · E3". */
    val label: String,
    val title: String,
    /** Episode title under the series title. */
    val subtitle: String?,
    val rating: String?,
    val age: String?,
    val year: String?,
    val runtime: String?,
    val genres: String?,
    val description: String?,
    val poster: RemoteImageSpec?,
    val backdropUrl: String?,
    val badges: List<String>,
    val fileLine: String?,
    val resumeMs: Long,
    val remainingMinutes: Int?,
    val favorite: Boolean,
    val played: Boolean,
    val audio: List<TrackOption>,
    val subtitles: List<TrackOption>,
    val castTitle: String,
    val cast: List<CastModel>,
)

@Immutable
data class DetailsState(
    val loading: Boolean = true,
    val error: String? = null,
    val item: DetailsModel? = null,
    /** Chosen audio stream index. */
    val audio: Int? = null,
    /** Chosen subtitle stream index, null = off. */
    val subtitle: Int? = null,
    val similar: List<PosterModel> = emptyList(),
)

/** Movie or episode page: details, the track choice passed on to the player, favourite and watched. */
@HiltViewModel(assistedFactory = DetailsViewModel.Factory::class)
class DetailsViewModel @AssistedInject constructor(
    @Assisted private val itemId: UUID,
    private val library: LibraryRepository,
    private val watch: WatchRepository,
    private val images: ImageUrls,
    private val cards: CardMapper,
    private val prefs: PlaybackPreferences,
    cache: SessionCache,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(itemId: UUID): DetailsViewModel
    }

    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state.asStateFlow()

    /** The user picked tracks on this page: keep the choice over reloads. */
    private var tracksChosen = false
    private var similarTargets: Map<String, Screen> = emptyMap()
    /** Tracks are remembered per series (an episode) or per movie, see [PlaybackPreferences]. */
    private var memoryKey: UUID = itemId
    private var media: MediaInfo? = null

    init {
        viewModelScope.launch { load() }
        // Back from the player (progress) or watched/favourite changed elsewhere: reload.
        viewModelScope.launch { cache.invalidations.collect { load() } }
    }

    fun similarTarget(key: String): Screen? = similarTargets[key]

    fun selectAudio(index: Int?) {
        tracksChosen = true
        _state.update { it.copy(audio = index) }
        viewModelScope.launch { TrackChoices.rememberAudio(prefs, memoryKey, media, index) }
    }

    fun selectSubtitle(index: Int?) {
        tracksChosen = true
        _state.update { it.copy(subtitle = index) }
        viewModelScope.launch { TrackChoices.rememberSubtitle(prefs, memoryKey, media, index) }
    }

    /** The player screen for this item: from the resume point unless [fromStart], with the chosen tracks. */
    fun player(fromStart: Boolean): Screen.Player? {
        val s = _state.value
        val item = s.item ?: return null
        return Screen.Player(
            itemId = item.id,
            engine = null,
            startPositionMs = if (fromStart) 0 else item.resumeMs,
            streams = StreamSelection(s.audio, s.subtitle),
        )
    }

    fun toggleFavorite() = toggle({ it.favorite }, { m, v -> m.copy(favorite = v) }) { watch.setFavorite(itemId, it) }

    fun togglePlayed() = toggle({ it.played }, { m, v -> m.copy(played = v) }) { watch.setPlayed(itemId, it) }

    /** Shows the new value at once and reverts it if the server refuses. */
    private fun toggle(get: (DetailsModel) -> Boolean, set: (DetailsModel, Boolean) -> DetailsModel, send: suspend (Boolean) -> Unit) {
        val item = _state.value.item ?: return
        val value = !get(item)
        _state.update { s -> s.copy(item = s.item?.let { set(it, value) }) }
        viewModelScope.launch {
            try {
                send(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Details: update failed")
                _state.update { s -> s.copy(item = s.item?.let { set(it, !value) }) }
            }
        }
    }

    private suspend fun load() {
        try {
            coroutineScope {
                val similar = async { runCatching { library.similar(itemId) }.getOrDefault(emptyList()) }
                val details = library.details(itemId)
                val model = withContext(Dispatchers.Default) { details.toModel() }
                val media = details.media
                this@DetailsViewModel.media = media
                memoryKey = details.summary.seriesId ?: itemId
                val initial = media?.let { TrackChoices.initial(prefs, memoryKey, it) }
                _state.update { s ->
                    val keep = tracksChosen && s.item != null
                    s.copy(
                        loading = false,
                        error = null,
                        item = model,
                        audio = if (keep) s.audio else initial?.first,
                        subtitle = if (keep) s.subtitle else initial?.second,
                    )
                }
                // Similar items only make sense for movies; an episode would get random other series.
                val related = if (details.summary.kind == ItemKind.Episode) emptyList() else similar.await()
                val (posters, targets) = withContext(Dispatchers.Default) {
                    related.map { cards.poster(it, SIMILAR) } to related.associate { cards.key(SIMILAR, it) to cards.target(it) }
                }
                similarTargets = targets
                _state.update { it.copy(similar = posters) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Details: load failed")
            // Keep what is shown when a reload fails.
            _state.update { if (it.item == null) it.copy(loading = false, error = e.message ?: e.toString()) else it }
        }
    }

    private fun ItemDetails.toModel(): DetailsModel {
        val item = summary
        val episode = item.kind == ItemKind.Episode
        val media = media
        val directors = people.filter { it.kind == PersonKind.Director }.map { it.name }
        val actors = people.filter { it.kind == PersonKind.Actor }.take(CAST_LIMIT)
        val progress = item.user.progress
        return DetailsModel(
            id = item.id,
            label = if (episode) {
                listOfNotNull("СЕРИЯ", item.seasonNumber?.let { "S$it" }, item.episodeNumber?.let { "E$it" }).joinToString(" · ")
            } else {
                "ФИЛЬМ"
            },
            title = if (episode) item.seriesName ?: item.name else item.name,
            subtitle = if (episode) item.name else null,
            rating = item.communityRating?.let { String.format(Locale.US, "★ %.1f", it) },
            // "RU-16+", "age6+" → "16+", "6+": the rating system's prefix says nothing on screen. "PG-13" stays.
            age = item.officialRating?.let { r -> Regex("""(\d{1,2}\+)$""").find(r)?.value ?: r },
            year = item.year?.toString(),
            runtime = MediaLabels.runtime(item.runtimeMs),
            genres = item.genres.take(3).joinToString(", ").ifEmpty { null },
            description = item.overview,
            poster = (if (episode) item.seriesPrimary ?: item.primary else item.primary)?.let {
                RemoteImageSpec(images.url(it, cards.px(DetailsLayout.POSTER_W), cards.px(DetailsLayout.POSTER_H)), it.blurHash)
            },
            backdropUrl = item.backdrop?.let { images.url(it, cards.px(DetailsLayout.BACKDROP_W), cards.px(DetailsLayout.BACKDROP_H), quality = 80) },
            badges = MediaLabels.badges(media),
            fileLine = MediaLabels.fileLine(media),
            resumeMs = item.user.positionMs.takeIf { progress != null } ?: 0,
            remainingMinutes = if (progress != null && item.runtimeMs != null) (item.runtimeMs!! * (1 - progress) / 60_000).roundToInt() else null,
            favorite = item.user.favorite,
            played = item.user.played,
            audio = TrackChoices.audio(media),
            subtitles = TrackChoices.subtitles(media),
            castTitle = listOfNotNull("В ролях", directors.takeIf { it.isNotEmpty() }?.let { "режиссёр " + it.joinToString(", ") }).joinToString(" · "),
            cast = actors.map { p ->
                CastModel(
                    key = "cast/${p.id}",
                    name = p.name,
                    role = p.role,
                    letters = p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                    image = p.image?.let { RemoteImageSpec(images.url(it, cards.px(DetailsLayout.AVATAR), cards.px(DetailsLayout.AVATAR)), it.blurHash) },
                )
            },
        )
    }

    private companion object {
        const val SIMILAR = "similar"
        const val CAST_LIMIT = 20
    }
}

/** Sizes (dp) of the page shared by the layout, the image requests and the baked backdrop. */
internal object DetailsLayout {
    const val PAGE_W = 960f
    const val PAGE_H = 540f
    const val POSTER_X = 60f
    const val POSTER_Y = 48f
    const val POSTER_W = 220f
    const val POSTER_H = 330f
    const val POSTER_CORNER = 11f
    /** The backdrop covers 70 % of the page width and height at the top right (see [BackdropTransformation]). */
    const val BACKDROP_W = 672f
    const val BACKDROP_H = 378f
    const val AVATAR = 39f
}

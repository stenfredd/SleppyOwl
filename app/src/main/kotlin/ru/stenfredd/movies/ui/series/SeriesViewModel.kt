package ru.stenfredd.movies.ui.series

import android.os.SystemClock
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.movies.core.data.ImageUrls
import ru.stenfredd.movies.core.data.LibraryRepository
import ru.stenfredd.movies.core.data.SessionCache
import ru.stenfredd.movies.core.data.WatchRepository
import ru.stenfredd.movies.core.data.model.ItemDetails
import ru.stenfredd.movies.core.data.model.ItemSummary
import ru.stenfredd.movies.core.data.model.MediaInfo
import ru.stenfredd.movies.playback.api.SubtitleStyle
import ru.stenfredd.movies.playback.core.PlaybackPreferences
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.RemoteImageSpec
import ru.stenfredd.movies.ui.details.MediaLabels
import ru.stenfredd.movies.ui.details.TrackChoices
import ru.stenfredd.movies.ui.details.TrackOption
import ru.stenfredd.movies.ui.model.CardMapper
import ru.stenfredd.movies.ui.player.PlayerLabels
import ru.stenfredd.movies.ui.model.plural
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Where an episode is in watching: the season map colours and the card's corner badge. */
enum class EpisodeState { Watched, InProgress, Next, New, None }

@Immutable
data class SeriesEpisode(
    val key: String,
    val id: UUID,
    /** Index of its season in [SeriesModel.seasons]. */
    val season: Int,
    /** Position in the episode row, which has a divider before every season. */
    val rowIndex: Int,
    /** "S2·E04" */
    val code: String,
    /** "СЕЗОН 2 · СЕРИЯ 4" */
    val longCode: String,
    val title: String,
    /** "56 мин · 13.06.2023" */
    val meta: String?,
    val duration: String?,
    val airDate: String?,
    val overview: String?,
    val image: RemoteImageSpec?,
    val progress: Float?,
    val state: EpisodeState,
    val resumeMs: Long,
)

@Immutable
data class SeriesSeason(
    val key: String,
    /** "Сезон 2", or the server's name for specials. */
    val label: String,
    /** "✓", "новый", "3/10" */
    val stat: String,
    /** "S2 · 3/10" over the season map. */
    val mapLabel: String,
    /** Its episodes are [first] until [first] + [count] in [SeriesModel.episodes]. */
    val first: Int,
    val count: Int,
    /** The episode ↓ from the season chip goes to: started, next, first unwatched, else the first. */
    val target: Int,
    /** Row index of the season's divider. */
    val dividerIndex: Int,
)

@Immutable
data class SeriesModel(
    val id: UUID,
    /** "СЕРИАЛ · 3 СЕЗОНА · 28 СЕРИЙ" */
    val label: String,
    val title: String,
    val rating: String?,
    val age: String?,
    val year: String?,
    val genres: String?,
    val poster: RemoteImageSpec?,
    val backdropUrl: String?,
    val favorite: Boolean,
    val seasons: List<SeriesSeason>,
    val episodes: List<SeriesEpisode>,
    /** Index of the episode "Продолжить"/"Смотреть" plays. */
    val nextUp: Int,
    /** "Продолжить S2 E4 · ещё 32 мин" */
    val playLabel: String,
)

@Immutable
data class SeriesState(
    val loading: Boolean = true,
    val error: String? = null,
    val item: SeriesModel? = null,
    /** Formats of the next episode's file: "4K", "HDR10", "Atmos". */
    val badges: List<String> = emptyList(),
    /** Tracks of the next episode's file, for the "Аудио и субтитры" tray. */
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: Int? = null,
    /** "Русский 5.1" */
    val audioLabel: String? = null,
    /** "Русский", "без субтитров" */
    val subtitleLabel: String? = null,
)

/**
 * Series page: header, season map and chips, every episode in one row, and the audio/subtitle choice for the series
 * (remembered per series, see [PlaybackPreferences]; the player picks it up for every episode).
 */
@HiltViewModel(assistedFactory = SeriesViewModel.Factory::class)
class SeriesViewModel @AssistedInject constructor(
    @Assisted private val seriesId: UUID,
    private val library: LibraryRepository,
    private val watch: WatchRepository,
    private val images: ImageUrls,
    private val cards: CardMapper,
    private val prefs: PlaybackPreferences,
    cache: SessionCache,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(seriesId: UUID): SeriesViewModel
    }

    private val _state = MutableStateFlow(SeriesState())
    val state: StateFlow<SeriesState> = _state.asStateFlow()

    val subtitleStyle: StateFlow<SubtitleStyle> = prefs.subtitleStyle.stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleStyle())

    /** File of the next episode: its tracks are what the tray offers. */
    private var media: MediaInfo? = null

    init {
        viewModelScope.launch { load() }
        // Back from the player (progress, next episode) or watched/favourite changed elsewhere: reload.
        viewModelScope.launch { cache.invalidations.collect { load() } }
    }

    fun player(episode: SeriesEpisode): Screen.Player = Screen.Player(
        itemId = episode.id,
        engine = null,
        // A watched episode starts over; the tracks come from the series' memory.
        startPositionMs = if (episode.state == EpisodeState.Watched) 0 else episode.resumeMs,
    )

    fun selectAudio(index: Int?) {
        _state.update { it.withTracks(media, index, it.selectedSubtitle) }
        viewModelScope.launch { TrackChoices.rememberAudio(prefs, seriesId, media, index) }
    }

    fun selectSubtitle(index: Int?) {
        _state.update { it.withTracks(media, it.selectedAudio, index) }
        viewModelScope.launch { TrackChoices.rememberSubtitle(prefs, seriesId, media, index) }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        viewModelScope.launch { prefs.setSubtitleStyle(style) }
    }

    /** Shows the new value at once and reverts it if the server refuses. */
    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val value = !item.favorite
        _state.update { s -> s.copy(item = s.item?.copy(favorite = value)) }
        viewModelScope.launch {
            try {
                watch.setFavorite(seriesId, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Series: favourite failed")
                _state.update { s -> s.copy(item = s.item?.copy(favorite = !value)) }
            }
        }
    }

    private suspend fun load() {
        try {
            coroutineScope {
                val started = SystemClock.elapsedRealtime()
                val seasons = async { library.seasons(seriesId) }
                val episodes = async { library.episodes(seriesId) }
                val details = library.details(seriesId)
                val model = withContext(Dispatchers.Default) { model(details, seasons.await(), episodes.await()) }
                _state.update { it.copy(loading = false, error = null, item = model) }
                Timber.i("Series loaded in %d ms (%d episodes)", SystemClock.elapsedRealtime() - started, model.episodes.size)
                // The tray and the badges describe the file of the episode "Продолжить" plays.
                model.episodes.getOrNull(model.nextUp)?.let { next ->
                    val media = runCatching { library.details(next.id).media }.getOrNull()
                    this@SeriesViewModel.media = media
                    val (audio, subtitle) = media?.let { TrackChoices.initial(prefs, seriesId, it) } ?: (null to null)
                    _state.update { s ->
                        s.copy(badges = MediaLabels.badges(media), audio = TrackChoices.audio(media), subtitles = TrackChoices.subtitles(media))
                            .withTracks(media, audio, subtitle)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Series: load failed")
            // Keep what is shown when a reload fails.
            _state.update { if (it.item == null) it.copy(loading = false, error = e.message ?: e.toString()) else it }
        }
    }

    private fun SeriesState.withTracks(media: MediaInfo?, audio: Int?, subtitle: Int?) = copy(
        selectedAudio = audio,
        selectedSubtitle = subtitle,
        audioLabel = media?.takeIf { it.audio.isNotEmpty() }?.let { PlayerLabels.audioChip(it, audio) },
        subtitleLabel = media?.takeIf { it.subtitles.isNotEmpty() }?.let { m ->
            if (subtitle == null) "без субтитров" else PlayerLabels.subtitleChip(m, subtitle)
        },
    )

    private fun model(details: ItemDetails, seasonItems: List<ItemSummary>, episodeItems: List<ItemSummary>): SeriesModel {
        val item = details.summary
        // Specials (season 0) go after the regular seasons; episodes of seasons the server didn't list are left out.
        val ordered = seasonItems.sortedBy { if (it.seasonNumber == 0) 1 else 0 }
        val bySeason = episodeItems.groupBy { it.seasonId }
        val groups = ordered.mapNotNull { s -> bySeason[s.id]?.takeIf { it.isNotEmpty() }?.let { s to it } }
        val all = groups.flatMap { it.second }
        val nextUp = nextUp(all)

        val episodes = ArrayList<SeriesEpisode>(all.size)
        val seasons = ArrayList<SeriesSeason>(groups.size)
        var row = 0
        groups.forEachIndexed { si, (season, list) ->
            val first = episodes.size
            val dividerIndex = row++
            list.forEachIndexed { i, e ->
                episodes += episode(e, si, row++, i, season, isNextUp = e === all.getOrNull(nextUp))
            }
            val own = episodes.subList(first, episodes.size)
            val watched = own.count { it.state == EpisodeState.Watched }
            val number = season.seasonNumber
            seasons += SeriesSeason(
                key = "season/${season.id}",
                label = if (number != null && number > 0) "Сезон $number" else season.name,
                stat = when {
                    watched == own.size -> "✓"
                    watched == 0 && own.all { it.state == EpisodeState.New } -> "новый"
                    else -> "$watched/${own.size}"
                },
                mapLabel = "${if (number != null && number > 0) "S$number" else "SP"} · $watched/${own.size}",
                first = first,
                count = own.size,
                target = first + (
                    own.indexOfFirst { it.state == EpisodeState.InProgress || it.state == EpisodeState.Next }.takeIf { it >= 0 }
                        ?: own.indexOfFirst { it.state != EpisodeState.Watched }.takeIf { it >= 0 }
                        ?: 0
                    ),
                dividerIndex = dividerIndex,
            )
        }

        val next = episodes.getOrNull(nextUp)
        val regularSeasons = groups.count { (it.first.seasonNumber ?: 1) > 0 }
        return SeriesModel(
            id = item.id,
            label = listOf(
                "СЕРИАЛ",
                "$regularSeasons ${plural(regularSeasons, "СЕЗОН", "СЕЗОНА", "СЕЗОНОВ")}",
                "${all.size} ${plural(all.size, "СЕРИЯ", "СЕРИИ", "СЕРИЙ")}",
            ).joinToString(" · "),
            title = item.name,
            rating = item.communityRating?.let { String.format(Locale.US, "★ %.1f", it) },
            age = item.officialRating?.let { r -> Regex("""(\d{1,2}\+)$""").find(r)?.value ?: r },
            year = item.year?.toString(),
            genres = item.genres.take(3).joinToString(", ").ifEmpty { null },
            poster = cards.image(item.primary, SeriesLayout.POSTER_W, SeriesLayout.POSTER_H),
            backdropUrl = item.backdrop?.let { images.url(it, cards.px(SeriesLayout.BACKDROP_W), cards.px(SeriesLayout.BACKDROP_H), quality = 80) },
            favorite = item.user.favorite,
            seasons = seasons,
            episodes = episodes,
            nextUp = nextUp.coerceAtLeast(0),
            playLabel = next?.let { playLabel(it, all[nextUp]) } ?: "Смотреть",
        )
    }

    private fun episode(e: ItemSummary, season: Int, rowIndex: Int, position: Int, seasonItem: ItemSummary, isNextUp: Boolean): SeriesEpisode {
        val seasonNumber = e.seasonNumber ?: seasonItem.seasonNumber ?: 0
        // Some files have no episode number: their place in the season stands in.
        val number = e.episodeNumber ?: (position + 1)
        val numberLabel = e.episodeNumberEnd?.takeIf { it > number }?.let { "$number–$it" } ?: "%02d".format(number)
        val progress = e.user.progress?.takeIf { !e.user.played }
        val state = when {
            e.user.played -> EpisodeState.Watched
            progress != null -> EpisodeState.InProgress
            isNextUp -> EpisodeState.Next
            cards.isNew(e) -> EpisodeState.New
            else -> EpisodeState.None
        }
        val duration = e.runtimeMs?.let { "${(it / 60_000.0).roundToInt()} мин" }
        val air = e.premiereAt?.let { DateFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
        return SeriesEpisode(
            key = "ep/${e.id}",
            id = e.id,
            season = season,
            rowIndex = rowIndex,
            code = "S$seasonNumber·E$numberLabel",
            longCode = "СЕЗОН $seasonNumber · СЕРИЯ ${e.episodeNumber ?: number}",
            title = e.name,
            meta = listOfNotNull(duration, air).joinToString(" · ").ifEmpty { null },
            duration = duration,
            airDate = air,
            overview = e.overview,
            image = cards.image(e.primary ?: e.thumb ?: e.backdrop, SeriesLayout.EPISODE_W, SeriesLayout.EPISODE_H),
            progress = progress,
            state = state,
            resumeMs = if (progress != null) e.user.positionMs else 0,
        )
    }

    private fun playLabel(next: SeriesEpisode, item: ItemSummary): String {
        val code = next.code.replace("·", " ").replace(Regex("""E0(\d)"""), "E$1")
        return when (next.state) {
            EpisodeState.InProgress -> {
                val left = item.runtimeMs?.let { (it * (1 - (next.progress ?: 0f)) / 60_000).roundToInt() }
                "Продолжить $code" + (left?.let { " · ещё $it мин" } ?: "")
            }
            else -> "Смотреть $code" + (next.duration?.let { " · $it" } ?: "")
        }
    }

    /**
     * The episode to continue with: the last one played if it isn't finished (also when it was only started for a few
     * seconds and the server kept no position), else the first unwatched one after it; nothing played yet — the first
     * unwatched. Specials only count when the series has nothing else.
     */
    private fun nextUp(all: List<ItemSummary>): Int {
        if (all.isEmpty()) return -1
        val regular = all.indices.filter { (all[it].seasonNumber ?: 1) > 0 }.ifEmpty { all.indices.toList() }
        val last = regular.filter { all[it].user.lastPlayedAt != null }.maxByOrNull { all[it].user.lastPlayedAt!! }
        if (last != null) {
            val e = all[last]
            if (!e.user.played) return last
            regular.firstOrNull { it > last && !all[it].user.played }?.let { return it }
        }
        return regular.firstOrNull { !all[it].user.played } ?: regular.first()
    }

    private companion object {
        val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}

/** Sizes (dp) shared by the layout, the image requests and the baked backdrop. */
internal object SeriesLayout {
    const val PAGE_W = 960f
    const val PAGE_H = 540f
    const val POSTER_W = 90f
    const val POSTER_H = 135f
    const val EPISODE_W = 200f
    const val EPISODE_H = 112.5f
    /** The backdrop covers 70 % of the page width and height at the top right, as on the movie page. */
    const val BACKDROP_W = 672f
    const val BACKDROP_H = 378f
}

package ru.stenfredd.movies.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.movies.core.data.LibraryRepository
import ru.stenfredd.movies.core.data.model.ItemDetails
import ru.stenfredd.movies.core.data.model.ItemKind
import ru.stenfredd.movies.core.data.model.ItemSummary
import ru.stenfredd.movies.core.data.model.PersonKind
import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.StreamSelection
import ru.stenfredd.movies.playback.api.SubtitleStyle
import ru.stenfredd.movies.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.movies.playback.core.EngineSelector
import ru.stenfredd.movies.playback.core.PlaybackPreferences
import ru.stenfredd.movies.playback.core.PlaybackSession
import ru.stenfredd.movies.playback.core.PlaybackSessionFactory
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.RemoteImageSpec
import ru.stenfredd.movies.ui.details.CastModel
import ru.stenfredd.movies.ui.details.MediaLabels
import ru.stenfredd.movies.ui.model.CardMapper
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/** "Качество": [bitrate] null = the original file. */
@Immutable
data class QualityOption(val key: String, val bitrate: Int?, val label: String, val detail: String?)

/** One episode in the player's drawer. */
@Immutable
data class EpisodeModel(
    val key: String,
    val id: UUID,
    /** "E04" */
    val number: String,
    /** "4. Разлом" */
    val title: String,
    val duration: String?,
    val image: RemoteImageSpec?,
    val progress: Float?,
    val watched: Boolean,
    val isNew: Boolean,
)

/** What the controls and the drawer show about the item being played; display strings only. */
@Immutable
data class PlayerInfo(
    val id: UUID,
    val isEpisode: Boolean,
    /** "ФИЛЬМ", "СЕРИАЛ" */
    val label: String,
    val title: String,
    /** "2024 · Драма, Приключения" or "Сезон 2 · Серия 4 · Разлом" */
    val subtitle: String?,
    val chaptersMs: List<Long>,
    val poster: RemoteImageSpec?,
    val rating: String?,
    val age: String?,
    val year: String?,
    val runtime: String?,
    val genres: String?,
    val description: String?,
    val director: String?,
    val cast: List<CastModel>,
    val seasonNumber: Int?,
    val qualities: List<QualityOption>,
)

/** The season of the episode being played, for the drawer and ⏮/⏭. */
@Immutable
data class SeasonEpisodes(
    /** "СЕЗОН 2 · СЕРИИ" */
    val title: String,
    /** "10 серий · 3 просмотрено" */
    val stat: String,
    val episodes: List<EpisodeModel>,
    val previous: UUID?,
    val next: UUID?,
)

@HiltViewModel
class PlayerViewModel @javax.inject.Inject constructor(
    private val factory: PlaybackSessionFactory,
    val selector: EngineSelector,
    val probe: DeviceCapabilitiesProbe,
    private val library: LibraryRepository,
    private val cards: CardMapper,
    private val prefs: PlaybackPreferences,
) : ViewModel() {
    private val _session = MutableStateFlow<PlaybackSession?>(null)
    val session: StateFlow<PlaybackSession?> = _session.asStateFlow()

    private val _info = MutableStateFlow<PlayerInfo?>(null)
    val info: StateFlow<PlayerInfo?> = _info.asStateFlow()

    private val _season = MutableStateFlow<SeasonEpisodes?>(null)
    val season: StateFlow<SeasonEpisodes?> = _season.asStateFlow()

    val subtitleStyle: StateFlow<SubtitleStyle> = prefs.subtitleStyle.stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleStyle())

    private var startedFor: Screen.Player? = null
    private var infoJob: Job? = null
    /** Quality chosen in the player: kept for the next episode. */
    private var maxBitrate: Int? = null
    /** Every episode of the series being played, in order. */
    private var allEpisodes: List<ItemSummary> = emptyList()

    fun start(screen: Screen.Player) {
        if (screen == startedFor) return
        startedFor = screen
        play(screen.itemId, screen.engine, screen.startPositionMs, screen.streams, screen.startFraction)
    }

    /** Switches to another episode (drawer, ⏮/⏭, end of an episode): from its resume point, with the remembered tracks. */
    fun playEpisode(id: UUID) {
        val episode = allEpisodes.firstOrNull { it.id == id } ?: return
        play(id, null, episode.resumeMs(), streams = null, startFraction = null)
    }

    private fun ItemSummary.resumeMs(): Long = user.progress?.takeIf { it < RESUME_LIMIT }?.let { user.positionMs } ?: 0

    private fun play(itemId: UUID, engine: EngineId?, startMs: Long, streams: StreamSelection?, startFraction: Double?) {
        val old = _session.value
        val session = factory.create(viewModelScope)
        session.setMaxBitrate(maxBitrate)
        _session.value = session
        old?.release()
        session.start(itemId, engine, startMs, streams, startFraction)
        loadInfo(itemId)
    }

    fun setQuality(bitrate: Int?) {
        maxBitrate = bitrate
        _session.value?.setMaxBitrate(bitrate)
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        _session.value?.setSubtitleStyle(style)
        viewModelScope.launch { prefs.setSubtitleStyle(style) }
    }

    /** Releases [target] (default: the current session); a stale screen disposing late can't kill a newer session. */
    fun release(target: PlaybackSession? = _session.value) {
        target?.release()
        if (target === _session.value) {
            _session.value = null
            startedFor = null
        }
    }

    override fun onCleared() = release()

    private fun loadInfo(itemId: UUID) {
        infoJob?.cancel()
        if (_info.value?.id != itemId) _info.value = null
        infoJob = viewModelScope.launch {
            try {
                val details = library.details(itemId)
                _info.value = withContext(Dispatchers.Default) { details.toInfo() }
                val item = details.summary
                val seriesId = item.seriesId
                if (item.kind == ItemKind.Episode && seriesId != null) {
                    val all = library.episodes(seriesId)
                    allEpisodes = all
                    _season.value = withContext(Dispatchers.Default) { season(item, all) }
                } else {
                    _season.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Player: info failed")
            }
        }
    }

    private fun season(current: ItemSummary, all: List<ItemSummary>): SeasonEpisodes {
        val index = all.indexOfFirst { it.id == current.id }
        val inSeason = all.filter { it.seasonId == current.seasonId }
        val watched = inSeason.count { it.user.played }
        return SeasonEpisodes(
            title = listOfNotNull(current.seasonNumber?.let { "СЕЗОН $it" }, "СЕРИИ").joinToString(" · "),
            stat = "${inSeason.size} ${plural(inSeason.size, "серия", "серии", "серий")} · $watched просмотрено",
            episodes = inSeason.map { e ->
                val progress = e.user.progress
                EpisodeModel(
                    key = "ep/${e.id}",
                    id = e.id,
                    number = e.episodeNumber?.let { "E%02d".format(it) } ?: "",
                    title = listOfNotNull(e.episodeNumber?.let { "$it." }, e.name).joinToString(" "),
                    duration = e.runtimeMs?.let { "${it / 60_000} мин" },
                    image = cards.image(e.primary ?: e.thumb ?: e.backdrop, PlayerLayout.EPISODE_W, PlayerLayout.EPISODE_H),
                    progress = progress,
                    watched = e.user.played,
                    isNew = cards.isNew(e),
                )
            },
            previous = all.getOrNull(index - 1)?.id?.takeIf { index > 0 },
            next = all.getOrNull(index + 1)?.id?.takeIf { index >= 0 },
        )
    }

    private fun ItemDetails.toInfo(): PlayerInfo {
        val item = summary
        val episode = item.kind == ItemKind.Episode
        val media = media
        return PlayerInfo(
            id = item.id,
            isEpisode = episode,
            label = if (episode) "СЕРИАЛ" else "ФИЛЬМ",
            title = if (episode) item.seriesName ?: item.name else item.name,
            subtitle = if (episode) {
                // Episodes without their own title are often named "Серия 3": don't say it twice.
                val name = item.name.takeUnless { n -> item.episodeNumber != null && n.equals("Серия ${item.episodeNumber}", ignoreCase = true) }
                listOfNotNull(item.seasonNumber?.let { "Сезон $it" }, item.episodeNumber?.let { "Серия $it" }, name).joinToString(" · ")
            } else {
                listOfNotNull(item.year?.toString(), item.genres.take(3).joinToString(", ").ifEmpty { null }).joinToString(" · ").ifEmpty { null }
            },
            chaptersMs = chapters.map { it.startMs }.filter { it > 0 },
            poster = cards.image(if (episode) item.seriesPrimary ?: item.primary else item.primary, PlayerLayout.POSTER_W, PlayerLayout.POSTER_H),
            rating = item.communityRating?.let { String.format(Locale.US, "★ %.1f", it) },
            age = item.officialRating?.let { r -> Regex("""(\d{1,2}\+)$""").find(r)?.value ?: r },
            year = item.year?.toString(),
            runtime = MediaLabels.runtime(item.runtimeMs),
            genres = item.genres.take(3).joinToString(", ").ifEmpty { null },
            description = item.overview,
            director = people.filter { it.kind == PersonKind.Director }.joinToString(", ") { it.name }.ifEmpty { null },
            cast = people.filter { it.kind == PersonKind.Actor }.take(CAST_LIMIT).map { p ->
                CastModel(
                    key = "cast/${p.id}",
                    name = p.name,
                    role = p.role,
                    letters = p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                    image = cards.image(p.image, PlayerLayout.AVATAR, PlayerLayout.AVATAR),
                )
            },
            seasonNumber = item.seasonNumber,
            qualities = qualities(media?.bitrate, media?.video?.height),
        )
    }

    /** "Оригинал" and the transcode caps below the file's own bitrate, labelled with the resulting resolution. */
    private fun qualities(fileBitrate: Int?, height: Int?): List<QualityOption> {
        val original = QualityOption("q/original", null, "Оригинал", fileBitrate?.let { PlayerLabels.mbps(it) })
        val caps = QUALITY_CAPS.filter { (bitrate, _) -> fileBitrate == null || bitrate < fileBitrate * 0.9 }
        return listOf(original) + caps.map { (bitrate, lines) ->
            val shown = if (height != null && height in 1 until lines) height else lines
            QualityOption("q/$bitrate", bitrate, "${shown}p", PlayerLabels.mbps(bitrate))
        }
    }

    private companion object {
        const val CAST_LIMIT = 4
        /** An episode watched further than this starts from the beginning when picked in the drawer. */
        const val RESUME_LIMIT = 0.9f
        /** Bitrate cap → resolution the server scales to at that bitrate (its own rule of thumb). */
        val QUALITY_CAPS = listOf(20_000_000 to 1080, 10_000_000 to 1080, 4_000_000 to 720, 1_500_000 to 480)
    }
}

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** Sizes (dp) shared by the layout and the image requests. */
internal object PlayerLayout {
    const val EPISODE_W = 180f
    const val EPISODE_H = 101f
    const val POSTER_W = 100f
    const val POSTER_H = 150f
    const val AVATAR = 24f
}

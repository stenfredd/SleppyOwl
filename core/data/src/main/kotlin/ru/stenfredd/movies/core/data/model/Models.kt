@file:UseSerializers(UUIDSerializer::class)

package ru.stenfredd.movies.core.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

/*
 * App-side models of server data. Screens and view models work with these, never with SDK DTOs: they hold only what
 * the app shows, are immutable (the UI module marks this package stable for Compose) and serializable (the home
 * snapshot on disk).
 */

enum class ItemKind { Movie, Series, Season, Episode, BoxSet, Folder, Video, Other }

enum class ImageKind { Primary, Backdrop, Thumb, Logo }

/** One server image; the URL with the needed size comes from `ImageUrls`. */
@Serializable
data class ImageRef(val itemId: UUID, val kind: ImageKind, val tag: String, val blurHash: String? = null)

/** Per-user state of an item. */
@Serializable
data class UserState(
    val played: Boolean = false,
    val favorite: Boolean = false,
    /** 0..1 for a started item, else null. */
    val progress: Float? = null,
    val positionMs: Long = 0,
    /** Unwatched episodes of a series or season. */
    val unplayedCount: Int? = null,
    val lastPlayedAt: Long? = null,
)

/** An item as cards and lists show it: movie, series, season or episode. */
@Serializable
data class ItemSummary(
    val id: UUID,
    val kind: ItemKind,
    val name: String,
    val year: Int? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val runtimeMs: Long? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    /** Width of the main video stream, px: the "4K" badge. */
    val videoWidth: Int? = null,
    val dateAddedAt: Long? = null,
    val premiereAt: Long? = null,
    val seriesId: UUID? = null,
    val seriesName: String? = null,
    val seasonId: UUID? = null,
    /** Season number of an episode, or of the season itself. */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeNumberEnd: Int? = null,
    val childCount: Int? = null,
    val user: UserState = UserState(),
    /** Poster (movie, series, season) or still (episode). */
    val primary: ImageRef? = null,
    /** The item's own backdrop, else its parent's (series for an episode). */
    val backdrop: ImageRef? = null,
    /** Landscape thumb of the item or its series. */
    val thumb: ImageRef? = null,
    /** Poster of the series an episode or season belongs to. */
    val seriesPrimary: ImageRef? = null,
    val logo: ImageRef? = null,
) {
    val isPlayable: Boolean get() = kind == ItemKind.Movie || kind == ItemKind.Episode || kind == ItemKind.Video
}

/** A user view (library) on the server. */
@Serializable
data class LibraryView(val id: UUID, val name: String, val type: LibraryType, val image: ImageRef? = null)

enum class LibraryType { Movies, Shows, Mixed, Other }

/**
 * What a library's lists and counts ask for. One type where the library has one: the server takes ~10× longer
 * (0.2 s instead of 0.02) to list or count "movies and series" than just movies.
 */
val LibraryView.itemKinds: Set<ItemKind>
    get() = when (type) {
        LibraryType.Movies -> setOf(ItemKind.Movie)
        LibraryType.Shows -> setOf(ItemKind.Series)
        else -> setOf(ItemKind.Movie, ItemKind.Series)
    }

@Serializable
data class Person(val id: UUID, val name: String, val role: String?, val kind: PersonKind, val image: ImageRef?)

enum class PersonKind { Actor, Director, Writer, Producer, Other }

/** One page of a long list: [items] start at [start] of [total]. */
data class Page<T>(val items: List<T>, val start: Int, val total: Int) {
    val end: Int get() = start + items.size
}

enum class ItemSort { Name, DateAdded, Year, Rating, DatePlayed, PremiereDate, Random }

/** Filter and order of an item list (library grid, favourites, history). */
data class ItemQuery(
    val parentId: UUID? = null,
    val kinds: Set<ItemKind> = setOf(ItemKind.Movie, ItemKind.Series),
    val sort: ItemSort = ItemSort.Name,
    val descending: Boolean = false,
    val unplayedOnly: Boolean = false,
    val playedOnly: Boolean = false,
    val favoritesOnly: Boolean = false,
    val genre: String? = null,
    val personId: UUID? = null,
    val searchTerm: String? = null,
)

/** Everything the movie/episode/series screens show about one item. */
data class ItemDetails(
    val summary: ItemSummary,
    val originalTitle: String?,
    val tagline: String?,
    val studios: List<String>,
    val people: List<Person>,
    val media: MediaInfo?,
    val chapters: List<Chapter>,
)

data class Chapter(val name: String, val startMs: Long)

/** The file of a playable item: what the format badges and the "file" line show, and the track choices. */
data class MediaInfo(
    val sourceId: String,
    val container: String?,
    val sizeBytes: Long?,
    val bitrate: Int?,
    val video: VideoTrack?,
    val audio: List<AudioTrack>,
    val subtitles: List<SubtitleTrack>,
    val defaultAudioIndex: Int?,
    val defaultSubtitleIndex: Int?,
)

data class VideoTrack(
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val range: VideoRange,
    val dvProfile: Int?,
    val bitDepth: Int?,
    val frameRate: Float?,
)

enum class VideoRange { SDR, HDR10, HDR10Plus, HLG, DolbyVision, Other }

data class AudioTrack(
    val index: Int,
    /** Server's display title ("Russian - Dolby TrueHD + Dolby Atmos 7.1 - Default"). */
    val title: String,
    /** The stream's own title from the file ("MVO, HDRezka"): tells apart tracks of one language. */
    val name: String?,
    val language: String?,
    val codec: String?,
    /** Codec profile ("DTS-HD MA"). */
    val profile: String?,
    val channels: Int?,
    /** Dolby Atmos (TrueHD or E-AC-3 JOC). */
    val atmos: Boolean,
    val isDefault: Boolean,
)

data class SubtitleTrack(
    val index: Int,
    val title: String,
    /** The stream's own title from the file ("Надписи", "Full"). */
    val name: String?,
    val language: String?,
    val codec: String?,
    val external: Boolean,
    val forced: Boolean,
    val isDefault: Boolean,
)

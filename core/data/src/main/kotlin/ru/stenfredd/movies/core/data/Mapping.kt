package ru.stenfredd.movies.core.data

import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import ru.stenfredd.movies.core.data.model.AudioTrack
import ru.stenfredd.movies.core.data.model.Chapter
import ru.stenfredd.movies.core.data.model.ImageKind
import ru.stenfredd.movies.core.data.model.ImageRef
import ru.stenfredd.movies.core.data.model.ItemDetails
import ru.stenfredd.movies.core.data.model.ItemKind
import ru.stenfredd.movies.core.data.model.ItemSummary
import ru.stenfredd.movies.core.data.model.LibraryType
import ru.stenfredd.movies.core.data.model.LibraryView
import ru.stenfredd.movies.core.data.model.MediaInfo
import ru.stenfredd.movies.core.data.model.Person
import ru.stenfredd.movies.core.data.model.PersonKind
import ru.stenfredd.movies.core.data.model.SubtitleTrack
import ru.stenfredd.movies.core.data.model.UserState
import ru.stenfredd.movies.core.data.model.VideoRange
import ru.stenfredd.movies.core.data.model.VideoTrack
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Fields every list asks for on top of the defaults: the format badge, "new" and episode counts. */
internal val CARD_FIELDS = listOf(ItemFields.WIDTH, ItemFields.DATE_CREATED, ItemFields.CHILD_COUNT)

/** Lists whose first card is shown big (continue watching) also need the description and genres. */
internal val FEATURED_FIELDS = CARD_FIELDS + listOf(ItemFields.OVERVIEW, ItemFields.GENRES)

internal val DETAIL_FIELDS = FEATURED_FIELDS + listOf(
    ItemFields.ORIGINAL_TITLE, ItemFields.TAGLINES, ItemFields.STUDIOS, ItemFields.PEOPLE, ItemFields.MEDIA_SOURCES,
    ItemFields.MEDIA_STREAMS, ItemFields.CHAPTERS,
)

private const val TICKS_PER_MS = 10_000L

internal fun BaseItemDto.toSummary(): ItemSummary {
    val kind = type.toKind()
    val primaryTag = imageTags?.get(ImageType.PRIMARY)
    return ItemSummary(
        id = id,
        kind = kind,
        name = name.orEmpty(),
        year = productionYear,
        communityRating = communityRating,
        officialRating = officialRating,
        runtimeMs = runTimeTicks?.div(TICKS_PER_MS),
        overview = overview,
        genres = genres.orEmpty(),
        videoWidth = width,
        dateAddedAt = dateCreated?.epochMs(),
        premiereAt = premiereDate?.epochMs(),
        seriesId = seriesId,
        seriesName = seriesName,
        seasonId = seasonId,
        seasonNumber = if (kind == ItemKind.Season) indexNumber else parentIndexNumber,
        episodeNumber = if (kind == ItemKind.Episode) indexNumber else null,
        episodeNumberEnd = if (kind == ItemKind.Episode) indexNumberEnd else null,
        childCount = childCount,
        user = userData?.let { u ->
            UserState(
                played = u.played,
                favorite = u.isFavorite,
                progress = u.playedPercentage?.takeIf { it > 0 && kind != ItemKind.Series && kind != ItemKind.Season }?.let { (it / 100).toFloat() },
                positionMs = u.playbackPositionTicks / TICKS_PER_MS,
                unplayedCount = u.unplayedItemCount,
                lastPlayedAt = u.lastPlayedDate?.epochMs(),
            )
        } ?: UserState(),
        primary = primaryTag?.let { image(id, ImageKind.Primary, it) },
        backdrop = backdropImageTags?.firstOrNull()?.let { image(id, ImageKind.Backdrop, it) }
            ?: parentBackdropItemId?.let { pid -> parentBackdropImageTags?.firstOrNull()?.let { image(pid, ImageKind.Backdrop, it) } },
        thumb = imageTags?.get(ImageType.THUMB)?.let { image(id, ImageKind.Thumb, it) }
            ?: parentThumbItemId?.let { pid -> parentThumbImageTag?.let { image(pid, ImageKind.Thumb, it) } }
            ?: seriesId?.let { sid -> seriesThumbImageTag?.let { image(sid, ImageKind.Thumb, it) } },
        seriesPrimary = seriesId?.let { sid -> seriesPrimaryImageTag?.let { image(sid, ImageKind.Primary, it) } }
            ?: if (kind == ItemKind.Season) parentPrimaryImageItemId?.let { pid -> parentPrimaryImageTag?.let { image(pid, ImageKind.Primary, it) } } else null,
        logo = imageTags?.get(ImageType.LOGO)?.let { image(id, ImageKind.Logo, it) }
            ?: parentLogoItemId?.let { pid -> parentLogoImageTag?.let { image(pid, ImageKind.Logo, it) } },
    )
}

internal fun BaseItemDto.toDetails(): ItemDetails = ItemDetails(
    summary = toSummary(),
    originalTitle = originalTitle?.takeIf { it != name },
    tagline = taglines?.firstOrNull(),
    studios = studios.orEmpty().mapNotNull { it.name },
    people = people.orEmpty().map { it.toPerson() },
    media = mediaSources?.firstOrNull()?.toMediaInfo(),
    chapters = chapters.orEmpty().map { Chapter(it.name.orEmpty(), it.startPositionTicks / TICKS_PER_MS) },
)

internal fun BaseItemDto.toLibraryView(): LibraryView = LibraryView(
    id = id,
    name = name.orEmpty(),
    type = when (collectionType) {
        CollectionType.MOVIES -> LibraryType.Movies
        CollectionType.TVSHOWS -> LibraryType.Shows
        null -> LibraryType.Mixed
        else -> LibraryType.Other
    },
    image = imageTags?.get(ImageType.PRIMARY)?.let { image(id, ImageKind.Primary, it) },
)

internal fun BaseItemPerson.toPerson() = Person(
    id = id,
    name = name.orEmpty(),
    role = role?.takeIf { it.isNotBlank() },
    kind = when (type.serialName) {
        "Actor", "GuestStar" -> PersonKind.Actor
        "Director" -> PersonKind.Director
        "Writer" -> PersonKind.Writer
        "Producer" -> PersonKind.Producer
        else -> PersonKind.Other
    },
    image = primaryImageTag?.let { ImageRef(id, ImageKind.Primary, it, imageBlurHashes?.get(ImageType.PRIMARY)?.get(it)) },
)

internal fun BaseItemDto.toPerson() = Person(
    id = id,
    name = name.orEmpty(),
    role = null,
    kind = PersonKind.Other,
    image = imageTags?.get(ImageType.PRIMARY)?.let { image(id, ImageKind.Primary, it) },
)

/** The file as the app models it; the player also maps the source its session got from PlaybackInfo. */
fun MediaSourceInfo.toMediaInfo(): MediaInfo {
    val streams = mediaStreams.orEmpty()
    return MediaInfo(
        sourceId = id.orEmpty(),
        container = container,
        sizeBytes = size,
        bitrate = bitrate,
        video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.toVideo(),
        audio = streams.filter { it.type == MediaStreamType.AUDIO }.map { it.toAudio(defaultAudioStreamIndex) },
        subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }.map { it.toSubtitle(defaultSubtitleStreamIndex) },
        defaultAudioIndex = defaultAudioStreamIndex?.takeIf { it >= 0 },
        // -1 is the server's "no subtitles".
        defaultSubtitleIndex = defaultSubtitleStreamIndex?.takeIf { it >= 0 },
    )
}

private fun MediaStream.toVideo() = VideoTrack(
    codec = codec,
    width = width,
    height = height,
    range = videoRangeType.toRange(),
    dvProfile = dvProfile,
    bitDepth = bitDepth,
    frameRate = realFrameRate ?: averageFrameRate,
)

private fun VideoRangeType.toRange(): VideoRange = when {
    this == VideoRangeType.SDR -> VideoRange.SDR
    this == VideoRangeType.HLG -> VideoRange.HLG
    serialName.startsWith("DOVI") -> VideoRange.DolbyVision
    serialName == "HDR10Plus" -> VideoRange.HDR10Plus
    serialName == "HDR10" -> VideoRange.HDR10
    else -> VideoRange.Other
}

private fun MediaStream.toAudio(defaultIndex: Int?) = AudioTrack(
    index = index,
    title = displayTitle ?: title ?: codec.orEmpty(),
    name = title?.takeIf { it.isNotBlank() },
    language = language,
    codec = codec,
    profile = profile,
    channels = channels,
    atmos = audioSpatialFormat == AudioSpatialFormat.DOLBY_ATMOS || profile?.contains("Atmos", ignoreCase = true) == true,
    isDefault = index == defaultIndex,
)

private fun MediaStream.toSubtitle(defaultIndex: Int?) = SubtitleTrack(
    index = index,
    title = displayTitle ?: title ?: codec.orEmpty(),
    name = title?.takeIf { it.isNotBlank() },
    language = language,
    codec = codec,
    external = isExternal,
    forced = isForced,
    isDefault = index == defaultIndex,
)

private fun BaseItemDto.image(itemId: UUID, kind: ImageKind, tag: String): ImageRef {
    val type = when (kind) {
        ImageKind.Primary -> ImageType.PRIMARY
        ImageKind.Backdrop -> ImageType.BACKDROP
        ImageKind.Thumb -> ImageType.THUMB
        ImageKind.Logo -> ImageType.LOGO
    }
    return ImageRef(itemId, kind, tag, imageBlurHashes?.get(type)?.get(tag))
}

internal fun BaseItemKind.toKind(): ItemKind = when (this) {
    BaseItemKind.MOVIE -> ItemKind.Movie
    BaseItemKind.SERIES -> ItemKind.Series
    BaseItemKind.SEASON -> ItemKind.Season
    BaseItemKind.EPISODE -> ItemKind.Episode
    BaseItemKind.BOX_SET -> ItemKind.BoxSet
    BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> ItemKind.Folder
    BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> ItemKind.Video
    else -> ItemKind.Other
}

internal fun ItemKind.toBaseKind(): BaseItemKind? = when (this) {
    ItemKind.Movie -> BaseItemKind.MOVIE
    ItemKind.Series -> BaseItemKind.SERIES
    ItemKind.Season -> BaseItemKind.SEASON
    ItemKind.Episode -> BaseItemKind.EPISODE
    ItemKind.BoxSet -> BaseItemKind.BOX_SET
    ItemKind.Folder -> BaseItemKind.FOLDER
    ItemKind.Video -> BaseItemKind.VIDEO
    ItemKind.Other -> null
}

/** The SDK converts server dates to the device's time zone. */
private fun LocalDateTime.epochMs(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

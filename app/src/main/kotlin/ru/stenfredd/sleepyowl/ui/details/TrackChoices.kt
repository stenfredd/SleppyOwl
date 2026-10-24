package ru.stenfredd.sleepyowl.ui.details

import ru.stenfredd.sleepyowl.core.data.model.MediaInfo
import ru.stenfredd.sleepyowl.playback.core.PlaybackPreferences
import ru.stenfredd.sleepyowl.playback.core.TrackPick
import java.util.UUID

/** Audio and subtitle choices of a file as the movie page and the series tray show them. */
internal object TrackChoices {
    fun audio(media: MediaInfo?): List<TrackOption> =
        media?.audio.orEmpty().map { TrackOption("audio/${it.index}", it.index, MediaLabels.audioLabel(it), MediaLabels.audioDetail(it)) }

    /** "Выкл" first, then the tracks; empty when the file has no subtitles. */
    fun subtitles(media: MediaInfo?): List<TrackOption> =
        if (media == null || media.subtitles.isEmpty()) {
            emptyList()
        } else {
            listOf(TrackOption("sub/off", null, "Выкл", null)) +
                media.subtitles.map { TrackOption("sub/${it.index}", it.index, MediaLabels.subtitleLabel(it), MediaLabels.subtitleDetail(it)) }
        }

    /**
     * The tracks picked last time for this movie or series ([key]), found in [media]: (audio, subtitle), or null if
     * nothing is remembered. Unmatched audio stays null (the file's default is used); a remembered subtitle that this
     * file doesn't have falls back to the default too.
     */
    suspend fun remembered(prefs: PlaybackPreferences, key: UUID, media: MediaInfo): Pair<Int?, Int?>? {
        val memory = prefs.tracks(key) ?: return null
        val audio = memory.audio?.bestOf(media.audio) { TrackPick(it.language, it.name, it.codec) }?.index
        val subtitle = when {
            memory.subtitleOff -> null
            else -> memory.subtitle?.bestOf(media.subtitles) { TrackPick(it.language, it.name, it.codec) }?.index ?: media.defaultSubtitleIndex
        }
        return audio to subtitle
    }

    /** Remembered tracks, else the file's defaults: (audio, subtitle). */
    suspend fun initial(prefs: PlaybackPreferences, key: UUID, media: MediaInfo): Pair<Int?, Int?> {
        val remembered = remembered(prefs, key, media)
        return Pair(
            remembered?.first ?: media.defaultAudioIndex ?: media.audio.firstOrNull()?.index,
            if (remembered != null) remembered.second else media.defaultSubtitleIndex,
        )
    }

    suspend fun rememberAudio(prefs: PlaybackPreferences, key: UUID, media: MediaInfo?, index: Int?) {
        val track = media?.audio?.firstOrNull { it.index == index } ?: return
        prefs.rememberAudio(key, TrackPick(track.language, track.name, track.codec))
    }

    suspend fun rememberSubtitle(prefs: PlaybackPreferences, key: UUID, media: MediaInfo?, index: Int?) {
        val pick = media?.subtitles?.firstOrNull { it.index == index }?.let { TrackPick(it.language, it.name, it.codec) }
        prefs.rememberSubtitle(key, pick)
    }
}

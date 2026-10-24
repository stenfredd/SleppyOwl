package ru.stenfredd.sleepyowl.playback.api

import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

private val attachedPictureCodecs = setOf("mjpeg", "png", "bmp", "gif", "webp")

/** Main video stream, ignoring cover art that containers expose as a video stream. */
val MediaSourceInfo.videoStream: MediaStream?
    get() = mediaStreams.orEmpty().firstOrNull {
        it.type == MediaStreamType.VIDEO && it.codec?.lowercase() !in attachedPictureCodecs
    }

fun MediaSourceInfo.stream(index: Int?): MediaStream? =
    index?.let { i -> mediaStreams.orEmpty().firstOrNull { it.index == i } }

fun MediaSourceInfo.streams(type: MediaStreamType) = mediaStreams.orEmpty().filter { it.type == type }

/**
 * Engines number embedded tracks per type in container order; Jellyfin uses the global ffprobe index.
 * Returns the 0-based position of [index] among embedded streams of the same type, or null.
 */
fun MediaSourceInfo.embeddedOrdinal(index: Int, type: MediaStreamType): Int? {
    val embedded = streams(type).filter { !it.isExternal }.let { list ->
        if (type == MediaStreamType.VIDEO) list.filter { it.codec?.lowercase() !in attachedPictureCodecs } else list
    }
    return embedded.indexOfFirst { it.index == index }.takeIf { it >= 0 }
}

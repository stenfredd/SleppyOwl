package ru.stenfredd.movies.playback.api

import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod

@JvmInline
value class EngineId(val value: String) {
    override fun toString() = value
}

data class EngineState(
    val phase: Phase = Phase.IDLE,
    val playWhenReady: Boolean = false,
) {
    enum class Phase { IDLE, BUFFERING, READY, ENDED }

    val isPlaying get() = phase == Phase.READY && playWhenReady
}

sealed interface EngineEvent {
    data object FirstFrameRendered : EngineEvent
    data class Error(val error: EngineError) : EngineEvent
}

data class EngineError(
    val kind: Kind,
    val message: String,
    val cause: Throwable? = null,
) {
    enum class Kind {
        /** Decoder could not be created or crashed: another engine may succeed. */
        DECODER,
        /** Container/stream format not understood: another engine may succeed. */
        FORMAT,
        AUDIO_OUTPUT,
        NETWORK,
        NO_VIDEO_OUTPUT,
        /** Playing but not progressing, or dropping a large share of frames (seen by the session's stall watchdog). */
        STALLED,
        UNKNOWN,
    }

    val worthFallback get() = kind != Kind.NETWORK
}

data class ExternalSubtitle(
    val streamIndex: Int,
    val url: String,
    val codec: String?,
    val language: String?,
    val title: String?,
)

data class LoadRequest(
    val url: String,
    val playMethod: PlayMethod,
    /** Source as returned by PlaybackInfo, used to map Jellyfin stream indexes to engine tracks. */
    val mediaSource: MediaSourceInfo,
    val startPositionMs: Long,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val externalSubtitles: List<ExternalSubtitle>,
)

/** Diagnostics shown in the debug overlay. Every field is optional: engines fill what they know. */
data class EngineStats(
    val videoDecoder: String? = null,
    val videoFormat: String? = null,
    val audioDecoder: String? = null,
    val audioFormat: String? = null,
    val audioOutput: String? = null,
    val droppedFrames: Int = 0,
    val extra: Map<String, String> = emptyMap(),
)

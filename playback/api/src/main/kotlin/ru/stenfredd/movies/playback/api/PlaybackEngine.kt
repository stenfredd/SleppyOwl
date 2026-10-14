package ru.stenfredd.movies.playback.api

import android.widget.FrameLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A playback engine (ExoPlayer, libVLC, ...). Every engine lives in its own Gradle module and is
 * registered through an [EngineProvider]. The rest of the app only talks to this interface.
 *
 * All methods must be called on the main thread.
 */
interface PlaybackEngine {
    val id: EngineId
    val state: StateFlow<EngineState>
    val events: Flow<EngineEvent>
    val stats: StateFlow<EngineStats>

    val positionMs: Long
    val durationMs: Long

    /** False once the engine knows it can't seek in this source (e.g. a Matroska index media3 can't find). */
    val isSeekable: Boolean get() = true

    /** Adds the engine's own video views (surface, subtitle overlay) into [container]. */
    fun attachVideo(container: FrameLayout)
    fun detachVideo()

    fun load(request: LoadRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** Selects tracks by Jellyfin `MediaStream.index`; `null` subtitle means "off". */
    fun selectAudio(streamIndex: Int)
    fun selectSubtitle(streamIndex: Int?)

    fun release()
}

/** Optional capabilities: checked with `engine as? X`, the UI hides what an engine can't do. */
interface AudioDelayCapable {
    fun setAudioDelay(delayMs: Long)
}

/**
 * Engines that can show video either on the TV's hardware video plane (where the TV applies its own picture
 * processing) or composited through the GPU like ordinary graphics (no TV processing, but no HDR and limited to
 * the UI resolution).
 */
interface VideoPathSwitchable {
    enum class Mode { AUTO, VIDEO_PLANE, GPU }

    val videoPathMode: Mode
    /** The path in use for the current file ([Mode.AUTO] resolved). */
    val usingGpuPath: Boolean
    fun setVideoPathMode(mode: Mode)
}

/**
 * Dolby Vision files with a base layer can be decoded either as that base layer (HDR10/HLG, the default) or through
 * the Dolby Vision decoder, which switches the TV into its Dolby Vision mode. Must be set before [PlaybackEngine.load].
 */
interface DolbyVisionSwitchable {
    var useDolbyVisionDecoder: Boolean
}

interface SubtitleDelayCapable {
    fun setSubtitleDelay(delayMs: Long)
}

@file:OptIn(UnstableApi::class)

package ru.stenfredd.movies.playback.exo

import android.content.Context
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import ru.stenfredd.movies.playback.api.Codecs
import ru.stenfredd.movies.playback.api.EngineError
import ru.stenfredd.movies.playback.api.EngineEvent
import ru.stenfredd.movies.playback.api.EngineState
import ru.stenfredd.movies.playback.api.EngineStats
import ru.stenfredd.movies.playback.api.LoadRequest
import ru.stenfredd.movies.playback.api.PlaybackEngine
import ru.stenfredd.movies.playback.api.VideoPathSwitchable
import ru.stenfredd.movies.playback.api.embeddedOrdinal
import ru.stenfredd.movies.playback.api.videoStream
import timber.log.Timber

class ExoEngine(context: Context) : PlaybackEngine, VideoPathSwitchable {
    override val id = ExoEngineProvider.ID

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setTunnelingEnabled(false)
            .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            .build()
    }

    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            .setMediaCodecSelector(::decoderInfos)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    )
        .setTrackSelector(trackSelector)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .build()

    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = _events.asSharedFlow()
    private val _stats = MutableStateFlow(EngineStats())
    override val stats = _stats.asStateFlow()

    override val positionMs get() = player.currentPosition
    override val durationMs get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0

    private val surfaceView = SurfaceView(context)
    private val textureView = TextureView(context)
    private val aspectFrame = AspectRatioFrameLayout(context).apply {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val subtitleView = SubtitleView(context).apply {
        setUserDefaultStyle()
        setUserDefaultTextSize()
    }

    private var request: LoadRequest? = null

    /** Profile 5 has no backward-compatible base layer: only a Dolby Vision decoder renders it correctly. */
    private var requiresDolbyVisionDecoder = false
    private var tracksApplied = false
    private var errorReported = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = updateState()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateState()
        override fun onRenderedFirstFrame() {
            _events.tryEmit(EngineEvent.FirstFrameRendered)
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                aspectFrame.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
        }
        override fun onCues(cueGroup: CueGroup) = subtitleView.setCues(cueGroup.cues)
        override fun onTracksChanged(tracks: Tracks) = onTracks(tracks)
        override fun onPlayerError(error: PlaybackException) = reportError(map(error))
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            _stats.update { it.copy(videoDecoder = decoderName) }
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            _stats.update { it.copy(audioDecoder = decoderName) }
        }
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            _stats.update { it.copy(videoFormat = describeVideo(format)) }
        }
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            _stats.update { it.copy(audioFormat = "${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz") }
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            _stats.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
        }
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val c = audioTrackConfig
            val kind = if (c.encoding == C.ENCODING_PCM_16BIT || c.encoding == C.ENCODING_PCM_FLOAT) "PCM (декодировано)" else "passthrough"
            _stats.update {
                it.copy(audioOutput = "$kind ${Codecs.encodingName(c.encoding)} ${c.sampleRate}Hz" + if (c.tunneling) " tunneled" else "")
            }
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        applyVideoPath()
    }

    override val videoPathMode get() = pathMode
    override var usingGpuPath = false
        private set
    private var pathApplied = false

    override fun setVideoPathMode(mode: VideoPathSwitchable.Mode) {
        pathMode = mode
        applyVideoPath()
    }

    /**
     * On the Xiaomi TV the hardware video plane gets the TV's own "enhancement" (sharpening, contrast): compressed
     * SDR sources look visibly harsher than through the GPU (user A/B against VLC, 2026-10-12). AUTO therefore
     * composites SDR up to 1080p through the GPU, and keeps the video plane where it is required: HDR (the GPU
     * path is SDR only) and >1080p (the UI surface is 1080p, the GPU path would throw away resolution).
     */
    private fun gpuPathFor(request: LoadRequest?): Boolean = when (pathMode) {
        VideoPathSwitchable.Mode.GPU -> true
        VideoPathSwitchable.Mode.VIDEO_PLANE -> false
        VideoPathSwitchable.Mode.AUTO -> request?.mediaSource?.videoStream?.let { v ->
            v.videoRange != VideoRange.HDR && (v.width ?: 0) <= 1920 && (v.height ?: 0) <= 1088
        } == true
    }

    private fun applyVideoPath() {
        val gpu = gpuPathFor(request)
        if (pathApplied && gpu == usingGpuPath) return
        pathApplied = true
        usingGpuPath = gpu
        val (view, other) = if (gpu) textureView to surfaceView else surfaceView to textureView
        aspectFrame.removeView(other)
        if (view.parent == null) {
            aspectFrame.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (gpu) player.setVideoTextureView(textureView) else player.setVideoSurfaceView(surfaceView)
        val label = (if (gpu) "через GPU (TextureView)" else "видеослой ТВ (SurfaceView)") +
            if (pathMode == VideoPathSwitchable.Mode.AUTO) ", авто" else ", вручную"
        _stats.update { it.copy(extra = it.extra + ("вывод видео" to label)) }
    }

    override fun attachVideo(container: FrameLayout) {
        detachVideo()
        container.addView(aspectFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        container.addView(subtitleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun detachVideo() {
        (aspectFrame.parent as? ViewGroup)?.removeView(aspectFrame)
        (subtitleView.parent as? ViewGroup)?.removeView(subtitleView)
    }

    override fun load(request: LoadRequest) {
        this.request = request
        tracksApplied = false
        requiresDolbyVisionDecoder = request.mediaSource.videoStream?.videoRangeType == VideoRangeType.DOVI
        applyVideoPath()
        val subtitles = request.externalSubtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(s.url.toUri())
                .setId("$EXTERNAL_PREFIX${s.streamIndex}")
                .setMimeType(subtitleMime(s.codec))
                .setLanguage(s.language)
                .setLabel(s.title)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(request.url)
            .setSubtitleConfigurations(subtitles)
            .apply { if (request.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item, request.startPositionMs)
        player.prepare()
    }

    /**
     * Without a Dolby Vision decoder media3 falls back to the base-layer codec (HEVC/AVC/AV1) and its HDR10/HDR10+/HLG
     * metadata. See [ExoEngineProvider.USE_DOLBY_VISION_DECODER] for why that is the default.
     */
    private fun decoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        if (mimeType == MimeTypes.VIDEO_DOLBY_VISION && !ExoEngineProvider.USE_DOLBY_VISION_DECODER && !requiresDolbyVisionDecoder) {
            return emptyList()
        }
        return MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
    }

    override val isSeekable get() = player.currentTimeline.isEmpty || player.isCurrentMediaItemSeekable

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun selectAudio(streamIndex: Int) {
        val req = request ?: return
        val ordinal = req.mediaSource.embeddedOrdinal(streamIndex, MediaStreamType.AUDIO) ?: return
        selectEmbedded(C.TRACK_TYPE_AUDIO, ordinal)
    }

    override fun selectSubtitle(streamIndex: Int?) {
        val req = request ?: return
        val builder = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (streamIndex == null) {
            trackSelector.parameters = builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return
        }
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val external = textGroups.firstOrNull { it.getTrackFormat(0).id?.endsWith("$EXTERNAL_PREFIX$streamIndex") == true }
        val group = external ?: req.mediaSource.embeddedOrdinal(streamIndex, MediaStreamType.SUBTITLE)?.let { ordinal ->
            textGroups.filterNot { it.getTrackFormat(0).id?.contains(EXTERNAL_PREFIX) == true }.getOrNull(ordinal)
        }
        if (group != null) builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        trackSelector.parameters = builder.build()
    }

    override fun release() {
        detachVideo()
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        player.release()
    }

    private fun selectEmbedded(type: Int, ordinal: Int) {
        val group = player.currentTracks.groups.filter { it.type == type }.getOrNull(ordinal) ?: return
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    private fun onTracks(tracks: Tracks) {
        val req = request ?: return
        if (!tracksApplied && tracks.groups.isNotEmpty()) {
            tracksApplied = true
            // A transcode already contains only the selected streams.
            if (req.playMethod == PlayMethod.DIRECT_PLAY) {
                req.audioStreamIndex?.let(::selectAudio)
                selectSubtitle(req.subtitleStreamIndex)
            } else if (req.subtitleStreamIndex == null) {
                selectSubtitle(null)
            }
        }
        // Decoders often under-report their limits (e.g. HEVC L5.1 High tier on MediaTek), so only a track that
        // is unsupported even when exceeding the declared capabilities is treated as unplayable.
        val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (video.isNotEmpty() && video.none { it.isSupported(true) }) {
            reportError(EngineError(EngineError.Kind.DECODER, "Видеодорожка не поддерживается: ${describeVideo(video.first().getTrackFormat(0))}"))
        }
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isNotEmpty() && audio.none { it.isSupported(true) }) {
            reportError(EngineError(EngineError.Kind.DECODER, "Аудиодорожка не поддерживается: ${audio.first().getTrackFormat(0).sampleMimeType}"))
        }
    }

    private fun updateState() {
        val phase = when (player.playbackState) {
            Player.STATE_BUFFERING -> EngineState.Phase.BUFFERING
            Player.STATE_READY -> EngineState.Phase.READY
            Player.STATE_ENDED -> EngineState.Phase.ENDED
            else -> EngineState.Phase.IDLE
        }
        _state.value = EngineState(phase, player.playWhenReady)
    }

    private fun reportError(error: EngineError) {
        if (errorReported) return
        errorReported = true
        Timber.w(error.cause, "Exo error: %s", error.message)
        _events.tryEmit(EngineEvent.Error(error))
    }

    private fun map(e: PlaybackException): EngineError {
        val kind = when (e.errorCode) {
            in 4000..4999 -> EngineError.Kind.DECODER
            in 3000..3999 -> EngineError.Kind.FORMAT
            in 5000..5999 -> EngineError.Kind.AUDIO_OUTPUT
            in 2000..2999 -> EngineError.Kind.NETWORK
            else -> EngineError.Kind.UNKNOWN
        }
        return EngineError(kind, "${e.errorCodeName}: ${e.message}", e)
    }

    private fun describeVideo(f: Format) = buildString {
        append(f.sampleMimeType)
        f.codecs?.let { append(" ").append(it) }
        if (f.width > 0) append(" ${f.width}x${f.height}")
        if (f.frameRate > 0) append(" %.3ffps".format(f.frameRate))
        f.colorInfo?.let { append(" ").append(it) }
    }

    private fun subtitleMime(codec: String?) = when (codec?.lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    companion object {
        /** Chosen in the player menu; kept for the process lifetime (no settings screen yet). */
        private var pathMode = VideoPathSwitchable.Mode.AUTO

        private const val EXTERNAL_PREFIX = "ext:"
    }
}

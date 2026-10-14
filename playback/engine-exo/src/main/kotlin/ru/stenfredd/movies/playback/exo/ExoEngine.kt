@file:OptIn(UnstableApi::class)

package ru.stenfredd.movies.playback.exo

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import ru.stenfredd.movies.playback.api.Codecs
import ru.stenfredd.movies.playback.api.DolbyVisionSwitchable
import ru.stenfredd.movies.playback.api.EngineError
import ru.stenfredd.movies.playback.api.EngineEvent
import ru.stenfredd.movies.playback.api.EngineState
import ru.stenfredd.movies.playback.api.EngineStats
import ru.stenfredd.movies.playback.api.LoadRequest
import ru.stenfredd.movies.playback.api.PlaybackEngine
import ru.stenfredd.movies.playback.api.SubtitleStyle
import ru.stenfredd.movies.playback.api.SubtitleStyleable
import ru.stenfredd.movies.playback.api.VideoPathSwitchable
import ru.stenfredd.movies.playback.api.embeddedOrdinal
import ru.stenfredd.movies.playback.api.videoStream
import timber.log.Timber

class ExoEngine(context: Context) : PlaybackEngine, VideoPathSwitchable, DolbyVisionSwitchable, SubtitleStyleable {
    override val id = ExoEngineProvider.ID

    /** See [gpuPathFor]: only TVs known to over-process SDR on the video plane. */
    private val sdrThroughGpu = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setTunnelingEnabled(false)
            .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            .build()
    }

    /**
     * Full ASS/SSA rendering (styles, positioning, embedded fonts) through libass (ass-media), drawn by an overlay
     * view above the video. Without it media3 turns ASS into plain text cues.
     */
    private val assHandler = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig(cacheSize = ASS_CACHE_MB, maxRenderPixels = 1920 * 1080))
    private val assParserFactory = AssSubtitleParserFactory(assHandler)

    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        AssRenderersFactory(
            assHandler,
            DefaultRenderersFactory(context)
                .setMediaCodecSelector(::decoderInfos)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
        ),
    )
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setTargetBufferBytes(bufferBytes())
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context, ExoExtractorsFactory { AssMatroskaExtractor(assParserFactory, assHandler) })
                // External ASS files are parsed by this factory directly.
                .setSubtitleParserFactory(assParserFactory),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .build()

    /**
     * The default target (~145 MB for video) is sized for phones with big heaps: this TV gives the app 192 MB
     * (512 MB with largeHeap), and once the buffer filled up after a few engine switches GC thrashed, playback
     * stuttered and the app died with OutOfMemoryError. A quarter of the heap, at most 64 MB: still ~8 s of a 60 Mbit/s 4K remux, plenty over the LAN.
     */
    private fun bufferBytes(): Int {
        val heapMb = (Runtime.getRuntime().maxMemory() shr 20).toInt()
        return (heapMb / 4).coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB) shl 20
    }

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
    private val subtitleView = SubtitleView(context)

    init {
        setSubtitleStyle(SubtitleStyle())
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

    private var audioUnderruns = 0
    private var rebuffers = 0
    /** Buffering right after load/seek is expected, only later ones are counted. */
    private var startedAtMs = 0L

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
        // Not visible as dropped frames: AC3-in-AVI / MPEG-PS style timing problems show up as these.
        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            audioUnderruns++
            Timber.w("Exo audio underrun #%d (%d ms since last feed)", audioUnderruns, elapsedSinceLastFeedMs)
            _stats.update { it.copy(extra = it.extra + ("срывы звука" to audioUnderruns.toString())) }
        }
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            Timber.d("Exo bandwidth: %.1f Mbit/s (%d KB in %d ms)", bitrateEstimate / 1e6, totalBytesLoaded / 1024, totalLoadTimeMs)
        }
        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            Timber.w(audioSinkError, "Exo audio sink error")
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            if (state != Player.STATE_BUFFERING || eventTime.realtimeMs - startedAtMs < 3000) return
            rebuffers++
            Timber.w("Exo rebuffering #%d at %d ms", rebuffers, eventTime.currentPlaybackPositionMs)
            _stats.update { it.copy(extra = it.extra + ("буферизаций" to rebuffers.toString())) }
        }
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val c = audioTrackConfig
            val kind = if (c.encoding == C.ENCODING_PCM_16BIT || c.encoding == C.ENCODING_PCM_FLOAT) "PCM (декодировано)" else "passthrough"
            _stats.update {
                it.copy(audioOutput = "$kind ${Codecs.encodingName(c.encoding)} ${c.sampleRate}Hz" + if (c.tunneling) " tunneled" else "")
            }
        }
    }

    /**
     * Chosen in the player menu or by a variant key. Per engine: a new playback starts in AUTO, within one playback
     * PlaybackSession carries it over to the next engine instance (fallback, restart).
     */
    private var pathMode = VideoPathSwitchable.Mode.AUTO

    /**
     * Default set by [ExoEngineProvider.create] (on if the display supports Dolby Vision); debug variants 1/4 override it.
     * Carried over like [pathMode]. With it the TV does switch to Dolby Vision (DispService "Hdr Dolby").
     */
    override var useDolbyVisionDecoder = false

    override val videoPathMode get() = pathMode
    override var usingGpuPath = false
        private set
    private var pathApplied = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRendered = -1
    private var lastSkipped = 0

    init {
        assHandler.init(player)
        // Inside the aspect frame: libass lays out relative to the picture, not to the letterboxed screen.
        aspectFrame.addView(AssSubtitleView(context, assHandler), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        applyVideoPath()
        mainHandler.postDelayed(::sampleFrameRate, FRAME_RATE_SAMPLE_MS)
    }

    /**
     * Frames actually shown per second, from the decoder counters. "Dropped" only counts frames that were too late;
     * a stream the decoder skips or never outputs still plays at a moving position with 0 dropped
     * (a DVD rip: ~1 fps on screen, 0 dropped).
     */
    private fun sampleFrameRate() {
        val counters = player.videoDecoderCounters
        if (counters != null && player.isPlaying) {
            counters.ensureUpdated()
            val rendered = counters.renderedOutputBufferCount
            if (lastRendered >= 0) {
                val fps = (rendered - lastRendered) * 1000f / FRAME_RATE_SAMPLE_MS
                val skipped = counters.skippedOutputBufferCount + counters.skippedInputBufferCount
                Timber.d("Exo video: %.1f fps shown, skipped %d, dropped %d", fps, skipped - lastSkipped, counters.droppedBufferCount)
                _stats.update { it.copy(extra = it.extra + ("кадров/с на экране" to "%.1f".format(fps)) + ("пропущено декодером" to skipped.toString())) }
                lastSkipped = skipped
            }
            lastRendered = rendered
        } else {
            lastRendered = -1
        }
        mainHandler.postDelayed(::sampleFrameRate, FRAME_RATE_SAMPLE_MS)
    }

    override fun setVideoPathMode(mode: VideoPathSwitchable.Mode) {
        pathMode = mode
        applyVideoPath()
    }

    /**
     * On the Xiaomi TV the hardware video plane gets the TV's own "enhancement" (sharpening, contrast): compressed
     * SDR sources look visibly harsher than through the GPU (user A/B against VLC, 2026-10-12). AUTO therefore
     * composites SDR up to 1080p through the GPU, and keeps the video plane where it is required: HDR (the GPU
     * path is SDR only), >1080p (the UI surface is 1080p, the GPU path would throw away resolution) and interlaced
     * video (only the video plane deinterlaces; through the GPU the fields show as combing). Also frame rates that
     * don't divide 60 Hz (23.976/24/25/50): the video plane switches the panel to 100/120 Hz, the GPU path stays at
     * 60 Hz and judders (3:2). The user preferred the smooth video plane (2026-10-13). Other devices (phones, TVs
     * without such post-processing) always use the video plane: it is cheaper and keeps the panel's own refresh rate.
     */
    private fun gpuPathFor(request: LoadRequest?): Boolean = when (pathMode) {
        VideoPathSwitchable.Mode.GPU -> true
        VideoPathSwitchable.Mode.VIDEO_PLANE -> false
        VideoPathSwitchable.Mode.AUTO -> sdrThroughGpu && request?.mediaSource?.videoStream?.let { v ->
            v.videoRange != VideoRange.HDR && !v.isInterlaced && (v.width ?: 0) <= 1920 && (v.height ?: 0) <= 1088 &&
                fitsUiRefreshRate(v.realFrameRate ?: v.averageFrameRate)
        } == true
    }

    /** 30/60 fps (and 29.97/59.94) show evenly on the 60 Hz UI surface; unknown rate counts as fitting. */
    private fun fitsUiRefreshRate(fps: Float?): Boolean {
        if (fps == null || fps <= 0f) return true
        val cadence = UI_REFRESH_HZ / fps
        return cadence >= 0.99f && abs(cadence - cadence.roundToInt()) < 0.02f
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
        Timber.i("Exo video output: %s", label)
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
        startedAtMs = SystemClock.elapsedRealtime()
        player.setMediaItem(item, request.startPositionMs)
        player.prepare()
    }

    /**
     * Without a Dolby Vision decoder media3 falls back to the base-layer codec (HEVC/AVC/AV1) and its HDR10/HDR10+/HLG
     * metadata. See [ExoEngineProvider.USE_DOLBY_VISION_DECODER] for why that is the default.
     */
    private fun decoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        if (mimeType == MimeTypes.VIDEO_DOLBY_VISION && !useDolbyVisionDecoder && !requiresDolbyVisionDecoder) {
            return emptyList()
        }
        return MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
    }

    override val isSeekable get() = player.currentTimeline.isEmpty || player.isCurrentMediaItemSeekable

    override val bufferedPositionMs get() = player.bufferedPosition

    override fun setSubtitleStyle(style: SubtitleStyle): Boolean {
        val box = style.background == SubtitleStyle.Background.BOX
        subtitleView.setStyle(
            CaptionStyleCompat(
                style.color,
                if (box) BOX_COLOR else Color.TRANSPARENT,
                Color.TRANSPARENT,
                if (style.background == SubtitleStyle.Background.SHADOW) CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.BLACK,
                null,
            ),
        )
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.size.scale)
        return true
    }

    override fun setSubtitleLift(fraction: Float) {
        subtitleView.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION + fraction)
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) {
        startedAtMs = SystemClock.elapsedRealtime()
        player.seekTo(positionMs)
    }

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
        Timber.i(
            "Exo subtitle %d -> %s (text tracks: %s)",
            streamIndex,
            group?.getTrackFormat(0)?.let { "${it.id} ${it.sampleMimeType}" } ?: "не найдена",
            textGroups.joinToString { "${it.getTrackFormat(0).id}" },
        )
        trackSelector.parameters = builder.build()
    }

    override fun release() {
        mainHandler.removeCallbacksAndMessages(null)
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
        private const val EXTERNAL_PREFIX = "ext:"
        /** "Плашка" behind the text: black, ~72 % opaque as in the mockup. */
        private const val BOX_COLOR = 0xB8000000.toInt()
        private const val MIN_BUFFER_MB = 32
        private const val FRAME_RATE_SAMPLE_MS = 2000L
        /** Compose UI surface on the TV; the GPU path is shown at this rate. */
        private const val UI_REFRESH_HZ = 60f
        /** libass glyph/bitmap cache; the default 128 MB of native memory is a lot on a 2 GB TV. */
        private const val ASS_CACHE_MB = 32
        private const val MAX_BUFFER_MB = 64
    }
}

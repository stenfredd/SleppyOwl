package ru.stenfredd.sleepyowl.playback.vlc

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.net.toUri
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import ru.stenfredd.sleepyowl.playback.api.AudioDelayCapable
import ru.stenfredd.sleepyowl.playback.api.Codecs
import ru.stenfredd.sleepyowl.playback.api.DeviceCapabilities
import ru.stenfredd.sleepyowl.playback.api.EngineError
import ru.stenfredd.sleepyowl.playback.api.EngineEvent
import ru.stenfredd.sleepyowl.playback.api.EngineState
import ru.stenfredd.sleepyowl.playback.api.EngineStats
import ru.stenfredd.sleepyowl.playback.api.LoadRequest
import ru.stenfredd.sleepyowl.playback.api.PlaybackEngine
import ru.stenfredd.sleepyowl.playback.api.SubtitleDelayCapable
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyleable
import ru.stenfredd.sleepyowl.playback.api.embeddedOrdinal
import ru.stenfredd.sleepyowl.playback.api.stream
import ru.stenfredd.sleepyowl.playback.api.streams
import ru.stenfredd.sleepyowl.playback.api.videoStream
import timber.log.Timber

/**
 * libVLC engine. Owns its LibVLC instance so every playback starts clean and releases all native
 * objects in [release] (leaks here were what killed libVLC in the official Android TV client).
 * All network access goes through [LoopbackProxy]: libVLC itself only sees plain HTTP on 127.0.0.1.
 */
class VlcEngine(context: Context, httpClient: OkHttpClient, private val device: DeviceCapabilities) :
    PlaybackEngine, AudioDelayCapable, SubtitleDelayCapable, SubtitleStyleable {
    override val id = VlcEngineProvider.ID

    private val appContext = context.applicationContext
    private var subtitleStyle = SubtitleStyle()

    // Created on first use: libVLC reads the text subtitle look only from its instance options (see [setSubtitleStyle]).
    private val libVlcLazy = lazy {
        LibVLC(appContext, OPTIONS + styleOptions(subtitleStyle)).also {
            // The native library is loaded by the first LibVLC: version() only works after that.
            _stats.update { s -> s.copy(videoDecoder = "libVLC ${LibVLC.version()}") }
        }
    }
    private val libVlc by libVlcLazy
    private val playerLazy = lazy {
        Dialog.setCallbacks(libVlc, dialogCallbacks)
        MediaPlayer(libVlc).apply { setEventListener(::onEvent) }
    }
    private val player by playerLazy
    private val proxy = LoopbackProxy(httpClient)
    private var videoLayout: VLCVideoLayout? = null
    private var media: Media? = null
    private var viewsAttached = false

    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = _events.asSharedFlow()
    private val _stats = MutableStateFlow(EngineStats())
    override val stats = _stats.asStateFlow()

    override val positionMs get() = player.time.coerceAtLeast(0)
    override val durationMs get() = player.length.coerceAtLeast(0)

    private var request: LoadRequest? = null
    private var tracksApplied = false
    private var errorReported = false
    private var firstFrame = false
    private var lastDialogError: String? = null
    private var lastStatsAt = 0L
    private var released = false
    private var audioOutputMode: String? = null

    private val dialogCallbacks = object : Dialog.Callbacks {
        override fun onDisplay(dialog: Dialog.ErrorMessage) {
            lastDialogError = "${dialog.title}: ${dialog.text}"
            Timber.w("VLC error dialog: %s", lastDialogError)
            _stats.update { it.copy(extra = it.extra + ("vlc" to lastDialogError.orEmpty())) }
        }
        override fun onDisplay(dialog: Dialog.LoginDialog) = dialog.dismiss()
        override fun onDisplay(dialog: Dialog.QuestionDialog) {
            // E.g. an untrusted TLS certificate. Never accept silently.
            Timber.w("VLC question dialog dismissed: %s / %s", dialog.title, dialog.text)
            lastDialogError = "${dialog.title}: ${dialog.text}"
            dialog.dismiss()
        }
        override fun onDisplay(dialog: Dialog.ProgressDialog) = Unit
        override fun onCanceled(dialog: Dialog?) = Unit
        override fun onProgressUpdate(dialog: Dialog.ProgressDialog) = Unit
    }

    override fun attachVideo(container: FrameLayout) {
        detachVideo()
        // Must be created with the Activity context: libVLC's VideoHelper only computes the surface size
        // when it can resolve an Activity, otherwise the video is shown at its native size in a corner.
        val layout = VLCVideoLayout(container.context).apply {
            // libVLC sizes its surfaces once; re-apply when the container gets its real size.
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (viewsAttached && (r - l != or - ol || b - t != ob - ot)) player.updateVideoSurfaces()
            }
        }
        videoLayout = layout
        container.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        player.attachViews(layout, null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        viewsAttached = true
    }

    override fun detachVideo() {
        if (viewsAttached) {
            player.detachViews()
            viewsAttached = false
        }
        videoLayout?.let { (it.parent as? ViewGroup)?.removeView(it) }
        videoLayout = null
    }

    override fun load(request: LoadRequest) {
        this.request = request
        tracksApplied = false
        val video = request.mediaSource.videoStream
        // VLC is the software fallback: hardware-friendly streams go to ExoPlayer. Probing MediaCodec from libVLC
        // also takes ~45 s on MediaTek TVs (every getCapabilitiesForType call throws), so only use it where
        // software decoding cannot keep up.
        val needsHardware = (video?.width ?: 0) * (video?.height ?: 0) > 1920 * 1088
        val m = Media(libVlc, proxy.localUrl(request.url).toUri()).apply {
            setHWDecoderEnabled(needsHardware, false)
            if (request.startPositionMs > 0) addOption(":start-time=${request.startPositionMs / 1000.0}")
            if (request.startPositionMs > 0) startSeek(request.startPositionMs) else seekTargetMs = -1
            for (sub in request.externalSubtitles) addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, proxy.localUrl(sub.url)))
        }
        media?.release()
        media = m
        player.media = m
        applyAudioOutput(request.audioStreamIndex)
        _state.value = EngineState(EngineState.Phase.BUFFERING, false)
    }

    override fun play() {
        player.play()
        _state.update { it.copy(playWhenReady = true) }
    }

    override fun pause() {
        player.pause()
        _state.update { it.copy(playWhenReady = false) }
    }

    override fun seekTo(positionMs: Long) {
        startSeek(positionMs)
        player.setTime(positionMs, false)
    }

    /*
     * libVLC seeks in MPEG-PS (DVD rips) by an average-bitrate estimate and lands minutes off: asked for 30:00 it
     * played 26:25 of a DVD rip, and its clock is right about that (checked against ffmpeg frames),
     * so the user simply sees the wrong scene. After a seek (or a start position) we look where playback landed and
     * re-seek with the error subtracted, a few times at most. Precise containers land within tolerance at once.
     */
    private var seekTargetMs = -1L
    private var seekRequestMs = 0L
    private var seekIssuedAt = 0L
    private var seekAttempts = 0

    private fun startSeek(targetMs: Long) {
        seekTargetMs = targetMs
        seekRequestMs = targetMs
        seekIssuedAt = SystemClock.elapsedRealtime()
        seekAttempts = 0
    }

    private fun checkSeek() {
        if (seekTargetMs < 0) return
        val time = player.time
        val elapsed = SystemClock.elapsedRealtime() - seekIssuedAt
        if (time <= 0 || elapsed < SEEK_SETTLE_MS) return
        // Playback has been running for at most `elapsed` since the seek.
        val landed = time - elapsed.coerceAtMost(time)
        val error = seekTargetMs - landed
        if (error in -SEEK_TOLERANCE_MS..SEEK_TOLERANCE_MS || seekAttempts >= MAX_SEEK_CORRECTIONS) {
            if (seekAttempts > 0) Timber.i("VLC seek to %d s: landed at %d s after %d corrections", seekTargetMs / 1000, landed / 1000, seekAttempts)
            seekTargetMs = -1
            return
        }
        seekAttempts++
        seekRequestMs = (seekRequestMs + error).coerceAtLeast(0)
        seekIssuedAt = SystemClock.elapsedRealtime()
        Timber.i("VLC seek to %d s landed at %d s, retrying with %d s", seekTargetMs / 1000, landed / 1000, seekRequestMs / 1000)
        player.setTime(seekRequestMs, false)
    }

    override fun selectAudio(streamIndex: Int) {
        val req = request ?: return
        val ordinal = req.mediaSource.embeddedOrdinal(streamIndex, MediaStreamType.AUDIO) ?: return
        player.audioTracks?.filter { it.id >= 0 }?.getOrNull(ordinal)?.let { player.setAudioTrack(it.id) }
        applyAudioOutput(streamIndex)
    }

    /**
     * libVLC's default "pcm" device outputs multichannel PCM as is. On the Xiaomi TV 5.1 PCM is routed to a
     * DIRECT output that never plays (silence). So: formats the device takes undecoded go out as passthrough
     * (like ExoPlayer, the TV's own Dolby/DTS decoder handles them), everything else is downmixed to stereo.
     */
    private fun applyAudioOutput(streamIndex: Int?) {
        val source = request?.mediaSource ?: return
        val stream = source.stream(streamIndex)?.takeIf { it.type == MediaStreamType.AUDIO }
            ?: source.streams(MediaStreamType.AUDIO).let { all -> all.firstOrNull { it.isDefault } ?: all.firstOrNull() }
        val codec = outputAudioCodec(stream?.codec)
        val encoding = Codecs.passthroughEncoding(codec, stream?.profile.takeIf { codec == stream?.codec })
        val passthrough = encoding != null && encoding in device.passthroughEncodings
        val mode = if (passthrough) "encoded" else "stereo"
        if (mode == audioOutputMode) return
        audioOutputMode = mode
        if (passthrough) {
            player.forceAudioDigitalEncodings(device.passthroughEncodings.toIntArray())
        } else {
            player.setAudioOutputDevice(mode)
        }
        val label = if (passthrough) "passthrough ${Codecs.encodingName(encoding!!)}" else "PCM стерео (${codec ?: "?"})"
        Timber.i("VLC audio output: %s", label)
        _stats.update { it.copy(audioOutput = label) }
    }

    /**
     * The codec VLC will actually get. A transcode keeps the source audio only if it is in the URL's `AudioCodec`
     * list (stream copy), otherwise the server encodes to the first one: TrueHD transcoded comes out as AAC, and
     * forcing TrueHD passthrough for it would give silence.
     */
    private fun outputAudioCodec(sourceCodec: String?): String? {
        val req = request ?: return sourceCodec
        if (req.playMethod != PlayMethod.TRANSCODE) return sourceCodec
        val allowed = req.url.toHttpUrlOrNull()?.queryParameter("AudioCodec")?.split(',')?.filter(String::isNotBlank)
        if (allowed.isNullOrEmpty()) return sourceCodec
        return allowed.firstOrNull { it.equals(sourceCodec, ignoreCase = true) } ?: allowed.first()
    }

    /**
     * Subtitle asked for before its track existed: external files are slaves that libVLC opens after the main
     * input, so at the first ESAdded only the embedded tracks are there. Retried on every later ESAdded.
     */
    private var pendingSubtitle: Int? = null

    override fun selectSubtitle(streamIndex: Int?) {
        val req = request ?: return
        pendingSubtitle = null
        if (streamIndex == null) {
            player.setSpuTrack(-1)
            return
        }
        val tracks = player.spuTracks?.filter { it.id >= 0 }.orEmpty()
        val stream = req.mediaSource.mediaStreams.orEmpty().firstOrNull { it.index == streamIndex } ?: return
        val track = if (stream.isExternal || req.externalSubtitles.any { it.streamIndex == streamIndex }) {
            // Slaves are appended after the embedded tracks, in the order they were added.
            val externalPosition = req.externalSubtitles.indexOfFirst { it.streamIndex == streamIndex }
            val embeddedCount = req.mediaSource.mediaStreams.orEmpty().count { it.type == MediaStreamType.SUBTITLE && !it.isExternal }
            tracks.getOrNull(embeddedCount + externalPosition)
        } else {
            req.mediaSource.embeddedOrdinal(streamIndex, MediaStreamType.SUBTITLE)?.let(tracks::getOrNull)
        }
        if (track == null) {
            Timber.d("VLC subtitle %d not there yet (spu tracks: %s)", streamIndex, tracks.joinToString { "${it.id} ${it.name}" })
            pendingSubtitle = streamIndex
            return
        }
        Timber.i("VLC subtitle %d -> track %d «%s»", streamIndex, track.id, track.name)
        player.setSpuTrack(track.id)
    }

    override fun setAudioDelay(delayMs: Long) {
        player.setAudioDelay(delayMs * 1000)
    }

    override fun setSubtitleDelay(delayMs: Long) {
        player.setSpuDelay(delayMs * 1000)
    }

    /** Only before the first [attachVideo]/[load]: later the session restarts the engine with the new style. */
    override fun setSubtitleStyle(style: SubtitleStyle): Boolean {
        if (playerLazy.isInitialized()) return false
        subtitleStyle = style
        return true
    }

    override fun release() {
        if (released) return
        released = true
        if (!playerLazy.isInitialized()) {
            proxy.close()
            return
        }
        player.setEventListener(null)
        detachVideo()
        val m = media
        media = null
        // stop() joins libVLC's input threads; a stuck stream can block it indefinitely, which must never
        // freeze the UI. Native teardown therefore happens on its own thread.
        Thread({
            val started = SystemClock.elapsedRealtime()
            // Closing the proxy first fails VLC's pending reads, so stop() doesn't wait on the network.
            proxy.close()
            player.stop()
            Dialog.setCallbacks(libVlc, null)
            m?.release()
            player.release()
            libVlc.release()
            Timber.i("libVLC released in %d ms", SystemClock.elapsedRealtime() - started)
        }, "vlc-release").start()
    }

    private fun onEvent(event: MediaPlayer.Event) {
        if (released) return
        when (event.type) {
            MediaPlayer.Event.Buffering -> {
                val phase = if (event.buffering < 100f) EngineState.Phase.BUFFERING else EngineState.Phase.READY
                _state.update { it.copy(phase = phase) }
            }
            MediaPlayer.Event.Playing -> _state.update { it.copy(phase = EngineState.Phase.READY, playWhenReady = true) }
            MediaPlayer.Event.Paused -> _state.update { it.copy(playWhenReady = false) }
            MediaPlayer.Event.EndReached -> _state.update { it.copy(phase = EngineState.Phase.ENDED) }
            MediaPlayer.Event.Vout -> if (event.voutCount > 0 && !firstFrame) {
                firstFrame = true
                _events.tryEmit(EngineEvent.FirstFrameRendered)
                updateStats(force = true)
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> {
                applyInitialTracks()
                pendingSubtitle?.let(::selectSubtitle)
            }
            MediaPlayer.Event.TimeChanged -> {
                checkSeek()
                updateStats(force = false)
            }
            MediaPlayer.Event.EncounteredError -> reportError(
                EngineError(EngineError.Kind.DECODER, lastDialogError ?: "libVLC: EncounteredError"),
            )
        }
    }

    private fun applyInitialTracks() {
        val req = request ?: return
        if (tracksApplied || req.playMethod != PlayMethod.DIRECT_PLAY) return
        if (player.audioTracksCount <= 0) return
        tracksApplied = true
        req.audioStreamIndex?.let(::selectAudio)
        selectSubtitle(req.subtitleStreamIndex)
    }

    private fun updateStats(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatsAt < 1000) return
        lastStatsAt = now
        val video = player.currentVideoTrack
        val s = media?.stats
        // libVLC's decodedVideo counts frames twice and inputBitrate averages in the prefetch: use deltas instead.
        val rates = s?.let { st ->
            val prev = lastStatsSample
            lastStatsSample = StatsSample(now, st.displayedPictures, st.readBytes)
            prev?.takeIf { now > it.atMs && st.displayedPictures >= it.displayed }?.let { p ->
                val sec = (now - p.atMs) / 1000.0
                // readBytes is an Int that wraps after 2 GB; the Int difference is still right.
                (st.displayedPictures - p.displayed) / sec to (st.readBytes - p.readBytes).toUInt().toDouble() * 8 / sec / 1e6
            }
        }
        _stats.update {
            it.copy(
                videoFormat = video?.let { v ->
                    "${v.codec} ${v.width}x${v.height} " + if (v.frameRateDen > 0) "%.3ffps".format(v.frameRateNum.toDouble() / v.frameRateDen) else ""
                },
                droppedFrames = s?.lostPictures ?: it.droppedFrames,
                extra = it.extra + listOfNotNull(
                    s?.let { st -> "кадров показано" to "${st.displayedPictures}" },
                    rates?.let { (fps, _) -> "кадров/с на экране" to "%.1f".format(fps) },
                    rates?.let { (_, mbps) -> "чтение входа" to "%.1f Мбит/с".format(mbps) },
                    "прокси" to "${proxy.activeConnections} соед., %.1f МБ".format(proxy.bytesTransferred / 1048576.0),
                ),
            )
        }
    }

    private class StatsSample(val atMs: Long, val displayed: Int, val readBytes: Int)
    private var lastStatsSample: StatsSample? = null

    private fun reportError(error: EngineError) {
        if (errorReported) return
        errorReported = true
        Timber.w("VLC error: %s", error.message)
        _events.tryEmit(EngineEvent.Error(error))
    }

    private companion object {
        /** freetype's size divisor: smaller = bigger text, 16 is libVLC's default. */
        fun styleOptions(style: SubtitleStyle): List<String> = listOf(
            "--freetype-rel-fontsize=" + when (style.size) {
                SubtitleStyle.Size.S -> 20
                SubtitleStyle.Size.M -> 16
                SubtitleStyle.Size.L -> 13
                SubtitleStyle.Size.XL -> 10
            },
            "--freetype-color=${style.color and 0xFFFFFF}",
            "--freetype-background-opacity=${if (style.background == SubtitleStyle.Background.BOX) 184 else 0}",
            "--freetype-background-color=0",
            "--freetype-shadow-opacity=${if (style.background == SubtitleStyle.Background.SHADOW) 160 else 0}",
            // libVLC's default outline keeps text readable on any picture; "Нет" means plain text, as in ExoPlayer.
            "--freetype-outline-thickness=${if (style.background == SubtitleStyle.Background.NONE) 0 else 4}",
        )

        const val SEEK_SETTLE_MS = 1500L
        const val SEEK_TOLERANCE_MS = 5000L
        const val MAX_SEEK_CORRECTIONS = 3
        val OPTIONS = arrayListOf(
            "--network-caching=3000",
            "--audio-time-stretch",
            "--no-sub-autodetect-file",
            "--deinterlace=-1",
            "--deinterlace-mode=yadif",
            "-v",
        )
    }
}

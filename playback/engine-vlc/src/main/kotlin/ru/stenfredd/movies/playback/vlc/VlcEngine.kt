package ru.stenfredd.movies.playback.vlc

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
import okhttp3.OkHttpClient
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import ru.stenfredd.movies.playback.api.AudioDelayCapable
import ru.stenfredd.movies.playback.api.Codecs
import ru.stenfredd.movies.playback.api.DeviceCapabilities
import ru.stenfredd.movies.playback.api.EngineError
import ru.stenfredd.movies.playback.api.EngineEvent
import ru.stenfredd.movies.playback.api.EngineState
import ru.stenfredd.movies.playback.api.EngineStats
import ru.stenfredd.movies.playback.api.LoadRequest
import ru.stenfredd.movies.playback.api.PlaybackEngine
import ru.stenfredd.movies.playback.api.SubtitleDelayCapable
import ru.stenfredd.movies.playback.api.embeddedOrdinal
import ru.stenfredd.movies.playback.api.stream
import ru.stenfredd.movies.playback.api.streams
import ru.stenfredd.movies.playback.api.videoStream
import timber.log.Timber

/**
 * libVLC engine. Owns its LibVLC instance so every playback starts clean and releases all native
 * objects in [release] (leaks here were what killed libVLC in the official Android TV client).
 * All network access goes through [LoopbackProxy]: libVLC itself only sees plain HTTP on 127.0.0.1.
 */
class VlcEngine(context: Context, httpClient: OkHttpClient, private val device: DeviceCapabilities) : PlaybackEngine, AudioDelayCapable, SubtitleDelayCapable {
    override val id = VlcEngineProvider.ID

    private val libVlc = LibVLC(context.applicationContext, OPTIONS)
    private val player = MediaPlayer(libVlc)
    private val proxy = LoopbackProxy(httpClient)
    private var videoLayout: VLCVideoLayout? = null
    private var media: Media? = null
    private var viewsAttached = false

    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = _events.asSharedFlow()
    private val _stats = MutableStateFlow(EngineStats(videoDecoder = "libVLC ${LibVLC.version()}"))
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

    init {
        Dialog.setCallbacks(libVlc, dialogCallbacks)
        player.setEventListener(::onEvent)
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
        player.setTime(positionMs, false)
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
        val encoding = Codecs.passthroughEncoding(stream?.codec, stream?.profile)
        val passthrough = encoding != null && encoding in device.passthroughEncodings
        val mode = if (passthrough) "encoded" else "stereo"
        if (mode == audioOutputMode) return
        audioOutputMode = mode
        if (passthrough) {
            player.forceAudioDigitalEncodings(device.passthroughEncodings.toIntArray())
        } else {
            player.setAudioOutputDevice(mode)
        }
        val label = if (passthrough) "passthrough ${Codecs.encodingName(encoding!!)}" else "PCM стерео (${stream?.codec ?: "?"})"
        Timber.i("VLC audio output: %s", label)
        _stats.update { it.copy(audioOutput = label) }
    }

    override fun selectSubtitle(streamIndex: Int?) {
        val req = request ?: return
        if (streamIndex == null) {
            player.setSpuTrack(-1)
            return
        }
        val tracks = player.spuTracks?.filter { it.id >= 0 } ?: return
        val stream = req.mediaSource.mediaStreams.orEmpty().firstOrNull { it.index == streamIndex } ?: return
        val track = if (stream.isExternal || req.externalSubtitles.any { it.streamIndex == streamIndex }) {
            // Slaves are appended after the embedded tracks, in the order they were added.
            val externalPosition = req.externalSubtitles.indexOfFirst { it.streamIndex == streamIndex }
            val embeddedCount = req.mediaSource.mediaStreams.orEmpty().count { it.type == MediaStreamType.SUBTITLE && !it.isExternal }
            tracks.getOrNull(embeddedCount + externalPosition)
        } else {
            req.mediaSource.embeddedOrdinal(streamIndex, MediaStreamType.SUBTITLE)?.let(tracks::getOrNull)
        }
        track?.let { player.setSpuTrack(it.id) }
    }

    override fun setAudioDelay(delayMs: Long) {
        player.setAudioDelay(delayMs * 1000)
    }

    override fun setSubtitleDelay(delayMs: Long) {
        player.setSpuDelay(delayMs * 1000)
    }

    override fun release() {
        if (released) return
        released = true
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
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> applyInitialTracks()
            MediaPlayer.Event.TimeChanged -> updateStats(force = false)
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
        _stats.update {
            it.copy(
                videoFormat = video?.let { v ->
                    "${v.codec} ${v.width}x${v.height} " + if (v.frameRateDen > 0) "%.3ffps".format(v.frameRateNum.toDouble() / v.frameRateDen) else ""
                },
                droppedFrames = s?.lostPictures ?: it.droppedFrames,
                extra = it.extra + listOfNotNull(
                    s?.let { st -> "кадры показано/декод." to "${st.displayedPictures}/${st.decodedVideo}" },
                    s?.let { st -> "битрейт входа" to "%.1f Мбит/с".format(st.inputBitrate * 8000 / 1000) },
                    "прокси" to "${proxy.activeConnections} соед., %.1f МБ".format(proxy.bytesTransferred / 1048576.0),
                ),
            )
        }
    }

    private fun reportError(error: EngineError) {
        if (errorReported) return
        errorReported = true
        Timber.w("VLC error: %s", error.message)
        _events.tryEmit(EngineEvent.Error(error))
    }

    private companion object {
        val OPTIONS = arrayListOf(
            "--network-caching=3000",
            "--audio-time-stretch",
            "--no-sub-autodetect-file",
            "--deinterlace=-1",
            "--deinterlace-mode=yadif",
            "-vv",
        )
    }
}

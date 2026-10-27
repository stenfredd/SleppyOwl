package ru.stenfredd.sleepyowl.playback.mpv

import com.sun.jna.Pointer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.common.monotonicMs
import ru.stenfredd.sleepyowl.playback.api.AudioDelayCapable
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
import ru.stenfredd.sleepyowl.playback.api.videoStream
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * libmpv engine of the desktop (docs/desktop.md). One mpv core per playback, without a window of its own
 * (`vo=libmpv`): the picture comes out of [video] and is shown by [MpvVideoSurface].
 *
 * Commands go to mpv asynchronously, so the UI thread never waits on the core; mpv's events and observed properties are
 * read on the engine's own thread and land in volatile fields and state flows.
 */
class MpvEngine internal constructor() : PlaybackEngine, AudioDelayCapable, SubtitleDelayCapable, SubtitleStyleable {
    override val id = MpvEngineProvider.ID

    private val lib = LibMpv.INSTANCE
    private val handle: Pointer = lib.mpv_create() ?: error("mpv_create returned NULL")

    val video = MpvVideo(handle, onReady = ::onRenderReady, onFirstFrame = ::onFirstFrame, onFailure = { t ->
        reportError(EngineError(EngineError.Kind.NO_VIDEO_OUTPUT, "mpv: нет вывода видео (${t.message})", t))
    })

    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = _events.asSharedFlow()
    private val _stats = MutableStateFlow(EngineStats(videoDecoder = "mpv"))
    override val stats = _stats.asStateFlow()

    @Volatile private var position = 0L
    @Volatile private var duration = 0L
    @Volatile private var cacheEnd = -1L
    @Volatile private var seekable = true
    override val positionMs get() = position
    override val durationMs get() = duration
    override val isSeekable get() = seekable
    override val bufferedPositionMs get() = cacheEnd

    private val lock = Object()
    private var renderReady = false
    /** loadfile and the options before it, waiting for the render context. */
    private var pendingLoad: List<Array<String>>? = null
    @Volatile private var request: LoadRequest? = null
    @Volatile private var fileLoaded = false
    /** A seek asked for before the file was open: done once it is. */
    @Volatile private var pendingSeekMs = -1L
    @Volatile private var tracks: List<Track> = emptyList()
    private var audioIndex: Int? = null
    private var subtitleIndex: Int? = null

    private var paused = true
    private var pausedForCache = false
    private var seeking = false
    private var eof = false
    private val props = HashMap<String, String>()
    private var lastStatsAt = 0L

    @Volatile private var running = true
    @Volatile private var released = false
    private var errorReported = false
    private val eventThread: Thread

    init {
        // SLEEPYOWL_MPV_OPTIONS="mute=yes,hwdec=no": extra options for checks and measurements.
        val extra = System.getenv("SLEEPYOWL_MPV_OPTIONS").orEmpty().split(',').mapNotNull { o ->
            o.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
        }
        val audio = listOf("volume" to "$startVolume", "mute" to if (startMuted) "yes" else "no")
        for ((name, value) in OPTIONS + audio + extra) {
            val rc = lib.mpv_set_option_string(handle, name, value)
            if (rc < 0) Log.w("mpv option %s=%s: %s", name, value, lib.mpv_error_string(rc))
        }
        lib.mpv_request_log_messages(handle, System.getenv("SLEEPYOWL_MPV_LOG") ?: "warn")
        val rc = lib.mpv_initialize(handle)
        check(rc >= 0) { "mpv_initialize: ${lib.mpv_error_string(rc)}" }
        for ((name, format) in OBSERVED) lib.mpv_observe_property(handle, 0, name, format)
        eventThread = thread(name = "mpv-events", isDaemon = true) { eventLoop() }
        video.start()
    }

    override fun load(request: LoadRequest) {
        this.request = request
        audioIndex = request.audioStreamIndex
        subtitleIndex = request.subtitleStreamIndex
        val direct = request.playMethod == PlayMethod.DIRECT_PLAY
        val commands = mutableListOf<Array<String>>()
        commands += arrayOf("set", "start", if (request.startPositionMs > 0) seconds(request.startPositionMs) else "none")
        commands += arrayOf("set", "deinterlace", if (request.mediaSource.videoStream?.isInterlaced == true) "yes" else "no")
        // Until the file's tracks are known, embedded tracks by their place among the same type; checked on file-loaded.
        val aid = request.audioStreamIndex?.takeIf { direct }?.let { request.mediaSource.embeddedOrdinal(it, MediaStreamType.AUDIO) }
        commands += arrayOf("set", "aid", aid?.let { "${it + 1}" } ?: "auto")
        val sid = request.subtitleStreamIndex?.takeIf { direct }?.let { request.mediaSource.embeddedOrdinal(it, MediaStreamType.SUBTITLE) }
        commands += arrayOf("set", "sid", sid?.let { "${it + 1}" } ?: "no")
        commands += arrayOf("change-list", "sub-files", "clr", "")
        // One at a time: a list value would be split at ':' (the path list separator), and URLs are full of them.
        for (sub in request.externalSubtitles) commands += arrayOf("change-list", "sub-files", "append", sub.url)
        commands += arrayOf("loadfile", request.url)
        _state.value = EngineState(EngineState.Phase.BUFFERING, false)
        val now = synchronized(lock) {
            if (!renderReady) pendingLoad = commands
            renderReady
        }
        if (now) commands.forEach(::command)
    }

    /** On the render thread: mpv can open its video output now. */
    private fun onRenderReady() {
        val commands = synchronized(lock) {
            renderReady = true
            pendingLoad.also { pendingLoad = null }
        }
        if (!released) commands?.forEach(::command)
    }

    private fun onFirstFrame() {
        if (released) return
        Log.i("mpv: first frame (%s, %s)", video.renderSize, video.glInfo)
        _events.tryEmit(EngineEvent.FirstFrameRendered)
    }

    override fun play() {
        command("set", "pause", "no")
        _state.update { it.copy(playWhenReady = true) }
    }

    override fun pause() {
        command("set", "pause", "yes")
        _state.update { it.copy(playWhenReady = false) }
    }

    override fun seekTo(positionMs: Long) {
        position = positionMs
        if (!fileLoaded) {
            pendingSeekMs = positionMs
            return
        }
        // hr-seek=yes: absolute seeks land on the exact frame.
        command("seek", seconds(positionMs), "absolute")
    }

    override fun selectAudio(streamIndex: Int) {
        audioIndex = streamIndex
        if (fileLoaded) applyTracks()
    }

    override fun selectSubtitle(streamIndex: Int?) {
        subtitleIndex = streamIndex
        if (fileLoaded) applyTracks()
    }

    /**
     * Jellyfin's stream index → mpv's track id. Embedded tracks carry ffmpeg's stream index (`ff-index`), which is
     * Jellyfin's index too (both come from ffprobe) after the external files; external subtitles are found by their URL. In a transcode the
     * server has already picked the audio, so only subtitles are chosen.
     */
    private fun applyTracks() {
        val req = request ?: return
        val list = tracks
        val direct = req.playMethod == PlayMethod.DIRECT_PLAY
        val aid = if (direct) audioIndex?.let { index ->
            trackId(req, list, index, "audio").also { if (it == null) Log.w("mpv: audio stream %d not found among %s", index, list) }
        } else {
            null
        }
        aid?.let { command("set", "aid", "$it") }
        val sub = subtitleIndex
        val sid = when {
            sub == null -> "no"
            else -> trackId(req, list, sub, "sub")?.toString().also { if (it == null) Log.w("mpv: subtitle stream %d not found among %s", sub, list) }
        }
        sid?.let { command("set", "sid", it) }
        Log.i("mpv: audio %s → aid %s, subtitles %s → sid %s", audioIndex, aid, sub, sid)
    }

    private fun trackId(req: LoadRequest, list: List<Track>, streamIndex: Int, type: String): Int? {
        val external = req.externalSubtitles.indexOfFirst { it.streamIndex == streamIndex }
        if (external >= 0) {
            val url = req.externalSubtitles[external].url
            return list.firstOrNull { it.external && it.filename == url }?.id
                ?: list.filter { it.type == type && it.external }.getOrNull(external)?.id
        }
        if (req.playMethod != PlayMethod.DIRECT_PLAY) return null
        // Jellyfin numbers external files first, then the container's streams by ffmpeg's index.
        val ffIndex = streamIndex - req.mediaSource.mediaStreams.orEmpty().count { it.isExternal && it.index < streamIndex }
        list.firstOrNull { it.type == type && !it.external && it.ffIndex == ffIndex }?.let { return it.id }
        val mediaType = if (type == "audio") MediaStreamType.AUDIO else MediaStreamType.SUBTITLE
        val ordinal = req.mediaSource.embeddedOrdinal(streamIndex, mediaType) ?: return null
        return list.filter { it.type == type && !it.external }.getOrNull(ordinal)?.id
    }

    /** 0…[MAX_VOLUME] %; the player keeps it in its settings and sets [startVolume] for the next engine. */
    fun setVolume(percent: Int) = command("set", "volume", "${percent.coerceIn(0, MAX_VOLUME)}")

    fun setMuted(muted: Boolean) = command("set", "mute", if (muted) "yes" else "no")

    override fun setAudioDelay(delayMs: Long) = command("set", "audio-delay", seconds(delayMs))

    override fun setSubtitleDelay(delayMs: Long) = command("set", "sub-delay", seconds(delayMs))

    /** Text subtitles only: ASS keeps its own styles except for the size (`sub-ass-override=scale`). */
    override fun setSubtitleStyle(style: SubtitleStyle): Boolean {
        command("set", "sub-scale", "%.2f".format(Locale.ROOT, style.size.scale))
        command("set", "sub-color", color(style.color))
        when (style.background) {
            SubtitleStyle.Background.NONE -> {
                command("set", "sub-border-style", "outline-and-shadow")
                command("set", "sub-outline-size", "0")
                command("set", "sub-shadow-offset", "0")
            }
            SubtitleStyle.Background.SHADOW -> {
                command("set", "sub-border-style", "outline-and-shadow")
                command("set", "sub-outline-size", "1.65")
                command("set", "sub-outline-color", "#FF000000")
                command("set", "sub-shadow-offset", "1.5")
                command("set", "sub-back-color", "#A0000000")
            }
            SubtitleStyle.Background.BOX -> {
                // The box of opaque-box is drawn in the outline colour.
                command("set", "sub-border-style", "opaque-box")
                command("set", "sub-outline-size", "4")
                command("set", "sub-outline-color", "#B8000000")
                command("set", "sub-shadow-offset", "0")
            }
        }
        return true
    }

    /**
     * `sub-pos` (100 = the bottom, % of the picture's height): unlike `sub-margin-y`, it moves ASS subtitles too — libass
     * leaves the signs placed by the script where they are.
     */
    override fun setSubtitleLift(fraction: Float) {
        command("set", "sub-pos", "${(100 - fraction * 100).roundToInt().coerceIn(0, 100)}")
    }

    override fun release() {
        if (released) return
        released = true
        // Teardown waits for the render thread and for mpv's own threads (a stalled network read can hold them):
        // never on the UI thread.
        thread(name = "mpv-release") {
            val started = monotonicMs()
            running = false
            lib.mpv_wakeup(handle)
            eventThread.join(2000)
            video.stop()
            lib.mpv_terminate_destroy(handle)
            val frames = video.rendered.get()
            Log.i(
                "mpv released in %d ms; %d frames drawn, %.1f ms render, %.1f ms copy on average, %d replaced before shown",
                monotonicMs() - started, frames, video.renderNs.get() / 1e6 / frames.coerceAtLeast(1),
                video.readbackNs.get() / 1e6 / frames.coerceAtLeast(1), video.frames.lost,
            )
        }
    }

    private fun command(vararg args: String) {
        if (released) return
        lib.mpv_command_async(handle, 0, arrayOf(*args, null))
    }

    private fun eventLoop() {
        while (running) {
            val ev = lib.mpv_wait_event(handle, 0.5)
            if (!running) return
            when (ev.getInt(0)) {
                LibMpv.EVENT_NONE -> Unit
                LibMpv.EVENT_SHUTDOWN -> return
                LibMpv.EVENT_LOG_MESSAGE -> {
                    val msg = ev.getPointer(16)
                    val prefix = msg.getPointer(0).getString(0)
                    val level = msg.getPointer(8).getString(0)
                    val text = msg.getPointer(16).getString(0, "UTF-8").trimEnd()
                    if (level == "error" || level == "fatal") lastError = "$prefix: $text"
                    Log.i("mpv [%s] %s: %s", prefix, level, text)
                }
                LibMpv.EVENT_PROPERTY_CHANGE -> onProperty(ev.getPointer(16))
                LibMpv.EVENT_FILE_LOADED -> onFileLoaded()
                LibMpv.EVENT_END_FILE -> {
                    val data = ev.getPointer(16)
                    if (data.getInt(0) == LibMpv.END_FILE_REASON_ERROR) onLoadError(data.getInt(4))
                }
            }
            val now = monotonicMs()
            if (now - lastStatsAt >= 1000) {
                lastStatsAt = now
                publishStats()
            }
        }
    }

    private var lastError: String? = null

    private fun onFileLoaded() {
        tracks = readTracks()
        Log.i("mpv: file loaded, tracks %s", tracks)
        fileLoaded = true
        applyTracks()
        val seek = pendingSeekMs
        if (seek >= 0) {
            pendingSeekMs = -1
            command("seek", seconds(seek), "absolute")
        }
        publishState()
    }

    private fun onLoadError(code: Int) {
        val kind = when (code) {
            LibMpv.ERROR_LOADING_FAILED -> EngineError.Kind.NETWORK
            LibMpv.ERROR_UNKNOWN_FORMAT, LibMpv.ERROR_NOTHING_TO_PLAY -> EngineError.Kind.FORMAT
            LibMpv.ERROR_AO_INIT_FAILED -> EngineError.Kind.AUDIO_OUTPUT
            LibMpv.ERROR_VO_INIT_FAILED -> EngineError.Kind.NO_VIDEO_OUTPUT
            else -> EngineError.Kind.UNKNOWN
        }
        reportError(EngineError(kind, "mpv: ${lib.mpv_error_string(code)}" + (lastError?.let { " ($it)" } ?: "")))
    }

    /** Synchronous reads, but on the event thread: the core never waits for it. */
    private fun readTracks(): List<Track> {
        val count = getString("track-list/count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val id = getString("track-list/$i/id")?.toIntOrNull() ?: return@mapNotNull null
            Track(
                id = id,
                type = getString("track-list/$i/type").orEmpty(),
                ffIndex = getString("track-list/$i/ff-index")?.toIntOrNull(),
                external = getString("track-list/$i/external") == "yes",
                filename = getString("track-list/$i/external-filename"),
            )
        }
    }

    private fun getString(name: String): String? {
        val p = lib.mpv_get_property_string(handle, name) ?: return null
        return try {
            p.getString(0, "UTF-8")
        } finally {
            lib.mpv_free(p)
        }
    }

    private fun onProperty(p: Pointer) {
        val name = p.getPointer(0).getString(0)
        val format = p.getInt(8)
        val data = p.getPointer(16)
        val value: Any? = when {
            data == null || format == LibMpv.FORMAT_NONE -> null
            format == LibMpv.FORMAT_DOUBLE -> data.getDouble(0)
            format == LibMpv.FORMAT_FLAG -> data.getInt(0) != 0
            format == LibMpv.FORMAT_INT64 -> data.getLong(0)
            format == LibMpv.FORMAT_STRING -> data.getPointer(0)?.getString(0, "UTF-8")
            else -> null
        }
        when (name) {
            "pause" -> { paused = value == true; publishState() }
            "paused-for-cache" -> { pausedForCache = value == true; publishState() }
            "seeking" -> { seeking = value == true; publishState() }
            "eof-reached" -> { eof = value == true; publishState() }
            "time-pos" -> (value as? Double)?.let { position = (it * 1000).toLong() }
            "duration" -> duration = ((value as? Double) ?: 0.0).times(1000).toLong()
            "demuxer-cache-time" -> cacheEnd = (value as? Double)?.times(1000)?.toLong() ?: -1
            "seekable" -> seekable = value != false
            "dwidth", "dheight" -> {
                props[name] = value?.toString().orEmpty()
                video.setVideoSize(props["dwidth"]?.toIntOrNull() ?: 0, props["dheight"]?.toIntOrNull() ?: 0)
            }
            else -> props[name] = when (value) {
                null -> ""
                is Double -> "%.3f".format(Locale.ROOT, value)
                else -> value.toString()
            }
        }
    }

    private fun publishState() {
        val phase = when {
            eof -> EngineState.Phase.ENDED
            !fileLoaded || pausedForCache || seeking -> EngineState.Phase.BUFFERING
            else -> EngineState.Phase.READY
        }
        _state.update { it.copy(phase = phase, playWhenReady = !paused) }
    }

    private fun publishStats() {
        fun p(name: String) = props[name]?.takeIf { it.isNotEmpty() }
        val frames = video.rendered.get().coerceAtLeast(1)
        _stats.value = EngineStats(
            videoDecoder = "mpv · " + (p("hwdec-current")?.takeIf { it != "no" }?.let { "аппаратно ($it)" } ?: "программно"),
            videoFormat = listOfNotNull(
                p("video-codec"),
                p("video-params/w")?.let { w -> "${w}x${p("video-params/h")}" },
                p("video-params/pixelformat"),
                p("container-fps")?.let { "${it.trimEnd('0').trimEnd('.')} fps" },
            ).joinToString(" ").ifEmpty { null },
            audioDecoder = p("audio-codec-name"),
            audioFormat = listOfNotNull(
                p("audio-params/channel-count")?.let { "$it кан." },
                p("audio-params/samplerate")?.let { "$it Гц" },
            ).joinToString(", ").ifEmpty { null },
            audioOutput = p("current-ao"),
            droppedFrames = (p("frame-drop-count")?.toIntOrNull() ?: 0) + (p("decoder-frame-drop-count")?.toIntOrNull() ?: 0),
            extra = listOf(
                "GPU" to video.glInfo,
                "кадр в памяти" to video.renderSize,
                "отрисовка / копия" to "%.1f / %.1f мс".format(Locale.ROOT, video.renderNs.get() / 1e6 / frames, video.readbackNs.get() / 1e6 / frames),
                "потеряно в окне" to "${video.frames.lost}",
            ).toMap(),
        )
    }

    private fun reportError(error: EngineError) {
        if (errorReported || released) return
        errorReported = true
        Log.w("mpv error: %s", error.message)
        _events.tryEmit(EngineEvent.Error(error))
    }

    internal data class Track(val id: Int, val type: String, val ffIndex: Int?, val external: Boolean, val filename: String?) {
        override fun toString() = "$type#$id" + (ffIndex?.let { "/ff$it" } ?: "") + if (external) "/ext" else ""
    }

    companion object {
        /** mpv's own default `volume-max`. */
        const val MAX_VOLUME = 130

        /** Volume and mute an engine starts with (from the first sample on: no loud blip before the player sets them). */
        @Volatile var startVolume = 100
        @Volatile var startMuted = false


        private fun seconds(ms: Long) = "%.3f".format(Locale.ROOT, ms / 1000.0)

        /** ARGB → mpv's `#AARRGGBB`. */
        private fun color(argb: Int) = "#%08X".format(argb)

        private val OPTIONS = listOf(
            "vo" to "libmpv",
            "hwdec" to "auto-safe",
            "config" to "no",
            "terminal" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "osc" to "no",
            "ytdl" to "no",
            "idle" to "yes",
            "keep-open" to "yes",
            "hr-seek" to "yes",
            "cache" to "yes",
            "demuxer-max-bytes" to "150MiB",
            "demuxer-max-back-bytes" to "50MiB",
            "audio-channels" to "auto-safe",
            "ao" to "pipewire,pulse,alsa",
            "audio-client-name" to "SleepyOwl",
            "tone-mapping" to "auto",
            "sub-auto" to "no",
            "sub-ass-override" to "scale",
            "user-agent" to "SleepyOwl",
        )

        private val OBSERVED = listOf(
            "pause" to LibMpv.FORMAT_FLAG,
            "paused-for-cache" to LibMpv.FORMAT_FLAG,
            "seeking" to LibMpv.FORMAT_FLAG,
            "eof-reached" to LibMpv.FORMAT_FLAG,
            "seekable" to LibMpv.FORMAT_FLAG,
            "time-pos" to LibMpv.FORMAT_DOUBLE,
            "duration" to LibMpv.FORMAT_DOUBLE,
            "demuxer-cache-time" to LibMpv.FORMAT_DOUBLE,
            "dwidth" to LibMpv.FORMAT_INT64,
            "dheight" to LibMpv.FORMAT_INT64,
            "hwdec-current" to LibMpv.FORMAT_STRING,
            "video-codec" to LibMpv.FORMAT_STRING,
            "video-params/w" to LibMpv.FORMAT_INT64,
            "video-params/h" to LibMpv.FORMAT_INT64,
            "video-params/pixelformat" to LibMpv.FORMAT_STRING,
            "container-fps" to LibMpv.FORMAT_DOUBLE,
            "audio-codec-name" to LibMpv.FORMAT_STRING,
            "audio-params/channel-count" to LibMpv.FORMAT_INT64,
            "audio-params/samplerate" to LibMpv.FORMAT_INT64,
            "current-ao" to LibMpv.FORMAT_STRING,
            "frame-drop-count" to LibMpv.FORMAT_INT64,
            "decoder-frame-drop-count" to LibMpv.FORMAT_INT64,
        )
    }
}

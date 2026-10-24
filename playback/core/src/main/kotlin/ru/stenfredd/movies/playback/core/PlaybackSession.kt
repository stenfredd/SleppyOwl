package ru.stenfredd.movies.playback.core

import android.net.Uri
import android.os.SystemClock
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import ru.stenfredd.movies.playback.api.DolbyVisionSwitchable
import ru.stenfredd.movies.playback.api.EngineError
import ru.stenfredd.movies.playback.api.EngineEvent
import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.EngineProvider
import ru.stenfredd.movies.playback.api.EngineState
import ru.stenfredd.movies.playback.api.EngineStats
import ru.stenfredd.movies.playback.api.ExternalSubtitle
import ru.stenfredd.movies.playback.api.LoadRequest
import ru.stenfredd.movies.playback.api.PlaybackEngine
import ru.stenfredd.movies.playback.api.StreamSelection
import ru.stenfredd.movies.playback.api.SubtitleStyle
import ru.stenfredd.movies.playback.api.SubtitleStyleable
import ru.stenfredd.movies.playback.api.stream
import ru.stenfredd.movies.playback.api.streams
import ru.stenfredd.movies.playback.api.Verdict
import ru.stenfredd.movies.playback.api.VideoPathSwitchable
import ru.stenfredd.movies.playback.api.videoStream
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class PlaybackUiState(
    val title: String = "",
    val loading: Boolean = true,
    val engine: EngineId? = null,
    val engineReason: String = "",
    val engineState: EngineState = EngineState(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Loaded up to here, or -1 if the engine doesn't tell. */
    val bufferedMs: Long = -1,
    /** The item being played (an episode, a movie) and the series of an episode. */
    val itemId: UUID? = null,
    val seriesId: UUID? = null,
    /** Quality cap chosen in the player, bit/s; null = original. */
    val maxBitrate: Int? = null,
    /** The engine was picked by the user ("Дополнительно"), not by the selector. */
    val manualEngine: Boolean = false,
    val playMethod: PlayMethod? = null,
    val transcodeReasons: String? = null,
    val source: MediaSourceInfo? = null,
    val selection: StreamSelection = StreamSelection(null, null),
    val ranking: List<Pair<EngineId, Verdict>> = emptyList(),
    val stats: EngineStats = EngineStats(),
    val history: List<String> = emptyList(),
    val error: String? = null,
)

class PlaybackSessionFactory @Inject constructor(
    private val session: JellyfinSession,
    private val selector: EngineSelector,
    private val probe: DeviceCapabilitiesProbe,
    private val prefs: PlaybackPreferences,
) {
    fun create(scope: CoroutineScope) = PlaybackSession(session, selector, probe, prefs, scope)
}

/**
 * One playback of one item: picks the engine, asks the server for a stream with that engine's
 * profile, reports progress, and on an engine failure switches to the next engine at the same
 * position (falling back to a server transcode as the last resort).
 *
 * Must be driven from the main thread.
 */
class PlaybackSession(
    private val session: JellyfinSession,
    private val selector: EngineSelector,
    private val probe: DeviceCapabilitiesProbe,
    private val prefs: PlaybackPreferences,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val api get() = session.api
    private val device get() = probe.capabilities

    private var engine: PlaybackEngine? = null

    /** For optional engine capabilities (`as? X`) in the UI. */
    val activeEngine: PlaybackEngine? get() = engine
    private var container: FrameLayout? = null
    private var generation = 0
    private var engineJobs = mutableListOf<Job>()

    private lateinit var item: BaseItemDto
    private lateinit var source: MediaSourceInfo
    private var selection = StreamSelection(null, null)
    private var ranking: List<RankedEngine> = emptyList()
    private val tried = mutableSetOf<EngineId>()
    private var transcodeTried = false
    private var nextVideoPath: VideoPathSwitchable.Mode? = null
    private var nextDolbyVision: Boolean? = null
    /** Set by [switchToAuto]: the next engine gets the default decoder choice instead of the old engine's. */
    private var defaultDolbyVision = false
    /** Settings screen defaults, read at [start]. */
    private var defaults = PlaybackDefaults()
    /** The user picked the engine: the stall watchdog only logs instead of switching away from their choice. */
    private var manualEngine = false
    private var lastSeekAt = 0L
    private var maxBitrate: Int? = null
    /** [setMaxBitrate] was called before [start] (quality kept from the previous episode): the default doesn't apply. */
    private var maxBitrateSet = false
    private var subtitleStyle = SubtitleStyle()
    private var subtitleLift = 0f

    private var playSessionId: String? = null
    private var playMethod: PlayMethod = PlayMethod.DIRECT_PLAY
    private var playSource: MediaSourceInfo? = null
    private var reportedStart = false

    fun attachVideo(view: FrameLayout) {
        container = view
        engine?.attachVideo(view)
    }

    fun detachVideo() {
        engine?.detachVideo()
        container = null
    }

    fun start(itemId: UUID, forcedEngine: EngineId?, startPositionMs: Long, streams: StreamSelection? = null, startFraction: Double? = null) {
        scope.launch {
            runCatching {
                val user = session.requireSession()
                session.refreshLocalAddress()
                withContext(Dispatchers.Default) { probe.capabilities }
                item = session.call { libraryApi.getItem(itemId, user.userId).content }
                source = item.mediaSources?.firstOrNull() ?: error("У элемента нет медиаисточников")
                subtitleStyle = prefs.subtitleStyle()
                defaults = prefs.defaults()
                if (!maxBitrateSet) {
                    maxBitrate = defaults.maxBitrate
                    _state.update { it.copy(maxBitrate = maxBitrate) }
                }
                selection = streams ?: rememberedSelection() ?: StreamSelection(source.defaultAudioStreamIndex, source.defaultSubtitleStreamIndex)
                val startMs = startFraction?.let { ((item.runTimeTicks ?: 0) / TICKS_PER_MS * it).toLong() } ?: startPositionMs
                ranking = selector.rank(source, selection, device)
                _state.update {
                    it.copy(
                        title = item.name.orEmpty(),
                        itemId = item.id,
                        seriesId = item.seriesId,
                        source = source,
                        selection = selection,
                        ranking = ranking.map { r -> r.provider.id to r.verdict },
                    )
                }
                log("Ранжирование: " + ranking.joinToString { "${it.provider.id}=${it.verdict.describe()}" })
                val first = forcedEngine?.let(selector::provider) ?: ranking.first().provider
                val reason = if (forcedEngine != null) "выбран вручную" else ranking.first().verdict.describe()
                manualEngine = forcedEngine != null
                _state.update { it.copy(manualEngine = manualEngine) }
                launchEngine(first, startMs, forceTranscode = false, reason = reason)
            }.onFailure { fail("Не удалось начать воспроизведение", it) }
        }
    }

    fun togglePlayPause() {
        val e = engine ?: return
        if (e.state.value.playWhenReady) e.pause() else e.play()
        reportProgressAsync()
    }

    fun pause() {
        val e = engine ?: return
        if (!e.state.value.playWhenReady) return
        e.pause()
        reportProgressAsync()
    }

    /**
     * The app left the screen (Home on the remote) with the player open: pause, save the position on the server and
     * say the item's progress changed, so lists outside the app (the TV's home screen) show it.
     */
    fun pauseInBackground() {
        val e = engine ?: return
        if (e.state.value.playWhenReady) e.pause()
        scope.launch {
            reportProgress()
            session.notifyUserDataChanged(item.id)
        }
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs() + deltaMs)

    fun seekTo(positionMs: Long) {
        val e = engine ?: return
        val duration = e.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0, duration)
        if (!e.isSeekable) {
            // Hand over to an engine that can seek in this file, already at the target position.
            val next = ranking.firstOrNull { it.provider.id != e.id && it.verdict !is Verdict.No }?.provider
            if (next != null) {
                scope.launch {
                    runCatching { launchEngine(next, target, forceTranscode = false, reason = "${e.id} не умеет перематывать этот файл") }
                        .onFailure { fail("Не удалось переключиться", it) }
                }
                return
            }
        }
        lastSeekAt = SystemClock.elapsedRealtime()
        e.seekTo(target)
        reportProgressAsync()
    }

    fun selectAudio(streamIndex: Int) {
        if (selection.audioStreamIndex == streamIndex) return
        selection = selection.copy(audioStreamIndex = streamIndex)
        onSelectionChanged("смена аудио") { it.selectAudio(streamIndex) }
        source.stream(streamIndex)?.let { s -> scope.launch { prefs.rememberAudio(memoryKey(), s.pick()) } }
    }

    fun selectSubtitle(streamIndex: Int?) {
        if (selection.subtitleStreamIndex == streamIndex) return
        selection = selection.copy(subtitleStreamIndex = streamIndex)
        onSelectionChanged("смена субтитров") { it.selectSubtitle(streamIndex) }
        val pick = streamIndex?.let { source.stream(it)?.pick() }
        scope.launch { prefs.rememberSubtitle(memoryKey(), pick) }
    }

    /** Remembered per series (an episode) or per movie. */
    private fun memoryKey(): UUID = item.seriesId ?: item.id

    /** The tracks picked last time for this movie or series, found again in this file. */
    private suspend fun rememberedSelection(): StreamSelection? {
        val memory = prefs.tracks(memoryKey()) ?: return null
        val audio = memory.audio?.bestOf(source.streams(MediaStreamType.AUDIO)) { it.pick() }?.index
        val subtitle = if (memory.subtitleOff) null else memory.subtitle?.bestOf(source.streams(MediaStreamType.SUBTITLE)) { it.pick() }?.index
        if (audio == null && subtitle == null && !memory.subtitleOff) return null
        log("Запомненные дорожки: аудио $audio, субтитры $subtitle")
        return StreamSelection(audio ?: source.defaultAudioStreamIndex, if (memory.subtitleOff || subtitle != null) subtitle else source.defaultSubtitleStreamIndex)
    }

    private fun MediaStream.pick() = TrackPick(language, title?.takeIf { it.isNotBlank() }, codec)

    /** Quality: null = original (direct play where possible), else the server transcodes down to [bitrate]. */
    fun setMaxBitrate(bitrate: Int?) {
        maxBitrateSet = true
        if (bitrate == maxBitrate) return
        maxBitrate = bitrate
        _state.update { it.copy(maxBitrate = bitrate) }
        val provider = engine?.id?.let(selector::provider) ?: return
        val position = positionMs()
        val reason = if (bitrate == null) "качество: оригинал" else "качество: до ${bitrate / 1000} кбит/с"
        // The server decides: direct play if the file fits under the cap, else a transcode.
        scope.launch {
            runCatching { launchEngine(provider, position, forceTranscode = false, reason = reason) }.onFailure { fail("Не удалось сменить качество", it) }
        }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        if (style == subtitleStyle) return
        subtitleStyle = style
        val e = engine as? SubtitleStyleable ?: return
        // libVLC takes the look only at start: restart it, but only if text subtitles are on screen now.
        if (!e.setSubtitleStyle(style) && selection.subtitleStreamIndex != null) restartCurrent("вид субтитров")
    }

    fun setSubtitleLift(fraction: Float) {
        subtitleLift = fraction
        (engine as? SubtitleStyleable)?.setSubtitleLift(fraction)
    }

    /**
     * Tracks change what each engine can do (ASS subtitles, TrueHD audio, ...): re-rank, and in automatic mode move
     * to an engine that is now strictly better. Engines that already failed in this session are not retried.
     */
    private fun onSelectionChanged(what: String, applyInPlace: (PlaybackEngine) -> Unit) {
        ranking = selector.rank(source, selection, device)
        _state.update { it.copy(selection = selection, ranking = ranking.map { r -> r.provider.id to r.verdict }) }
        val e = engine ?: return
        val current = ranking.firstOrNull { it.provider.id == e.id }
        val best = ranking.firstOrNull { it.provider.id == e.id || it.provider.id !in tried }
        if (!manualEngine && current != null && best != null && best.provider.id != e.id &&
            selector.score(best.verdict) < selector.score(current.verdict)
        ) {
            val position = positionMs()
            log("$what: ${best.provider.id} теперь подходит лучше (${best.verdict.describe()})")
            scope.launch { launchEngine(best.provider, position, forceTranscode = false, reason = "$what: ${best.verdict.describe()}") }
            return
        }
        if (playMethod == PlayMethod.DIRECT_PLAY) applyInPlace(e) else restartCurrent(what)
    }

    /** [videoPath] is applied to the new engine before it loads (if it is [VideoPathSwitchable]). */
    fun switchEngine(id: EngineId, transcode: Boolean = false, videoPath: VideoPathSwitchable.Mode? = null, dolbyVision: Boolean? = null) {
        val position = positionMs()
        scope.launch {
            tried.clear()
            transcodeTried = false
            nextVideoPath = videoPath
            nextDolbyVision = dolbyVision
            manualEngine = true
            _state.update { it.copy(manualEngine = true) }
            launchEngine(selector.provider(id), position, forceTranscode = transcode, reason = if (transcode) "транскод вручную" else "выбран вручную")
        }
    }

    /** Back to what the selector would pick for this file. */
    fun switchToAuto() {
        val first = ranking.firstOrNull() ?: return
        val position = positionMs()
        scope.launch {
            tried.clear()
            transcodeTried = false
            nextVideoPath = defaults.videoPath
            nextDolbyVision = null
            defaultDolbyVision = true
            manualEngine = false
            _state.update { it.copy(manualEngine = false) }
            launchEngine(first.provider, position, forceTranscode = false, reason = first.verdict.describe())
        }
    }

    fun forceTranscode() {
        val position = positionMs()
        val provider = engine?.id?.let(selector::provider) ?: return
        manualEngine = true
        _state.update { it.copy(manualEngine = true) }
        scope.launch { launchEngine(provider, position, forceTranscode = true, reason = "транскод вручную") }
    }

    fun release() {
        generation++
        val old = engine
        engine = null
        engineJobs.forEach(Job::cancel)
        old?.let { reportStopAsync(it.positionMs, failed = false) }
        old?.release()
    }

    private fun restartCurrent(why: String) {
        val provider = engine?.id?.let(selector::provider) ?: return
        val position = positionMs()
        scope.launch { launchEngine(provider, position, forceTranscode = playMethod != PlayMethod.DIRECT_PLAY, reason = why) }
    }

    private suspend fun launchEngine(provider: EngineProvider, startMs: Long, forceTranscode: Boolean, reason: String) {
        val gen = ++generation
        engineJobs.forEach(Job::cancel)
        engineJobs.clear()
        engine?.let { old ->
            if (nextVideoPath == null) nextVideoPath = (old as? VideoPathSwitchable)?.videoPathMode
            if (nextDolbyVision == null && !defaultDolbyVision) nextDolbyVision = (old as? DolbyVisionSwitchable)?.useDolbyVisionDecoder
            reportStop(positionMs(), failed = false)
            old.release()
        }
        engine = null
        launchPositionMs = startMs
        tried += provider.id
        if (forceTranscode) transcodeTried = true
        _state.update { it.copy(loading = true, error = null, engine = provider.id, engineReason = reason) }
        log("Запуск ${provider.id} с ${startMs / 1000} с ($reason)" + if (forceTranscode) ", принудительный транскод" else "")

        val user = session.requireSession()
        val profile = provider.deviceProfile(device)
        val info = session.call {
            mediaInfoApi.getPostedPlaybackInfo(
            item.id,
            PlaybackInfoDto(
                userId = user.userId,
                maxStreamingBitrate = maxBitrate ?: MAX_BITRATE,
                startTimeTicks = startMs * TICKS_PER_MS,
                audioStreamIndex = selection.audioStreamIndex,
                subtitleStreamIndex = selection.subtitleStreamIndex,
                mediaSourceId = source.id,
                deviceProfile = profile,
                enableDirectPlay = !forceTranscode,
                enableDirectStream = !forceTranscode,
                enableTranscoding = true,
                allowVideoStreamCopy = true,
                allowAudioStreamCopy = true,
                autoOpenLiveStream = true,
                alwaysBurnInSubtitleWhenTranscoding = false,
            ),
            ).content
        }
        if (gen != generation) return
        info.errorCode?.let { error("Сервер отказал: $it") }
        val ms = info.mediaSources.firstOrNull() ?: error("PlaybackInfo без медиаисточника")
        playSessionId = info.playSessionId
        playSource = ms

        val url: String
        if (ms.supportsDirectPlay && !forceTranscode) {
            playMethod = PlayMethod.DIRECT_PLAY
            url = session.authorizedUrl(
                api.videoApi.getVideoStreamUrl(
                    itemId = item.id,
                    static = true,
                    mediaSourceId = ms.id,
                    playSessionId = playSessionId,
                    deviceId = session.deviceId,
                    tag = ms.eTag,
                ),
            )
        } else {
            val transcodingUrl = ms.transcodingUrl ?: error("Сервер не предложил ни прямого воспроизведения, ни транскода")
            playMethod = PlayMethod.TRANSCODE
            url = session.authorizedUrl(transcodingUrl)
        }
        val transcodeReasons = ms.transcodingUrl?.let { Regex("TranscodeReasons=([^&]+)").find(it)?.groupValues?.get(1) }
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val host = Uri.parse(url).let { "${it.scheme}://${it.authority}" }
        log("${provider.id}: $playMethod через $host" + (transcodeReasons?.let { " (причины транскода: $it)" } ?: ""))

        val external = ms.mediaStreams.orEmpty()
            .filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null }
            .map { ExternalSubtitle(it.index, session.authorizedUrl(it.deliveryUrl!!), it.codec, it.language, it.displayTitle) }

        val newEngine = provider.create(device)
        (newEngine as? VideoPathSwitchable)?.setVideoPathMode(nextVideoPath ?: defaults.videoPath)
        nextVideoPath = null
        (newEngine as? DolbyVisionSwitchable)?.useDolbyVisionDecoder = nextDolbyVision ?: defaults.dolbyVision
        (newEngine as? SubtitleStyleable)?.let {
            it.setSubtitleStyle(subtitleStyle)
            it.setSubtitleLift(subtitleLift)
        }
        nextDolbyVision = null
        defaultDolbyVision = false
        engine = newEngine
        firstFrameSeen = false
        container?.let(newEngine::attachVideo)
        engineJobs += scope.launch { newEngine.events.collect { onEngineEvent(gen, it) } }
        engineJobs += scope.launch { newEngine.state.collect { s -> _state.update { it.copy(engineState = s, loading = s.phase == EngineState.Phase.BUFFERING) } } }
        engineJobs += scope.launch { newEngine.stats.collect { s -> _state.update { it.copy(stats = s) } } }
        engineJobs += scope.launch { tickLoop(gen) }
        engineJobs += scope.launch { firstFrameWatchdog(gen) }
        engineJobs += scope.launch { stallWatchdog(gen) }

        _state.update { it.copy(playMethod = playMethod, transcodeReasons = transcodeReasons) }
        newEngine.load(
            LoadRequest(
                url = url,
                playMethod = playMethod,
                mediaSource = ms,
                startPositionMs = startMs,
                audioStreamIndex = selection.audioStreamIndex,
                subtitleStreamIndex = selection.subtitleStreamIndex,
                externalSubtitles = external,
            ),
        )
        newEngine.play()
        reportStart(startMs)
    }

    private var firstFrameSeen = false
    /** Where the current engine was asked to start; see [positionMs]. */
    private var launchPositionMs = 0L

    /**
     * Playback position for handing over to another engine. Until the engine shows its first frame its own position
     * is meaningless (0 or -1, or relative to a transcode), so the position it was started at is used: otherwise a
     * quick variant switch (or a fallback from an engine that never got going) restarts the film from the beginning.
     */
    private fun positionMs(): Long {
        val e = engine
        return if (e == null || !firstFrameSeen) launchPositionMs else e.positionMs
    }

    private suspend fun firstFrameWatchdog(gen: Int) {
        if (source.videoStream == null) return
        delay(FIRST_FRAME_TIMEOUT_MS)
        if (gen == generation && !firstFrameSeen) {
            onEngineEvent(gen, EngineEvent.Error(EngineError(EngineError.Kind.NO_VIDEO_OUTPUT, "Нет первого кадра за ${FIRST_FRAME_TIMEOUT_MS / 1000} с")))
        }
    }

    /**
     * Catches what the first-frame watchdog can't: an engine that started fine but then hangs (reports READY and
     * playing, position stands still) or plays so badly it is useless (e.g. Exo on AC3-in-AVI with broken audio
     * timing: ~40% of frames dropped). Buffering alone is not treated as a stall: that is usually the network, and
     * another engine would not fetch faster.
     */
    private suspend fun stallWatchdog(gen: Int) {
        val video = source.videoStream ?: return
        val fps = (video.realFrameRate ?: video.averageFrameRate ?: 25f).coerceIn(10f, 120f)
        var lastPosition = -1L
        var stuckMs = 0L
        val drops = ArrayDeque<Int>()
        while (scope.isActive && gen == generation) {
            delay(STALL_TICK_MS)
            val e = engine ?: return
            val s = e.state.value
            val settled = firstFrameSeen && SystemClock.elapsedRealtime() - lastSeekAt > SEEK_GRACE_MS
            if (!s.playWhenReady || s.phase == EngineState.Phase.ENDED || !settled) {
                lastPosition = -1
                stuckMs = 0
                drops.clear()
                continue
            }
            val position = e.positionMs
            stuckMs = if (s.phase == EngineState.Phase.READY && lastPosition >= 0 && position <= lastPosition) stuckMs + STALL_TICK_MS else 0
            lastPosition = position
            drops.addLast(_state.value.stats.droppedFrames)
            if (drops.size > DROP_WINDOW_TICKS + 1) drops.removeFirst()
            val dropped = if (drops.size > DROP_WINDOW_TICKS) drops.last() - drops.first() else 0
            val windowS = DROP_WINDOW_TICKS * STALL_TICK_MS / 1000
            val reason = when {
                stuckMs >= STALL_TIMEOUT_MS -> "играет, но позиция стоит ${stuckMs / 1000} с"
                dropped > fps * windowS * MAX_DROPPED_SHARE -> "пропущено $dropped кадров за $windowS с"
                else -> continue
            }
            if (manualEngine) {
                log("Застой ${e.id}: $reason (движок выбран вручную, не переключаю)")
                stuckMs = 0
                drops.clear()
                continue
            }
            onEngineEvent(gen, EngineEvent.Error(EngineError(EngineError.Kind.STALLED, "Застой: $reason")))
            return
        }
    }

    private fun onEngineEvent(gen: Int, event: EngineEvent) {
        if (gen != generation) return
        when (event) {
            EngineEvent.FirstFrameRendered -> {
                firstFrameSeen = true
                _state.update { it.copy(loading = false) }
            }
            is EngineEvent.Error -> {
                val failedEngine = engine?.id
                val position = positionMs()
                log("Ошибка $failedEngine: ${event.error.kind} — ${event.error.message}")
                Timber.w(event.error.cause, "Engine %s failed: %s", failedEngine, event.error.message)
                val next = if (event.error.worthFallback) {
                    ranking.firstOrNull { it.provider.id !in tried && it.verdict !is Verdict.No }?.provider
                } else {
                    null
                }
                scope.launch {
                    runCatching {
                        when {
                            next != null -> launchEngine(next, position, forceTranscode = false, reason = "fallback после ошибки $failedEngine")
                            !transcodeTried -> launchEngine(ranking.first().provider, position, forceTranscode = true, reason = "все движки не смогли — транскод")
                            else -> fail("Воспроизведение не удалось: ${event.error.message}", event.error.cause)
                        }
                    }.onFailure { fail("Не удалось переключиться", it) }
                }
            }
        }
    }

    private suspend fun tickLoop(gen: Int) {
        var sinceReport = 0L
        while (scope.isActive && gen == generation) {
            val e = engine ?: return
            _state.update { it.copy(positionMs = positionMs(), durationMs = e.durationMs, bufferedMs = e.bufferedPositionMs) }
            delay(TICK_MS)
            sinceReport += TICK_MS
            if (sinceReport >= PROGRESS_INTERVAL_MS) {
                sinceReport = 0
                reportProgress()
            }
        }
    }

    private suspend fun reportStart(positionMs: Long) {
        reportedStart = true
        runCatching {
            session.call { sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    canSeek = true,
                    itemId = item.id,
                    mediaSourceId = source.id,
                    audioStreamIndex = selection.audioStreamIndex,
                    subtitleStreamIndex = selection.subtitleStreamIndex,
                    isPaused = false,
                    isMuted = false,
                    positionTicks = positionMs * TICKS_PER_MS,
                    playMethod = playMethod,
                    playSessionId = playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            ) }
        }.onFailure { Timber.w(it, "reportPlaybackStart failed") }
    }

    private suspend fun reportProgress() {
        val e = engine ?: return
        // Engines are main-thread only: read their state before switching to the network thread.
        val paused = !e.state.value.playWhenReady
        val positionTicks = e.positionMs * TICKS_PER_MS
        runCatching {
            session.call { sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    canSeek = true,
                    itemId = item.id,
                    mediaSourceId = source.id,
                    audioStreamIndex = selection.audioStreamIndex,
                    subtitleStreamIndex = selection.subtitleStreamIndex,
                    isPaused = paused,
                    isMuted = false,
                    positionTicks = positionTicks,
                    playMethod = playMethod,
                    playSessionId = playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            ) }
        }.onFailure { Timber.w(it, "reportPlaybackProgress failed") }
    }

    private fun reportProgressAsync() {
        scope.launch { reportProgress() }
    }

    private suspend fun reportStop(positionMs: Long, failed: Boolean) {
        if (!reportedStart) return
        reportedStart = false
        val sessionId = playSessionId
        val method = playMethod
        withContext(NonCancellable) {
            runCatching {
                    session.call { sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = item.id,
                        mediaSourceId = source.id,
                        positionTicks = positionMs * TICKS_PER_MS,
                        playSessionId = sessionId,
                        failed = failed,
                    ),
                ) }
                session.notifyUserDataChanged(item.id)
                if (method == PlayMethod.TRANSCODE && sessionId != null) {
                    // HLS endpoints are hidden from the 12.x OpenAPI, so the SDK has no method for this.
                    session.call {
                        request(
                            method = HttpMethod.DELETE,
                            pathTemplate = "/Videos/ActiveEncodings",
                            queryParameters = mapOf("deviceId" to session.deviceId, "playSessionId" to sessionId),
                        )
                    }
                }
            }.onFailure { Timber.w(it, "reportPlaybackStopped failed") }
        }
    }

    private fun reportStopAsync(positionMs: Long, failed: Boolean) {
        // The owner's scope is being cancelled at this point; reporting must still reach the server.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch { reportStop(positionMs, failed) }
    }

    private fun log(message: String) {
        Timber.i(message)
        _state.update { it.copy(history = (it.history + message).takeLast(20)) }
    }

    private fun fail(message: String, cause: Throwable?) {
        Timber.e(cause, message)
        log("$message: ${cause?.message.orEmpty()}")
        _state.update { it.copy(loading = false, error = "$message\n${cause?.message.orEmpty()}") }
    }

    private companion object {
        const val TICKS_PER_MS = 10_000L
        const val MAX_BITRATE = 400_000_000
        const val TICK_MS = 500L
        const val PROGRESS_INTERVAL_MS = 10_000L
        const val FIRST_FRAME_TIMEOUT_MS = 60_000L
        const val STALL_TICK_MS = 1000L
        const val STALL_TIMEOUT_MS = 8_000L
        /** Seeking rebuffers and drops a few frames: give the engine time to settle before judging it. */
        const val SEEK_GRACE_MS = 5_000L
        const val DROP_WINDOW_TICKS = 10
        const val MAX_DROPPED_SHARE = 0.25
    }
}

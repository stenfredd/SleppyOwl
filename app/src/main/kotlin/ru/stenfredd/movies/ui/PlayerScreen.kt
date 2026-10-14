package ru.stenfredd.movies.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import ru.stenfredd.movies.playback.api.Codecs
import ru.stenfredd.movies.playback.api.DolbyVisionSwitchable
import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.EngineState
import ru.stenfredd.movies.playback.api.VideoPathSwitchable
import ru.stenfredd.movies.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.movies.playback.core.EngineSelector
import ru.stenfredd.movies.playback.core.PlaybackSession
import ru.stenfredd.movies.playback.core.PlaybackSessionFactory
import ru.stenfredd.movies.playback.core.PlaybackUiState
import ru.stenfredd.movies.playback.core.describe
import ru.stenfredd.movies.playback.exo.ExoEngineProvider
import ru.stenfredd.movies.playback.vlc.VlcEngineProvider
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val factory: PlaybackSessionFactory,
    val selector: EngineSelector,
    val probe: DeviceCapabilitiesProbe,
) : ViewModel() {
    var session: PlaybackSession? = null
        private set

    fun start(screen: Screen.Player): PlaybackSession {
        release()
        return factory.create(viewModelScope).also {
            session = it
            it.start(screen.itemId, screen.engine, screen.startPositionMs, screen.streams, screen.startFraction)
        }
    }

    /** Releases [target] (default: the current session); a stale screen disposing late can't kill a newer session. */
    fun release(target: PlaybackSession? = session) {
        target?.release()
        if (target === session) session = null
    }

    override fun onCleared() = release()
}

/** Debug tour context for [PlayerScreen]: ↑ cycles playback variants, ↓ goes to the next file. */
class TourInfo(val number: Int, val total: Int, val title: String, val label: String, val onNextFile: () -> Unit)

/** Variants ↑ steps through in the tour: auto first, then each engine/path, then a server transcode. */
private val TOUR_VARIANTS = listOf(0) + Variant.entries.map { it.number } + TRANSCODE_KEY

@Composable
fun PlayerScreen(screen: Screen.Player, onExit: () -> Unit, tour: TourInfo? = null, vm: PlayerViewModel = hiltViewModel()) {
    val session = remember(screen) { vm.start(screen) }
    DisposableEffect(session) { onDispose { vm.release(session) } }
    val state by session.state.collectAsStateWithLifecycle()

    // ↑ cycles: variant badge only → badge + diagnostics → nothing.
    var overlay by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(screen.engine != null) }
    var showMenu by remember { mutableStateOf(false) }
    var tourStep by remember { mutableIntStateOf(0) }
    var barTick by remember { mutableIntStateOf(0) }
    var showBar by remember { mutableStateOf(true) }
    val rootFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }

    LaunchedEffect(barTick) {
        showBar = true
        delay(4000)
        showBar = false
    }
    LaunchedEffect(showMenu) {
        runCatching { if (showMenu) menuFocus.requestFocus() else rootFocus.requestFocus() }
    }
    BackHandler { if (showMenu) showMenu = false else onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (showMenu || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> session.togglePlayPause()
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> session.seekBy(-10_000)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> session.seekBy(30_000)
                    AndroidKeyEvent.KEYCODE_DPAD_UP if tour != null -> {
                        tourStep = (tourStep + 1) % TOUR_VARIANTS.size
                        manual = applyVariant(TOUR_VARIANTS[tourStep], session) ?: manual
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN if tour != null -> tour.onNextFile()
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_INFO -> overlay = (overlay + 1) % 3
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
                        manual = applyVariant(e.key.nativeKeyCode - AndroidKeyEvent.KEYCODE_0, session) ?: return@onPreviewKeyEvent false
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU -> showMenu = true
                    else -> return@onPreviewKeyEvent false
                }
                barTick++
                true
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx -> FrameLayout(ctx).also(session::attachVideo) },
            onRelease = { session.detachVideo() },
            modifier = Modifier.fillMaxSize(),
        )
        if (overlay == 1) DebugPanel(state, vm, Modifier.align(Alignment.TopStart))
        if (overlay < 2) VariantBadge(state, session, manual, tour, Modifier.align(Alignment.TopEnd).padding(24.dp))
        if (showBar || state.loading || state.error != null) StatusBar(state, tour != null, Modifier.align(Alignment.BottomCenter))
        if (showMenu) {
            Menu(state, session, vm, Modifier.align(Alignment.CenterEnd).focusRequester(menuFocus), onManual = { manual = it }) { showMenu = false }
        }
    }
}

@Composable
private fun StatusBar(state: PlaybackUiState, touring: Boolean, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Color(0xAA000000)).padding(24.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val phase = when {
            state.error != null -> "Ошибка"
            state.loading -> "Загрузка…"
            state.engineState.phase == EngineState.Phase.ENDED -> "Конец"
            state.engineState.playWhenReady -> "▶"
            else -> "❚❚"
        }
        Text("$phase  ${state.title}   ${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val hint = if (touring) {
            "OK — пауза · ←/→ — перемотка · ↑ — следующий режим · ↓ — следующий файл · Меню — дорожки · Info — диагностика"
        } else {
            "OK — пауза · ←/→ — перемотка · ↑ — значок/диагностика · ↓ — варианты, дорожки, движок"
        }
        Text(hint, fontSize = 12.sp, color = Color.LightGray)
    }
}

/**
 * Numbered playback variants for A/B testing on the TV: the badge in the corner shows the number, so the tester can
 * note "file — variant — impression". 0 returns to the automatic choice. Picked from the ↓ menu or with digit keys.
 */
private enum class Variant(
    val number: Int,
    val label: String,
    val engine: EngineId,
    val path: VideoPathSwitchable.Mode? = null,
    /** For Exo: decode Dolby Vision files through the DV decoder instead of the base layer. */
    val dolbyVision: Boolean = false,
) {
    EXO_PLANE(1, "Exo · видеослой ТВ", ExoEngineProvider.ID, VideoPathSwitchable.Mode.VIDEO_PLANE),
    EXO_GPU(2, "Exo · через GPU", ExoEngineProvider.ID, VideoPathSwitchable.Mode.GPU),
    VLC(3, "VLC", VlcEngineProvider.ID),
    EXO_DV(4, "Exo · DV-декодер", ExoEngineProvider.ID, VideoPathSwitchable.Mode.VIDEO_PLANE, dolbyVision = true),
}

private const val TRANSCODE_KEY = 9

/** Returns the new "manual" flag, or null if [number] is not a variant. */
private fun applyVariant(number: Int, session: PlaybackSession): Boolean? {
    when (number) {
        0 -> {
            session.switchToAuto()
            return false
        }
        TRANSCODE_KEY -> session.forceTranscode()
        else -> {
            val v = Variant.entries.firstOrNull { it.number == number } ?: return null
            val current = session.activeEngine
            val sameDecoder = (current as? DolbyVisionSwitchable)?.useDolbyVisionDecoder?.let { it == v.dolbyVision } ?: true
            if (current?.id == v.engine && current is VideoPathSwitchable && v.path != null && sameDecoder) current.setVideoPathMode(v.path)
            else session.switchEngine(v.engine, videoPath = v.path, dolbyVision = v.dolbyVision.takeIf { v.engine == ExoEngineProvider.ID })
        }
    }
    return true
}

/** The variant actually playing, e.g. "2" (Exo through GPU) or "3Т" (VLC on a server transcode). */
private fun currentVariantCode(state: PlaybackUiState, session: PlaybackSession): String {
    val engine = session.activeEngine
    val gpu = (engine as? VideoPathSwitchable)?.usingGpuPath
    val dv = (engine as? DolbyVisionSwitchable)?.useDolbyVisionDecoder == true
    val v = Variant.entries.firstOrNull {
        it.engine == state.engine && it.dolbyVision == dv && (it.path == null || gpu == (it.path == VideoPathSwitchable.Mode.GPU))
    }
    val transcode = state.playMethod != null && state.playMethod != PlayMethod.DIRECT_PLAY
    return (v?.number?.toString() ?: "?") + if (transcode) "Т" else ""
}

@Composable
private fun VariantBadge(state: PlaybackUiState, session: PlaybackSession, manual: Boolean, tour: TourInfo?, modifier: Modifier) {
    val engine = session.activeEngine
    val path = (engine as? VideoPathSwitchable)?.let { if (it.usingGpuPath) "GPU" else "видеослой" }
        ?.plus(if ((engine as? DolbyVisionSwitchable)?.useDolbyVisionDecoder == true) " · DV" else "")
    val transcode = state.playMethod != null && state.playMethod != PlayMethod.DIRECT_PLAY
    val head = listOfNotNull(state.engine?.value?.uppercase(), path, if (transcode) "ТРАНСКОД" else null).joinToString(" · ")
    val mode = when {
        manual -> "вручную"
        state.engineReason.startsWith("fallback") || "не умеет" in state.engineReason || "все движки" in state.engineReason -> "авто, запасной"
        else -> "авто"
    }
    Column(
        modifier.background(Color(0xC0000000)).padding(16.dp, 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        tour?.let {
            Text("Отладка: файл ${it.number} из ${it.total}", fontSize = 14.sp, color = Color(0xFF80CBC4))
        }
        Text(
            "${currentVariantCode(state, session)}  $head",
            fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFFFFD54F),
        )
        Text(
            "$mode · ${formatTime(state.positionMs)} · пропуски ${state.stats.droppedFrames}",
            fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color.White,
        )
        Text(tour?.title ?: state.title, fontSize = if (tour != null) 16.sp else 12.sp, color = Color.LightGray)
        tour?.let { Text(it.label, fontSize = 14.sp, color = Color.White, modifier = Modifier.widthIn(max = 900.dp)) }
    }
}

@Composable
private fun DebugPanel(state: PlaybackUiState, vm: PlayerViewModel, modifier: Modifier) {
    val caps = vm.probe.capabilities
    val lines = buildList {
        add("Движок: ${state.engine} — ${state.engineReason}")
        add("Метод: ${state.playMethod?.serialName ?: "?"}" + (state.transcodeReasons?.let { " ($it)" } ?: ""))
        state.stats.videoDecoder?.let { add("Видеодекодер: $it") }
        state.stats.videoFormat?.let { add("Видео: $it") }
        state.stats.audioDecoder?.let { add("Аудиодекодер: $it") }
        state.stats.audioFormat?.let { add("Аудио: $it") }
        state.stats.audioOutput?.let { add("Аудиовыход: $it") }
        add("Пропущено кадров: ${state.stats.droppedFrames}")
        state.stats.extra.forEach { (k, v) -> add("$k: $v") }
        state.ranking.forEach { (id, verdict) -> add("· $id: ${verdict.describe()}") }
        add("Устройство: ${caps.manufacturer} ${caps.model} (${caps.soc}), HDR ${caps.displayHdrTypes}, passthrough ${caps.passthroughEncodings.map(Codecs::encodingName)}")
        add("— журнал —")
        addAll(state.history.takeLast(8))
    }
    Column(modifier.width(900.dp).background(Color(0xB0000000)).padding(12.dp)) {
        lines.forEach { Text(it, fontSize = 11.sp, color = Color.White, lineHeight = 13.sp) }
    }
}

@Composable
private fun Menu(
    state: PlaybackUiState,
    session: PlaybackSession,
    vm: PlayerViewModel,
    modifier: Modifier,
    onManual: (Boolean) -> Unit,
    close: () -> Unit,
) {
    val source = state.source ?: return
    fun mark(selected: Boolean) = if (selected) "● " else "   "
    LazyColumn(
        modifier.fillMaxHeight().width(520.dp).background(Color(0xE0101010)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Вариант (сейчас ${currentVariantCode(state, session)})", style = MaterialTheme.typography.titleMedium) }
        val variants = listOf(0 to "Авто") + Variant.entries.map { it.number to it.label } + listOf(TRANSCODE_KEY to "Транскод сервером (текущим движком)")
        variants.forEach { (number, label) ->
            item {
                Button(onClick = { applyVariant(number, session)?.let(onManual); close() }) { Text("$number  $label") }
            }
        }
        item { Text("Аудио", style = MaterialTheme.typography.titleMedium) }
        source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }.forEach { s ->
            item {
                Button(onClick = { session.selectAudio(s.index); close() }) {
                    Text(mark(state.selection.audioStreamIndex == s.index) + (s.displayTitle ?: s.codec.orEmpty()))
                }
            }
        }
        item { Text("Субтитры", style = MaterialTheme.typography.titleMedium) }
        item {
            Button(onClick = { session.selectSubtitle(null); close() }) { Text(mark(state.selection.subtitleStreamIndex == null) + "Выключены") }
        }
        source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }.forEach { s ->
            item {
                Button(onClick = { session.selectSubtitle(s.index); close() }) {
                    Text(mark(state.selection.subtitleStreamIndex == s.index) + (s.displayTitle ?: s.codec.orEmpty()))
                }
            }
        }
        item { Text("Движок", style = MaterialTheme.typography.titleMedium) }
        vm.selector.engines.forEach { p ->
            item { Button(onClick = { session.switchEngine(p.id); onManual(true); close() }) { Text(mark(state.engine == p.id) + p.displayName) } }
        }
        item { Button(onClick = { session.forceTranscode(); onManual(true); close() }) { Text("   Транскод на сервере") } }
        (session.activeEngine as? VideoPathSwitchable)?.let { engine ->
            item { Text("Вывод видео", style = MaterialTheme.typography.titleMedium) }
            val modes = listOf(
                VideoPathSwitchable.Mode.AUTO to "Авто (сейчас: ${if (engine.usingGpuPath) "через GPU" else "видеослой ТВ"})",
                VideoPathSwitchable.Mode.VIDEO_PLANE to "Видеослой ТВ (с HDR, обработка ТВ)",
                VideoPathSwitchable.Mode.GPU to "Через GPU, как VLC (без HDR, до 1080p)",
            )
            modes.forEach { (mode, label) ->
                item {
                    Button(onClick = { engine.setVideoPathMode(mode); onManual(mode != VideoPathSwitchable.Mode.AUTO); close() }) {
                        Text(mark(engine.videoPathMode == mode) + label)
                    }
                }
            }
        }
    }
}

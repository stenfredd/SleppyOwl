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
import ru.stenfredd.movies.playback.api.Codecs
import ru.stenfredd.movies.playback.api.EngineState
import ru.stenfredd.movies.playback.api.VideoPathSwitchable
import ru.stenfredd.movies.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.movies.playback.core.EngineSelector
import ru.stenfredd.movies.playback.core.PlaybackSession
import ru.stenfredd.movies.playback.core.PlaybackSessionFactory
import ru.stenfredd.movies.playback.core.PlaybackUiState
import ru.stenfredd.movies.playback.core.describe
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
            it.start(screen.itemId, screen.engine, screen.startPositionMs)
        }
    }

    fun release() {
        session?.release()
        session = null
    }

    override fun onCleared() = release()
}

@Composable
fun PlayerScreen(screen: Screen.Player, onExit: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val session = remember(screen) { vm.start(screen) }
    DisposableEffect(session) { onDispose { vm.release() } }
    val state by session.state.collectAsStateWithLifecycle()

    var showDebug by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
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
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_INFO -> showDebug = !showDebug
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
        if (showDebug) DebugPanel(state, vm, Modifier.align(Alignment.TopStart))
        if (showBar || state.loading || state.error != null) StatusBar(state, Modifier.align(Alignment.BottomCenter))
        if (showMenu) {
            Menu(state, session, vm, Modifier.align(Alignment.CenterEnd).focusRequester(menuFocus)) { showMenu = false }
        }
    }
}

@Composable
private fun StatusBar(state: PlaybackUiState, modifier: Modifier) {
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
        Text("OK — пауза · ←/→ — перемотка · ↑ — диагностика · ↓ — дорожки и движок", fontSize = 12.sp, color = Color.LightGray)
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
private fun Menu(state: PlaybackUiState, session: PlaybackSession, vm: PlayerViewModel, modifier: Modifier, close: () -> Unit) {
    val source = state.source ?: return
    fun mark(selected: Boolean) = if (selected) "● " else "   "
    LazyColumn(
        modifier.fillMaxHeight().width(520.dp).background(Color(0xE0101010)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
            item { Button(onClick = { session.switchEngine(p.id); close() }) { Text(mark(state.engine == p.id) + p.displayName) } }
        }
        item { Button(onClick = { session.forceTranscode(); close() }) { Text("   Транскод на сервере") } }
        (session.activeEngine as? VideoPathSwitchable)?.let { engine ->
            item { Text("Вывод видео", style = MaterialTheme.typography.titleMedium) }
            val modes = listOf(
                VideoPathSwitchable.Mode.AUTO to "Авто (сейчас: ${if (engine.usingGpuPath) "через GPU" else "видеослой ТВ"})",
                VideoPathSwitchable.Mode.VIDEO_PLANE to "Видеослой ТВ (с HDR, обработка ТВ)",
                VideoPathSwitchable.Mode.GPU to "Через GPU, как VLC (без HDR, до 1080p)",
            )
            modes.forEach { (mode, label) ->
                item { Button(onClick = { engine.setVideoPathMode(mode); close() }) { Text(mark(engine.videoPathMode == mode) + label) } }
            }
        }
    }
}

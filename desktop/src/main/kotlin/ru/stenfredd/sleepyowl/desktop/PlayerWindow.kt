package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Window
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.common.PreferenceStores
import ru.stenfredd.sleepyowl.playback.api.PlaybackEngine
import ru.stenfredd.sleepyowl.playback.api.VideoHost
import ru.stenfredd.sleepyowl.playback.mpv.MpvEngine
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.menu.WatchMenuViewModel
import ru.stenfredd.sleepyowl.ui.player.PlayerViewModel
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/** The player window's modes (§1.1): they change on the fly, the video goes on. */
enum class PlayerMode { Mini, Window, Fullscreen }

/**
 * "Смотреть" opens this: one player window at a time; a new item replaces the one playing. Lives in Koin, the window
 * itself is composed by [main] next to the library window.
 */
class DesktopPlayer(stores: PreferenceStores, val settings: DesktopSettings) {
    var screen by mutableStateOf<Screen.Player?>(null)
        private set

    val audio = PlayerAudio(stores)
    val placement = PlayerPlacement(stores)

    /** The window's mode; the next player opens in the last one (§1.1). */
    var mode by mutableStateOf(placement.mode)
        private set

    /** The mode full screen goes back to: the mini window or the normal one. */
    var windowedMode = placement.windowedMode
        private set

    val fullscreen: Boolean get() = mode == PlayerMode.Fullscreen

    /** Opens a page in the library window and brings that window up; set by the library window. */
    var openPage: (Screen) -> Unit = {}

    /** Brings the library window up; set by it. */
    var showLibrary: () -> Unit = {}

    /** What the window plays, for the system ([SystemMedia]); null while nothing does. Set by the window. */
    var nowPlaying by mutableStateOf<NowPlaying?>(null)

    /** The window's controls for the media keys and the sound applet ([SystemMedia]); set by the window. */
    var commands: PlayerCommands? = null

    fun play(screen: Screen.Player) {
        Log.i("Player: %s from %d ms", screen.itemId, screen.startPositionMs)
        // "Открывать плеер" (L9): a new window opens in the chosen mode; a window already open keeps its own.
        if (this.screen == null) settings.playerOpening.mode?.let(::switchTo)
        this.screen = screen
    }

    fun close() {
        screen = null
        nowPlaying = null
        commands = null
    }

    fun switchTo(new: PlayerMode) {
        if (new == mode) return
        if (new != PlayerMode.Fullscreen) windowedMode = new
        mode = new
        placement.saveMode(new, windowedMode)
    }

    /** `T`: on top of everything ↔ the normal window. */
    fun toggleOnTop() = switchTo(if (mode == PlayerMode.Mini) PlayerMode.Window else PlayerMode.Mini)

    fun toggleFullscreen() = switchTo(if (fullscreen) windowedMode else PlayerMode.Fullscreen)

    fun leaveFullscreen() {
        if (fullscreen) switchTo(windowedMode)
    }

    /** A double click on the video: mini → normal window → full screen → back. */
    fun cycleMode() = switchTo(
        when (mode) {
            PlayerMode.Mini -> PlayerMode.Window
            PlayerMode.Window -> PlayerMode.Fullscreen
            PlayerMode.Fullscreen -> windowedMode
        },
    )
}

/**
 * Where the player window goes (§1.1): the last mode and each windowed mode's own place and size (client area, screen
 * pixels — the monitor is in them). A place no monitor shows any more gives way to the default one.
 */
class PlayerPlacement(stores: PreferenceStores) {
    private val store = stores["desktop_player"]
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastWrite: Job? = null

    // Read once, before the first window: the window opens where it was.
    private val saved: Preferences? = try {
        runBlocking(Dispatchers.IO) { withTimeoutOrNull(2_000) { store.data.first() } }
    } catch (e: Exception) {
        Log.w(e, "Player placement not read")
        null
    }

    var mode = saved?.get(KEY_MODE)?.let(::modeOf) ?: PlayerMode.Mini
        private set
    var windowedMode = saved?.get(KEY_WINDOWED)?.let(::modeOf)?.takeIf { it != PlayerMode.Fullscreen } ?: PlayerMode.Mini
        private set

    /** The mini window is on every workspace (`_NET_WM_STATE_STICKY`). */
    var sticky by mutableStateOf(saved?.get(KEY_STICKY) ?: false)
        private set

    private val bounds = mutableMapOf<PlayerMode, Rectangle>().apply {
        saved?.get(KEY_MINI)?.let(::rectangleOf)?.let { put(PlayerMode.Mini, it) }
        saved?.get(KEY_WINDOW)?.let(::rectangleOf)?.let { put(PlayerMode.Window, it) }
    }

    fun saveMode(mode: PlayerMode, windowed: PlayerMode) {
        this.mode = mode
        windowedMode = windowed
        write {
            it[KEY_MODE] = mode.name
            it[KEY_WINDOWED] = windowed.name
        }
    }

    fun saveSticky(on: Boolean) {
        sticky = on
        write { it[KEY_STICKY] = on }
    }

    /** [mode]'s place (a windowed one): kept when it changed. */
    fun save(mode: PlayerMode, r: Rectangle) {
        if (mode == PlayerMode.Fullscreen || r.width < MIN_WIDTH || r.height < MIN_HEIGHT || bounds[mode] == r) return
        bounds[mode] = Rectangle(r)
        write { it[if (mode == PlayerMode.Mini) KEY_MINI else KEY_WINDOW] = "${r.x},${r.y},${r.width},${r.height}" }
    }

    /** Where [mode] (a windowed one) opens: its last place while a monitor shows it, else the default place. */
    fun bounds(mode: PlayerMode): Rectangle = bounds[mode]?.takeIf(::onScreen)?.let(::Rectangle) ?: defaultBounds(mode)

    /** Waits for the last write: before the process ends. */
    suspend fun flush() {
        lastWrite?.join()
    }

    private fun write(change: (MutablePreferences) -> Unit) {
        lastWrite = scope.launch {
            try {
                store.edit(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(e, "Player placement not saved")
            }
        }
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_WINDOWED = stringPreferencesKey("windowed_mode")
        val KEY_STICKY = booleanPreferencesKey("mini_sticky")
        val KEY_MINI = stringPreferencesKey("mini_bounds")
        val KEY_WINDOW = stringPreferencesKey("window_bounds")

        fun modeOf(name: String) = PlayerMode.entries.firstOrNull { it.name == name }

        fun rectangleOf(text: String): Rectangle? =
            text.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }?.let { (x, y, w, h) -> Rectangle(x, y, w, h) }

        /** Its middle and most of it on one monitor, and it fits there. */
        fun onScreen(r: Rectangle): Boolean = screens().any { s ->
            val shown = s.intersection(r)
            s.contains(r.centerX, r.centerY) && r.width <= s.width && r.height <= s.height &&
                shown.width.toLong() * shown.height >= r.width.toLong() * r.height / 2
        }

        fun screens(): List<Rectangle> = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())

        /** The mini window: 640×360 in the bottom right corner of the main monitor, 24 px in; the normal one in its middle. */
        fun defaultBounds(mode: PlayerMode): Rectangle {
            val area = runCatching {
                val gc = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                val s = gc.bounds
                Rectangle(s.x + insets.left, s.y + insets.top, s.width - insets.left - insets.right, s.height - insets.top - insets.bottom)
            }.getOrDefault(Rectangle(0, 0, 1920, 1080))
            return if (mode == PlayerMode.Mini) {
                Rectangle(area.x + area.width - MINI_WIDTH - MINI_MARGIN, area.y + area.height - MINI_HEIGHT - MINI_MARGIN, MINI_WIDTH, MINI_HEIGHT)
            } else {
                val w = minOf(WINDOW_WIDTH, area.width)
                val h = minOf(WINDOW_HEIGHT, area.height)
                Rectangle(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
            }
        }
    }
}

/**
 * Volume and mute: kept across players and launches (§1.3). The engine starts with them ([MpvEngine.startVolume]), so a
 * new episode is never loud for a moment.
 */
class PlayerAudio(stores: PreferenceStores) {
    private val store = stores["desktop_player"]
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var volume by mutableIntStateOf(100)
        private set
    var muted by mutableStateOf(false)
        private set

    init {
        scope.launch {
            try {
                val prefs = store.data.first()
                apply(prefs[KEY_VOLUME] ?: 100, prefs[KEY_MUTED] ?: false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(e, "Player audio settings not read")
            }
        }
    }

    fun set(volume: Int, muted: Boolean) {
        apply(volume, muted)
        scope.launch {
            store.edit {
                it[KEY_VOLUME] = volume
                it[KEY_MUTED] = muted
            }
        }
    }

    private fun apply(volume: Int, muted: Boolean) {
        this.volume = volume.coerceIn(0, MpvEngine.MAX_VOLUME)
        this.muted = muted
        MpvEngine.startVolume = this.volume
        MpvEngine.startMuted = muted
    }

    private companion object {
        val KEY_VOLUME = intPreferencesKey("volume")
        val KEY_MUTED = booleanPreferencesKey("muted")
    }
}

/** The player's place for video: the session hands it each engine it starts. */
private class ComposeVideoHost : VideoHost {
    var engine by mutableStateOf<PlaybackEngine?>(null)
        private set

    override fun show(engine: PlaybackEngine) {
        this.engine = engine
    }

    override fun hide(engine: PlaybackEngine) {
        if (this.engine === engine) this.engine = null
    }
}

/**
 * The player window: the mpv engine under the shared [PlayerViewModel] and [PlaybackSession] (progress, tracks,
 * fallback to a transcode) with the desktop's controls ([PlayerContent]), in one of three modes ([PlayerFrame]).
 *
 * The window is made here ([PlayerFrame.create]) rather than by Compose's `Window(state = …)`: its place and size
 * belong to the window manager and [PlayerPlacement], not to a `WindowState` kept in step with them.
 */
@Composable
fun PlayerWindow(player: DesktopPlayer, screen: Screen.Player) {
    // The window's view models: cleared (and the playback released) when the window goes.
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm = koinViewModel<PlayerViewModel>()
        val watch = koinViewModel<WatchMenuViewModel>()
        val scope = rememberCoroutineScope()
        LaunchedEffect(screen) { vm.start(screen) }
        val session by vm.session.collectAsState()
        val info by vm.info.collectAsState()
        val host = remember { ComposeVideoHost() }
        DisposableEffect(session) {
            val s = session
            s?.attachVideo(host)
            onDispose { s?.detachVideo() }
        }
        val keys = remember { PlayerKeys() }
        val frame = remember { PlayerFrame(player) }
        val title = info?.let { i -> listOfNotNull(i.title, i.subtitle.takeIf { i.isEpisode }).joinToString(" · ") } ?: "SleepyOwl"
        Window(
            visible = true,
            onPreviewKeyEvent = { keys.handler(it) },
            onKeyEvent = { false },
            create = { frame.create(title) },
            dispose = { frame.dispose() },
            update = { it.title = title },
        ) {
            frame.Modes((host.engine as? MpvEngine)?.video?.displaySize ?: IntSize.Zero)
            val s = session
            if (s == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                ScaledContent(player.settings) {
                    PlayerContent(
                        s, vm, host.engine, player, frame, keys,
                        onClose = player::close,
                        onMarkWatched = { id, played ->
                            scope.launch {
                                runCatching { watch.setPlayed(id, played) }.onFailure { Log.w(it, "Player: marking watched failed") }
                            }
                        },
                    )
                }
            }
        }
    }
}

internal const val MIN_WIDTH = 320
internal const val MIN_HEIGHT = 180
private const val MINI_WIDTH = 640
private const val MINI_HEIGHT = 360
private const val MINI_MARGIN = 24
private const val WINDOW_WIDTH = 1280
private const val WINDOW_HEIGHT = 720

package ru.stenfredd.sleepyowl.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.SingletonImageLoader
import org.jetbrains.skia.Image
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.common.logToFile
import ru.stenfredd.sleepyowl.core.data.dataModule
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.core.jellyfin.jellyfinModule
import ru.stenfredd.sleepyowl.playback.core.PlaybackSession
import ru.stenfredd.sleepyowl.playback.core.playbackModule
import ru.stenfredd.sleepyowl.playback.mpv.MpvVideo
import ru.stenfredd.sleepyowl.playback.mpv.mpvEngineModule
import ru.stenfredd.sleepyowl.ui.uiModule
import ru.stenfredd.sleepyowl.ui.Screen
import java.awt.Dimension
import java.util.UUID
import java.awt.Frame
import java.awt.Toolkit
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `--play <item id> [--start <seconds>]` opens the player on that item right away (tests and checks of the engine; the
 * account must already be signed in).
 */
fun main(args: Array<String>) {
    val instance = SingleInstance.acquire(DesktopPaths.runtime) ?: run {
        System.err.println("SleepyOwl is already running: its window has been asked to show")
        return
    }
    // Before any window: mpv's own GL context must be able to coexist with Skiko's.
    MpvVideo.prepareProcess()
    logToFile(DesktopPaths.log)
    Log.i("SleepyOwl %s, Java %s, %s", BuildInfo.VERSION, System.getProperty("java.version"), System.getenv("DISPLAY"))
    Toolkit.getDefaultToolkit()
    X11App.setClassName("sleepyowl")

    val koin = startKoin { modules(jellyfinModule, dataModule, playbackModule, mpvEngineModule, uiModule, desktopModule()) }.koin
    val settings = koin.get<DesktopSettings>()
    SingletonImageLoader.setSafe { context -> imageLoader(context, koin.get(), koin.get()) }
    val icon = Main::class.java.getResourceAsStream("/icons/sleepyowl-256.png")?.use { BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }

    val player = koin.get<DesktopPlayer>()
    val media = koin.get<SystemMedia>().apply { start() }
    playArgument(args)?.let { screen ->
        // After the main window has restored the session.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            koin.get<JellyfinSession>().session.first { it != null }
            player.play(screen)
        }
    }
    application(exitProcessOnExit = false) {
        player.screen?.let { PlayerWindow(player, it) }
        val keys = remember { BackKeys() }
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp), position = WindowPosition(Alignment.Center), isMinimized = TestMode.passive)
        Window(
            onCloseRequest = ::exitApplication, state = state, title = "SleepyOwl", icon = icon, focusable = !TestMode.passive,
            onPreviewKeyEvent = keys::onPreview, onKeyEvent = keys::onKey,
        ) {
            LaunchedEffect(window) {
                window.minimumSize = Dimension(800, 500)
                instance.listen { SwingUtilities.invokeLater { raise(window) } }
            }
            ScaledContent(settings) { App(window, keys) }
        }
    }
    Log.i("Exit")
    // The player window is gone with the application; its last position is still on its way to the server.
    runBlocking {
        withTimeoutOrNull(3000) {
            PlaybackSession.awaitStopReports()
            player.placement.flush()
        }
    }
    media.close()
    instance.release()
    KoinPlatform.stopKoin()
    // Not exitProcess: on exit() the destructor of libGLX (glvnd) waits for AWT's X display lock, held by the AWT
    // thread the JVM has frozen — the L1 prototype hung on about every third exit.
    System.out.flush()
    System.err.flush()
    CLib._exit(0)
}

/** Brings [window] up: out of the taskbar if minimized, on top, with the focus. */
internal fun raise(window: Frame) {
    if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    window.toFront()
    window.requestFocus()
}

private fun playArgument(args: Array<String>): Screen.Player? {
    val id = args.indexOf("--play").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: return null
    val start = args.indexOf("--start").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toDoubleOrNull() } ?: 0.0
    val uuid = runCatching { UUID.fromString(id.replace(Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$"), "$1-$2-$3-$4-$5")) }.getOrNull() ?: return null
    return Screen.Player(uuid, engine = null, startPositionMs = (start * 1000).toLong())
}

/**
 * Automated runs on the user's own screen (the engine's measurements): `SLEEPYOWL_TEST_PASSIVE=1` keeps every window
 * from taking the focus (a test window once took the user's typing, L1) and starts the main window minimized;
 * `SLEEPYOWL_PLAYER_BOUNDS=x,y,w,h` places the player window (pixels).
 */
object TestMode {
    val passive = System.getenv("SLEEPYOWL_TEST_PASSIVE") == "1"
    val playerBounds: List<Int>? = System.getenv("SLEEPYOWL_PLAYER_BOUNDS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 }
}

/** The class of this file, for its resources. */
private object Main

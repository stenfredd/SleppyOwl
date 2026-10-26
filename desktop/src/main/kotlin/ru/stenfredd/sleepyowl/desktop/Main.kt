package ru.stenfredd.sleepyowl.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import ru.stenfredd.sleepyowl.core.jellyfin.jellyfinModule
import ru.stenfredd.sleepyowl.playback.core.playbackModule
import ru.stenfredd.sleepyowl.ui.uiModule
import java.awt.Dimension
import java.awt.Frame
import java.awt.Toolkit
import javax.swing.SwingUtilities

fun main() {
    val instance = SingleInstance.acquire(DesktopPaths.runtime) ?: run {
        System.err.println("SleepyOwl is already running: its window has been asked to show")
        return
    }
    logToFile(DesktopPaths.log)
    Log.i("SleepyOwl %s, Java %s, %s", BuildInfo.VERSION, System.getProperty("java.version"), System.getenv("DISPLAY"))
    Toolkit.getDefaultToolkit()
    X11App.setClassName("sleepyowl")

    val scale = UiScale.fromEnvironment()
    val koin = startKoin { modules(jellyfinModule, dataModule, playbackModule, uiModule, desktopModule(scale)) }.koin
    SingletonImageLoader.setSafe { context -> imageLoader(context, koin.get(), koin.get()) }
    val icon = Main::class.java.getResourceAsStream("/icons/sleepyowl-256.png")?.use { BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }

    application(exitProcessOnExit = false) {
        val keys = remember { BackKeys() }
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp), position = WindowPosition(Alignment.Center))
        Window(onCloseRequest = ::exitApplication, state = state, title = "SleepyOwl", icon = icon, onPreviewKeyEvent = keys::onPreview, onKeyEvent = keys::onKey) {
            LaunchedEffect(window) {
                window.minimumSize = Dimension(800, 500)
                instance.listen {
                    SwingUtilities.invokeLater {
                        if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                        window.toFront()
                        window.requestFocus()
                    }
                }
            }
            val system = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(system.density * scale.factor, system.fontScale)) {
                App(window, keys)
            }
        }
    }
    Log.i("Exit")
    instance.release()
    KoinPlatform.stopKoin()
    // Not exitProcess: on exit() the destructor of libGLX (glvnd) waits for AWT's X display lock, held by the AWT
    // thread the JVM has frozen — the L1 prototype hung on about every third exit.
    System.out.flush()
    System.err.flush()
    CLib._exit(0)
}

/** The class of this file, for its resources. */
private object Main

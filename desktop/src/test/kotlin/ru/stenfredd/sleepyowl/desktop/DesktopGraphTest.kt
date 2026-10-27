package ru.stenfredd.sleepyowl.desktop

import okhttp3.OkHttpClient
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import ru.stenfredd.sleepyowl.core.data.dataModule
import ru.stenfredd.sleepyowl.core.jellyfin.jellyfinModule
import ru.stenfredd.sleepyowl.playback.api.EngineProvider
import ru.stenfredd.sleepyowl.playback.core.playbackModule
import ru.stenfredd.sleepyowl.playback.mpv.mpvEngineModule
import ru.stenfredd.sleepyowl.ui.uiModule
import java.io.File
import java.util.UUID

/** The desktop app's Koin graph, as [main] starts it: every definition finds what it needs. */
class DesktopGraphTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun graphIsComplete() {
        module { includes(jellyfinModule, dataModule, playbackModule, mpvEngineModule, uiModule, desktopModule()) }.verify(
            // UUID is the id view models get through parametersOf; File and OkHttpClient.Builder are used inside lambdas;
            // EngineProvider: EngineSelector takes all of them (getAll), which verify() can't follow.
            extraTypes = listOf(UUID::class, OkHttpClient.Builder::class, File::class, EngineProvider::class),
        )
    }
}

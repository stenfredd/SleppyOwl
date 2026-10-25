package ru.stenfredd.sleepyowl.ui

import okhttp3.OkHttpClient
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.test.verify.verify
import ru.stenfredd.sleepyowl.core.common.PreferenceStores
import ru.stenfredd.sleepyowl.core.common.desktopAppDirs
import ru.stenfredd.sleepyowl.core.data.dataModule
import ru.stenfredd.sleepyowl.core.jellyfin.DesktopJellyfinPlatform
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinPlatform
import ru.stenfredd.sleepyowl.core.jellyfin.jellyfinModule
import ru.stenfredd.sleepyowl.playback.api.EngineProvider
import ru.stenfredd.sleepyowl.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.sleepyowl.playback.core.playbackModule
import java.io.File
import java.util.UUID

/**
 * The shared view models on the desktop: everything they need comes from the shared modules plus the few platform
 * definitions the desktop app will add (L4); the engine and its probe come with L6.
 */
class DesktopKoinGraphTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun graphIsComplete() {
        val desktop = module {
            single { desktopAppDirs() }
            singleOf(::PreferenceStores)
            single<JellyfinPlatform> { DesktopJellyfinPlatform() }
            single<DeviceCapabilitiesProbe> { error("L6") }
            single<UiPlatform> { error("L4") }
        }
        module { includes(jellyfinModule, dataModule, playbackModule, uiModule, desktop) }.verify(
            // UUID is the id view models get through parametersOf; File and OkHttpClient.Builder are used inside lambdas;
            // EngineProvider: EngineSelector takes all of them (getAll); the desktop has none until mpv (L6).
            extraTypes = listOf(UUID::class, OkHttpClient.Builder::class, File::class, EngineProvider::class),
        )
    }
}

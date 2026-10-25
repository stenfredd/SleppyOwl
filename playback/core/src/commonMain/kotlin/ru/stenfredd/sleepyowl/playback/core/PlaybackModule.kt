package ru.stenfredd.sleepyowl.playback.core

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin: engine choice and playback sessions. The platform adds a [DeviceCapabilitiesProbe]; every engine module adds
 * its `EngineProvider`.
 */
val playbackModule = module {
    single { EngineSelector(getAll()) }
    singleOf(::PlaybackPreferences)
    singleOf(::PlaybackSessionFactory)
}

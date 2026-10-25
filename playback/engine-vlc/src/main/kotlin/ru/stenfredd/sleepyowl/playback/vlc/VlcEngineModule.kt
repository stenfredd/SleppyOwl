package ru.stenfredd.sleepyowl.playback.vlc

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import ru.stenfredd.sleepyowl.playback.api.EngineProvider

val vlcEngineModule = module {
    single { VlcEngineProvider(androidContext()) } bind EngineProvider::class
}

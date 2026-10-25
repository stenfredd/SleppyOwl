package ru.stenfredd.sleepyowl.playback.exo

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import ru.stenfredd.sleepyowl.playback.api.EngineProvider

val exoEngineModule = module {
    single { ExoEngineProvider(androidContext()) } bind EngineProvider::class
}

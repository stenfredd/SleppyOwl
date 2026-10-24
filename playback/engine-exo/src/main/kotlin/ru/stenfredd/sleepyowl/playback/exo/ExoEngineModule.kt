package ru.stenfredd.sleepyowl.playback.exo

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.stenfredd.sleepyowl.playback.api.EngineProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class ExoEngineModule {
    @Binds
    @IntoSet
    abstract fun bindProvider(provider: ExoEngineProvider): EngineProvider
}

package ru.stenfredd.movies.playback.vlc

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.stenfredd.movies.playback.api.EngineProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class VlcEngineModule {
    @Binds
    @IntoSet
    abstract fun bindProvider(provider: VlcEngineProvider): EngineProvider
}

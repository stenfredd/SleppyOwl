package ru.stenfredd.sleepyowl.core.jellyfin

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** One HTTP client for the API (SDK) and images (Coil): shared connection pool, LAN routing via [ServerRouting]. */
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {
    @Provides
    @Singleton
    fun serverRouting(): ServerRouting = ServerRouting()

    @Provides
    @Singleton
    fun okHttpClient(routing: ServerRouting): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(routing)
        .build()
}

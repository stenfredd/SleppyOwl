package ru.stenfredd.sleepyowl.core.jellyfin

import okhttp3.OkHttpClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin: the session, login and HTTP of the shared core; the platform adds [JellyfinPlatform] and `PreferenceStores`.
 * One HTTP client for the API (SDK) and images (Coil): shared connection pool, LAN routing via [ServerRouting].
 */
val jellyfinModule = module {
    singleOf(::ServerRouting)
    single { OkHttpClient.Builder().addInterceptor(get<ServerRouting>()).build() }
    singleOf(::JellyfinSession)
    singleOf(::ServerLogin)
}

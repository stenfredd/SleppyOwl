package ru.stenfredd.sleepyowl.core.data

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** Koin: repositories and caches of the shared core (see [ru.stenfredd.sleepyowl.core.jellyfin.jellyfinModule]). */
val dataModule = module {
    singleOf(::SessionCache)
    singleOf(::LibraryRepository)
    singleOf(::WatchRepository)
    singleOf(::HomeRepository)
    singleOf(::UserSettingsRepository)
    singleOf(::ImageUrls)
    singleOf(::SearchHistory)
}

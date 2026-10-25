package ru.stenfredd.sleepyowl.ui

import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import ru.stenfredd.sleepyowl.ui.details.DetailsViewModel
import ru.stenfredd.sleepyowl.ui.favorites.FavoritesViewModel
import ru.stenfredd.sleepyowl.ui.home.HomeViewModel
import ru.stenfredd.sleepyowl.ui.library.LibraryMemory
import ru.stenfredd.sleepyowl.ui.library.LibraryViewModel
import ru.stenfredd.sleepyowl.ui.login.LoginViewModel
import ru.stenfredd.sleepyowl.ui.menu.WatchMenuViewModel
import ru.stenfredd.sleepyowl.ui.model.CardMapper
import ru.stenfredd.sleepyowl.ui.person.PersonViewModel
import ru.stenfredd.sleepyowl.ui.player.PlayerViewModel
import ru.stenfredd.sleepyowl.ui.search.SearchViewModel
import ru.stenfredd.sleepyowl.ui.series.SeriesViewModel
import ru.stenfredd.sleepyowl.ui.settings.SettingsViewModel

/**
 * Koin: the shared view models and what they need besides the core. The app adds its [UiPlatform]. View models with
 * an id (Details, Person, Series) get it from `parametersOf(id)`: Koin hands an injected parameter to the constructor
 * argument of its type.
 */
val uiModule = module {
    singleOf(::LibraryMemory)
    singleOf(::Library)
    singleOf(::CardMapper)

    viewModelOf(::BrowseViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::LibraryViewModel)
    viewModelOf(::DetailsViewModel)
    viewModelOf(::SeriesViewModel)
    viewModelOf(::PersonViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::FavoritesViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::PlayerViewModel)
    viewModelOf(::WatchMenuViewModel)
}

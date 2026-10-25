package ru.stenfredd.sleepyowl.ui.shell

import ru.stenfredd.sleepyowl.ui.components.AppIcon
import java.util.UUID

/** The root screens of the side rail. */
enum class Tab(val label: String, val icon: AppIcon) {
    Search("Поиск", AppIcon.Search),
    Home("Главная", AppIcon.Home),
    Library("Медиатека", AppIcon.Library),
    Favorites("Избранное", AppIcon.Heart),
    /** Not in the mockup; at the bottom of the rail, above the profile. */
    Settings("Настройки", AppIcon.Settings),
}

/** A library listed on the rail under the tabs; choosing it opens the library tab on it. */
data class RailLibrary(val id: UUID, val name: String, val icon: AppIcon)

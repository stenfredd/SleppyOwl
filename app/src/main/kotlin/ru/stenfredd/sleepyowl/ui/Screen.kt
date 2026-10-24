package ru.stenfredd.sleepyowl.ui

import ru.stenfredd.sleepyowl.playback.api.EngineId
import ru.stenfredd.sleepyowl.playback.api.StreamSelection
import java.util.UUID

sealed interface Screen {
    /** Login flow; also "сменить профиль" from the menu (then Back returns to the app). */
    data object Login : Screen

    /**
     * Root screens with the side rail. [account] keeps each account's screens (and their view models) apart:
     * after a profile switch the stack is rebuilt, and an equal entry would be kept with the old account's state.
     * [library]: the library tab opened on a library from the rail; a new entry, so the tab starts over on it.
     */
    data class Root(val tab: ru.stenfredd.sleepyowl.ui.shell.Tab, val account: String, val library: UUID? = null) : Screen

    /** Collections and folders: the prototype's plain grid until they get a real screen. */
    data class Items(val parentId: UUID, val title: String) : Screen
    data class Detail(val itemId: UUID) : Screen

    /** A person: photo, name and their movies and series. */
    data class Person(val personId: UUID) : Screen

    /** Series page; [episodeId] = the episode to show first (a new episode, continue watching), else the next one to watch. */
    data class Series(val seriesId: UUID, val episodeId: UUID? = null) : Screen
    data class Player(
        val itemId: UUID,
        val engine: EngineId?,
        val startPositionMs: Long,
        /** Tracks to start with instead of the file's defaults. */
        val streams: StreamSelection? = null,
        /** Start at this fraction of the runtime instead of [startPositionMs]. */
        val startFraction: Double? = null,
    ) : Screen

    /** Text of a licence from "О приложении" in the settings; [asset] is a file in `assets/licenses`. */
    data class License(val name: String, val license: String, val asset: String) : Screen

    /** Debug tour: plays a prepared list of files covering every format on the server, see [TourScreen]. */
    data class Tour(val startIndex: Int = 0) : Screen
}

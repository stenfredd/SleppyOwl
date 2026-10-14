package ru.stenfredd.movies.ui

import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.StreamSelection
import java.util.UUID

sealed interface Screen {
    /** Login flow; also "сменить профиль" from the menu (then Back returns to the app). */
    data object Login : Screen

    /**
     * Root screens with the side rail. [account] keeps each account's screens (and their view models) apart:
     * after a profile switch the stack is rebuilt, and an equal entry would be kept with the old account's state.
     */
    data class Root(val tab: ru.stenfredd.movies.ui.shell.Tab, val account: String) : Screen

    /** Old prototype home (libraries, text search, logout, debug tour) while the real screens are being built. */
    data object Prototype : Screen
    data class Items(val parentId: UUID, val title: String) : Screen
    data class Detail(val itemId: UUID) : Screen

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

    /** Debug tour: plays a prepared list of files covering every format on the server, see [TourScreen]. */
    data class Tour(val startIndex: Int = 0) : Screen
}

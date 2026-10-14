package ru.stenfredd.movies.ui

import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.StreamSelection
import java.util.UUID

sealed interface Screen {
    data object Login : Screen

    /** Root screens with the side rail. */
    data class Root(val tab: ru.stenfredd.movies.ui.shell.Tab) : Screen

    /** Old prototype home (libraries, text search, logout, debug tour) while the real screens are being built. */
    data object Prototype : Screen
    data class Items(val parentId: UUID, val title: String) : Screen
    data class Detail(val itemId: UUID) : Screen
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

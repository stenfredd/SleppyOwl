package ru.stenfredd.movies.ui

import ru.stenfredd.movies.playback.api.EngineId
import java.util.UUID

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data class Items(val parentId: UUID, val title: String) : Screen
    data class Detail(val itemId: UUID) : Screen
    data class Player(val itemId: UUID, val engine: EngineId?, val startPositionMs: Long) : Screen
}

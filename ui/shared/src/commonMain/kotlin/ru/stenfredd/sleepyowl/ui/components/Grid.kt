package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/*
 * Screens built as one lazy grid of posters (library, favourites, search results, a person's films): headers and
 * rows of cards are full-width items of the grid, posters are its cells, so only what is on screen is composed.
 */

/** A full-width item of the grid (header, row of cards). */
fun LazyGridScope.fullItem(key: String, type: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = type) { content() }

/**
 * Lays a full-width item out [width] wide starting [side] to the left of the grid's content, i.e. outside the grid's
 * side padding: rows of cards then scroll from the screen edge to edge, as on home.
 */
fun Modifier.bleed(width: Dp, side: Dp) = layout { measurable, constraints ->
    val w = width.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-side.roundToPx(), 0) }
}

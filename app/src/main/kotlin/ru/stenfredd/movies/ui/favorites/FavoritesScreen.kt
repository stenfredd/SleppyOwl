package ru.stenfredd.movies.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.AnchoredScrolling
import ru.stenfredd.movies.ui.components.Chip
import ru.stenfredd.movies.ui.components.PosterCard
import ru.stenfredd.movies.ui.components.anchorViewport
import ru.stenfredd.movies.ui.components.anchoredRow
import ru.stenfredd.movies.ui.components.fullItem
import ru.stenfredd.movies.ui.focus.ForgetMissing
import ru.stenfredd.movies.ui.focus.Restore
import ru.stenfredd.movies.ui.focus.rememberScreenFocus
import ru.stenfredd.movies.ui.focus.remembered
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppDimens
import ru.stenfredd.movies.ui.theme.AppType

/** Favourites tab (mockup screen «Избранное»): title with the count, library filter chips, grid of posters. */
@Composable
fun FavoritesScreen(onOpen: (Screen) -> Unit, vm: FavoritesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = rememberScreenFocus()
    val grid = rememberLazyGridState()

    when {
        state.loading -> Message("Загрузка…")
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
        else -> AnchoredScrolling(grid, anchor = AppDimens.CONTENT_TOP.dp) { anchored ->
            LazyVerticalGrid(
                columns = GridCells.FixedSize(AppDimens.POSTER_W.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().anchorViewport(anchored),
                contentPadding = PaddingValues(start = Side, end = Side, top = AppDimens.CONTENT_TOP.dp, bottom = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(GridGap),
            ) {
                fullItem("header", "header") {
                    Column(Modifier.anchoredRow(anchored).padding(bottom = 18.dp)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            BasicText("Избранное", style = AppType.Display.copy(fontSize = 28.sp))
                            BasicText(state.total, Modifier.padding(bottom = 4.dp), style = AppType.MonoCount.copy(fontSize = 11.sp, color = AppColors.TextDim))
                        }
                        Row(Modifier.padding(top = 15.dp).focusRestorer(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (f in state.filters) {
                                Chip(
                                    f.label,
                                    f.libraryId == state.selected,
                                    { vm.select(f.libraryId) },
                                    Modifier.remembered(focus, f.key, isDefault = f.key == FavoritesViewModel.ALL_KEY),
                                    detail = f.count.toString(),
                                )
                            }
                        }
                    }
                }
                if (state.items.isEmpty()) {
                    fullItem("empty", "text") {
                        BasicText(
                            "Здесь появятся фильмы и сериалы, отмеченные сердечком на их странице.",
                            Modifier.padding(top = 12.dp).widthIn(max = 550.dp),
                            style = AppType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        )
                    }
                }
                items(state.items, key = { it.key }, contentType = { "poster" }) { p ->
                    PosterCard(
                        p,
                        { vm.target(p.key)?.let(onOpen) },
                        Modifier.padding(bottom = RowGap).anchoredRow(anchored).remembered(focus, p.key),
                    )
                }
            }
        }
    }
    val ready = !state.loading && state.error == null
    if (ready) focus.ForgetMissing(state.filters.mapTo(HashSet()) { it.key } + state.items.map { it.key })
    focus.Restore(ready = ready)
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BasicText(text, style = AppType.Body) }
}

private val Side = AppDimens.CONTENT_START.dp
private val GridGap = 14.dp
private val RowGap = 18.dp

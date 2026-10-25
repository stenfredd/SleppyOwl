package ru.stenfredd.sleepyowl.ui.person

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AnchoredScrolling
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.anchorViewport
import ru.stenfredd.sleepyowl.ui.components.anchoredRow
import ru.stenfredd.sleepyowl.ui.components.fullItem
import ru.stenfredd.sleepyowl.ui.focus.Restore
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * A person (not in the mockup, built in its style like the favourites screen): photo, name, "12 фильмов · 3 сериала",
 * a grid of their movies and series. Full screen without the rail, like the movie page.
 */
@Composable
fun PersonScreen(
    screen: Screen.Person,
    onOpen: (Screen) -> Unit,
    vm: PersonViewModel = koinViewModel { parametersOf(screen.personId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = rememberScreenFocus()
    val grid = rememberLazyGridState()

    when {
        state.loading -> Box(Modifier.fillMaxSize())
        state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Не удалось загрузить: ${state.error}", style = AppType.Body)
        }
        else -> AnchoredScrolling(grid, anchor = AppDimens.CONTENT_TOP.dp) { anchored ->
            LazyVerticalGrid(
                columns = GridCells.FixedSize(AppDimens.POSTER_W.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().anchorViewport(anchored),
                contentPadding = PaddingValues(start = Side, end = Side, top = AppDimens.CONTENT_TOP.dp, bottom = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                fullItem("header", "header") {
                    Row(Modifier.padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val size = PersonLayout.PHOTO.dp
                        if (state.photo != null) {
                            RemoteImage(state.photo, size / 2, Modifier.size(size))
                        } else {
                            Avatar(state.name, state.name, size, AppType.Hero.copy(fontSize = 22.sp), letters = state.letters)
                        }
                        Column {
                            BasicText("ЧЕЛОВЕК", style = AppType.MonoLabel)
                            BasicText(state.name, Modifier.padding(top = 4.dp), style = AppType.Display.copy(fontSize = 28.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(state.summary, Modifier.padding(top = 4.dp), style = AppType.MonoMeta.copy(fontSize = 11.sp, color = AppColors.TextMuted))
                        }
                    }
                }
                items(state.items, key = { it.key }, contentType = { "poster" }) { p ->
                    PosterCard(
                        p,
                        { vm.target(p.key)?.let(onOpen) },
                        Modifier.padding(bottom = 18.dp).anchoredRow(anchored).remembered(focus, p.key, isDefault = p === state.items.first()),
                    )
                }
            }
        }
    }
    focus.Restore(ready = !state.loading && state.error == null)
}

private val Side = AppDimens.CONTENT_START.dp

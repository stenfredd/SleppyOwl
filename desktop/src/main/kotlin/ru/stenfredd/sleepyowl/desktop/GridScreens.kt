package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import ru.stenfredd.sleepyowl.ui.BrowseViewModel
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.RemoteImageSpec
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.components.fullItem
import ru.stenfredd.sleepyowl.ui.favorites.FavoritesViewModel
import ru.stenfredd.sleepyowl.ui.focus.ForgetMissing
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.isPlayable
import ru.stenfredd.sleepyowl.ui.label
import ru.stenfredd.sleepyowl.ui.person.PersonLayout
import ru.stenfredd.sleepyowl.ui.person.PersonViewModel
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/*
 * Screens that are one grid of posters under a header: favourites, a person, a collection or folder. The grid has as
 * many columns as the window fits.
 */

@Composable
private fun PosterGrid(header: @Composable () -> Unit, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.FixedSize(AppDimens.POSTER_W.dp),
        state = rememberLazyGridState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Side, end = Side, top = AppDimens.CONTENT_TOP.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        fullItem("header", "header", header)
        content()
    }
}

/** Favourites tab: title with the count, library filter chips, grid of posters. */
@Composable
fun FavoritesScreen(onOpen: (Screen) -> Unit, vm: FavoritesViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    val focus = rememberScreenFocus()
    when {
        state.loading -> Message("Загрузка…")
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
        else -> PosterGrid(
            header = {
                Column(Modifier.padding(bottom = 18.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        BasicText("Избранное", style = AppType.Display.copy(fontSize = 28.sp))
                        BasicText(state.total, Modifier.padding(bottom = 4.dp), style = AppType.MonoCount.copy(fontSize = 11.sp, color = AppColors.TextDim))
                    }
                    FlowRow(Modifier.padding(top = 15.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (f in state.filters) {
                            Chip(
                                f.label,
                                f.libraryId == state.selected,
                                { vm.select(f.libraryId) },
                                Modifier.hoverFocus().remembered(focus, f.key, isDefault = f.key == FavoritesViewModel.ALL_KEY),
                                detail = f.count.toString(),
                            )
                        }
                    }
                }
            },
        ) {
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
                PosterCard(p, { vm.target(p.key)?.let(onOpen) }, Modifier.padding(bottom = RowGap).hoverFocus().remembered(focus, p.key))
            }
        }
    }
    val ready = !state.loading && state.error == null
    if (ready) focus.ForgetMissing(state.filters.mapTo(HashSet()) { it.key } + state.items.map { it.key })
    focus.Entry(ready = ready)
}

/** A person: photo, name, "12 фильмов · 3 сериала", a grid of their movies and series. */
@Composable
fun PersonScreen(screen: Screen.Person, onOpen: (Screen) -> Unit, vm: PersonViewModel = koinViewModel { parametersOf(screen.personId) }) {
    val state by vm.state.collectAsState()
    val focus = rememberScreenFocus()
    when {
        state.loading -> Box(Modifier.fillMaxSize())
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
        else -> PosterGrid(
            header = {
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
            },
        ) {
            items(state.items, key = { it.key }, contentType = { "poster" }) { p ->
                PosterCard(
                    p,
                    { vm.target(p.key)?.let(onOpen) },
                    Modifier.padding(bottom = RowGap).hoverFocus().remembered(focus, p.key, isDefault = p === state.items.first()),
                )
            }
        }
    }
    focus.Entry(ready = !state.loading && state.error == null)
}

/** Collections and folders: the TV's plain grid of what is inside. */
@Composable
fun ItemsScreen(screen: Screen.Items, onOpen: (Screen) -> Unit, vm: BrowseViewModel = koinViewModel()) {
    var items by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = rememberScreenFocus()
    LaunchedEffect(screen.parentId) {
        runCatching { items = vm.library.children(screen.parentId) }.onFailure { error = it.message }
    }
    PosterGrid(
        header = {
            Column(Modifier.padding(bottom = 18.dp)) {
                BasicText(screen.title, style = AppType.Display.copy(fontSize = 28.sp))
                error?.let { BasicText("Не удалось загрузить: $it", Modifier.padding(top = 12.dp), style = AppType.Body.copy(color = AppColors.Red)) }
                if (items == null && error == null) BasicText("Загрузка…", Modifier.padding(top = 12.dp), style = AppType.Body)
            }
        },
    ) {
        items(items.orEmpty(), key = { it.id }) { item ->
            Column(Modifier.padding(bottom = RowGap)) {
                Box(
                    Modifier
                        .hoverFocus()
                        .remembered(focus, item.id.toString())
                        .focusCard(FocusStyle.Poster)
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clickable(interactionSource = null, indication = null) { onOpen(item.target()) },
                ) {
                    val url = remember(item.id) { vm.library.posterUrl(item) }
                    RemoteImage(url?.let { RemoteImageSpec(it) }, 7.dp, Modifier.fillMaxSize())
                    if (url == null) BasicText(item.name.orEmpty(), Modifier.padding(8.dp), style = AppType.PosterTitle)
                }
                BasicText(item.label, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = AppType.MonoCaption)
            }
        }
    }
    focus.Entry(ready = items != null)
}

private fun BaseItemDto.target(): Screen = when {
    type == BaseItemKind.SERIES -> Screen.Series(id)
    isPlayable -> Screen.Detail(id)
    else -> Screen.Items(id, name.orEmpty())
}

private val Side = AppDimens.CONTENT_START.dp
private val RowGap = 18.dp

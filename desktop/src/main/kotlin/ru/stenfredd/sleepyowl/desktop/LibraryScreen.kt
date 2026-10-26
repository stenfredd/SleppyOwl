package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.WideCard
import ru.stenfredd.sleepyowl.ui.components.bleed
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.components.fullItem
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.library.LibraryContent
import ru.stenfredd.sleepyowl.ui.library.LibraryLayout
import ru.stenfredd.sleepyowl.ui.library.LibraryState
import ru.stenfredd.sleepyowl.ui.library.LibraryTile
import ru.stenfredd.sleepyowl.ui.library.LibraryViewModel
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * The library tab, as on the TV: library tiles; under them, for the selected library, continue watching, recently
 * added, watch history and the whole library as a grid with sort and filter chips. One lazy grid, so a library of
 * thousands only composes what is on screen; it has as many columns as the window fits. A tile is picked by a click
 * (on the TV focus picks it: here the pointer passing over the tiles would switch libraries).
 */
@Composable
fun LibraryScreen(onOpen: (Screen) -> Unit, vm: LibraryViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    val focus = rememberScreenFocus()
    when {
        state.loading -> Message("Загрузка…")
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
        state.tiles.isEmpty() -> Message("На сервере нет библиотек с видео")
        else -> Library(state, vm, focus, onOpen)
    }
    focus.Entry(ready = !state.loading && state.error == null && state.tiles.isNotEmpty())
}

@Composable
private fun Library(state: LibraryState, vm: LibraryViewModel, focus: ScreenFocus, onOpen: (Screen) -> Unit) {
    val open = { key: String -> vm.target(key)?.let(onOpen) ?: Unit }
    val grid = rememberLazyGridState()
    val content = state.content
    LoadMore(grid, vm)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Full-width items reach from the window edge to edge, outside the grid's side padding (see [bleed]).
        val fullWidth = maxWidth
        LazyVerticalGrid(
            columns = GridCells.FixedSize(AppDimens.POSTER_W.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Side, end = Side, top = AppDimens.CONTENT_TOP.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(LibraryLayout.GRID_GAP.dp),
        ) {
            fullItem("tiles", "tiles") {
                Tiles(state, vm, focus, Modifier.bleed(fullWidth, Side))
            }
            if (content == null) {
                state.contentError?.let { error -> fullItem("error", "text") { Note("Не удалось загрузить: $error") } }
                return@LazyVerticalGrid
            }
            rows(content, focus, open, fullWidth)
            fullItem("all", "all") { AllHeader(content, vm, focus) }
            items(content.grid, key = { it.key }, contentType = { "poster" }) { p ->
                PosterCard(p, { open(p.key) }, Modifier.padding(bottom = LibraryLayout.GRID_ROW_GAP.dp).hoverFocus().remembered(focus, p.key))
            }
            if (content.grid.isEmpty()) {
                fullItem("grid-note", "text") {
                    Note(
                        when {
                            !content.gridComplete -> "Загрузка…"
                            content.unwatched -> "Всё просмотрено"
                            else -> "Здесь пока пусто"
                        },
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.rows(content: LibraryContent, focus: ScreenFocus, open: (String) -> Unit, fullWidth: Dp) {
    val id = content.libraryId
    if (content.continueItems.isNotEmpty()) {
        fullItem("$id/cont", "wide-row") {
            ContentRow("Продолжить просмотр", content.continueItems.size, Modifier.bleed(fullWidth, Side).padding(top = RowGap)) {
                items(content.continueItems, key = { it.key }, contentType = { "wide" }) { w ->
                    WideCard(w, { open(w.key) }, Modifier.hoverFocus().remembered(focus, w.key))
                }
            }
        }
    }
    for ((name, title, posters) in listOf(Triple("new", "Недавно добавлено", content.latest), Triple("hist", "История просмотра", content.history))) {
        if (posters.isEmpty()) continue
        fullItem("$id/$name", "poster-row") {
            ContentRow(title, posters.size, Modifier.bleed(fullWidth, Side).padding(top = RowGap)) {
                items(posters, key = { it.key }, contentType = { "poster" }) { p ->
                    PosterCard(p, { open(p.key) }, Modifier.hoverFocus().remembered(focus, p.key))
                }
            }
        }
    }
}

@Composable
private fun Tiles(state: LibraryState, vm: LibraryViewModel, focus: ScreenFocus, modifier: Modifier = Modifier) {
    ScrollRow(
        modifier,
        // The selected tile in view from the start: it can be far right when opened from the rail.
        state = rememberLazyListState((state.tiles.indexOfFirst { it.id == state.selected } - 2).coerceAtLeast(0)),
        contentPadding = PaddingValues(horizontal = AppDimens.CONTENT_START.dp, vertical = 8.dp),
    ) {
        items(state.tiles, key = { it.key }, contentType = { "tile" }) { tile ->
            LibraryTileCard(
                tile,
                selected = tile.id == state.selected,
                onClick = { vm.selectLibrary(tile.id) },
                modifier = Modifier.hoverFocus().remembered(focus, tile.key, isDefault = tile.id == state.selected),
            )
        }
    }
}

@Composable
private fun LibraryTileCard(tile: LibraryTile, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LibraryLayout.TILE_CORNER.dp)
    Box(
        modifier
            .focusCard(TileFocus)
            .size(LibraryLayout.TILE_W.dp, LibraryLayout.TILE_H.dp)
            .clip(shape)
            .background(AppColors.Surface)
            .border(0.5.dp, if (selected) AppColors.SelectedBorder else TileBorder, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        TileArt(tile.art, if (selected) 1f else 0.55f)
        Row(
            Modifier.padding(start = 15.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(6.dp).background(tile.dot, CircleShape))
            BasicText("БИБЛИОТЕКА", style = AppType.MonoLabel.copy(fontSize = 8.5.sp, color = AppColors.TextDim))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 15.dp, end = 15.dp, bottom = 15.dp)) {
            BasicText(tile.name, style = AppType.Hero.copy(fontSize = 23.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Reserved while the count loads, so the name doesn't jump.
            BasicText(tile.stats ?: " ", Modifier.padding(top = 4.dp), style = AppType.MonoMeta.copy(fontSize = 10.sp, color = AppColors.TextMuted), maxLines = 1)
        }
    }
}

/**
 * The tile's background from the mockup: three posters tilted on the right, a fade from the left. The TV bakes it into
 * one bitmap for its weak GPU; here it is drawn as it is.
 */
@Composable
private fun TileArt(urls: List<String>, alpha: Float) {
    if (urls.isEmpty()) return
    val w = LibraryLayout.ART_POSTER_W
    val h = LibraryLayout.ART_POSTER_H
    val gap = 6f
    // `right:-20px; top:-20px; transform:rotate(8deg)` in mockup px, each next poster 18 px lower.
    val rowWidth = urls.size * w + (urls.size - 1) * gap
    Box(
        Modifier
            .offset((LibraryLayout.TILE_W + 10 - rowWidth).dp, (-10).dp)
            .size(rowWidth.dp, (h + 9 * urls.size).dp)
            .graphicsLayer {
                rotationZ = 8f
                this.alpha = alpha
            },
    ) {
        urls.forEachIndexed { i, url ->
            AsyncImage(
                url,
                contentDescription = null,
                modifier = Modifier.offset((i * (w + gap)).dp, (i * 9).dp).size(w.dp, h.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
    // `linear-gradient(90deg, #12141c 40%, rgba(18,20,28,.2))`
    Spacer(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to AppColors.Surface, 0.4f to AppColors.Surface, 1f to AppColors.Surface.copy(alpha = 0.2f)),
        ),
    )
}

/** "Все сериалы 171  [А–Я] [Добавлено] [Год] [Рейтинг] [Непросмотренное]"; the chips go under the title when narrow. */
@Composable
private fun AllHeader(content: LibraryContent, vm: LibraryViewModel, focus: ScreenFocus) {
    FlowRow(
        Modifier.padding(top = 22.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.padding(end = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(content.allTitle, style = AppType.Section)
            content.allCount?.let { BasicText(it.toString(), Modifier.padding(start = 8.dp, top = 3.dp), style = AppType.MonoCount) }
        }
        for (chip in vm.sortChips) {
            Chip(chip.label, chip.sort == content.sort, { vm.setSort(chip.sort) }, Modifier.hoverFocus().remembered(focus, chip.key))
        }
        Chip(
            (if (content.unwatched) "✓ " else "") + "Непросмотренное",
            content.unwatched,
            vm::toggleUnwatched,
            Modifier.hoverFocus().remembered(focus, LibraryViewModel.UNWATCHED_KEY),
        )
    }
}

/** Asks for the next page when the grid's last loaded cards come into view. */
@Composable
private fun LoadMore(grid: LazyGridState, vm: LibraryViewModel) {
    LaunchedEffect(grid) {
        snapshotFlow {
            val info = grid.layoutInfo
            // With the item count, so a page that still leaves the end in view asks for the next one.
            ((info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - PREFETCH) to info.totalItemsCount
        }.distinctUntilChanged().collect { (nearEnd, _) -> if (nearEnd) vm.loadMore() }
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.padding(top = 8.dp), style = AppType.Body)
}

private val Side = AppDimens.CONTENT_START.dp
private val RowGap = (AppDimens.SECTION_GAP - 14).dp
private val TileBorder = Color(0xFF262A3A)
private val TileFocus = FocusStyle.Tile.copy(corner = LibraryLayout.TILE_CORNER.dp)

/** Next page is asked for this many cards before the end. */
private const val PREFETCH = 24

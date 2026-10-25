package ru.stenfredd.sleepyowl.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AnchoredScroll
import ru.stenfredd.sleepyowl.ui.components.AnchoredScrolling
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.ContentRow
import ru.stenfredd.sleepyowl.ui.components.DefaultScrolling
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.WideCard
import ru.stenfredd.sleepyowl.ui.components.anchorViewport
import ru.stenfredd.sleepyowl.ui.components.anchoredRow
import ru.stenfredd.sleepyowl.ui.components.bleed
import ru.stenfredd.sleepyowl.ui.components.fullItem
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.focus.Restore
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * The library tab (mockup screen «Медиатека»): library tiles on top; under them, for the selected library,
 * continue watching, recently added, watch history, then the whole library as a grid with sort and filter chips.
 *
 * The screen is one lazy grid: rows and headers are full-width items, posters are its cells, so a library of
 * thousands of items only composes what is on screen. Vertical scrolling is [AnchoredScrolling], as on home.
 */
@Composable
fun LibraryScreen(onOpen: (Screen) -> Unit, vm: LibraryViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = rememberScreenFocus()
    val ready = !state.loading && state.error == null && state.tiles.isNotEmpty()

    when {
        state.loading -> Message("Загрузка…")
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
        state.tiles.isEmpty() -> Message("На сервере нет библиотек с видео")
        else -> Library(state, vm, focus, onOpen)
    }
    focus.Restore(ready = ready)
}

@Composable
private fun Library(state: LibraryState, vm: LibraryViewModel, focus: ScreenFocus, onOpen: (Screen) -> Unit) {
    val open = { key: String -> vm.target(key)?.let(onOpen) ?: Unit }
    val grid = rememberLazyGridState()
    val content = state.content
    LoadMore(grid, vm)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Full-width items reach from the screen edge to edge, outside the grid's side padding (see [bleed]).
        val fullWidth = maxWidth
        AnchoredScrolling(grid, anchor = AppDimens.CONTENT_TOP.dp) { anchored ->
            LazyVerticalGrid(
                columns = GridCells.FixedSize(AppDimens.POSTER_W.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().anchorViewport(anchored),
                contentPadding = PaddingValues(start = Side, end = Side, top = AppDimens.CONTENT_TOP.dp, bottom = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(LibraryLayout.GRID_GAP.dp),
            ) {
                fullItem("tiles", "tiles") {
                    Tiles(state, vm, focus, Modifier.bleed(fullWidth, Side).anchoredRow(anchored))
                }
                if (content == null) {
                    state.contentError?.let { error -> fullItem("error", "text") { Note("Не удалось загрузить: $error") } }
                    return@LazyVerticalGrid
                }
                rows(content, focus, open, fullWidth, anchored)
                fullItem("all", "all") {
                    AllHeader(content, vm, focus, Modifier.anchoredRow(anchored))
                }
                items(content.grid, key = { it.key }, contentType = { "poster" }) { p ->
                    PosterCard(
                        p,
                        { open(p.key) },
                        Modifier.padding(bottom = LibraryLayout.GRID_ROW_GAP.dp).anchoredRow(anchored).remembered(focus, p.key),
                    )
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
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.rows(
    content: LibraryContent,
    focus: ScreenFocus,
    open: (String) -> Unit,
    fullWidth: Dp,
    anchored: AnchoredScroll<LazyGridState>,
) {
    val id = content.libraryId
    if (content.continueItems.isNotEmpty()) {
        fullItem("$id/cont", "wide-row") {
            ContentRow("Продолжить просмотр", content.continueItems.size, Modifier.bleed(fullWidth, Side).padding(top = RowGap).anchoredRow(anchored)) {
                items(content.continueItems, key = { it.key }, contentType = { "wide" }) { w ->
                    WideCard(w, { open(w.key) }, Modifier.remembered(focus, w.key))
                }
            }
        }
    }
    for ((name, title, posters) in listOf(Triple("new", "Недавно добавлено", content.latest), Triple("hist", "История просмотра", content.history))) {
        if (posters.isEmpty()) continue
        fullItem("$id/$name", "poster-row") {
            ContentRow(title, posters.size, Modifier.bleed(fullWidth, Side).padding(top = RowGap).anchoredRow(anchored)) {
                items(posters, key = { it.key }, contentType = { "poster" }) { p ->
                    PosterCard(p, { open(p.key) }, Modifier.remembered(focus, p.key))
                }
            }
        }
    }
}

/** Library tiles; focusing one selects it, OK moves down into its content. */
@Composable
private fun Tiles(state: LibraryState, vm: LibraryViewModel, focus: ScreenFocus, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    DefaultScrolling {
        LazyRow(
            modifier.focusRestorer(),
            // The selected tile in view from the start (it can be far right when opened from the rail): only composed
            // tiles can take the first focus, and focusing another one would switch the library.
            state = rememberLazyListState((state.tiles.indexOfFirst { it.id == state.selected } - 2).coerceAtLeast(0)),
            contentPadding = PaddingValues(horizontal = AppDimens.CONTENT_START.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.ROW_GAP.dp),
        ) {
            items(state.tiles, key = { it.key }, contentType = { "tile" }) { tile ->
                LibraryTileCard(
                    tile,
                    selected = tile.id == state.selected,
                    onFocus = { vm.focusLibrary(tile.id) },
                    onClick = { focusManager.moveFocus(FocusDirection.Down) },
                    modifier = Modifier.remembered(focus, tile.key, isDefault = tile.id == state.selected),
                )
            }
        }
    }
}

@Composable
private fun LibraryTileCard(tile: LibraryTile, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LibraryLayout.TILE_CORNER.dp)
    val sizePx = with(LocalDensity.current) { IntSize(LibraryLayout.TILE_W.dp.roundToPx(), LibraryLayout.TILE_H.dp.roundToPx()) }
    val art by rememberTileArt(tile.art, sizePx)
    Box(
        modifier
            .focusCard(TileFocus)
            .size(LibraryLayout.TILE_W.dp, LibraryLayout.TILE_H.dp)
            .then(
                art?.let { a ->
                    val image = if (selected) a.selected else a.normal
                    Modifier.drawBehind { drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero) }
                } ?: Modifier.background(AppColors.Surface, shape),
            )
            .border(0.5.dp, if (selected) AppColors.SelectedBorder else TileBorder, shape)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
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

/** "Все сериалы 171  [А–Я] [Добавлено] [Год] [Рейтинг] [Непросмотренное]" */
@Composable
private fun AllHeader(content: LibraryContent, vm: LibraryViewModel, focus: ScreenFocus, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = 22.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(content.allTitle, style = AppType.Section)
        content.allCount?.let { BasicText(it.toString(), Modifier.padding(start = 8.dp, top = 3.dp), style = AppType.MonoCount) }
        Row(Modifier.padding(start = 16.dp).focusRestorer(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (chip in vm.sortChips) {
                Chip(chip.label, chip.sort == content.sort, { vm.setSort(chip.sort) }, Modifier.remembered(focus, chip.key))
            }
            Chip(
                (if (content.unwatched) "✓ " else "") + "Непросмотренное",
                content.unwatched,
                vm::toggleUnwatched,
                Modifier.remembered(focus, LibraryViewModel.UNWATCHED_KEY),
            )
        }
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

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BasicText(text, style = AppType.Body) }
}

private val Side = AppDimens.CONTENT_START.dp
private val RowGap = (AppDimens.SECTION_GAP - 14).dp
private val TileBorder = Color(0xFF262A3A)
private val TileFocus = FocusStyle.Tile.copy(corner = LibraryLayout.TILE_CORNER.dp)

/** Next page is asked for this many cards before the end (two rows of the grid). */
private const val PREFETCH = 12

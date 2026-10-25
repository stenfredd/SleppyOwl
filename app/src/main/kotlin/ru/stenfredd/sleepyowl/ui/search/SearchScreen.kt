package ru.stenfredd.sleepyowl.ui.search

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AnchoredScrolling
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.anchorViewport
import ru.stenfredd.sleepyowl.ui.components.anchoredRow
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.components.fullItem
import ru.stenfredd.sleepyowl.ui.focus.Restore
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * Search tab (mockup screen «Поиск»): on the left the query and an on-screen keyboard (Russian; one key switches
 * to Latin letters and digits), on the right recent queries and newest items, or the results by library and people.
 * Typing on a physical keyboard (or digits on the remote) works anywhere on the screen.
 */
@Composable
fun SearchScreen(onOpen: (Screen) -> Unit, vm: SearchViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = rememberScreenFocus()
    val open = { key: String -> vm.open(key)?.let(onOpen) ?: Unit }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val native = e.nativeKeyEvent
                if (native.keyCode == NativeKeyEvent.KEYCODE_DEL) {
                    vm.backspace()
                    return@onPreviewKeyEvent true
                }
                val c = native.unicodeChar
                if (c != 0 && (Character.isLetterOrDigit(c) || c == ' '.code || c == '-'.code)) {
                    vm.type(String(Character.toChars(c)).lowercase())
                    true
                } else {
                    false
                }
            },
    ) {
        Column(Modifier.padding(start = AppDimens.CONTENT_START.dp, top = Top).width(SearchLayout.KEYBOARD_W.dp)) {
            QueryField(state.query)
            Keyboard(state.latin, vm, focus, Modifier.padding(top = 12.dp))
            BasicText(
                "Можно печатать и с клавиатуры, цифры — с пульта.",
                Modifier.padding(top = 11.dp),
                style = AppType.Body.copy(fontSize = 10.sp, lineHeight = 13.sp, color = Color(0xFF6F7592)),
            )
        }
        Results(state, focus, vm, open, Modifier.padding(start = SearchLayout.RESULTS_START.dp))
    }
    focus.Restore(ready = true)
}

/** The query with a blinking caret (two redraws a second, a step, not an animation). */
@Composable
private fun QueryField(query: String) {
    var caret by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        caret = true
        while (true) {
            delay(500)
            caret = !caret
        }
    }
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(AppColors.Surface, shape)
            .border(0.5.dp, Color(0xFF2C3044), shape)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcon.Search, AppColors.Accent, Modifier.size(16.dp), strokeWidth = 2.2f)
        Spacer(Modifier.width(7.dp))
        if (query.isNotEmpty()) {
            BasicText(
                query.replaceFirstChar { it.uppercase() },
                Modifier.weight(1f, fill = false),
                style = AppType.Hero.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(1.5.dp, 23.dp)
                .graphicsLayer { alpha = if (caret) 1f else 0f }
                .background(Color(0xFF7AA2FF)),
        )
        if (query.isEmpty()) {
            BasicText("Название, жанр, актёр", Modifier.padding(start = 4.dp), style = AppType.Body.copy(fontSize = 15.sp, color = AppColors.TextFaint), maxLines = 1)
        }
    }
}

private const val RUSSIAN = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ-.,"
private const val LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

/** 6 columns: 36 character keys, then ⌫, space, layout switch. */
@Composable
private fun Keyboard(latin: Boolean, vm: SearchViewModel, focus: ScreenFocus, modifier: Modifier = Modifier) {
    val chars = if (latin) LATIN else RUSSIAN
    Column(modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        chars.chunked(COLUMNS).forEachIndexed { row, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                line.forEachIndexed { col, c ->
                    // Keys are remembered by position: after switching the layout focus stays where it was.
                    val key = "key/${row * COLUMNS + col}"
                    Key(c.toString(), { vm.type(c.lowercase()) }, Modifier.weight(1f).remembered(focus, key, isDefault = row == 0 && col == 0))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            Key("⌫", vm::backspace, Modifier.weight(1f).remembered(focus, "key/del"), onLongClick = vm::clear)
            Key("Пробел", { vm.type(" ") }, Modifier.weight(3f).remembered(focus, "key/space"), small = true)
            Key(if (latin) "АБВ" else "ABC·123", vm::toggleLayout, Modifier.weight(2f).remembered(focus, "key/layout"), small = true)
        }
    }
}

private val KeyFocus = FocusStyle(scale = 1.06f, corner = 6.dp, ring = 1.dp, glows = FocusStyle.ButtonGlows)

@Composable
private fun Key(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, onLongClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .focusCard(KeyFocus)
            .height(38.dp)
            .background(if (focused) AppColors.FocusFill else AppColors.SurfaceRaised, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else Color(0xFF232637), shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = null, indication = null, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = AppType.Button.copy(fontSize = if (small) 11.sp else 14.sp, color = if (focused) AppColors.OnFocusFill else Color(0xFFD9DCEC)),
            maxLines = 1,
        )
    }
}

@Composable
private fun Results(state: SearchState, focus: ScreenFocus, vm: SearchViewModel, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val grid = rememberLazyGridState()
    // A new query starts the results from the top.
    LaunchedEffect(state.results) { if (grid.firstVisibleItemIndex > 0) grid.scrollToItem(0) }
    AnchoredScrolling(grid, anchor = Top) { anchored ->
        LazyVerticalGrid(
            columns = GridCells.FixedSize(AppDimens.POSTER_W.dp),
            state = grid,
            modifier = modifier.fillMaxSize().anchorViewport(anchored),
            contentPadding = PaddingValues(start = 12.dp, end = AppDimens.CONTENT_START.dp, top = Top, bottom = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val results = state.results
            if (results == null) {
                if (state.recentQueries.isNotEmpty()) {
                    fullItem("recent-q", "label") {
                        Column(Modifier.anchoredRow(anchored).padding(bottom = 22.dp)) {
                            Label("НЕДАВНИЕ ЗАПРОСЫ")
                            FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.recentQueries.forEachIndexed { i, q ->
                                    Chip(q, false, { vm.pickRecent(q) }, Modifier.remembered(focus, "rq/$i"))
                                }
                            }
                        }
                    }
                }
                if (state.recentItems.isNotEmpty()) {
                    fullItem("recent-label", "label") { Label("НЕДАВНО ДОБАВЛЕНО", Modifier.padding(bottom = 13.dp)) }
                    posters(state.recentItems, focus, open, anchored)
                }
                return@LazyVerticalGrid
            }
            fullItem("found", "label") {
                Label(if (state.searching) "ИЩЕМ…" else "НАЙДЕНО: ${results.sumOf { it.size }}", Modifier.anchoredRow(anchored))
            }
            if (results.isEmpty() && !state.searching) {
                fullItem("none", "text") {
                    BasicText(
                        state.error?.let { "Не удалось найти: $it" } ?: "Ничего не нашлось по «${state.query.trim()}». Попробуйте часть названия или имя актёра.",
                        Modifier.padding(top = 12.dp).widthIn(max = 480.dp),
                        style = AppType.Body.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    )
                }
            }
            for (group in results) {
                fullItem(group.key, "group") {
                    Row(Modifier.padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText(group.title, style = AppType.Section)
                        BasicText(group.size.toString(), Modifier.padding(bottom = 1.dp), style = AppType.MonoCount)
                    }
                }
                posters(group.posters, focus, open, anchored)
                items(group.people, key = { it.key }, contentType = { "person" }) { p ->
                    PersonCard(p, { open(p.key) }, Modifier.padding(bottom = 18.dp).anchoredRow(anchored).remembered(focus, p.key))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.posters(
    list: List<ru.stenfredd.sleepyowl.ui.components.PosterModel>,
    focus: ScreenFocus,
    open: (String) -> Unit,
    anchored: ru.stenfredd.sleepyowl.ui.components.AnchoredScroll<*>,
) {
    items(list, key = { it.key }, contentType = { "poster" }) { p ->
        PosterCard(p, { open(p.key) }, Modifier.padding(bottom = 18.dp).anchoredRow(anchored).remembered(focus, p.key))
    }
}

private val PersonFocus = FocusStyle(scale = 1.05f, corner = (SearchLayout.PERSON_PHOTO / 2).dp, ring = 1.dp)

/** Round photo (or initials) and the name under it. */
@Composable
private fun PersonCard(person: PersonCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val size = SearchLayout.PERSON_PHOTO.dp
    Column(modifier.width(AppDimens.POSTER_W.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .focusCard(PersonFocus)
                .size(size)
                .clickable(interactionSource = null, indication = null, onClick = onClick),
        ) {
            if (person.photo != null) {
                RemoteImage(person.photo, size / 2, Modifier.fillMaxSize())
            } else {
                Avatar(person.name, person.key, size, AppType.Hero.copy(fontSize = 24.sp), letters = person.letters)
            }
        }
        BasicText(
            person.name,
            Modifier.padding(top = 8.dp),
            style = AppType.PosterTitle.copy(textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = AppType.MonoLabel.copy(color = AppColors.TextDim))
}

private val Top = (AppDimens.CONTENT_TOP + 10).dp
private val KeyGap = 5.dp
private const val COLUMNS = 6

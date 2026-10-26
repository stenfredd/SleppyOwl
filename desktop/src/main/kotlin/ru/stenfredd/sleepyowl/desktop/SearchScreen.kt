package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.PosterModel
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.components.fullItem
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.search.PersonCardModel
import ru.stenfredd.sleepyowl.ui.search.SearchLayout
import ru.stenfredd.sleepyowl.ui.search.SearchState
import ru.stenfredd.sleepyowl.ui.search.SearchViewModel
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * Search tab: a plain text field (the TV's on-screen keyboard isn't needed), under it recent queries and newest items,
 * or the results by library and people. The field has focus when the tab opens.
 */
@Composable
fun SearchScreen(onOpen: (Screen) -> Unit, vm: SearchViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    val focus = rememberScreenFocus()
    val open = { key: String -> vm.open(key)?.let(onOpen) ?: Unit }
    var text by rememberSaveable { mutableStateOf(state.query) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = AppDimens.CONTENT_START.dp, end = AppDimens.CONTENT_START.dp, top = AppDimens.CONTENT_TOP.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AppIcon.Search, AppColors.Accent, Modifier.size(18.dp), strokeWidth = 2.2f)
            TextField(
                text,
                {
                    text = it
                    vm.setQuery(it)
                },
                Modifier.widthIn(max = 480.dp),
                placeholder = "Название, жанр, актёр",
                textStyle = AppType.BodyLarge.copy(fontSize = 16.sp, color = AppColors.Text),
                focusRequester = field,
            )
        }
        Results(state, focus, vm, open) { query ->
            text = query
            vm.pickRecent(query)
        }
    }
    focus.Entry(ready = true)
}

@Composable
private fun Results(state: SearchState, focus: ScreenFocus, vm: SearchViewModel, open: (String) -> Unit, onRecent: (String) -> Unit) {
    val grid = rememberLazyGridState()
    // A new query starts the results from the top.
    LaunchedEffect(state.results) { if (grid.firstVisibleItemIndex > 0) grid.scrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.FixedSize(AppDimens.POSTER_W.dp),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = AppDimens.CONTENT_START.dp, end = AppDimens.CONTENT_START.dp, top = 16.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val results = state.results
        if (results == null) {
            if (state.recentQueries.isNotEmpty()) {
                fullItem("recent-q", "label") {
                    Column(Modifier.padding(bottom = 22.dp)) {
                        Label("НЕДАВНИЕ ЗАПРОСЫ")
                        FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.recentQueries.forEachIndexed { i, q ->
                                Chip(q, false, { onRecent(q) }, Modifier.hoverFocus().remembered(focus, "rq/$i"))
                            }
                        }
                    }
                }
            }
            if (state.recentItems.isNotEmpty()) {
                fullItem("recent-label", "label") { Label("НЕДАВНО ДОБАВЛЕНО", Modifier.padding(bottom = 13.dp)) }
                posters(state.recentItems, focus, open)
            }
            return@LazyVerticalGrid
        }
        fullItem("found", "label") {
            Label(if (state.searching) "ИЩЕМ…" else "НАЙДЕНО: ${results.sumOf { it.size }}")
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
            posters(group.posters, focus, open)
            items(group.people, key = { it.key }, contentType = { "person" }) { p ->
                PersonCard(p, { open(p.key) }, Modifier.padding(bottom = 18.dp).hoverFocus().remembered(focus, p.key))
            }
        }
    }
}

private fun LazyGridScope.posters(list: List<PosterModel>, focus: ScreenFocus, open: (String) -> Unit) {
    items(list, key = { it.key }, contentType = { "poster" }) { p ->
        PosterCard(p, { open(p.key) }, Modifier.padding(bottom = 18.dp).hoverFocus().remembered(focus, p.key))
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

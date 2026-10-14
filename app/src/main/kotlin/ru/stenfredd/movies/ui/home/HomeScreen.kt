package ru.stenfredd.movies.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.AnchoredScrolling
import ru.stenfredd.movies.ui.components.anchorViewport
import ru.stenfredd.movies.ui.components.anchoredRow
import ru.stenfredd.movies.ui.components.ContentRow
import ru.stenfredd.movies.ui.components.FeaturedCard
import ru.stenfredd.movies.ui.components.PosterCard
import ru.stenfredd.movies.ui.components.SectionTitle
import ru.stenfredd.movies.ui.components.WideCard
import ru.stenfredd.movies.ui.focus.Restore
import ru.stenfredd.movies.ui.focus.rememberScreenFocus
import ru.stenfredd.movies.ui.focus.remembered
import ru.stenfredd.movies.ui.theme.AppDimens
import ru.stenfredd.movies.ui.theme.AppType

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = rememberScreenFocus()
    val open = { key: String -> vm.target(key)?.let(onOpen) ?: Unit }

    when {
        state.loading -> Message("Загрузка…")
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
        // A plain scrolling column, not a LazyColumn: home has a handful of rows, and composing a row while it
        // scrolls into view cost up to 50 ms frames on the test TV. All rows are composed once on load;
        // the cards inside each row stay lazy.
        else -> AnchoredScrolling(rememberScrollState(), anchor = AppDimens.CONTENT_TOP.dp) { anchored ->
            Column(
                Modifier
                    .fillMaxSize()
                    .anchorViewport(anchored)
                    .verticalScroll(anchored.scroll)
                    .padding(top = AppDimens.CONTENT_TOP.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(AppDimens.SECTION_GAP.dp - 14.dp),
            ) {
                state.featured?.let { featured ->
                    Column(Modifier.anchoredRow(anchored)) {
                        SectionTitle("Продолжить просмотр", state.continueCount)
                        Row(
                            Modifier.padding(start = AppDimens.CONTENT_START.dp, top = 13.dp, bottom = 14.dp).focusRestorer(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            FeaturedCard(featured, { open(featured.key) }, Modifier.remembered(focus, featured.key, isDefault = true))
                            ContinueGrid(state, focus, open)
                        }
                    }
                }
                for (row in state.rows) {
                    key(row.key) {
                        ContentRow(row.title, row.count, Modifier.anchoredRow(anchored)) {
                            items(row.items, key = { it.key }, contentType = { "poster" }) { p ->
                                PosterCard(p, { open(p.key) }, Modifier.remembered(focus, p.key, isDefault = state.featured == null && p === state.rows.first().items.first()))
                            }
                        }
                    }
                }
            }
        }
    }
    focus.Restore(ready = !state.loading && state.error == null)
}

@Composable
private fun ContinueGrid(state: HomeState, focus: ru.stenfredd.movies.ui.focus.ScreenFocus, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.continueItems.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { w -> WideCard(w, { open(w.key) }, Modifier.remembered(focus, w.key)) }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BasicText(text, style = AppType.Body) }
}

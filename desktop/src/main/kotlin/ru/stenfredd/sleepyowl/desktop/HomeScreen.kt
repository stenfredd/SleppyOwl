package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.FeaturedCard
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.WideCard
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.home.HomeState
import ru.stenfredd.sleepyowl.ui.home.HomeViewModel
import ru.stenfredd.sleepyowl.ui.theme.AppDimens

/** Home: continue watching (the big card and four more), recently added, a row per library — as on the TV. */
@Composable
fun HomeScreen(onOpen: (Screen) -> Unit, vm: HomeViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    val focus = rememberScreenFocus()
    val open = { key: String -> vm.target(key)?.let(onOpen) ?: Unit }

    when {
        state.loading -> Message("Загрузка…")
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
        else -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = AppDimens.CONTENT_TOP.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(AppDimens.SECTION_GAP.dp - 14.dp),
        ) {
            state.featured?.let { featured ->
                Column {
                    SectionTitle("Продолжить просмотр", state.continueCount)
                    // The four small cards go under the big one when the window is too narrow for them beside it.
                    FlowRow(
                        Modifier.padding(start = AppDimens.CONTENT_START.dp, end = AppDimens.CONTENT_START.dp, top = 13.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        FeaturedCard(featured, { open(featured.key) }, Modifier.hoverFocus().remembered(focus, featured.key, isDefault = true))
                        ContinueGrid(state, focus, open)
                    }
                }
            }
            for (row in state.rows) {
                key(row.key) {
                    ContentRow(row.title, row.count) {
                        items(row.items, key = { it.key }, contentType = { "poster" }) { p ->
                            PosterCard(p, { open(p.key) }, Modifier.hoverFocus().remembered(focus, p.key, isDefault = state.featured == null && p === state.rows.first().items.first()))
                        }
                    }
                }
            }
        }
    }
    focus.Entry(ready = !state.loading && state.error == null)
}

@Composable
private fun ContinueGrid(state: HomeState, focus: ScreenFocus, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.continueItems.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { w -> WideCard(w, { open(w.key) }, Modifier.hoverFocus().remembered(focus, w.key)) }
            }
        }
    }
}

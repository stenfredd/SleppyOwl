package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/** "Недавно добавлено  12" */
@Composable
fun SectionTitle(title: String, count: Int?, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = AppDimens.CONTENT_START.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(title, style = AppType.Section)
        count?.let { BasicText(it.toString(), Modifier.padding(bottom = 1.dp), style = AppType.MonoCount) }
    }
}

/**
 * A titled horizontal row. Returning to the row (↑/↓) focuses the card that was focused before,
 * not the first visible one.
 */
@Composable
fun ContentRow(title: String, count: Int?, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier) {
        SectionTitle(title, count)
        DefaultScrolling { LazyRow(
            state = rememberLazyListState(),
            modifier = Modifier.focusRestorer(),
            // Vertical padding leaves room for the focused card's scale and glow.
            contentPadding = PaddingValues(horizontal = AppDimens.CONTENT_START.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.ROW_GAP.dp),
            content = content,
        ) }
    }
}

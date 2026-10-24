package ru.stenfredd.sleepyowl.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/** Text of a licence from `assets/licenses`; ↑/↓ scroll it (a page at a time with ←/→ or channel keys). */
@Composable
fun LicenseScreen(screen: Screen.License) {
    val context = LocalContext.current
    var paragraphs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(screen.asset) {
        paragraphs = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/${screen.asset}").bufferedReader().use { it.readText() } }
                .getOrDefault("Текст лицензии не найден.")
                .split(Regex("""\n\s*\n"""))
                // Hard-wrapped lines are joined: the screen wraps them itself.
                .map { p -> p.trim().replace(Regex("""\s*\n\s*"""), " ") }
                .filter { it.isNotEmpty() }
        }
    }

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    val step = with(LocalDensity.current) { 120.dp.toPx() }
    val page = with(LocalDensity.current) { 400.dp.toPx() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }

    LazyColumn(
        state = list,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(requester)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val by = when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> step
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> -step
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> page
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> -page
                    else -> return@onKeyEvent false
                }
                scope.launch { list.animateScrollBy(by) }
                true
            }
            .focusable(),
        contentPadding = PaddingValues(start = AppDimens.CONTENT_START.dp, end = AppDimens.CONTENT_START.dp, top = AppDimens.CONTENT_TOP.dp, bottom = 80.dp),
    ) {
        item(key = "title", contentType = "title") {
            Column(Modifier.padding(bottom = 18.dp)) {
                BasicText(screen.name, style = AppType.Display.copy(fontSize = 26.sp))
                BasicText(screen.license, Modifier.padding(top = 6.dp), style = AppType.MonoLabel)
            }
        }
        itemsIndexed(paragraphs, contentType = { _, _ -> "text" }) { _, p ->
            BasicText(
                p,
                Modifier.widthIn(max = 720.dp).padding(bottom = 10.dp),
                style = AppType.Body.copy(fontSize = 11.5.sp, lineHeight = 17.sp, color = AppColors.TextSecondary),
            )
        }
    }
}

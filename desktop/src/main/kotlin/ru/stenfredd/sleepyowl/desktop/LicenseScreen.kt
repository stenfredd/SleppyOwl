package ru.stenfredd.sleepyowl.desktop

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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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

/** A component of the desktop app and its licence; [asset] is the licence text in the `licenses/` resources. */
@Immutable
data class Component(val name: String, val note: String?, val license: String, val asset: String)

/**
 * "О приложении" on the desktop: built from other parts than the TV (mpv instead of Media3 and libVLC, a JRE inside the
 * package). The texts: the TV's `app/src/main/assets/licenses` (copied in by the build) and the desktop's own.
 */
object Licenses {
    val app = Component("SleepyOwl", "это приложение", "GPL-3.0", "GPL-3.0.txt")

    val components = listOf(
        Component("mpv, FFmpeg", "системная libmpv, движок воспроизведения", "GPL-2.0+", "GPL-2.0.txt"),
        Component("OpenJDK", "Java внутри пакета", "GPL-2.0 + Classpath", "OpenJDK-GPL-2.0-CE.txt"),
        Component("Jellyfin SDK", "Jellyfin, работа с сервером", "LGPL-3.0", "LGPL-3.0.txt"),
        Component("Compose Multiplatform, Skiko", "JetBrains, интерфейс", "Apache 2.0", "Apache-2.0.txt"),
        Component("Skia", "Google, графика внутри Skiko", "BSD-3-Clause", "Skia-BSD-3.txt"),
        Component("AndroidX Lifecycle, DataStore", "Google", "Apache 2.0", "Apache-2.0.txt"),
        Component("Kotlin, kotlinx", "JetBrains", "Apache 2.0", "Apache-2.0.txt"),
        Component("Koin", "Kotzilla, связывание компонентов", "Apache 2.0", "Apache-2.0.txt"),
        Component("Coil", "загрузка картинок", "Apache 2.0", "Apache-2.0.txt"),
        Component("OkHttp", "Square, сеть", "Apache 2.0", "Apache-2.0.txt"),
        Component("JNA", "вызовы libmpv, X11, OpenGL", "Apache 2.0", "Apache-2.0.txt"),
        Component("dbus-java", "MPRIS и хранитель экрана", "MIT", "MIT.txt"),
        Component("Шрифт Onest", null, "OFL 1.1", "Onest-OFL.txt"),
        Component("Шрифт JetBrains Mono", null, "OFL 1.1", "JetBrainsMono-OFL.txt"),
    )
}

/** Text of a licence; the wheel, ↑/↓ and PgUp/PgDn scroll it. */
@Composable
fun LicenseScreen(screen: Screen.License) {
    var paragraphs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(screen.asset) {
        paragraphs = withContext(Dispatchers.IO) {
            runCatching { Licenses::class.java.getResourceAsStream("/licenses/${screen.asset}")!!.bufferedReader().use { it.readText() } }
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
                val by = when (e.key) {
                    Key.DirectionDown -> step
                    Key.DirectionUp -> -step
                    Key.PageDown, Key.Spacebar -> page
                    Key.PageUp -> -page
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

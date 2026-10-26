package ru.stenfredd.sleepyowl.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.theme.AppColors
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

/** A titled horizontal row of cards, as on the TV, scrolled by the mouse ([ScrollRow]). */
@Composable
fun ContentRow(title: String, count: Int?, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier) {
        SectionTitle(title, count)
        // Vertical padding leaves room for the focused card's scale and glow.
        ScrollRow(contentPadding = PaddingValues(horizontal = AppDimens.CONTENT_START.dp, vertical = 14.dp), content = content)
    }
}

/**
 * A horizontal lazy row for the mouse: Shift+wheel and a touchpad's sideways swipe scroll it (Compose does both), and
 * while the pointer is over it, round ‹ › buttons at its ends turn it by most of a screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = AppDimens.CONTENT_START.dp),
    spacing: Dp = AppDimens.ROW_GAP.dp,
    content: LazyListScope.() -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun turn(direction: Int) {
        val page = state.layoutInfo.viewportSize.width * PAGE * direction
        scope.launch { state.animateScrollBy(page, tween(TURN_MILLIS, easing = FastOutSlowInEasing)) }
    }
    Box(
        modifier
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        LazyRow(state = state, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(spacing), content = content)
        if (hovered && state.canScrollBackward) RowArrow(back = true, Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { turn(-1) }
        if (hovered && state.canScrollForward) RowArrow(back = false, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { turn(1) }
    }
}

private const val PAGE = 0.8f
private const val TURN_MILLIS = 450

/** ‹ or ›: not focusable, the keyboard has its arrows. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RowArrow(back: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .zIndex(2f)
            .size(36.dp)
            .background(if (hovered) AppColors.FocusFill else Color(0xE6151824), CircleShape)
            .border(0.5.dp, if (hovered) AppColors.FocusFill else AppColors.Border, CircleShape)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcon.ChevronRight, if (hovered) AppColors.OnFocusFill else AppColors.Text, Modifier.size(16.dp).then(if (back) Modifier.rotate(180f) else Modifier))
    }
}

/** "Загрузка…", "Не удалось загрузить: …" in the middle of the screen. */
@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BasicText(text, style = AppType.Body) }
}

/** "‹" at the top left of the full-screen pages (movie, series, person): the mouse's way back. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackArrow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(32.dp)
            .background(if (hovered) AppColors.SurfaceFocused else Color(0xB3151824), CircleShape)
            .border(0.5.dp, if (hovered) AppColors.Accent else AppColors.Border, CircleShape)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcon.ChevronRight, if (hovered) AppColors.Text else AppColors.TextMuted, Modifier.size(15.dp).rotate(180f))
    }
}

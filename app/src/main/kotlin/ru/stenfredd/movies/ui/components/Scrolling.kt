package ru.stenfredd.movies.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Vertical scrolling of a screen of rows, done by hand: Compose's own bring-into-view animation can't be
 * slowed down (custom specs are no longer supported) and jumps too fast on a TV.
 *
 * When a row ([anchoredRow]) gets focus and isn't fully visible, the screen scrolls smoothly so the row's top
 * sits [anchor] below the viewport top; a visible row doesn't scroll at all. Automatic vertical
 * bring-into-view is switched off inside [content]; rows keep the platform horizontal behaviour, see [DefaultScrolling].
 */
@Stable
class AnchoredScroll internal constructor(val scroll: ScrollState, private val scope: CoroutineScope, private val anchorPx: Float) {
    internal var viewport: LayoutCoordinates? = null
    private var job: Job? = null

    internal fun bring(row: LayoutCoordinates) {
        val vp = viewport?.takeIf { it.isAttached } ?: return
        if (!row.isAttached) return
        val bounds = vp.localBoundingBoxOf(row, clipBounds = false)
        if (bounds.top >= 0f && bounds.bottom <= vp.size.height) return
        val target = (scroll.value + bounds.top - anchorPx).toInt().coerceIn(0, scroll.maxValue)
        job?.cancel()
        job = scope.launch { scroll.animateScrollTo(target, tween(ScrollMillis, easing = FastOutSlowInEasing)) }
    }

    private companion object {
        const val ScrollMillis = 450
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnchoredScrolling(scroll: ScrollState, anchor: Dp, content: @Composable (AnchoredScroll) -> Unit) {
    val default = LocalBringIntoViewSpec.current
    val scope = rememberCoroutineScope()
    val anchorPx = with(LocalDensity.current) { anchor.toPx() }
    val state = remember(scroll, anchorPx) { AnchoredScroll(scroll, scope, anchorPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScroll, LocalDefaultBringIntoViewSpec provides default) {
        content(state)
    }
}

/** The scroll container's viewport: put it before `verticalScroll`. */
fun Modifier.anchorViewport(state: AnchoredScroll): Modifier = onPlaced { state.viewport = it }

/** A row of the screen: scrolled to the anchor when focus enters it. */
fun Modifier.anchoredRow(state: AnchoredScroll): Modifier {
    var coords: LayoutCoordinates? = null
    return onPlaced { coords = it }.onFocusChanged { if (it.hasFocus) coords?.let(state::bring) }
}

@OptIn(ExperimentalFoundationApi::class)
private val NoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@OptIn(ExperimentalFoundationApi::class)
private val LocalDefaultBringIntoViewSpec = staticCompositionLocalOf<BringIntoViewSpec?> { null }

/** Restores the platform scrolling behaviour for nested (horizontal) lists inside [AnchoredScrolling]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DefaultScrolling(content: @Composable () -> Unit) {
    val default = LocalDefaultBringIntoViewSpec.current
    if (default == null) content() else CompositionLocalProvider(LocalBringIntoViewSpec provides default, content = content)
}

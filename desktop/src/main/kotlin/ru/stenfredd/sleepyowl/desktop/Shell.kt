package ru.stenfredd.sleepyowl.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.OwlMark
import ru.stenfredd.sleepyowl.ui.components.Wordmark
import ru.stenfredd.sleepyowl.ui.shell.RailLibrary
import ru.stenfredd.sleepyowl.ui.shell.Tab
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType
import java.util.UUID

/** From this width the rail stays open with its labels; narrower, it shows icons and opens over the content on hover. */
private val PinnedRailFrom = 1100.dp

/**
 * Root screens (the tabs) on the desktop: the TV's side rail, made for the mouse. A wide window keeps it open and
 * lays the content out beside it; a narrow one keeps it collapsed, and pointing at it opens it over the content.
 */
@Composable
fun Shell(
    current: Tab,
    userName: String,
    userSeed: String,
    libraries: List<RailLibrary>,
    onTab: (Tab) -> Unit,
    onLibrary: (UUID) -> Unit,
    onProfile: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pinned = maxWidth >= PinnedRailFrom
        val railWidth = if (pinned) AppDimens.RAIL_OPEN.dp else AppDimens.RAIL_COLLAPSED.dp
        Box(Modifier.fillMaxSize().padding(start = railWidth)) { content() }
        Rail(current, userName, userSeed, libraries, pinned, onTab, onLibrary, onProfile, Modifier.zIndex(1f))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(
    current: Tab,
    userName: String,
    userSeed: String,
    libraries: List<RailLibrary>,
    pinned: Boolean,
    onTab: (Tab) -> Unit,
    onLibrary: (UUID) -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val open = pinned || hovered || focused
    // A pinned rail does not animate: it is open from the first frame.
    val progress = animateFloatAsState(if (open) 1f else 0f, if (pinned) tween(0) else tween(150), label = "rail")

    Column(
        modifier
            .fillMaxHeight()
            .width(if (open) AppDimens.RAIL_OPEN.dp else AppDimens.RAIL_COLLAPSED.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) {
                hovered = false
                // The pointer gave an item focus (hoverFocus); leaving the rail closes it, as on the TV.
                if (focused) focusManager.clearFocus()
            }
            .onFocusChanged { focused = it.hasFocus }
            .drawBehind {
                val p = progress.value
                val w = lerp(AppDimens.RAIL_COLLAPSED.dp.toPx(), AppDimens.RAIL_OPEN.dp.toPx(), p)
                drawRect(lerp(AppColors.Rail, AppColors.RailOpen, if (pinned) 1f else p), size = Size(w, size.height))
                drawRect(AppColors.BorderSubtle, topLeft = Offset(w - 1f, 0f), size = Size(1f, size.height))
                // Edge shadow over the content, only while the rail is opened over it.
                if (!pinned && p > 0f) {
                    val shadow = 40.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.5f * p), Color.Transparent), startX = w, endX = w + shadow),
                        topLeft = Offset(w, 0f),
                        size = Size(shadow, size.height),
                    )
                }
            }
            .padding(horizontal = 9.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.padding(start = 1.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwlMark()
            Wordmark(Modifier.graphicsLayer { alpha = progress.value })
        }
        @Composable
        fun item(tab: Tab) = RailItem(tab.label, tab.icon, tab == current, progress, onClick = { if (tab != current) onTab(tab) })
        for (tab in Tab.entries) if (tab != Tab.Settings) item(tab)
        if (libraries.isNotEmpty()) {
            RailDivider(progress)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (library in libraries) {
                    RailItem(library.name, library.icon, selected = false, progress, onClick = { onLibrary(library.id) }, height = 30.dp)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        item(Tab.Settings)
        ProfileItem(userName, userSeed, progress, onProfile)
    }
}

/** Fill of the item under the pointer (or keyboard focus), growing with the rail panel. */
private fun Modifier.railHighlight(lit: Boolean, color: Color, progress: State<Float>) = drawBehind {
    if (!lit) return@drawBehind
    val collapsed = (AppDimens.RAIL_COLLAPSED - 18).dp.toPx()
    val w = lerp(collapsed, size.width, progress.value)
    drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(8.dp.toPx()))
}

/** The open rail marks the current tab with a fill (the closed one with the bar on its edge). */
private fun Modifier.selectedFill(selected: Boolean, progress: State<Float>) = drawBehind {
    if (selected) drawRoundRect(AppColors.SurfaceFocused, alpha = progress.value, cornerRadius = CornerRadius(8.dp.toPx()))
}

@Composable
private fun RailDivider(progress: State<Float>) {
    Box(
        Modifier.fillMaxWidth().height(11.dp).drawBehind {
            val collapsed = (AppDimens.RAIL_COLLAPSED - 18).dp.toPx()
            val w = lerp(collapsed, size.width, progress.value)
            drawRect(AppColors.BorderSubtle, topLeft = Offset(0f, (size.height / 2).toInt().toFloat()), size = Size(w, 1.dp.toPx().coerceAtLeast(1f)))
        },
    )
}

@Composable
private fun RailItem(label: String, icon: AppIcon, selected: Boolean, progress: State<Float>, onClick: () -> Unit, height: Dp = 36.dp) {
    var focused by remember { mutableStateOf(false) }
    val content = when {
        focused -> AppColors.OnFocusFill
        selected -> AppColors.Text
        else -> AppColors.TextDim
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        // Selected tab marker on the rail's left edge, visible while the rail is closed.
        if (selected) {
            Box(
                Modifier
                    .padding(vertical = 9.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = -9.dp.toPx()
                        alpha = 1f - progress.value
                    }
                    .background(AppColors.Accent, RoundedCornerShape(1.dp)),
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .hoverFocus()
                .selectedFill(selected, progress)
                .railHighlight(focused, AppColors.FocusFill, progress)
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = null, indication = null, onClick = onClick)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(icon, content, Modifier.size(16.dp))
            BasicText(
                label,
                Modifier.graphicsLayer { alpha = progress.value },
                style = AppType.Rail.copy(color = content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ProfileItem(userName: String, userSeed: String, progress: State<Float>, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .hoverFocus()
            .railHighlight(focused, AppColors.SurfaceFocused, progress)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Avatar(userName, userSeed, 24.dp, AppType.CardTitle.copy(fontSize = AppType.PosterTitle.fontSize))
        Column(Modifier.graphicsLayer { alpha = progress.value }) {
            BasicText(userName, style = AppType.CardTitle.copy(fontSize = AppType.Rail.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
            BasicText("сменить профиль", style = AppType.MonoCaption.copy(fontSize = AppType.MonoBadge.fontSize), maxLines = 1, softWrap = false)
        }
    }
}

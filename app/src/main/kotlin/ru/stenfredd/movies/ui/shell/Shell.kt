package ru.stenfredd.movies.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import ru.stenfredd.movies.ui.components.AppIcon
import ru.stenfredd.movies.ui.components.Avatar
import ru.stenfredd.movies.ui.components.Icon
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppDimens
import ru.stenfredd.movies.ui.theme.AppType
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Tab(val label: String, val icon: AppIcon) {
    Search("Поиск", AppIcon.Search),
    Home("Главная", AppIcon.Home),
    Library("Медиатека", AppIcon.Library),
    Favorites("Избранное", AppIcon.Heart),
}

/**
 * Root screens (the tabs): side rail on the left, clock on the top right, content behind.
 * The rail overlaps the content and widens over it when focused, so opening it never relays out the content.
 *
 * Back on a tab moves focus to the rail; Back on the rail leaves the app.
 */
@Composable
fun Shell(
    current: Tab,
    userName: String,
    /** Avatar colour seed (user id), see [Avatar]. */
    userSeed: String,
    onTab: (Tab) -> Unit,
    onProfile: () -> Unit,
    content: @Composable () -> Unit,
) {
    var railFocused by remember { mutableStateOf(false) }
    val railEntry = remember { FocusRequester() }
    BackHandler(enabled = !railFocused) { runCatching { railEntry.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = 32.dp).focusGroup()) { content() }
        Rail(
            current = current,
            userName = userName,
            userSeed = userSeed,
            entry = railEntry,
            onFocusChange = { railFocused = it },
            onTab = onTab,
            onProfile = onProfile,
            modifier = Modifier.zIndex(1f),
        )
        Clock(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = AppDimens.CONTENT_START.dp))
    }
}

/**
 * The rail's layout width switches once when it opens or closes; the visible animation (panel width, item
 * highlight, labels, edge shadow) runs in the draw phase only, so a frame of it re-records the rail and nothing else.
 */
@Composable
private fun Rail(
    current: Tab,
    userName: String,
    userSeed: String,
    entry: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onTab: (Tab) -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (open) 1f else 0f, tween(200), label = "rail")

    Column(
        modifier
            .fillMaxHeight()
            .width(if (open) AppDimens.RAIL_OPEN.dp else AppDimens.RAIL_COLLAPSED.dp)
            .drawBehind {
                val p = progress.value
                val w = lerp(AppDimens.RAIL_COLLAPSED.dp.toPx(), AppDimens.RAIL_OPEN.dp.toPx(), p)
                drawRect(lerp(AppColors.Rail, AppColors.RailOpen, p), size = Size(w, size.height))
                drawRect(AppColors.BorderSubtle, topLeft = Offset(w - 1f, 0f), size = Size(1f, size.height))
                // Edge shadow of the open rail over the content (`30px 0 80px rgba(0,0,0,.6)`), a narrow strip.
                if (p > 0f) {
                    val shadow = 40.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.5f * p), Color.Transparent), startX = w, endX = w + shadow),
                        topLeft = Offset(w, 0f),
                        size = Size(shadow, size.height),
                    )
                }
            }
            .onFocusChanged {
                open = it.hasFocus
                onFocusChange(it.hasFocus)
            }
            .focusRestorer(entry)
            .focusGroup()
            .padding(horizontal = 9.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Logo(progress, Modifier.padding(start = 13.dp, bottom = 20.dp))
        for (tab in Tab.entries) {
            RailItem(
                tab = tab,
                selected = tab == current,
                progress = progress,
                onClick = { if (tab != current) onTab(tab) },
                modifier = if (tab == current) Modifier.focusRequester(entry) else Modifier,
            )
        }
        Spacer(Modifier.weight(1f))
        ProfileItem(userName, userSeed, progress, onProfile)
    }
}

/** Neutral placeholder logo until the app has a name: a glowing dot and the working title. */
@Composable
private fun Logo(progress: State<Float>, modifier: Modifier = Modifier) {
    Row(modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(11.dp)
                .drawBehind {
                    drawCircle(AppColors.AccentStrong.copy(alpha = 0.35f), radius = size.minDimension)
                    drawCircle(AppColors.AccentStrong)
                },
        )
        BasicText(
            "movies",
            Modifier.graphicsLayer { alpha = progress.value },
            style = AppType.Rail.copy(fontSize = AppType.Section.fontSize, fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Focused item fill, growing with the rail panel. */
private fun Modifier.railHighlight(focused: Boolean, color: Color, progress: State<Float>) = drawBehind {
    if (!focused) return@drawBehind
    val collapsed = (AppDimens.RAIL_COLLAPSED - 18).dp.toPx()
    val w = lerp(collapsed, size.width, progress.value)
    drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(8.dp.toPx()))
}

@Composable
private fun RailItem(tab: Tab, selected: Boolean, progress: State<Float>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val content = when {
        focused -> AppColors.OnFocusFill
        selected -> AppColors.Text
        else -> AppColors.TextDim
    }
    Box(modifier.fillMaxWidth().height(36.dp)) {
        // Selected tab marker on the rail's left edge, visible while the rail is closed.
        if (selected) {
            Box(
                Modifier
                    .padding(top = 9.dp, bottom = 9.dp)
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
                .railHighlight(focused, AppColors.FocusFill, progress)
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = null, indication = null, onClick = onClick)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(tab.icon, content, Modifier.size(16.dp))
            BasicText(
                tab.label,
                Modifier.graphicsLayer { alpha = progress.value },
                style = AppType.Rail.copy(color = content),
                maxLines = 1,
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

/** HH:mm, recomposes once a minute and only itself. */
@Composable
fun Clock(modifier: Modifier = Modifier, style: TextStyle = AppType.Clock) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            now = LocalTime.now()
        }
    }
    BasicText(now.format(ClockFormat), modifier, style = style)
}

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

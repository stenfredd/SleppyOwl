package ru.stenfredd.sleepyowl.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.ImageShade
import ru.stenfredd.sleepyowl.ui.components.ProgressLine
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import java.util.UUID

/** Height of the drawer; the controls move up by this much while it is open. */
internal const val DRAWER_HEIGHT = 210

private val DrawerBackground = Color(0xFF0C0D14)
private val EpisodeShade = ImageShade(0.6f, listOf(0f to Color(0xB306070C), 1f to Color.Transparent))
private val EpisodeFocus = FocusStyle(scale = 1.06f, corner = 8.dp, animate = false)
private val PillFocus = FocusStyle(scale = 1f, corner = 15.dp, glows = FocusStyle.ButtonGlows, animate = false)

/**
 * Drawer under the controls (↓): "О фильме" for a movie, the season's episodes for an episode (picking one plays it
 * without leaving the player). ↑ from the header button folds it. [initial] gets focus when it opens.
 */
@Composable
internal fun PlayerDrawer(
    info: PlayerInfo?,
    season: SeasonEpisodes?,
    currentId: UUID?,
    playedFraction: Float,
    initial: FocusRequester,
    onClose: () -> Unit,
    onPage: () -> Unit,
    onEpisode: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodes = season?.takeIf { info?.isEpisode == true }
    val page = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { initial.requestFocus() }
    }
    Column(
        modifier
            .fillMaxWidth()
            .height(DRAWER_HEIGHT.dp)
            .background(DrawerBackground, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .drawBehind { drawRect(Color(0xFF2A2E48), size = Size(size.width, 0.5.dp.toPx())) },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp, 2.5.dp).background(Color(0xFF33384D), RoundedCornerShape(1.5.dp)))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(6.dp).background(if (episodes != null) AppColors.Blue else AppColors.Accent, CircleShape))
            BasicText(
                if (episodes != null) episodes.title else "О ФИЛЬМЕ",
                style = AppType.MonoLabel.copy(color = AppColors.TextSecondary, letterSpacing = 0.1.em),
            )
            BasicText(
                (if (episodes != null) episodes.stat else info?.title).orEmpty(),
                Modifier.weight(1f),
                style = AppType.MonoCaption.copy(fontSize = 9.5.sp, color = Color(0xFF6F7592)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText("▲ — свернуть", Modifier.padding(end = 6.dp), style = AppType.MonoCaption.copy(color = AppColors.TextFaint))
            PagePill(
                if (episodes != null) "К сериалу" else "Страница фильма",
                onClick = onPage,
                modifier = Modifier
                    .focusRequester(page)
                    .then(if (episodes == null) Modifier.focusRequester(initial) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        when {
            // ↑ from the episodes goes to "К сериалу" (the controls above would leave the drawer open behind them).
            episodes != null -> Box(
                Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                        runCatching { page.requestFocus() }
                        true
                    } else {
                        false
                    }
                },
            ) { EpisodeRow(episodes, currentId, playedFraction, initial, onEpisode) }
            info != null -> AboutMovie(info)
        }
    }
}

@Composable
private fun PagePill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .focusCard(PillFocus)
            .height(30.dp)
            .background(if (focused) AppColors.FocusFill else OsdColors.Fill, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else OsdColors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(start = 12.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (focused) AppColors.OnFocusFill else OsdColors.Text
        BasicText(label, style = AppType.Button.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color))
        Icon(AppIcon.ChevronRight, color, Modifier.size(10.dp), strokeWidth = 2.4f)
    }
}

@Composable
private fun AboutMovie(info: PlayerInfo) {
    Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        RemoteImage(info.poster, 7.dp, Modifier.offset(y = (-3).dp).size(PlayerLayout.POSTER_W.dp, PlayerLayout.POSTER_H.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                val meta = AppType.BodyLarge.copy(fontSize = 11.5.sp, color = AppColors.TextSecondary)
                info.rating?.let { BasicText(it, style = meta.copy(color = AppColors.Yellow, fontWeight = FontWeight.SemiBold)) }
                info.age?.let {
                    BasicText(
                        it,
                        Modifier.border(0.5.dp, Color(0xFF4A4F68), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        style = meta.copy(fontSize = 9.5.sp),
                    )
                }
                listOfNotNull(info.year, info.runtime, info.genres).forEach { BasicText(it, style = meta, maxLines = 1) }
            }
            info.description?.let {
                BasicText(
                    it,
                    Modifier.padding(top = 8.dp),
                    style = AppType.Body.copy(fontSize = 12.sp, lineHeight = 18.sp, color = AppColors.TextSecondary),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            info.director?.let {
                BasicText("Режиссёр: $it", Modifier.padding(top = 7.dp), style = AppType.Body.copy(fontSize = 10.5.sp, color = AppColors.TextDim), maxLines = 1)
            }
        }
        if (info.cast.isNotEmpty()) {
            Column(Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("В РОЛЯХ", style = AppType.MonoCaption.copy(color = Color(0xFF6F7592), letterSpacing = 0.1.em))
                Spacer(Modifier.height(1.dp))
                for (p in info.cast) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (p.image != null) {
                            RemoteImage(p.image, 12.dp, Modifier.size(PlayerLayout.AVATAR.dp))
                        } else {
                            Avatar(p.name, seed = p.key, size = PlayerLayout.AVATAR.dp, textStyle = AppType.MonoBadge.copy(fontSize = 9.sp, color = Color.White), letters = p.letters)
                        }
                        BasicText(p.name, style = AppType.CardTitle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(season: SeasonEpisodes, currentId: UUID?, playedFraction: Float, initial: FocusRequester, onEpisode: (UUID) -> Unit) {
    val currentIndex = season.episodes.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 13.dp),
        state = listState,
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(season.episodes, key = { it.key }, contentType = { "episode" }) { e ->
            val current = e.id == currentId
            EpisodeCard(
                e,
                current = current,
                progress = if (current) playedFraction else e.progress,
                onClick = { onEpisode(e.id) },
                modifier = if (current) Modifier.focusRequester(initial) else Modifier,
            )
        }
    }
}

@Composable
private fun EpisodeCard(e: EpisodeModel, current: Boolean, progress: Float?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(PlayerLayout.EPISODE_W.dp)) {
        Box(
            modifier
                .focusCard(EpisodeFocus)
                .size(PlayerLayout.EPISODE_W.dp, PlayerLayout.EPISODE_H.dp)
                .then(if (current) Modifier.border(1.dp, Color(0xFF8B7DFF), RoundedCornerShape(8.dp)) else Modifier)
                .clickable(interactionSource = null, indication = null, onClick = onClick),
        ) {
            RemoteImage(e.image, 8.dp, Modifier.fillMaxSize(), shade = EpisodeShade)
            BasicText(
                e.number,
                Modifier.padding(start = 8.dp, top = 6.dp),
                style = AppType.MonoMeta.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xE0FFFFFF)),
            )
            val badge = when {
                current -> Triple("СЕЙЧАС", AppColors.AccentStrong, Color.White)
                e.watched -> Triple("✓", Color(0xBF0A0B10), AppColors.Green)
                e.isNew -> Triple("НОВАЯ", Color(0xBF0A0B10), AppColors.Pink)
                else -> null
            }
            badge?.let { (text, bg, fg) ->
                BasicText(
                    text,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .background(bg, RoundedCornerShape(3.5.dp))
                        .then(if (current) Modifier else Modifier.border(0.5.dp, fg, RoundedCornerShape(3.5.dp)))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    style = AppType.MonoBadge.copy(color = fg),
                )
            }
            e.duration?.let {
                BasicText(it, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp), style = AppType.MonoMeta.copy(fontSize = 9.sp, color = Color(0xFFD9DCEC)))
            }
            progress?.takeIf { it > 0f }?.let {
                ProgressLine(it, Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 4.dp).fillMaxWidth().height(3.dp))
            }
        }
        BasicText(
            e.title,
            Modifier.padding(top = 7.dp),
            style = AppType.PosterTitle.copy(color = if (e.watched && !current) AppColors.TextMuted else AppColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

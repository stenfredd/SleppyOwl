package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.StatusDot
import ru.stenfredd.sleepyowl.ui.player.NextEpisode
import ru.stenfredd.sleepyowl.ui.player.PlayerLayout
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import kotlin.math.roundToInt

/* What the player puts over the video besides the controls: skip, "next episode", loading, errors, the right-click menu. */

/** Without known credits the card shows this long before the end of an episode that has a next one. */
internal const val NEXT_PROMPT_MS = 30_000L

private val PromptColor = Color(0xFF10121C)
private val PromptBorder = Color(0x40FFFFFF)

/** "Пропустить заставку 1:12" (time left of the part) in the corner where the "next episode" card goes. */
@Composable
internal fun SkipButton(label: String, left: String, ui: PlayerUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .height(40.dp)
            .holdsControls(ui, "skip")
            .onHover { hovered = it }
            .background(if (hovered) AppColors.FocusFill else PromptColor, shape)
            .border(0.5.dp, if (hovered) AppColors.FocusFill else PromptBorder, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = if (hovered) AppColors.OnFocusFill else Color.White
        BasicText(label, style = AppType.Button.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color), maxLines = 1)
        BasicText(left, style = AppType.MonoMeta.copy(fontSize = 10.sp, color = color.copy(alpha = 0.7f)))
    }
}

/** "Заставка пропущена" for a few seconds after an automatic skip. */
@Composable
internal fun SkipNotice(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    BasicText(
        text,
        modifier.background(PromptColor, shape).border(0.5.dp, PromptBorder, shape).padding(horizontal = 14.dp, vertical = 8.dp),
        style = AppType.MonoLabel.copy(color = AppColors.TextSecondary),
    )
}

private val CardColor = Color(0xFF0E1018)
private val CardBorder = Color(0xFF6F63FF)

/**
 * "Следующая через N с" at the end of an episode: still and title of the next one, "Смотреть сейчас" and
 * "Досмотреть титры"; [remainingMs] of [countdownMs] are left until it starts. [compact] (a window under 480 px): one
 * line.
 */
@Composable
internal fun NextEpisodeCard(
    next: NextEpisode,
    remainingMs: Long,
    countdownMs: Long,
    compact: Boolean,
    ui: PlayerUiState,
    onPlay: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
    val fraction = (1f - remainingMs.toFloat() / countdownMs).coerceIn(0f, 1f)
    Column(modifier.width(if (compact) 300.dp else 320.dp).holdsControls(ui, "next").background(CardColor, shape).border(0.5.dp, CardBorder, shape)) {
        if (compact) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    BasicText("ЧЕРЕЗ $seconds С", style = AppType.MonoLabel.copy(fontSize = 9.sp, letterSpacing = 0.08.em))
                    BasicText(next.title, Modifier.padding(top = 3.dp), style = AppType.Section.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PromptButton("Смотреть", primary = true, onClick = onPlay)
                GlyphButton(Glyph.Close, 28.dp, 12.dp, onClick = onStay, bare = true)
            }
        } else {
            Row(Modifier.padding(11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                RemoteImage(next.image, 6.dp, Modifier.size(PlayerLayout.NEXT_W.dp, PlayerLayout.NEXT_H.dp))
                Column(Modifier.weight(1f)) {
                    BasicText("СЛЕДУЮЩАЯ ЧЕРЕЗ $seconds С", style = AppType.MonoLabel.copy(fontSize = 9.sp, letterSpacing = 0.08.em))
                    BasicText(next.title, Modifier.padding(top = 5.dp), style = AppType.Section.copy(fontSize = 13.sp, lineHeight = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(start = 11.dp, end = 11.dp, bottom = 11.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PromptButton("Смотреть сейчас", primary = true, onClick = onPlay, modifier = Modifier.weight(1f))
                PromptButton("Досмотреть титры", primary = false, onClick = onStay)
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .padding(horizontal = 10.dp)
                .drawBehind {
                    drawRect(AppColors.ProgressTrack)
                    drawRect(AppColors.ProgressGradient, Offset.Zero, Size(size.width * fraction, size.height))
                },
        )
    }
}

@Composable
private fun PromptButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier
            .height(32.dp)
            .onHover { hovered = it }
            .then(
                when {
                    primary -> Modifier.background(AppColors.PrimaryGradient, shape)
                    hovered -> Modifier.background(AppColors.FocusFill, shape)
                    else -> Modifier.background(OsdColors.Fill, shape).border(0.5.dp, OsdColors.Border, shape)
                },
            )
            .then(if (primary && hovered) Modifier.border(1.5.dp, Color(0xFFD9D2FF), shape) else Modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (!primary && hovered) AppColors.OnFocusFill else Color.White
        if (primary) Icon(AppIcon.Play, color, Modifier.size(11.dp))
        BasicText(label, style = AppType.Button.copy(fontSize = 12.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, color = color), maxLines = 1)
    }
}

/** Until the first frame. */
@Composable
internal fun LoadingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xCC0A0B10), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(AppColors.Accent, pulsing = true)
        BasicText("ЗАГРУЗКА", style = AppType.MonoLabel.copy(color = AppColors.TextSecondary))
    }
}

@Composable
internal fun ErrorCard(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 420.dp)
            .padding(16.dp)
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .border(0.5.dp, AppColors.Border, RoundedCornerShape(12.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("Не удалось воспроизвести", style = AppType.Section.copy(fontWeight = FontWeight.Bold))
        BasicText(message, style = AppType.Body, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 6.dp)) { PromptButton("Закрыть", primary = false, onClick = onClose) }
    }
}

/** One line of the right-click menu; [hint] is its key. */
@Immutable
internal data class MenuItem(val label: String, val hint: String? = null, val action: () -> Unit)

/**
 * The right-click menu at [at] (moved in to stay whole inside the window). A click past it or Esc closes it
 * ([onDismiss]); picking a line closes it and runs the line.
 */
@Composable
internal fun PlayerMenu(items: List<List<MenuItem>>, at: Offset, ui: PlayerUiState, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onPress = { onDismiss() }) })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bounds = constraints
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier
                .layout { m, c ->
                    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                    val x = at.x.roundToInt().coerceAtMost(bounds.maxWidth - p.width).coerceAtLeast(0)
                    val y = at.y.roundToInt().coerceAtMost(bounds.maxHeight - p.height).coerceAtLeast(0)
                    layout(c.maxWidth, c.maxHeight) { p.place(IntOffset(x, y)) }
                }
                .width(250.dp)
                .holdsControls(ui, "menu")
                .background(PanelColors.Background, shape)
                .border(0.5.dp, PanelColors.Edge, shape)
                .pointerInput(Unit) { detectTapGestures { } }
                // A low window (the mini one) shows it whole by scrolling.
                .verticalScroll(rememberScrollState())
                .padding(vertical = 5.dp),
        ) {
            items.forEachIndexed { i, group ->
                if (i > 0) Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(0.5.dp).background(PanelColors.Edge))
                for (item in group) MenuLine(item) {
                    onDismiss()
                    item.action()
                }
            }
        }
    }
}

@Composable
private fun MenuLine(item: MenuItem, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .height(30.dp)
            .onHover { hovered = it }
            .background(if (hovered) PanelColors.Focused else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(item.label, Modifier.weight(1f), style = AppType.BodyLarge.copy(fontSize = 12.5.sp, color = if (hovered) Color.White else AppColors.TextSecondary), maxLines = 1)
        item.hint?.let { BasicText(it, style = AppType.MonoCaption.copy(color = PanelColors.SectionTitle)) }
    }
}

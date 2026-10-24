package ru.stenfredd.sleepyowl.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.core.data.model.MediaSegment
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import kotlin.math.roundToInt
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.StatusDot
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.shell.Clock
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

/*
 * The player's controls ("OSD") as drawn in the mockup's player screen; sizes are the mockup's px / 2.
 * Everything here is drawn over the video, which on TVs sits on its own hardware plane: the UI layer is only
 * redrawn when something in it changes (a second of the clock, a focus move), so the shades cost nothing while
 * the picture plays. Through the GPU path (SDR on the Xiaomi TV) every video frame redraws them, see APP_PLAN.md.
 */

internal object OsdColors {
    val Fill = Color(0x14FFFFFF)
    val Border = Color(0x24FFFFFF)
    val Text = Color(0xFFE3E5F2)
    /** `linear-gradient(180deg, rgba(5,6,10,.85), transparent)` */
    val TopShade = listOf(0f to Color(0xD905060A), 1f to Color(0x0005060A))
    /** `linear-gradient(0deg, rgba(5,6,10,.97), rgba(5,6,10,.6) 60%, transparent)` */
    val BottomShade = listOf(0f to Color(0x0005060A), 0.4f to Color(0x9905060A), 1f to Color(0xF705060A))
}

/**
 * A vertical shade drawn as thin bands of plain colour. On the test TV a real gradient over the video costs ~8 ms of
 * GPU per frame (HWUI dithers gradients) and a stretched gradient bitmap ~6 ms; plain fills cost about half that.
 * With bands a few px tall the steps are below what the eye sees over moving video.
 */
internal fun Modifier.verticalShade(stops: List<Pair<Float, Color>>): Modifier = drawWithCache {
    val bands = (size.height / SHADE_BAND_DP.dp.toPx()).toInt().coerceIn(2, MAX_SHADE_BANDS)
    // Whole-pixel edges: a rectangle at a fractional position is drawn antialiased, by a heavier shader.
    val edges = List(bands + 1) { i -> (size.height * i / bands).roundToInt().toFloat() }
    val colors = List(bands) { i -> colorAt(stops, (i + 0.5f) / bands) }
    val width = size.width.roundToInt().toFloat()
    onDrawBehind {
        for (i in 0 until bands) {
            if (colors[i].alpha > 0.004f) drawRect(colors[i], Offset(0f, edges[i]), Size(width, edges[i + 1] - edges[i]))
        }
    }
}

private fun colorAt(stops: List<Pair<Float, Color>>, t: Float): Color {
    val after = stops.indexOfFirst { it.first >= t }.let { if (it < 0) stops.lastIndex else it }
    if (after == 0) return stops[0].second
    val (t0, c0) = stops[after - 1]
    val (t1, c1) = stops[after]
    return androidx.compose.ui.graphics.lerp(c0, c1, ((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
}

private const val SHADE_BAND_DP = 4f
private const val MAX_SHADE_BANDS = 64

private val ButtonFocus = FocusStyle(scale = 1f, corner = 19.dp, glows = FocusStyle.ButtonGlows, animate = false)

/** `0 0 0 3px #d9d2ff, 0 0 36px 8px rgba(124,92,255,.7)`, scale 1.06 */
private val PlayFocus = FocusStyle(
    scale = 1.06f,
    corner = 23.dp,
    ringColor = Color(0xFFD9D2FF),
    glows = listOf(FocusStyle.Glow(18.dp, 4.dp, Color(0xB37C5CFF))),
    animate = false,
)

/** Label, title and episode on the left; clock and "закончится в" on the right. */
@Composable
internal fun OsdTop(label: String, title: String, subtitle: String?, endsAt: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = AppType.MonoLabel)
            BasicText(
                title,
                Modifier.padding(top = 5.dp),
                style = AppType.Hero.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                BasicText(it, Modifier.padding(top = 4.dp), style = AppType.BodyLarge.copy(color = AppColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(start = 24.dp), horizontalAlignment = Alignment.End) {
            Clock(style = AppType.Clock.copy(fontSize = 17.sp, color = AppColors.Text))
            endsAt?.let { BasicText(it, Modifier.padding(top = 3.dp), style = AppType.MonoCaption.copy(fontSize = 10.sp)) }
        }
    }
}

/**
 * Track with the buffered part, skippable parts (intro, credits), the played part and chapter marks; thicker with a
 * knob when focused. Drawn as plain rectangles in one draw call: no layers, no paths.
 */
@Composable
internal fun Timeline(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chaptersMs: List<Long>,
    spans: List<MediaSegment>,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .drawBehind {
                val trackH = (if (focused) 7.dp else 3.dp).toPx()
                val top = (size.height - trackH) / 2
                val r = CornerRadius(trackH / 2)
                val w = size.width
                fun x(ms: Long) = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) * w else 0f
                drawRoundRect(Color(0x29FFFFFF), Offset(0f, top), Size(w, trackH), r)
                if (bufferedMs > positionMs) drawRoundRect(Color(0x2EFFFFFF), Offset(0f, top), Size(x(bufferedMs), trackH), r)
                for (span in spans) {
                    val from = x(span.startMs)
                    drawRect(SpanColor, Offset(from, top), Size(x(span.endMs) - from, trackH))
                }
                val played = x(positionMs)
                if (played > 0f) {
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(Color(0xFF4D8BFF), Color(0xFF9B6BFF)), endX = played.coerceAtLeast(1f)),
                        Offset(0f, top),
                        Size(played, trackH),
                        r,
                    )
                }
                val mark = 1.5.dp.toPx()
                for (c in chaptersMs) {
                    if (c >= durationMs) continue
                    drawRect(Color(0xD90A0B10), Offset(x(c) - mark / 2, top), Size(mark, trackH))
                }
                if (focused) {
                    val center = Offset(played, size.height / 2)
                    drawCircle(Color(0x80A594FF), 9.5.dp.toPx(), center)
                    drawCircle(Color.White, 7.5.dp.toPx(), center)
                }
            },
    )
}

private val SpanColor = Color(0x598F6BFF)

/** ⟲ ⟳ ⏮ ⏭: translucent circle, light fill when focused. */
@Composable
internal fun OsdCircleButton(icon: AppIcon, size: Dp, iconSize: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusCard(ButtonFocus.copy(corner = size / 2))
            .size(size)
            .background(if (focused) AppColors.FocusFill else OsdColors.Fill, CircleShape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else OsdColors.Border, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (focused) AppColors.OnFocusFill else Color.White, Modifier.size(iconSize), strokeWidth = 2.2f)
    }
}

/** The big gradient ⏯ in the middle of the controls. */
@Composable
internal fun OsdPlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .focusCard(PlayFocus)
            .size(46.dp)
            .background(AppColors.PrimaryGradient, CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) Icon(AppIcon.Pause, Color.White, Modifier.size(19.dp)) else Icon(AppIcon.Play, Color.White, Modifier.size(20.dp))
    }
}

@Immutable
internal data class OsdChipModel(val key: String, val dot: Color, val label: String)

/** "● Русский 7.1": opens a panel. */
@Composable
internal fun OsdChip(chip: OsdChipModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier
            .focusCard(ButtonFocus.copy(corner = 17.dp))
            .height(34.dp)
            .background(if (focused) AppColors.FocusFill else OsdColors.Fill, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else OsdColors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(chip.dot, CircleShape))
        BasicText(
            chip.label,
            Modifier.widthIn(max = 170.dp),
            style = AppType.Button.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, color = if (focused) AppColors.OnFocusFill else OsdColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "⌄ О фильме" under the controls: ↓ opens the drawer. */
@Composable
internal fun PeekHint(label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(AppIcon.ChevronDown, AppColors.TextDim, Modifier.size(9.dp), strokeWidth = 2.4f)
        BasicText(label, style = AppType.MonoCaption.copy(fontSize = 9.5.sp, letterSpacing = 0.06.em))
    }
}

/** Shown in the middle while paused with the controls hidden. */
@Composable
internal fun PausedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(75.dp)
            .background(Color(0x8C0A0B10), CircleShape)
            .border(0.5.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcon.Pause, Color.White, Modifier.size(28.dp))
    }
}

/** Until the first frame. The dot pulses in steps: a smooth animation would redraw the whole screen every frame. */
@Composable
internal fun LoadingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0xCC0A0B10), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(AppColors.Accent, pulsing = true)
        BasicText("ЗАГРУЗКА", style = AppType.MonoLabel.copy(color = AppColors.TextSecondary))
    }
}

@Composable
internal fun ErrorCard(message: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    Column(
        modifier
            .width(420.dp)
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .border(0.5.dp, AppColors.Border, RoundedCornerShape(12.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("Не удалось воспроизвести", style = AppType.Section.copy(fontWeight = FontWeight.Bold))
        BasicText(message, style = AppType.Body, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

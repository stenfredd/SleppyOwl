package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.core.data.model.MediaSegment
import ru.stenfredd.sleepyowl.playback.mpv.MpvEngine
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.player.PlayerLabels
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import kotlin.math.roundToInt

/*
 * The desktop player's controls: the TV's OSD look (translucent circles, gradient ⏯, chips) lit by the pointer instead
 * of the remote's focus, and the timeline and volume for the mouse.
 */

internal object OsdColors {
    val Fill = Color(0x14FFFFFF)
    val Border = Color(0x24FFFFFF)
    val Text = Color(0xFFE3E5F2)
    val TopShade = Brush.verticalGradient(listOf(Color(0xD905060A), Color(0x0005060A)))
    val BottomShade = Brush.verticalGradient(0f to Color(0x0005060A), 0.4f to Color(0x9905060A), 1f to Color(0xF705060A))
}

/** Icons the TV doesn't have, in the same 24-unit grid and stroke as [AppIcon]. */
internal enum class Glyph(path: String) {
    Volume("M4 9h4l5-4v14l-5-4H4zM16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11"),
    VolumeLow("M4 9h4l5-4v14l-5-4H4zM16 9a4 4 0 0 1 0 6"),
    Muted("M4 9h4l5-4v14l-5-4H4zM16.5 9.5l5 5M21.5 9.5l-5 5"),
    Fullscreen("M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"),
    ExitFullscreen("M9 4v5H4M20 9h-5V4M15 20v-5h5M4 15h5v5"),
    Close("M6 6l12 12M18 6L6 18"),
    More("M5.5 12h.01M12 12h.01M18.5 12h.01"),
    Episodes("M4 6h16M4 12h16M4 18h10"),
    /** The mini window on top of everything: a small picture in a large one. */
    OnTop("M3 5h18v14H3zM12.5 11.5h6v5h-6z"),
    /** The normal window: a frame with a title bar. */
    Windowed("M3 5h18v14H3zM3 9h18"),
    /** "К медиатеке". */
    Library("M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"),
    ;

    val path by lazy { PathParser().parsePathString(path).toPath() }
}

@Composable
internal fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, strokeWidth: Float = 2f) {
    Spacer(
        modifier.drawWithCache {
            val k = size.minDimension / 24f
            // The dots of "⋯" are zero-length lines: their round caps draw them, thicker than a line.
            val width = if (glyph == Glyph.More) strokeWidth * 1.8f else strokeWidth
            val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind { scale(k, k, pivot = Offset.Zero) { drawPath(glyph.path, color, style = stroke) } }
        },
    )
}

/** Tracks the pointer over the element. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.onHover(onChange: (Boolean) -> Unit): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { onChange(true) }
    .onPointerEvent(PointerEventType.Exit) { onChange(false) }

/**
 * While the pointer is over this element the controls stay up (§1.3: buttons, the timeline, panels). [key] tells the
 * elements apart; one that leaves the composition under the pointer lets go too.
 */
internal fun Modifier.holdsControls(ui: PlayerUiState, key: Any): Modifier = composed {
    DisposableEffect(ui, key) { onDispose { ui.holders.remove(key) } }
    onHover { if (it) ui.holders[key] = Unit else ui.holders.remove(key) }
}

/** ⟲ ⟳ ⏮ ⏭ and the other round buttons: translucent circle ([bare]: none), light fill under the pointer. */
@Composable
internal fun RoundButton(
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bare: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .onHover { hovered = it }
            .background(if (hovered) AppColors.FocusFill else if (bare) Color.Transparent else OsdColors.Fill, CircleShape)
            .then(if (bare && !hovered) Modifier else Modifier.border(0.5.dp, if (hovered) AppColors.FocusFill else OsdColors.Border, CircleShape))
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (hovered) AppColors.OnFocusFill else Color.White)
    }
}

@Composable
internal fun IconButton(icon: AppIcon, size: Dp, iconSize: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, bare: Boolean = false) {
    RoundButton(size, onClick, modifier, bare) { Icon(icon, it, Modifier.size(iconSize), strokeWidth = 2.2f) }
}

@Composable
internal fun GlyphButton(glyph: Glyph, size: Dp, iconSize: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, bare: Boolean = false) {
    RoundButton(size, onClick, modifier, bare) { GlyphIcon(glyph, it, Modifier.size(iconSize), strokeWidth = 2f) }
}

/** The big gradient ⏯; a light ring under the pointer. */
@Composable
internal fun PlayButton(playing: Boolean, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .onHover { hovered = it }
            .background(AppColors.PrimaryGradient, CircleShape)
            .then(if (hovered) Modifier.border(2.dp, Color(0xFFD9D2FF), CircleShape) else Modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) Icon(AppIcon.Pause, Color.White, Modifier.size(size * 0.41f)) else Icon(AppIcon.Play, Color.White, Modifier.size(size * 0.43f))
    }
}

@Immutable
internal data class OsdChipModel(val panel: Panel, val dot: Color, val label: String)

/** "● Русский 7.1": opens a panel. */
@Composable
internal fun OsdChip(chip: OsdChipModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier
            .height(34.dp)
            .onHover { hovered = it }
            .background(if (hovered) AppColors.FocusFill else OsdColors.Fill, shape)
            .border(0.5.dp, if (hovered) AppColors.FocusFill else OsdColors.Border, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(chip.dot, CircleShape))
        BasicText(
            chip.label,
            Modifier.widthIn(max = 170.dp),
            style = AppType.Button.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, color = if (hovered) AppColors.OnFocusFill else OsdColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Track with the buffered part, skippable parts (intro, credits), the played part and chapter marks. Under the pointer
 * it thickens, shows a knob and the time at the pointer; pressing or dragging scrubs ([onScrub] with the time under the
 * pointer, [onRelease] when let go), the wheel seeks ±5 s ([onWheel] with −1 / +1). [thin]: the minimal layout's line.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Timeline(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chaptersMs: List<Long>,
    spans: List<MediaSegment>,
    ui: PlayerUiState,
    onScrub: (Long) -> Unit,
    onRelease: () -> Unit,
    onWheel: (Int) -> Unit,
    modifier: Modifier = Modifier,
    thin: Boolean = false,
) {
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(1f) }
    val duration by rememberUpdatedState(durationMs)
    val scrub by rememberUpdatedState(onScrub)
    val release by rememberUpdatedState(onRelease)
    fun at(x: Float): Long = if (duration > 0) ((x / width).coerceIn(0f, 1f) * duration).toLong() else 0L
    val active = hoverX != null || ui.dragging
    Box(
        modifier
            .fillMaxWidth()
            .height(if (thin) 16.dp else 26.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .holdsControls(ui, "timeline")
            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.first().position.x }
            .onPointerEvent(PointerEventType.Enter) { hoverX = it.changes.first().position.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.first().scrollDelta.y
                if (dy != 0f) onWheel(if (dy > 0) -1 else 1)
                e.changes.forEach { it.consume() }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (duration <= 0) return@awaitEachGesture
                    ui.dragging = true
                    try {
                        scrub(at(down.position.x))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            hoverX = change.position.x
                            if (!change.pressed) break
                            scrub(at(change.position.x))
                            change.consume()
                        }
                    } finally {
                        ui.dragging = false
                        release()
                    }
                }
            },
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(if (thin) 16.dp else 26.dp)
                .drawBehind {
                    val trackH = (if (active) (if (thin) 5.dp else 7.dp) else 3.dp).toPx()
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
                            Offset(0f, top), Size(played, trackH), r,
                        )
                    }
                    val mark = 1.5.dp.toPx()
                    for (c in chaptersMs) {
                        if (c >= durationMs) continue
                        drawRect(Color(0xD90A0B10), Offset(x(c) - mark / 2, top), Size(mark, trackH))
                    }
                    if (active) {
                        val center = Offset(played, size.height / 2)
                        drawCircle(Color(0x80A594FF), (if (thin) 7.dp else 9.5.dp).toPx(), center)
                        drawCircle(Color.White, (if (thin) 5.5.dp else 7.5.dp).toPx(), center)
                    }
                },
        )
        // The time under the pointer, above the track and kept inside it.
        val shownX = if (ui.dragging) width * (if (durationMs > 0) positionMs.toFloat() / durationMs else 0f) else hoverX
        if (shownX != null && durationMs > 0) {
            BasicText(
                PlayerLabels.time(if (ui.dragging) positionMs else at(shownX)),
                Modifier
                    .layout { m, c ->
                        val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                        val x = (shownX - p.width / 2f).roundToInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0))
                        layout(0, 0) { p.place(IntOffset(x, -p.height - 2.dp.roundToPx())) }
                    }
                    .background(Color(0xE60E1018), RoundedCornerShape(5.dp))
                    .border(0.5.dp, OsdColors.Border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                style = AppType.MonoMeta.copy(fontSize = 11.sp, color = Color.White),
            )
        }
    }
}

private val SpanColor = Color(0x598F6BFF)

/**
 * 🔊 and a short slider: the icon mutes, pressing or dragging along the slider sets the volume. The slider runs to
 * 100 %; louder (up to [MpvEngine.MAX_VOLUME]) only from the keys and the wheel.
 */
@Composable
internal fun VolumeControl(audio: PlayerAudio, ui: PlayerUiState, onChange: (Int) -> Unit, onMute: () -> Unit, modifier: Modifier = Modifier, slider: Boolean = true) {
    val level = if (audio.muted) 0 else audio.volume
    Row(modifier.holdsControls(ui, "volume"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GlyphButton(
            when {
                audio.muted || audio.volume == 0 -> Glyph.Muted
                audio.volume < 50 -> Glyph.VolumeLow
                else -> Glyph.Volume
            },
            size = 34.dp, iconSize = 17.dp, onClick = onMute, bare = true,
        )
        if (slider) {
            val change by rememberUpdatedState(onChange)
            var w by remember { mutableStateOf(1f) }
            Spacer(
                Modifier
                    .width(72.dp)
                    .height(24.dp)
                    .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            ui.dragging = true
                            try {
                                change(((down.position.x / w).coerceIn(0f, 1f) * 100).roundToInt())
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    change(((c.position.x / w).coerceIn(0f, 1f) * 100).roundToInt())
                                    c.consume()
                                }
                            } finally {
                                ui.dragging = false
                            }
                        }
                    }
                    .drawBehind {
                        val h = 3.dp.toPx()
                        val top = (size.height - h) / 2
                        val filled = size.width * (level.coerceAtMost(100) / 100f)
                        drawRoundRect(Color(0x29FFFFFF), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                        drawRoundRect(Color.White, Offset(0f, top), Size(filled, h), CornerRadius(h / 2))
                        drawCircle(Color.White, 5.dp.toPx(), Offset(filled, size.height / 2))
                    },
            )
        }
    }
}

/** "1:02:11 / 1:48:30" */
@Composable
internal fun TimeLabel(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    BasicText(
        PlayerLabels.time(positionMs) + if (durationMs > 0) " / " + PlayerLabels.time(durationMs) else "",
        modifier,
        style = AppType.MonoMeta.copy(fontSize = 11.sp, color = OsdColors.Text),
        maxLines = 1,
    )
}

/** Short message at the top of the video ("Громкость 70 %", "Аудио: Русский 5.1"). */
@Composable
internal fun Toast(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier
            .background(Color(0xE60E1018), RoundedCornerShape(16.dp))
            .border(0.5.dp, OsdColors.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = AppType.MonoLabel.copy(color = AppColors.TextSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

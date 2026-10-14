package ru.stenfredd.movies.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenfredd.movies.playback.api.SubtitleStyle
import ru.stenfredd.movies.ui.components.FocusStyle
import ru.stenfredd.movies.ui.components.MinimalScrolling
import ru.stenfredd.movies.ui.components.SecondaryButton
import ru.stenfredd.movies.ui.components.focusCard
import ru.stenfredd.movies.ui.details.TrackOption
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppType

enum class PlayerPanel { Audio, Subtitles, Quality, Info }

/** One choice in a panel list or chip row; [onPick] is applied by the screen. */
@Immutable
internal data class PanelChoice(val key: String, val label: String, val detail: String?, val selected: Boolean, val swatch: Color? = null)

@Immutable
internal data class PanelGroup(val key: String, val title: String, val choices: List<PanelChoice>)

private object PanelColors {
    val Background = Color(0xFF10121A)
    val Header = Color(0xFF161925)
    val HeaderLine = Color(0xFF232637)
    val Edge = Color(0xFF262A3A)
    val Option = Color(0xFF151824)
    val OptionBorder = Color(0xFF232637)
    val Selected = Color(0xFF1B1D38)
    val SelectedBorder = Color(0xFF5F55E6)
    val Focused = Color(0xFF1F2238)
    val Ring = Color(0xFF4A4F68)
    val SectionTitle = Color(0xFF6F7592)
}

/** `border-color: #a594ff; box-shadow: 0 0 0 1px #a594ff, 0 0 30px rgba(124,92,255,.45)` */
private val OptionFocus = FocusStyle(scale = 1f, corner = 7.dp, ring = 0.5.dp, glows = listOf(FocusStyle.Glow(15.dp, 0.dp, Color(0x737C5CFF))), animate = false)
private val ChipFocus = FocusStyle(scale = 1f, corner = 15.dp, glows = FocusStyle.ButtonGlows, animate = false)

/**
 * Settings panel on the right, full height, opaque (a translucent full-screen scrim is expensive on weak TV GPUs).
 * Focus stays inside: ←/→ at the edge don't leak to the controls behind. [initial] gets focus when it opens.
 */
@Composable
internal fun SidePanel(
    title: String,
    dot: Color,
    hint: String,
    initial: FocusRequester,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { initial.requestFocus() }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(350.dp)
            .background(PanelColors.Background)
            .drawBehind { drawRect(PanelColors.Edge, size = androidx.compose.ui.geometry.Size(0.5.dp.toPx(), size.height)) }
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection in TRAPPED) cancelFocusChange()
                }
            }
            .focusGroup(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(PanelColors.Header)
                .drawBehind {
                    val line = 0.5.dp.toPx()
                    drawRect(PanelColors.HeaderLine, Offset(0f, size.height - line), androidx.compose.ui.geometry.Size(size.width, line))
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            BasicText(title, Modifier.weight(1f), style = AppType.MonoLabel.copy(color = AppColors.TextSecondary, letterSpacing = 0.1.em))
            BasicText(hint, style = AppType.MonoCaption.copy(color = PanelColors.SectionTitle))
        }
        MinimalScrolling(margin = 14.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
                content = content,
            )
        }
    }
}

private val TRAPPED = setOf(FocusDirection.Left, FocusDirection.Right, FocusDirection.Up, FocusDirection.Down)

@Composable
internal fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        BasicText(title, style = AppType.MonoCaption.copy(color = PanelColors.SectionTitle, letterSpacing = 0.1.em))
        Column(Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun PanelNote(text: String) {
    BasicText(text, style = AppType.Body.copy(fontSize = 10.5.sp, lineHeight = 15.sp, color = AppColors.TextDim))
}

/** List option with a radio mark: "◉ Русский        TrueHD Atmos · 7.1". */
@Composable
internal fun PanelOption(choice: PanelChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .focusCard(OptionFocus)
            .fillMaxWidth()
            .height(42.dp)
            .background(if (focused) PanelColors.Focused else if (choice.selected) PanelColors.Selected else PanelColors.Option, shape)
            .border(0.5.dp, if (focused) AppColors.Accent else if (choice.selected) PanelColors.SelectedBorder else PanelColors.OptionBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(13.dp).border(1.dp, if (choice.selected) AppColors.Accent else PanelColors.Ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (choice.selected) Box(Modifier.size(6.dp).background(AppColors.Accent, CircleShape))
        }
        BasicText(
            choice.label,
            Modifier.weight(1f),
            style = AppType.BodyLarge.copy(fontWeight = FontWeight.Medium, color = AppColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        choice.detail?.let { BasicText(it, style = AppType.MonoCaption.copy(fontSize = 10.sp), maxLines = 1) }
    }
}

/** Small choice in a row: size "M", colour "● Жёлтый", background "Тень". */
@Composable
internal fun PanelChip(choice: PanelChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .focusCard(ChipFocus)
            .height(30.dp)
            .background(if (focused) AppColors.FocusFill else if (choice.selected) PanelColors.Selected else PanelColors.Option, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else if (choice.selected) PanelColors.SelectedBorder else PanelColors.OptionBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        choice.swatch?.let { Box(Modifier.size(9.dp).background(it, CircleShape).border(0.5.dp, Color(0x4D000000), CircleShape)) }
        BasicText(choice.label, style = AppType.Button.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, color = if (focused) AppColors.OnFocusFill else OsdColors.Text))
    }
}

/** A list of [choices]; the selected one (or the first) takes [initial] focus. */
@Composable
internal fun PanelList(choices: List<PanelChoice>, initial: FocusRequester?, onPick: (PanelChoice) -> Unit) {
    val target = choices.firstOrNull { it.selected } ?: choices.firstOrNull()
    for (c in choices) {
        PanelOption(c, onClick = { onPick(c) }, Modifier.then(if (c === target && initial != null) Modifier.focusRequester(initial) else Modifier))
    }
}

@Composable
internal fun PanelChips(choices: List<PanelChoice>, onPick: (PanelChoice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (c in choices) PanelChip(c, onClick = { onPick(c) })
    }
}

/** How text subtitles will look: sample line over a dark picture-like box. */
@Composable
internal fun SubtitlePreview(style: SubtitleStyle, on: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(85.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF2B4A66), Color(0xFF16233A), Color(0xFF241B36))), RoundedCornerShape(7.dp))
            .border(0.5.dp, PanelColors.Edge, RoundedCornerShape(7.dp))
            .padding(bottom = 11.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val box = style.background == SubtitleStyle.Background.BOX
        BasicText(
            if (on) "— Ты слышишь? Сигнал снова идёт со дна." else "Субтитры выключены",
            Modifier
                .then(if (box) Modifier.background(Color(0xB8000000), RoundedCornerShape(3.dp)) else Modifier)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            style = AppType.Body.copy(
                fontSize = 13.sp * style.size.scale,
                lineHeight = 16.sp * style.size.scale,
                fontWeight = FontWeight.SemiBold,
                color = Color(style.color),
                textAlign = TextAlign.Center,
                shadow = if (style.background == SubtitleStyle.Background.SHADOW) Shadow(Color.Black, Offset(0f, 2f), 6f) else null,
            ),
        )
    }
}

/** "Прямое воспроизведение / Файл воспроизводится без изменений" in a green (or orange) frame. */
@Composable
internal fun StreamModeCard(mode: StreamMode) {
    val color = if (mode.direct) AppColors.Green else AppColors.Orange
    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, color, RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        BasicText(mode.title, style = AppType.Section.copy(fontWeight = FontWeight.Bold, color = color))
        BasicText(mode.reason, Modifier.padding(top = 4.dp), style = AppType.Body.copy(color = AppColors.TextMuted))
    }
}

@Composable
internal fun InfoRows(rows: List<InfoRow>) {
    Column {
        for (r in rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val line = 0.5.dp.toPx()
                        drawRect(AppColors.BorderSubtle, Offset(0f, size.height - line), androidx.compose.ui.geometry.Size(size.width, line))
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).background(r.color, CircleShape))
                BasicText(r.key, Modifier.padding(start = 7.dp).width(75.dp), style = AppType.MonoMeta.copy(fontSize = 10.sp, color = PanelColors.SectionTitle))
                BasicText(r.value, Modifier.weight(1f), style = AppType.MonoMeta.copy(fontSize = 10.sp, color = OsdColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun PanelCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SecondaryButton("Закрыть", onClick = onClick, modifier = modifier)
}

internal fun List<TrackOption>.toChoices(selected: Int?): List<PanelChoice> =
    map { PanelChoice(it.key, it.label, it.detail, it.index == selected) }

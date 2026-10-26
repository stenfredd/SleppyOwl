package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.player.PanelChoice
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

/*
 * Options of the TV player's side panels, for the mouse: the series' audio and subtitle tray now, the desktop player's
 * panels in L7.
 */

internal object PanelColors {
    val Background = Color(0xFF10121A)
    val Header = Color(0xFF181B27)
    val Edge = Color(0xFF262A3A)
    val Option = Color(0xFF151824)
    val OptionBorder = Color(0xFF232637)
    val Selected = Color(0xFF1B1D38)
    val SelectedBorder = Color(0xFF5F55E6)
    val Focused = Color(0xFF1F2238)
    val Ring = Color(0xFF4A4F68)
    val SectionTitle = Color(0xFF6F7592)
}

private val OptionFocus = FocusStyle(scale = 1f, corner = 7.dp, ring = 0.5.dp, glows = listOf(FocusStyle.Glow(15.dp, 0.dp, Color(0x737C5CFF))))
private val ChipFocus = FocusStyle(scale = 1f, corner = 15.dp, glows = FocusStyle.ButtonGlows)

@Composable
fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        BasicText(title, style = AppType.MonoCaption.copy(color = PanelColors.SectionTitle, letterSpacing = 0.1.em))
        Column(Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** List option with a radio mark: "◉ Русский        TrueHD Atmos · 7.1". */
@Composable
fun PanelOption(choice: PanelChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .hoverFocus()
            .focusCard(OptionFocus)
            .fillMaxWidth()
            .height(40.dp)
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
fun PanelChip(choice: PanelChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .hoverFocus()
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
        BasicText(choice.label, style = AppType.Button.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, color = if (focused) AppColors.OnFocusFill else Color(0xFFE3E5F2)))
    }
}

@Composable
fun PanelList(choices: List<PanelChoice>, onPick: (PanelChoice) -> Unit) {
    for (c in choices) PanelOption(c, onClick = { onPick(c) })
}

@Composable
fun PanelChips(choices: List<PanelChoice>, onPick: (PanelChoice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (c in choices) PanelChip(c, onClick = { onPick(c) })
    }
}

/** How text subtitles will look: sample line over a dark picture-like box. */
@Composable
fun SubtitlePreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(85.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF2B4A66), Color(0xFF16233A), Color(0xFF241B36))), RoundedCornerShape(7.dp))
            .border(0.5.dp, PanelColors.Edge, RoundedCornerShape(7.dp))
            .padding(bottom = 11.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val box = style.background == SubtitleStyle.Background.BOX
        BasicText(
            "— Ты слышишь? Сигнал снова идёт со дна.",
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

package ru.stenfredd.movies.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppType

/** Main action: gradient pill "▶ Смотреть". */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: AppIcon? = AppIcon.Play, trailing: String? = null) {
    Row(
        modifier
            .focusCard(FocusStyle(scale = 1.04f, corner = 19.dp, glows = FocusStyle.ButtonGlows))
            .height(38.dp)
            .background(AppColors.PrimaryGradient, RoundedCornerShape(19.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, Color.White, Modifier.size(14.dp)) }
        BasicText(label, style = AppType.Button.copy(fontSize = AppType.CardTitle.fontSize))
        trailing?.let { BasicText(it, style = AppType.Body.copy(color = Color(0xCCFFFFFF))) }
    }
}

/** Secondary pill ("С начала") and round icon buttons: dark, filled light when focused. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusCard(FocusStyle(scale = 1.04f, corner = 19.dp, glows = FocusStyle.ButtonGlows))
            .height(38.dp)
            .background(if (focused) AppColors.FocusFill else AppColors.SurfaceRaised, RoundedCornerShape(19.dp))
            .border(0.5.dp, if (focused) Color.Transparent else AppColors.Border, RoundedCornerShape(19.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = AppType.Button.copy(fontSize = AppType.CardTitle.fontSize, color = if (focused) AppColors.OnFocusFill else AppColors.Text))
    }
}

@Composable
fun CircleButton(icon: AppIcon, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = AppColors.Text, size: Dp = 38.dp) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusCard(FocusStyle(scale = 1.06f, corner = size / 2, glows = FocusStyle.ButtonGlows))
            .size(size)
            .background(if (focused) AppColors.FocusFill else AppColors.SurfaceRaised, CircleShape)
            .border(0.5.dp, if (focused) Color.Transparent else AppColors.Border, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (focused) AppColors.OnFocusFill else tint, Modifier.size(size * 0.42f))
    }
}

/** Option chip: "Русский  TrueHD Atmos 7.1"; selected = violet. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .focusCard(FocusStyle(scale = 1.04f, corner = 18.dp, glows = FocusStyle.ButtonGlows))
            .height(36.dp)
            .background(if (selected) AppColors.Selected else AppColors.SurfaceRaised, shape)
            .border(0.5.dp, if (selected) AppColors.SelectedBorder else AppColors.Border, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(label, style = AppType.Button.copy(fontSize = 12.sp, color = if (selected) AppColors.Text else AppColors.TextSecondary))
        detail?.let { BasicText(it, style = AppType.MonoCaption) }
    }
}

/** Format badge: "4K", "HDR10", "Atmos" with its colour. */
@Composable
fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    val color = when (text) {
        "4K" -> AppColors.Blue
        "HDR10", "HDR10+", "DV", "HLG" -> AppColors.Yellow
        "Atmos", "5.1", "7.1", "FLAC", "TrueHD", "DTS" -> AppColors.Pink
        else -> AppColors.TextMuted
    }
    BasicText(
        text,
        modifier
            .border(0.5.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.5.dp),
        style = AppType.MonoCaption.copy(color = color),
    )
}

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppType

private val PrimaryFocus = FocusStyle(
    scale = 1.05f,
    corner = 21.dp,
    ringColor = Color(0xFFD9D2FF),
    // `0 0 36px 8px rgba(124,92,255,.65), 0 0 100px rgba(70,120,255,.35)`
    glows = listOf(FocusStyle.Glow(18.dp, 4.dp, Color(0xA67C5CFF)), FocusStyle.Glow(50.dp, 0.dp, Color(0x594678FF))),
)

private val LightFocus = FocusStyle(scale = 1.05f, corner = 21.dp, glows = FocusStyle.ButtonGlows)

/** Main action: gradient pill "▶ Смотреть". */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: AppIcon? = AppIcon.Play) {
    Row(
        modifier
            .focusCard(PrimaryFocus)
            .height(42.dp)
            .background(AppColors.PrimaryGradient, RoundedCornerShape(21.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(start = 15.dp, end = 19.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, Color.White, Modifier.size(15.dp)) }
        BasicText(label, style = AppType.Button.copy(fontSize = 14.sp, color = Color.White))
    }
}

/** Secondary pill ("С начала"): dark, filled light when focused. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusCard(LightFocus)
            .height(42.dp)
            .background(if (focused) AppColors.FocusFill else AppColors.SurfaceRaised, RoundedCornerShape(21.dp))
            .border(0.5.dp, if (focused) AppColors.FocusFill else AppColors.Border, RoundedCornerShape(21.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = AppType.Button.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = if (focused) AppColors.OnFocusFill else Color(0xFFE3E5F2)))
    }
}

/** Round icon button (favourite, watched); [tint] is the icon colour when not focused. */
@Composable
fun CircleButton(icon: AppIcon, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color(0xFFE3E5F2), size: Dp = 42.dp) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusCard(LightFocus.copy(corner = size / 2))
            .size(size)
            .background(if (focused) AppColors.FocusFill else AppColors.SurfaceRaised, CircleShape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else AppColors.Border, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (focused) AppColors.OnFocusFill else tint, Modifier.size(size * 0.36f))
    }
}

/** Option chip: "Русский  TrueHD Atmos 7.1" (or "● Жёлтый" with a [swatch]); selected = violet, focused = light fill. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null, swatch: Color? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.5.dp)
    val textColor = when {
        focused -> AppColors.OnFocusFill
        selected -> AppColors.Text
        else -> AppColors.TextSecondary
    }
    Row(
        modifier
            .focusCard(FocusStyle(scale = 1f, corner = 14.5.dp, glows = FocusStyle.ButtonGlows))
            .height(29.dp)
            .background(
                when {
                    focused -> AppColors.FocusFill
                    selected -> Color(0xFF211D4A)
                    else -> AppColors.SurfaceRaised
                },
                shape,
            )
            .border(0.5.dp, if (focused) AppColors.FocusFill else if (selected) Color(0xFF7A6CFF) else AppColors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        swatch?.let { Box(Modifier.size(9.dp).background(it, CircleShape).border(0.5.dp, Color(0x4D000000), CircleShape)) }
        BasicText(label, Modifier.widthIn(max = 190.dp), style = AppType.Button.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = textColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.let { BasicText(it, style = AppType.MonoCaption.copy(fontSize = 8.5.sp, color = textColor.copy(alpha = 0.65f)), maxLines = 1) }
    }
}

/** Format badge: "4K", "HDR10", "Atmos" with its colour. */
@Composable
fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    val color = when (text) {
        "4K" -> AppColors.Blue
        "HDR10", "HDR10+", "DV", "HLG", "HDR" -> AppColors.Yellow
        "Atmos", "5.1", "7.1", "FLAC", "TrueHD", "DTS", "DTS:X" -> AppColors.Pink
        else -> AppColors.TextMuted
    }
    BasicText(
        text,
        modifier
            .border(0.5.dp, color, RoundedCornerShape(3.5.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        style = AppType.MonoCaption.copy(color = color),
    )
}

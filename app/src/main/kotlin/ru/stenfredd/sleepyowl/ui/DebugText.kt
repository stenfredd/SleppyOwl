package ru.stenfredd.sleepyowl.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppFonts
import ru.stenfredd.sleepyowl.ui.theme.AppType

/** Plain text of the debug and prototype screens (the tour, the player's diagnostics, the folder grid). */
@Composable
internal fun DebugText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    color: Color = AppColors.Text,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily = AppFonts.Onest,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text,
        modifier,
        style = AppType.BodyLarge.copy(fontSize = fontSize, color = color, fontWeight = fontWeight, fontFamily = fontFamily, lineHeight = lineHeight),
        maxLines = maxLines,
    )
}

package ru.stenfredd.sleepyowl.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

private val SkipColor = Color(0xFF10121C)
private val SkipBorder = Color(0x40FFFFFF)
/** `0 0 0 3px #a594ff, 0 0 40px 8px rgba(124,92,255,.65)` */
private val SkipFocus = FocusStyle(scale = 1f, corner = 20.dp, glows = listOf(FocusStyle.Glow(20.dp, 4.dp, Color(0xA67C5CFF))), animate = false)

/**
 * "Пропустить заставку 1:12" (time left of the part) in the corner where the "next episode" card goes. Opaque, as
 * that card: a translucent one over video costs a blend every frame.
 */
@Composable
internal fun SkipButton(label: String, left: String, focusRequester: FocusRequester, onClick: () -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .focusRequester(focusRequester)
            .focusCard(SkipFocus)
            .height(40.dp)
            .background(if (focused) AppColors.FocusFill else SkipColor, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else SkipBorder, shape)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = if (focused) AppColors.OnFocusFill else Color.White
        BasicText(label, style = AppType.Button.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color))
        BasicText(left, style = AppType.MonoMeta.copy(fontSize = 10.sp, color = color.copy(alpha = 0.7f)))
    }
}

/** "Заставка пропущена" for a few seconds after an automatic skip; not focusable. */
@Composable
internal fun SkipNotice(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    BasicText(
        text,
        modifier
            .background(SkipColor, shape)
            .border(0.5.dp, SkipBorder, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = AppType.MonoLabel.copy(color = AppColors.TextSecondary),
    )
}

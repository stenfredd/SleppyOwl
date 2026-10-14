package ru.stenfredd.movies.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenfredd.movies.ui.components.AppIcon
import ru.stenfredd.movies.ui.components.FocusStyle
import ru.stenfredd.movies.ui.components.Icon
import ru.stenfredd.movies.ui.components.RemoteImage
import ru.stenfredd.movies.ui.components.focusCard
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppType

/** The card shows this long before the end of an episode that has a next one. */
internal const val NEXT_PROMPT_MS = 30_000L

private val CardColor = Color(0xFF0E1018)
private val CardBorder = Color(0xFF6F63FF)
private val PromptFocus = FocusStyle(scale = 1f, corner = 17.dp, glows = FocusStyle.ButtonGlows, animate = false)

/**
 * "Следующая через N с" at the end of an episode: still and title of the next one, "Смотреть сейчас" and
 * "Досмотреть титры". Opaque (over video a translucent card costs a blend every frame); the countdown and the bar
 * move once a second, as the position does.
 */
@Composable
internal fun NextEpisodeCard(
    next: NextEpisode,
    remainingMs: Long,
    playNow: FocusRequester,
    onPlay: () -> Unit,
    onStay: () -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
    val fraction = (1f - remainingMs.toFloat() / NEXT_PROMPT_MS).coerceIn(0f, 1f)
    Column(
        modifier
            .width(320.dp)
            .background(CardColor, shape)
            .border(0.5.dp, CardBorder, shape)
            .onFocusChanged { onFocus(it.hasFocus) },
    ) {
        Row(Modifier.padding(11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            RemoteImage(next.image, 6.dp, Modifier.size(PlayerLayout.NEXT_W.dp, PlayerLayout.NEXT_H.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    "СЛЕДУЮЩАЯ ЧЕРЕЗ $seconds С",
                    style = AppType.MonoLabel.copy(fontSize = 9.sp, letterSpacing = 0.08.em),
                )
                BasicText(
                    next.title,
                    Modifier.padding(top = 5.dp),
                    style = AppType.Section.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(start = 11.dp, end = 11.dp, bottom = 11.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PromptButton("Смотреть сейчас", primary = true, onClick = onPlay, modifier = Modifier.weight(1f).focusRequester(playNow))
            PromptButton("Досмотреть титры", primary = false, onClick = onStay)
        }
        // Countdown bar along the bottom edge, inside the rounded corners.
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
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier
            .focusCard(PromptFocus)
            .height(34.dp)
            .then(
                when {
                    primary -> Modifier.background(AppColors.PrimaryGradient, shape)
                    focused -> Modifier.background(AppColors.FocusFill, shape)
                    else -> Modifier.background(OsdColors.Fill, shape).border(0.5.dp, OsdColors.Border, shape)
                },
            )
            .then(if (primary && focused) Modifier.border(1.5.dp, Color(0xFFD9D2FF), shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (!primary && focused) AppColors.OnFocusFill else Color.White
        if (primary) Icon(AppIcon.Play, color, Modifier.size(11.dp))
        BasicText(label, style = AppType.Button.copy(fontSize = 12.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, color = color))
    }
}

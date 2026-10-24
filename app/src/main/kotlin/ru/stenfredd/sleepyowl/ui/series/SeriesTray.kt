package ru.stenfredd.sleepyowl.ui.series

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.ui.components.MinimalScrolling
import ru.stenfredd.sleepyowl.ui.player.PanelChips
import ru.stenfredd.sleepyowl.ui.player.PanelList
import ru.stenfredd.sleepyowl.ui.player.PanelSection
import ru.stenfredd.sleepyowl.ui.player.SubtitleChoices
import ru.stenfredd.sleepyowl.ui.player.toChoices
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * "Аудио и субтитры" tray at the bottom of the series page: audio, subtitles and their look, applied at once and
 * remembered for the whole series (the tracks are those of the next episode's file; other episodes get the closest
 * match). Opaque, without the mockup's full-screen dimming, which is expensive on weak TV GPUs.
 *
 * Focus stays inside; ↓ past the bottom or Back closes it.
 */
@Composable
internal fun SeriesTray(
    title: String,
    state: SeriesState,
    style: SubtitleStyle,
    vm: SeriesViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { initial.requestFocus() }
    }
    // Back went down inside the tray; it acts on release.
    val backDown = remember { booleanArrayOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .height(TRAY_H.dp)
            .background(AppColors.Background)
            .drawBehind { drawRect(Color(0xFF262A3A), size = Size(size.width, 1f)) }
            // Back acts on release, as Android does: closing on the press would send the release to the page, which
            // would leave it.
            .onPreviewKeyEvent {
                val code = it.key.nativeKeyCode
                if (code != AndroidKeyEvent.KEYCODE_BACK && code != AndroidKeyEvent.KEYCODE_ESCAPE) return@onPreviewKeyEvent false
                when (it.type) {
                    KeyEventType.KeyDown -> backDown[0] = true
                    KeyEventType.KeyUp -> if (backDown[0]) onClose()
                }
                true
            }
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Down) onClose()
                    cancelFocusChange()
                }
            }
            .focusGroup()
            .padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).background(AppColors.Accent, CircleShape))
            BasicText(
                "АУДИО И СУБТИТРЫ · " + title.uppercase(),
                Modifier.weight(1f),
                style = AppType.MonoLabel.copy(color = AppColors.TextSecondary, letterSpacing = 0.1.em),
                maxLines = 1,
            )
            BasicText("Запоминается для всего сериала · Назад или ▼ — закрыть", style = AppType.MonoCaption.copy(color = Color(0xFF6F7592)))
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val audioFirst = state.audio.isNotEmpty()
            if (state.audio.isNotEmpty()) {
                TrayColumn("АУДИО", AppColors.Pink) {
                    PanelList(state.audio.toChoices(state.selectedAudio), initial) { c ->
                        vm.selectAudio(state.audio.firstOrNull { it.key == c.key }?.index)
                    }
                }
            }
            if (state.subtitles.isNotEmpty()) {
                TrayColumn("СУБТИТРЫ", AppColors.Yellow) {
                    PanelList(state.subtitles.toChoices(state.selectedSubtitle), if (audioFirst) null else initial) { c ->
                        vm.selectSubtitle(state.subtitles.firstOrNull { it.key == c.key }?.index)
                    }
                }
            }
            TrayColumn("ВИД СУБТИТРОВ", AppColors.Yellow) {
                PanelSection("РАЗМЕР") { PanelChips(SubtitleChoices.sizes(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
                PanelSection("ЦВЕТ") { PanelChips(SubtitleChoices.colors(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
                PanelSection("ФОН") { PanelChips(SubtitleChoices.backgrounds(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            }
        }
    }
}

private const val TRAY_H = 262f

@Composable
private fun RowScope.TrayColumn(title: String, dot: Color, spacing: Dp = 4.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(Color(0xFF10121A), shape)
            .border(0.5.dp, Color(0xFF262A3A), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(25.dp)
                .background(Color(0xFF181B27), RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(5.dp).background(dot, CircleShape))
            BasicText(title, style = AppType.MonoCaption.copy(color = AppColors.TextMuted, letterSpacing = 0.1.em))
        }
        MinimalScrolling(margin = 10.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

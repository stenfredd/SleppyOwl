package ru.stenfredd.sleepyowl.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.player.SidePanel
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import android.view.KeyEvent as AndroidKeyEvent

/**
 * Hosts the watched menu over [content]: a panel on the right, opaque (a full-screen scrim is expensive on weak TV
 * GPUs), as the player's settings panels. Marking a season or series marks every episode on the server.
 */
@Composable
fun WatchMenuHost(content: @Composable () -> Unit) {
    val menu = remember { WatchMenu() }
    val vm: WatchMenuViewModel = koinViewModel()
    // Not the panel's scope: a request goes on when the panel is closed before the server answers.
    val scope = rememberCoroutineScope()

    fun close(shown: WatchMenu.Shown) {
        if (menu.shown !== shown) return
        // Focus goes back before the panel goes: a focused panel leaving would hand focus to the page's first button.
        val back = runCatching { shown.returnTo.requestFocus() }.getOrDefault(false)
        menu.shown = null
        if (!back) {
            scope.launch {
                withFrameNanos { }
                runCatching { shown.returnTo.requestFocus() }
            }
        }
    }

    CompositionLocalProvider(LocalWatchMenu provides menu) {
        Box(Modifier.fillMaxSize()) {
            content()
            menu.shown?.let { shown ->
                key(shown) {
                    var saving by remember { mutableStateOf<Boolean?>(null) }
                    var error by remember { mutableStateOf<String?>(null) }
                    WatchPanel(
                        shown.target,
                        saving = saving,
                        error = error,
                        onPick = { played ->
                            if (saving == null) {
                                saving = played
                                error = null
                                scope.launch {
                                    try {
                                        vm.setPlayed(shown.target.id, played)
                                        close(shown)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(e, "Watched menu: marking failed")
                                        saving = null
                                        error = e.message ?: e.toString()
                                    }
                                }
                            }
                        },
                        onClose = { close(shown) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

private val ActionFocus = FocusStyle(scale = 1f, corner = 7.dp, ring = 0.5.dp, glows = listOf(FocusStyle.Glow(15.dp, 0.dp, Color(0x737C5CFF))), animate = false)

/**
 * The menu itself: what the item is, how much of it is watched, and the actions. [saving] is the action being sent
 * (true = watched), [error] why the last one failed.
 */
@Composable
private fun WatchPanel(
    target: WatchTarget,
    saving: Boolean?,
    error: String?,
    onPick: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val initial = remember { FocusRequester() }
    // The long press that opened the menu may still be held: its repeats and release must not press an action,
    // so OK counts only from a fresh press.
    val okArmed = remember { booleanArrayOf(false) }
    // Back acts on release, as Android does: closing on the press would send the release to the page, which would leave it.
    val backDown = remember { booleanArrayOf(false) }
    SidePanel(
        title = target.kindLabel(),
        dot = AppColors.Green,
        hint = "Назад — закрыть",
        initial = initial,
        modifier = modifier.onPreviewKeyEvent {
            val native = it.nativeKeyEvent
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (it.type == KeyEventType.KeyDown && native.repeatCount == 0) okArmed[0] = true
                    !okArmed[0]
                }
                AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> {
                    when (it.type) {
                        KeyEventType.KeyDown -> backDown[0] = true
                        KeyEventType.KeyUp -> if (backDown[0]) onClose()
                    }
                    true
                }
                else -> false
            }
        },
    ) {
        Column {
            BasicText(target.title, style = AppType.Section.copy(fontSize = 17.sp, lineHeight = 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            target.subtitle?.let {
                BasicText(it, Modifier.padding(top = 5.dp), style = AppType.MonoMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            target.status()?.let {
                BasicText(it, Modifier.padding(top = 9.dp), style = AppType.Body.copy(color = if (target.played) AppColors.Green else AppColors.TextMuted))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            target.actions().forEachIndexed { i, action ->
                ActionRow(
                    action,
                    saving = saving == action.played,
                    onClick = { onPick(action.played) },
                    modifier = if (i == 0) Modifier.focusRequester(initial) else Modifier,
                )
            }
        }
        error?.let {
            BasicText("Не удалось сохранить: $it", style = AppType.Body.copy(color = AppColors.Red), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "✓ Отметить просмотренным" with what it touches under it. */
@Composable
private fun ActionRow(action: MenuAction, saving: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    val tint = if (action.played) AppColors.Green else AppColors.TextSecondary
    Row(
        modifier
            .focusCard(ActionFocus)
            .fillMaxWidth()
            .height(if (action.detail != null) 52.dp else 42.dp)
            .background(if (focused) Color(0xFF1F2238) else AppColors.SurfaceRaised, shape)
            .border(0.5.dp, if (focused) AppColors.Accent else Color(0xFF232637), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(action.icon, tint, Modifier.size(15.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                action.label,
                style = AppType.BodyLarge.copy(fontWeight = FontWeight.Medium, color = AppColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            (if (saving) "Сохраняем…" else action.detail)?.let {
                BasicText(it, Modifier.padding(top = 4.dp), style = AppType.MonoCaption.copy(fontSize = 9.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


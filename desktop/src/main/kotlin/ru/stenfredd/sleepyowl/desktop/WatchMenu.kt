package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.menu.LocalWatchMenu
import ru.stenfredd.sleepyowl.ui.menu.MenuAction
import ru.stenfredd.sleepyowl.ui.menu.WatchMenu
import ru.stenfredd.sleepyowl.ui.menu.WatchMenuViewModel
import ru.stenfredd.sleepyowl.ui.menu.WatchTarget
import ru.stenfredd.sleepyowl.ui.menu.actions
import ru.stenfredd.sleepyowl.ui.menu.kindLabel
import ru.stenfredd.sleepyowl.ui.menu.status
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import kotlin.math.roundToInt

/**
 * Hosts the watched menu over [content]: the right button on a card (or the ✓ of a series page) opens it where the
 * pointer is, as a context menu; a click past it or Esc closes it. Marking a season or series marks every episode on
 * the server. The TV's menu ([WatchMenu]) with the desktop's look.
 */
@Composable
fun WatchMenuHost(content: @Composable () -> Unit) {
    val menu = remember { WatchMenu() }
    val vm: WatchMenuViewModel = koinViewModel()
    // Not the menu's scope: a request goes on when the menu is closed before the server answers.
    val scope = rememberCoroutineScope()

    fun close(shown: WatchMenu.Shown) {
        if (menu.shown !== shown) return
        menu.shown = null
        runCatching { shown.returnTo.requestFocus() }
    }

    CompositionLocalProvider(LocalWatchMenu provides menu) {
        Box(Modifier.fillMaxSize()) {
            content()
            menu.shown?.let { shown ->
                key(shown) {
                    var saving by remember { mutableStateOf<Boolean?>(null) }
                    var error by remember { mutableStateOf<String?>(null) }
                    val at = remember { Input.pointer }
                    BackHandler { close(shown); true }
                    Spacer(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onPress = { close(shown) }) })
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val bounds = constraints
                        MenuCard(
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
                            // At the pointer, moved in to stay whole inside the window.
                            modifier = Modifier.layout { measurable, c ->
                                val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
                                val x = at.x.roundToInt().coerceAtMost(bounds.maxWidth - p.width).coerceAtLeast(0)
                                val y = at.y.roundToInt().coerceAtMost(bounds.maxHeight - p.height).coerceAtLeast(0)
                                layout(c.maxWidth, c.maxHeight) { p.place(IntOffset(x, y)) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** What the item is, how much of it is watched, and the actions. [saving]: the action being sent (true = watched). */
@Composable
private fun MenuCard(target: WatchTarget, saving: Boolean?, error: String?, onPick: (Boolean) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(350.dp)
            .background(PanelColors.Background, shape)
            .border(0.5.dp, PanelColors.Edge, shape)
            // Clicks inside stay inside (the layer behind closes the menu).
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            BasicText(target.kindLabel(), style = AppType.MonoLabel.copy(color = AppColors.Green, letterSpacing = 0.1.em))
            BasicText(target.title, Modifier.padding(top = 6.dp), style = AppType.Section.copy(fontSize = 15.sp, lineHeight = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            target.subtitle?.let { BasicText(it, Modifier.padding(top = 4.dp), style = AppType.MonoMeta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            target.status()?.let {
                BasicText(it, Modifier.padding(top = 7.dp), style = AppType.Body.copy(color = if (target.played) AppColors.Green else AppColors.TextMuted))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (action in target.actions()) ActionRow(action, saving = saving == action.played, onClick = { onPick(action.played) })
        }
        error?.let { BasicText("Не удалось сохранить: $it", style = AppType.Body.copy(color = AppColors.Red), maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

private val ActionFocus = FocusStyle(scale = 1f, corner = 7.dp, ring = 0.5.dp, glows = listOf(FocusStyle.Glow(15.dp, 0.dp, Color(0x737C5CFF))), animate = false)

/** "✓ Отметить просмотренным" with what it touches under it. */
@Composable
private fun ActionRow(action: MenuAction, saving: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    val tint = if (action.played) AppColors.Green else AppColors.TextSecondary
    Row(
        Modifier
            .hoverFocus()
            .focusCard(ActionFocus)
            .fillMaxWidth()
            .height(if (action.detail != null || saving) 48.dp else 38.dp)
            .background(if (focused) Color(0xFF1F2238) else AppColors.SurfaceRaised, shape)
            .border(0.5.dp, if (focused) AppColors.Accent else Color(0xFF232637), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(action.icon, tint, Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(action.label, style = AppType.BodyLarge.copy(fontWeight = FontWeight.Medium, color = AppColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            (if (saving) "Сохраняем…" else action.detail)?.let {
                BasicText(it, Modifier.padding(top = 4.dp), style = AppType.MonoCaption.copy(fontSize = 9.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

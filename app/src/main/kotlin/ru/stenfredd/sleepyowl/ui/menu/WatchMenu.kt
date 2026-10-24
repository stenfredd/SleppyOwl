package ru.stenfredd.sleepyowl.ui.menu

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.stenfredd.sleepyowl.core.data.WatchRepository
import ru.stenfredd.sleepyowl.core.data.model.ItemKind
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.model.plural
import ru.stenfredd.sleepyowl.ui.player.SidePanel
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** What the watched menu marks: a movie, an episode, or every episode of a season or a series. */
@Immutable
data class WatchTarget(
    val id: UUID,
    val kind: ItemKind,
    /** The movie or series name. */
    val title: String,
    /** Which part of the series: "S1 · E4 · Пилот", "Сезон 2"; the year of a movie. */
    val subtitle: String? = null,
    /** Watched; of a season or series — every episode. */
    val played: Boolean,
    /** A started movie or episode, 0..1. */
    val progress: Float? = null,
    /** Episodes of a season or series and how many are watched: known on the series page. */
    val episodes: Int? = null,
    val watchedEpisodes: Int? = null,
    /** Some episode of a season or series is started (has a place to continue from). */
    val started: Boolean = false,
    /** Unwatched episodes of a season or series, from a card (the server doesn't tell watched ones there). */
    val unplayed: Int? = null,
) {
    val isGroup: Boolean get() = kind == ItemKind.Series || kind == ItemKind.Season
}

/** Opens the watched menu; provided by [WatchMenuHost] above every screen. */
@Stable
class WatchMenu internal constructor() {
    internal var shown: Shown? by mutableStateOf(null)

    /** Shows the menu for [target]; focus goes back to [returnTo] when it closes. */
    fun open(target: WatchTarget, returnTo: FocusRequester) {
        shown = Shown(target, returnTo)
    }

    /** One opening of the menu (a class: a reply that comes late must not close the next one). */
    internal class Shown(val target: WatchTarget, val returnTo: FocusRequester)
}

val LocalWatchMenu = staticCompositionLocalOf<WatchMenu?> { null }

/**
 * Click of a card or chip that also opens the watched menu for [target]: OK held down (a long press), or the remote's
 * Menu key where it has one. Without a target it's a plain click. Put it where `clickable` would go.
 */
@Composable
fun Modifier.clickableWithMenu(target: WatchTarget?, onClick: () -> Unit): Modifier {
    val menu = LocalWatchMenu.current
    if (target == null || menu == null) return clickable(interactionSource = null, indication = null, onClick = onClick)
    val self = remember { FocusRequester() }
    val open = remember(menu, target) { { menu.open(target, self) } }
    return this
        .focusRequester(self)
        .onKeyEvent { e ->
            (e.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_MENU).also { if (it && e.type == KeyEventType.KeyUp) open() }
        }
        .combinedClickable(interactionSource = null, indication = null, onLongClick = open, onClick = onClick)
}

@HiltViewModel
class WatchMenuViewModel @Inject constructor(private val watch: WatchRepository) : ViewModel() {
    /** Screens reload on their own when it's done (the cache tells them the user data changed). */
    suspend fun setPlayed(id: UUID, played: Boolean) = watch.setPlayed(id, played)
}

/**
 * Hosts the watched menu over [content]: a panel on the right, opaque (a full-screen scrim is expensive on weak TV
 * GPUs), as the player's settings panels. Marking a season or series marks every episode on the server.
 */
@Composable
fun WatchMenuHost(content: @Composable () -> Unit) {
    val menu = remember { WatchMenu() }
    val vm: WatchMenuViewModel = hiltViewModel()
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
                                        Timber.w(e, "Watched menu: marking failed")
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

private class MenuAction(val played: Boolean, val icon: AppIcon, val label: String, val detail: String?)

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

private fun WatchTarget.kindLabel(): String = when (kind) {
    ItemKind.Episode -> "СЕРИЯ"
    ItemKind.Season -> "СЕЗОН"
    ItemKind.Series -> "СЕРИАЛ"
    else -> "ФИЛЬМ"
}

private fun episodesWord(n: Int) = plural(n, "серия", "серии", "серий")

/** "Просмотрено 12 из 62 серий", "Просмотрено 42%", "Не просмотрен". */
private fun WatchTarget.status(): String? {
    val feminine = kind == ItemKind.Episode
    if (!isGroup) {
        return when {
            played -> if (feminine) "Просмотрена" else "Просмотрен"
            progress != null -> "Просмотрено ${(progress * 100).roundToInt()}%"
            else -> if (feminine) "Не просмотрена" else "Не просмотрен"
        }
    }
    return when {
        played -> "Все серии просмотрены"
        episodes != null && watchedEpisodes != null -> when (watchedEpisodes) {
            0 -> "Ни одной серии не просмотрено"
            else -> "Просмотрено $watchedEpisodes из $episodes ${plural(episodes, "серии", "серий", "серий")}"
        }
        unplayed != null && unplayed > 0 -> "Не просмотрено: $unplayed ${episodesWord(unplayed)}"
        else -> null
    }
}

/**
 * Only what changes something: "watched" unless it already is, "unwatched" if anything is watched or started.
 * A card doesn't tell whether some episodes of a series are watched, so it offers both.
 */
private fun WatchTarget.actions(): List<MenuAction> = buildList {
    if (!isGroup) {
        val ending = if (kind == ItemKind.Episode) "ой" else "ым"
        if (!played) add(MenuAction(true, AppIcon.Check, "Отметить просмотренн$ending", null))
        if (played || progress != null) {
            add(MenuAction(false, AppIcon.Replay, "Отметить непросмотренн$ending", if (progress != null) "Место остановки сбросится" else null))
        }
        return@buildList
    }
    val noun = if (kind == ItemKind.Season) "сезон" else "сериал"
    if (!played) {
        add(MenuAction(true, AppIcon.Check, "Отметить весь $noun просмотренным", episodes?.let { "$it ${episodesWord(it)}" } ?: "Все серии"))
    }
    if (played || started || watchedEpisodes == null || watchedEpisodes > 0) {
        add(MenuAction(false, AppIcon.Replay, "Отметить весь $noun непросмотренным", "Отметки и места остановки всех серий сбросятся"))
    }
}

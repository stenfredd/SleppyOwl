package ru.stenfredd.sleepyowl.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.ViewModel
import ru.stenfredd.sleepyowl.core.data.WatchRepository
import ru.stenfredd.sleepyowl.core.data.model.ItemKind
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.model.plural
import java.util.UUID
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
class WatchMenu {
    /** The opening being shown, or null; the host clears it when the menu closes. */
    var shown: Shown? by mutableStateOf(null)

    /** Shows the menu for [target]; focus goes back to [returnTo] when it closes. */
    fun open(target: WatchTarget, returnTo: FocusRequester) {
        shown = Shown(target, returnTo)
    }

    /** One opening of the menu (a class: a reply that comes late must not close the next one). */
    class Shown(val target: WatchTarget, val returnTo: FocusRequester)
}

val LocalWatchMenu = staticCompositionLocalOf<WatchMenu?> { null }

/**
 * Click of a card or chip that also opens the watched menu for [target]: OK held down (a long press), the remote's
 * Menu key where it has one, or the right mouse button on the desktop. Without a target it's a plain click. Put it
 * where `clickable` would go.
 */
@Composable
fun Modifier.clickableWithMenu(target: WatchTarget?, onClick: () -> Unit): Modifier {
    val menu = LocalWatchMenu.current
    if (target == null || menu == null) return clickable(interactionSource = null, indication = null, onClick = onClick)
    val self = remember { FocusRequester() }
    val open = remember(menu, target) { { menu.open(target, self) } }
    return this
        .focusRequester(self)
        .secondaryClick(open)
        .onKeyEvent { e ->
            (e.key == Key.Menu).also { if (it && e.type == KeyEventType.KeyUp) open() }
        }
        .combinedClickable(interactionSource = null, indication = null, onLongClick = open, onClick = onClick)
}

/** The right mouse button runs [onClick]; nothing on the TV. */
internal expect fun Modifier.secondaryClick(onClick: () -> Unit): Modifier

class WatchMenuViewModel(private val watch: WatchRepository) : ViewModel() {
    /** Screens reload on their own when it's done (the cache tells them the user data changed). */
    suspend fun setPlayed(id: UUID, played: Boolean) = watch.setPlayed(id, played)
}

/** One action of the watched menu: mark watched ([played]) or unwatched, and what it touches ([detail]). */
class MenuAction(val played: Boolean, val icon: AppIcon, val label: String, val detail: String?)

/** "СЕРИЯ", "СЕЗОН", "СЕРИАЛ", "ФИЛЬМ": the menu's title. */
fun WatchTarget.kindLabel(): String = when (kind) {
    ItemKind.Episode -> "СЕРИЯ"
    ItemKind.Season -> "СЕЗОН"
    ItemKind.Series -> "СЕРИАЛ"
    else -> "ФИЛЬМ"
}

private fun episodesWord(n: Int) = plural(n, "серия", "серии", "серий")

/** "Просмотрено 12 из 62 серий", "Просмотрено 42%", "Не просмотрен". */
fun WatchTarget.status(): String? {
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
fun WatchTarget.actions(): List<MenuAction> = buildList {
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

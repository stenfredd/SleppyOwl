package ru.stenfredd.sleepyowl.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import ru.stenfredd.sleepyowl.ui.Screen

/**
 * The screen stack of the main window. Each entry owns its view models (cleared when the entry leaves the stack) and
 * its saved UI state (scroll positions), as the TV's NavDisplay entries do; only the top entry is composed.
 */
class NavStack(first: Screen) {
    class Entry internal constructor(val screen: Screen, internal val id: Long) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private var nextId = 0L
    private val removed = mutableListOf<Entry>()
    val entries = mutableStateListOf(Entry(first, nextId++))

    val top: Screen get() = entries.last().screen
    val size: Int get() = entries.size

    fun push(screen: Screen) {
        entries += Entry(screen, nextId++)
    }

    /** False on the last screen: nothing to go back to. */
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        drop(entries.removeAt(entries.lastIndex))
        return true
    }

    fun resetTo(screen: Screen) {
        val old = entries.toList()
        entries.clear()
        entries += Entry(screen, nextId++)
        old.forEach(::drop)
    }

    private fun drop(entry: Entry) {
        entry.viewModelStore.clear()
        removed += entry
    }

    @Composable
    fun Content(content: @Composable (Screen) -> Unit) {
        val holder = rememberSaveableStateHolder()
        val entry = entries.last()
        // State of entries that left the stack is no longer needed (their view models are already cleared).
        if (removed.isNotEmpty()) {
            removed.forEach { holder.removeState(it.id) }
            removed.clear()
        }
        key(entry.id) {
            holder.SaveableStateProvider(entry.id) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) { content(entry.screen) }
            }
        }
    }
}

/**
 * "Назад" (Esc, Alt+←, Backspace, the mouse's back button) goes to the innermost enabled [BackHandler] first — a step
 * of the login flow, an open panel — and pops the screen stack only when none takes it.
 */
class BackDispatcher {
    private val handlers = mutableListOf<() -> Boolean>()

    fun add(handler: () -> Boolean): () -> Unit {
        handlers += handler
        return { handlers -= handler }
    }

    /** True when a handler took it. */
    fun dispatch(): Boolean = handlers.asReversed().any { it() }
}

val LocalBackDispatcher = compositionLocalOf { BackDispatcher() }

/** [onBack] returns false to let "Назад" go further (to the screen stack). */
@Composable
fun BackHandler(onBack: () -> Boolean) {
    val dispatcher = LocalBackDispatcher.current
    val current = rememberUpdatedState(onBack)
    DisposableEffect(dispatcher) {
        val remove = dispatcher.add { current.value() }
        onDispose(remove)
    }
}

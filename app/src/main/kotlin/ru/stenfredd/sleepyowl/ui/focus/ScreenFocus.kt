package ru.stenfredd.sleepyowl.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Remembers which element of a screen had focus and puts focus back there when the user returns
 * (Back from a details page): navigation disposes the screen, its state holder keeps [lastKey]
 * and the lazy lists' scroll positions, and [Restore] focuses the same card again.
 *
 * Every focusable element of the screen gets a stable [key] via [Modifier.remembered].
 * Only the element whose key matches gets a [FocusRequester], so the cost per card is one focus callback.
 */
@Stable
class ScreenFocus(initialKey: String?) {
    var lastKey: String? by mutableStateOf(initialKey)
        internal set
    internal val requester = FocusRequester()

    /** Focuses the element with [lastKey] (after it has been composed with that key). */
    fun requestFocus() {
        runCatching { requester.requestFocus() }
    }
}

private val ScreenFocusSaver = Saver<ScreenFocus, String>(
    save = { it.lastKey.orEmpty() },
    restore = { ScreenFocus(it.ifEmpty { null }) },
)

@Composable
fun rememberScreenFocus(): ScreenFocus {
    return rememberSaveable(saver = ScreenFocusSaver) { ScreenFocus(null) }
}

/**
 * Marks a focusable element with [key]. [isDefault] marks the element to focus on the first visit.
 */
fun Modifier.remembered(focus: ScreenFocus, key: String, isDefault: Boolean = false): Modifier {
    val target = focus.lastKey ?: if (isDefault) key else null
    return this
        .then(if (target == key) Modifier.focusRequester(focus.requester) else Modifier)
        .onFocusChanged { if (it.isFocused) focus.lastKey = key }
}

/**
 * Focuses the remembered (or default) element once [ready] becomes true, after the content is laid out.
 */
@Composable
fun ScreenFocus.Restore(ready: Boolean) {
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        // Two frames: the content may still recompose once (see [ForgetMissing]) before the requester is in place.
        withFrameNanos { }
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/**
 * Forgets the remembered element when it is no longer among [keys] (an item left the list while the screen was in
 * the back stack, e.g. removed from favourites), so the default element gets focus instead of none.
 */
@Composable
fun ScreenFocus.ForgetMissing(keys: Set<String>) {
    SideEffect { if (lastKey != null && lastKey !in keys) lastKey = null }
}

package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.ui.focus.Restore
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * The pointer moves focus: the element under it gets the TV's focus look (ring, glow, light fill) and keyboard
 * arrows go on from there; the pointer leaving it takes the focus away. Put it before the element's `focusCard` and
 * `clickable` (or pass it as the modifier of a shared button or card). A text field being typed in keeps its focus:
 * the pointer passing over a card must not take the typing away.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.hoverFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val focused = remember { booleanArrayOf(false) }
    this
        .onPointerEvent(PointerEventType.Enter) { if (!typing) runCatching { requester.requestFocus() } }
        .onPointerEvent(PointerEventType.Exit) { if (focused[0] && !typing && !Input.keyboard) focusManager.clearFocus() }
        .focusRequester(requester)
        .onFocusChanged { focused[0] = it.hasFocus }
}

/**
 * What the window last got: keys or the pointer. Focus moved by keys scrolls its element into view; focus given by the
 * pointer doesn't (pointing at the half-hidden card at a row's edge must not jerk the row).
 */
object Input {
    var keyboard = false
        internal set

    /** Where the pointer last was in the window: the watched menu opens there. */
    var pointer = Offset.Zero
        internal set
}

/** Scrolls a focused element into view, with a margin for its glow, only when keys moved the focus ([Input]). */
@OptIn(ExperimentalFoundationApi::class)
internal val KeyboardBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (!Input.keyboard) return 0f
        val start = offset - BRING_MARGIN
        val end = offset + size + BRING_MARGIN
        return when {
            start < 0f -> start
            end > containerSize -> (end - containerSize).coerceAtMost(start)
            else -> 0f
        }
    }
}

private const val BRING_MARGIN = 40f

/** Keeps [Input] up to date with the pointer; on the window's root. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trackPointer(): Modifier = this
    .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) {
        Input.keyboard = false
        Input.pointer = it.changes.first().position
    }
    .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { Input.pointer = it.changes.first().position }

/**
 * The screen the arrow keys enter when nothing has focus: the window starts without a focused card (a mouse user
 * needs none), the first arrow focuses the screen's remembered or default element ([ScreenFocus.Entry]).
 */
class KeyboardEntry {
    internal var screen: ScreenFocus? = null

    /** True when there was a screen to enter. */
    fun enter(): Boolean = screen?.let { it.requestFocus(); true } ?: false
}

val LocalKeyboardEntry = staticCompositionLocalOf { KeyboardEntry() }

/**
 * The desktop's [Restore]: back on a screen (its remembered element known) focus goes back to that element, as on the
 * TV; on a first visit nothing is focused until an arrow key comes ([KeyboardEntry]).
 */
@Composable
fun ScreenFocus.Entry(ready: Boolean) {
    val entry = LocalKeyboardEntry.current
    DisposableEffect(this, entry) {
        entry.screen = this@Entry
        onDispose { if (entry.screen === this@Entry) entry.screen = null }
    }
    val returning = remember { lastKey != null }
    if (returning) Restore(ready)
}

/** A [TextField] has focus. */
internal var typing = false
    private set

/** One-line text field: dark box, violet border while typing; Enter runs [onSubmit]. */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = AppType.BodyLarge.copy(fontSize = 14.sp, color = AppColors.Text),
    password: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSubmit: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value,
        onValueChange,
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused != focused) typing = it.isFocused
                focused = it.isFocused
            }
            // Enter submits here: a single-line field on the desktop does not turn it into an IME action.
            .onPreviewKeyEvent {
                val enter = it.key == Key.Enter || it.key == Key.NumPadEnter
                if (enter && it.type == KeyEventType.KeyDown) onSubmit()
                enter
            },
        textStyle = textStyle,
        singleLine = true,
        cursorBrush = SolidColor(AppColors.Accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(AppColors.BackgroundDeep, shape)
                    .border(if (focused) 1.dp else 0.5.dp, if (focused) AppColors.SelectedBorder else AppColors.Border, shape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) BasicText(placeholder, style = textStyle.copy(color = AppColors.TextFaint))
                inner()
            }
        },
    )
}

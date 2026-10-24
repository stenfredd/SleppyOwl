package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.stenfredd.sleepyowl.ui.theme.AppColors

/**
 * Remote-friendly text field state. D-pad focus lands on the field's frame only; OK (or [edit]) puts the cursor in
 * and opens the on-screen keyboard. The keyboard's action button, Back, or Up/Down leave editing, so the remote can
 * always move on (a focused text field otherwise swallows the D-pad, and a keyboard popping up on mere focus is
 * annoying when passing through a form).
 */
@Stable
class TextInputState {
    internal var editing by mutableStateOf(false)
    internal val frame = FocusRequester()
    internal val field = FocusRequester()

    /** Focuses the field and starts typing. */
    fun edit() {
        editing = true
    }

    /** Focuses the field's frame without opening the keyboard. */
    fun focus() {
        runCatching { frame.requestFocus() }
    }
}

@Composable
fun rememberTextInputState() = remember { TextInputState() }

/**
 * How far a screen must move up so that the field being edited stays above the on-screen keyboard.
 *
 * Gboard for TV floats over the bottom ~45 % of the screen and reports no insets (its content insets start at the
 * screen bottom), so the system neither resizes nor pans the window. The keyboard top is taken from the IME insets
 * when a keyboard reports them, else assumed at [FLOATING_KEYBOARD_TOP] of the screen height.
 * The screen applies [offset] as a layer translation ([Modifier.liftForKeyboard]); fields report themselves.
 */
@Stable
class KeyboardLift {
    var offset by mutableFloatStateOf(0f)
        internal set
}

val LocalKeyboardLift = staticCompositionLocalOf<KeyboardLift?> { null }

fun Modifier.liftForKeyboard(lift: KeyboardLift) = graphicsLayer { translationY = -lift.offset }

private const val FLOATING_KEYBOARD_TOP = 0.54f

@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    state: TextInputState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    /** The keyboard's action button: next field, submit … */
    onAction: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lift = LocalKeyboardLift.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var focused by remember { mutableStateOf(false) }
    var swallowBackUp by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    fun stopEditing() {
        keyboard?.hide()
        state.editing = false
        state.focus()
    }

    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    if (lift != null) DisposableEffect(state.editing, imeBottom) {
        val editing = state.editing
        val c = coordinates
        if (editing && c != null && c.isAttached) {
            val screenHeight = c.findRootCoordinates().size.height
            val keyboardTop = if (imeBottom > 0) screenHeight - imeBottom else (screenHeight * FLOATING_KEYBOARD_TOP).toInt()
            // Position without the current lift, so that applying it doesn't feed back.
            val bottom = c.positionInRoot().y + lift.offset + c.size.height
            lift.offset = (bottom + with(density) { 12.dp.toPx() } - keyboardTop).coerceAtLeast(0f)
        }
        onDispose { if (editing) lift.offset = 0f }
    }

    LaunchedEffect(state.editing) {
        if (state.editing) {
            runCatching { state.field.requestFocus() }
            keyboard?.show()
        }
    }

    Box(
        modifier
            .onGloballyPositioned { coordinates = it }
            .focusRequester(state.frame)
            .focusCard(InputFocus)
            .height(42.dp)
            .background(Color(0xFF0D0F16), shape)
            .border(0.5.dp, if (focused) AppColors.Accent else Color(0xFF2C3044), shape)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(interactionSource = null, indication = null) { state.edit() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) BasicText(placeholder, style = textStyle.copy(color = AppColors.TextFaint))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(AppColors.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onAny = {
                stopEditing()
                onAction()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(state.field)
                .focusProperties { canFocus = state.editing }
                .onFocusChanged { if (!it.isFocused && state.editing) state.editing = false }
                .onPreviewKeyEvent { e ->
                    when {
                        e.key == Key.Back && e.type == KeyEventType.KeyUp && swallowBackUp -> {
                            swallowBackUp = false
                            true
                        }
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.Back -> {
                            swallowBackUp = true
                            stopEditing()
                            true
                        }
                        e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {
                            stopEditing()
                            focusManager.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}

private val InputFocus = FocusStyle(
    scale = 1f,
    corner = 8.dp,
    ring = 1.5.dp,
    ringColor = Color(0x59A594FF),
    glows = listOf(FocusStyle.Glow(15.dp, 0.dp, Color(0x597C5CFF))),
)

package ru.stenfredd.sleepyowl.ui.menu

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) onClick() }

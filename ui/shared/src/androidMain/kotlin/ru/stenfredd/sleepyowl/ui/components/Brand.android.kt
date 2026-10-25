package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import ru.stenfredd.sleepyowl.ui.shared.R

@Composable
internal actual fun owlPainter() = painterResource(R.drawable.logo_owl)

@Composable
internal actual fun wordmarkPainter() = painterResource(R.drawable.logo_wordmark)

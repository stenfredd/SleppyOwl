package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

@Composable
internal actual fun owlPainter(): Painter = remember { BitmapPainter(classpathImage("drawable-xhdpi/logo_owl.png")) }

@Composable
internal actual fun wordmarkPainter(): Painter = remember { BitmapPainter(classpathImage("drawable-xhdpi/logo_wordmark.png")) }

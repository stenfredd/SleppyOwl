package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's logo: the owl and the "SleepyOwl" wordmark from `design/logo/sleepyowl.png`, cut out of its navy
 * background by `tools/logo/make_assets.py`. The pictures are made at the default sizes here (xhdpi), so on the TV
 * they are drawn pixel for pixel; other sizes resample them.
 */
@Composable
fun OwlMark(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Image(owlPainter(), null, modifier.size(size))
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, height: Dp = 18.dp) {
    val painter = wordmarkPainter()
    Image(
        painter,
        "SleepyOwl",
        modifier.height(height).aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height),
    )
}

/** `drawable-xhdpi/logo_owl.png` of ui:shared: a resource on the TV, from the classpath on the desktop. */
@Composable
internal expect fun owlPainter(): Painter

/** `drawable-xhdpi/logo_wordmark.png`. */
@Composable
internal expect fun wordmarkPainter(): Painter

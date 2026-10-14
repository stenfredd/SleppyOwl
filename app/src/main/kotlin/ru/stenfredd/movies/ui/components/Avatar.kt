package ru.stenfredd.movies.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * User avatar from the mockup: a gradient circle with the first letter of the name,
 * `linear-gradient(145deg, oklch(62% .13 h), oklch(40% .12 h+40))`; the hue comes from [seed] (the user id),
 * so a user keeps the same colour everywhere.
 */
@Composable
fun Avatar(name: String, seed: String, size: Dp, textStyle: TextStyle, modifier: Modifier = Modifier, letters: String = name.trim().take(1).uppercase()) {
    val colors = remember(seed) { avatarColors(seed) }
    Box(
        modifier
            .size(size)
            .drawWithCache {
                val brush = Brush.linearGradient(colors, start = Offset(this.size.width * 0.2f, 0f), end = Offset(this.size.width * 0.8f, this.size.height))
                onDrawBehind { drawCircle(brush) }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(letters, style = textStyle)
    }
}

fun avatarColors(seed: String): List<Color> {
    val hue = Math.floorMod(seed.hashCode(), 360).toDouble()
    return listOf(oklch(0.62, 0.13, hue), oklch(0.40, 0.12, hue + 40))
}

/** CSS `oklch()` → sRGB. */
private fun oklch(l: Double, c: Double, hueDeg: Double): Color {
    val h = hueDeg * PI / 180
    val a = c * cos(h)
    val b = c * sin(h)
    val l1 = cube(l + 0.3963377774 * a + 0.2158037573 * b)
    val m1 = cube(l - 0.1055613458 * a - 0.0638541728 * b)
    val s1 = cube(l - 0.0894841775 * a - 1.2914855480 * b)
    val r = 4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1
    val g = -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1
    val bl = -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1
    return Color(gamma(r), gamma(g), gamma(bl))
}

private fun cube(x: Double) = x * x * x

private fun gamma(x: Double): Float {
    val v = x.coerceIn(0.0, 1.0)
    return (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).toFloat()
}


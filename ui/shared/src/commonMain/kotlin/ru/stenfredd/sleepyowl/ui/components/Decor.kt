package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

/**
 * Dashed rounded frame (`border: 2px dashed`), rasterised once per size into a bitmap: a dashed path is
 * re-tessellated every frame by the GPU, and the test TV redraws the whole screen on every frame.
 */
fun Modifier.dashedBorder(color: Color, width: Dp, corner: Dp, dash: Dp = 6.dp, gap: Dp = 4.dp): Modifier = drawWithCache {
    val w = ceil(size.width).toInt().coerceAtLeast(1)
    val h = ceil(size.height).toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    val stroke = width.toPx()
    val paint = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Stroke
        strokeWidth = stroke
        this.color = color
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()), 0f)
    }
    val r = corner.toPx()
    Canvas(bitmap).drawRoundRect(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2, r, r, paint)
    onDrawBehind { drawImage(bitmap) }
}

/**
 * Status dot; [pulsing] fades it in and out while something is in progress. The fade is stepped at ~8 frames
 * a second: the test TV redraws the whole screen for any change (~17 ms of GPU on the login screen), so a smooth
 * 60 fps pulse would keep its GPU busy for nothing.
 */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    val alpha = remember { mutableFloatStateOf(1f) }
    if (pulsing) {
        LaunchedEffect(Unit) {
            try {
                var t = 0
                while (true) {
                    alpha.floatValue = 0.625f + 0.375f * cos(t * PI / 6).toFloat()
                    t++
                    delay(PULSE_STEP_MS)
                }
            } finally {
                alpha.floatValue = 1f
            }
        }
    }
    Spacer(modifier.size(size).drawBehind { drawCircle(color, alpha = alpha.floatValue) })
}

private const val PULSE_STEP_MS = 120L

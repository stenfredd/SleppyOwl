package ru.stenfredd.movies.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil

private const val HEART = "M12 20s-7.5-4.6-7.5-10A4.2 4.2 0 0 1 12 7.6 4.2 4.2 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"

/**
 * Icons from the mockup's inline SVGs (24×24 viewBox). Paths are rasterised into a small bitmap once per
 * size and colour, then drawn as an image: weak TV GPUs re-rasterise antialiased paths every frame.
 */
enum class AppIcon(val pathData: String, val stroked: Boolean = true) {
    Search("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-3.5-3.5"),
    Home("M4 11l8-7 8 7v9h-5v-6H9v6H4z"),
    Library("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3 9h18M8 5v4M16 5v4"),
    Heart(HEART),
    HeartFilled(HEART, stroked = false),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Plus("M12 5v14M5 12h14"),
    Play("M7 4.5v15l13-7.5z", stroked = false),
    ;

    internal val path: Path by lazy { PathParser().parsePathString(pathData).toPath() }
}

@Composable
fun Icon(icon: AppIcon, color: Color, modifier: Modifier = Modifier, strokeWidth: Float = 2f) {
    Spacer(
        modifier.drawWithCache {
            val bitmapSize = IntSize(ceil(size.width).toInt().coerceAtLeast(1), ceil(size.height).toInt().coerceAtLeast(1))
            val bitmap = ImageBitmap(bitmapSize.width, bitmapSize.height)
            val k = size.minDimension / 24f
            val style = if (icon.stroked) Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), bitmapSize.toSize()) {
                scale(k, k, pivot = Offset.Zero) { drawPath(icon.path, color, style = style) }
            }
            onDrawBehind { drawImage(bitmap) }
        },
    )
}

@Composable
fun PlayIcon(modifier: Modifier = Modifier, color: Color = Color.White) = Icon(AppIcon.Play, color, modifier)

@Composable
fun CheckIcon(modifier: Modifier = Modifier, color: Color) = Icon(AppIcon.Check, color, modifier, strokeWidth = 3f)

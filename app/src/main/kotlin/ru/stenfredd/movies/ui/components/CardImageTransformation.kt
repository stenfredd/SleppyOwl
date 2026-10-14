package ru.stenfredd.movies.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil3.size.Dimension
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Bottom shade under text over an image: [stops] go from the bottom edge (0) to the top of the shade (1),
 * which covers the bottom [height] fraction of the image.
 */
@Immutable
data class ImageShade(val height: Float, val stops: List<Pair<Float, Color>>) {
    val cacheKey = "$height:" + stops.joinToString(",") { "${it.first}=${it.second.toArgb()}" }
}

/**
 * Rounded corners and the text shade baked into the bitmap once, on Coil's decode thread.
 * The TV this was measured on redraws the whole screen every frame (no dirty-region rendering), and every
 * translucent layer over an image costs GPU time on every frame; baked, the card is a single bitmap draw.
 */
class CardImageTransformation(private val radiusPx: Float, private val shade: ImageShade?) : Transformation() {
    override val cacheKey = "card:$radiusPx:${shade?.cacheKey}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Crop to the card's own size first: when the server image has other proportions (posters aren't all 2:3),
        // cropping it later at draw time would cut the baked corners off.
        val w = (size.width as? Dimension.Pixels)?.px ?: input.width
        val h = (size.height as? Dimension.Pixels)?.px ?: input.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scale = maxOf(w.toFloat() / input.width, h.toFloat() / input.height)
        paint.shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((w - input.width * scale) / 2f, (h - input.height * scale) / 2f)
                },
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radiusPx, radiusPx, paint)
        if (shade != null) {
            val top = h * (1f - shade.height)
            val gradient = Paint()
            gradient.shader = LinearGradient(
                0f, h.toFloat(), 0f, top,
                shade.stops.map { it.second.toArgb() }.toIntArray(),
                shade.stops.map { it.first }.toFloatArray(),
                Shader.TileMode.CLAMP,
            )
            // Only over the image's own pixels, so the rounded corners stay transparent.
            gradient.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            canvas.drawRect(0f, top, w.toFloat(), h.toFloat(), gradient)
        }
        return output
    }
}

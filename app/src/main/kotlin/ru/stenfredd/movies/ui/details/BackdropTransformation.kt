package ru.stenfredd.movies.ui.details

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import coil3.size.Dimension
import coil3.size.Size
import coil3.transform.Transformation
import ru.stenfredd.movies.ui.theme.AppColors

/**
 * The item page backdrop as one opaque bitmap: the image dimmed to 70 % over the app background and the mockup's
 * darkening gradient, baked in once on Coil's decode thread, so on screen it is a single opaque image draw.
 *
 * It covers only [area] (dp) of the [pageHeight] dp tall page, not the whole screen: on the test TV every frame
 * redraws the whole screen and a full-screen image costs ~10 ms of GPU per frame. Towards the left and bottom
 * edges of [area] the image fades into the background, so it has no visible edge.
 */
class BackdropTransformation(private val area: RectF, private val pageHeight: Float) : Transformation() {
    override val cacheKey = "backdrop:$area:$pageHeight"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = (size.width as? Dimension.Pixels)?.px ?: input.width
        val h = (size.height as? Dimension.Pixels)?.px ?: input.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bg = AppColors.Background
        canvas.drawColor(bg.toArgb())

        // Image, cropped to fill, at 70 % opacity.
        val image = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (0.7f * 255).toInt() }
        val scale = maxOf(w.toFloat() / input.width, h.toFloat() / input.height)
        canvas.drawBitmap(
            input,
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - input.width * scale) / 2f, (h - input.height * scale) / 2f)
            },
            image,
        )

        val k = w / area.width()
        // The mockup's full-page `linear-gradient(0deg, #0a0b10 0%, rgba(10,11,16,.75) 50%, rgba(10,11,16,.55) 100%)`,
        // placed in page coordinates.
        canvas.fill(w, h, LinearGradient(0f, (pageHeight - area.top) * k, 0f, -area.top * k, colors(bg.toArgb(), 1f, 0.75f, 0.55f), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP))
        // Fade into the background at the left and bottom edges.
        val fadeX = w * LEFT_FADE
        canvas.fill(w, h, LinearGradient(0f, 0f, fadeX, 0f, colors(bg.toArgb(), 1f, 0.6f, 0f), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP))
        val fadeY = h * BOTTOM_FADE
        canvas.fill(w, h, LinearGradient(0f, h.toFloat(), 0f, h - fadeY, colors(bg.toArgb(), 1f, 0f), null, Shader.TileMode.CLAMP))

        output.setHasAlpha(false)
        return output
    }

    private fun Canvas.fill(w: Int, h: Int, shader: Shader) = drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })

    private fun colors(argb: Int, vararg alphas: Float) = IntArray(alphas.size) { (argb and 0xFFFFFF) or ((alphas[it] * 255).toInt() shl 24) }

    private companion object {
        const val LEFT_FADE = 0.4f
        const val BOTTOM_FADE = 0.35f
    }
}

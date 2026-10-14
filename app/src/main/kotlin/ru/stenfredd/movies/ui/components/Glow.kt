package ru.stenfredd.movies.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * One soft glow layer, CSS `box-shadow: 0 0 <blur> <spread> <color>`, sizes in px.
 */
data class GlowLayer(val blurPx: Float, val spreadPx: Float, val color: Color)

/**
 * Pre-rendered focus glows. The mockup's glows are 30–120 px blurred shadows; blurring them every frame
 * is too slow for weak TVs, so each (size, shape, layers) combination is rendered once into a bitmap
 * at half resolution (a blur loses nothing when upscaled) and then drawn as a plain image.
 * Only the focused element draws its glow, and focusable elements of one kind share a size,
 * so the cache stays small.
 */
object GlowCache {
    private const val SCALE = 0.5f

    class Glow(val bitmap: ImageBitmap, val marginPx: Float)

    private data class Key(val w: Int, val h: Int, val corner: Int, val layers: List<GlowLayer>)

    // Bytes of all cached bitmaps; ~4 MB is a few dozen glows.
    private val cache = object : LruCache<Key, Glow>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Glow) = value.bitmap.width * value.bitmap.height * 4
    }

    fun get(widthPx: Float, heightPx: Float, cornerPx: Float, layers: List<GlowLayer>): Glow {
        val key = Key(widthPx.roundToInt(), heightPx.roundToInt(), cornerPx.roundToInt(), layers)
        return cache.get(key) ?: render(key).also { cache.put(key, it) }
    }

    private fun render(key: Key): Glow {
        // A blurred edge fades out at roughly 1× the CSS blur radius beyond the spread.
        val margin = key.layers.maxOf { it.blurPx + it.spreadPx }
        val w = ((key.w + 2 * margin) * SCALE).roundToInt().coerceAtLeast(1)
        val h = ((key.h + 2 * margin) * SCALE).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (layer in key.layers) {
            // CSS blur radius B is a Gaussian with sigma B/2; BlurMaskFilter's radius r has sigma ≈ 0.57735·r + 0.5.
            val radius = ((layer.blurPx / 2 - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            paint.color = layer.color.toArgb()
            val s = layer.spreadPx
            val rect = RectF(margin - s, margin - s, margin + key.w + s, margin + key.h + s)
            val corner = key.corner + s
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        return Glow(bitmap.asImageBitmap(), margin)
    }
}

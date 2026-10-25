package ru.stenfredd.sleepyowl.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.roundToInt

/**
 * The TV's BlurHash placeholders ([BlurHash]): decoded tiny, then upscaled to a quarter of the card with
 * the rounded corners baked in, like the real images ([CardImageTransformation]); drawn stretched to the card it is a
 * single cheap bitmap draw. Decoding runs off the main thread; results are cached by hash and size.
 */
internal object BlurHashPlaceholders {
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    /** A placeholder bitmap for a [widthPx]×[heightPx] card with [radiusPx] corners and [shade], or null for a broken hash. */
    fun placeholder(hash: String, widthPx: Int, heightPx: Int, radiusPx: Float, shade: ImageShade?): Bitmap? {
        val w = (widthPx / SCALE).coerceAtLeast(1)
        val h = (heightPx / SCALE).coerceAtLeast(1)
        val key = "$hash:$w:$h:$radiusPx:${shade?.cacheKey}"
        cache.get(key)?.let { return it }
        val smallH = (DECODE_SIZE * heightPx / widthPx.toFloat()).roundToInt().coerceAtLeast(1)
        val small = BlurHash.decode(hash, DECODE_SIZE, smallH)?.let { Bitmap.createBitmap(it, DECODE_SIZE, smallH, Bitmap.Config.ARGB_8888) } ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / small.width.toFloat(), h / small.height.toFloat()) })
        }
        val r = radiusPx / SCALE
        val canvas = Canvas(out)
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint)
        // The same shade as the real image, or the text over a bright blur would be unreadable.
        shade?.let { canvas.drawShade(it, w, h) }
        small.recycle()
        cache.put(key, out)
        return out
    }

    private const val DECODE_SIZE = 16
    /** The placeholder is a quarter of the card: it is a blur, upscaling it costs nothing visible. */
    private const val SCALE = 4
    private const val CACHE_ENTRIES = 300
}

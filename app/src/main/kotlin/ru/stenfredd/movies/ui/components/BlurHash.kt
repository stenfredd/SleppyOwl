package ru.stenfredd.movies.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.withSign

/**
 * Placeholder from the image's BlurHash (sent by the server with every item): a blurred colour sketch of the picture
 * while it loads.
 *
 * Decoded tiny (the hash holds only a few colour components anyway), then upscaled to a quarter of the card with
 * the rounded corners baked in, like the real images ([CardImageTransformation]); drawn stretched to the card it is a
 * single cheap bitmap draw. Decoding runs off the main thread; results are cached by hash and size.
 */
object BlurHash {
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    /** A placeholder bitmap for a [widthPx]×[heightPx] card with [radiusPx] corners and [shade], or null for a broken hash. */
    fun placeholder(hash: String, widthPx: Int, heightPx: Int, radiusPx: Float, shade: ImageShade?): Bitmap? {
        val w = (widthPx / SCALE).coerceAtLeast(1)
        val h = (heightPx / SCALE).coerceAtLeast(1)
        val key = "$hash:$w:$h:$radiusPx:${shade?.cacheKey}"
        cache.get(key)?.let { return it }
        val small = decode(hash, DECODE_SIZE, (DECODE_SIZE * heightPx / widthPx.toFloat()).roundToInt().coerceAtLeast(1)) ?: return null
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

    /** Standard BlurHash decoding (https://blurha.sh), punch 1. */
    fun decode(hash: String, width: Int, height: Int): Bitmap? {
        if (hash.length < 6) return null
        val sizeFlag = decode83(hash, 0, 1)
        val numX = sizeFlag % 9 + 1
        val numY = sizeFlag / 9 + 1
        if (hash.length != 4 + 2 * numX * numY) return null
        val maxAc = (decode83(hash, 1, 2) + 1) / 166f
        val colors = Array(numX * numY) { i ->
            if (i == 0) {
                val v = decode83(hash, 2, 6)
                floatArrayOf(srgbToLinear(v shr 16), srgbToLinear((v shr 8) and 255), srgbToLinear(v and 255))
            } else {
                val v = decode83(hash, 4 + i * 2, 6 + i * 2)
                floatArrayOf(signedPow2((v / (19 * 19) - 9) / 9f) * maxAc, signedPow2(((v / 19) % 19 - 9) / 9f) * maxAc, signedPow2((v % 19 - 9) / 9f) * maxAc)
            }
        }
        val cosX = FloatArray(width * numX) { cos(Math.PI * (it / numX) * (it % numX) / width).toFloat() }
        val cosY = FloatArray(height * numY) { cos(Math.PI * (it / numY) * (it % numY) / height).toFloat() }
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (j in 0 until numY) for (i in 0 until numX) {
                val basis = cosX[x * numX + i] * cosY[y * numY + j]
                val c = colors[j * numX + i]
                r += c[0] * basis
                g += c[1] * basis
                b += c[2] * basis
            }
            pixels[y * width + x] = (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private fun decode83(s: String, from: Int, to: Int): Int {
        var v = 0
        for (i in from until to) {
            val d = CHARS.indexOf(s[i])
            if (d < 0) return 0
            v = v * 83 + d
        }
        return v
    }

    private fun srgbToLinear(v: Int): Float {
        val f = v / 255f
        return if (f <= 0.04045f) f / 12.92f else ((f + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(v: Float): Int {
        val f = v.coerceIn(0f, 1f)
        return if (f <= 0.0031308f) (f * 12.92f * 255 + 0.5f).toInt() else ((1.055f * f.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
    }

    private fun signedPow2(v: Float) = (v * v).withSign(v)

    private const val DECODE_SIZE = 16
    /** The placeholder is a quarter of the card: it is a blur, upscaling it costs nothing visible. */
    private const val SCALE = 4
    private const val CACHE_ENTRIES = 300
}

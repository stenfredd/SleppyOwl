package ru.stenfredd.sleepyowl.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal actual fun cachedGlow(w: Int, h: Int, corner: Int, layers: List<GlowLayer>) = AndroidGlows.get(w, h, corner, layers)

// The TV's glows: rendered at half resolution (a blur loses nothing when upscaled), layers composited in float.
private object AndroidGlows {
    private const val SCALE = 0.5f

    private data class Key(val w: Int, val h: Int, val corner: Int, val layers: List<GlowLayer>)

    // Bytes of all cached bitmaps; ~4 MB is a few dozen glows.
    private val cache = object : LruCache<Key, GlowCache.Glow>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: GlowCache.Glow) = value.bitmap.width * value.bitmap.height * 4
    }

    fun get(w: Int, h: Int, corner: Int, layers: List<GlowLayer>): GlowCache.Glow {
        val key = Key(w, h, corner, layers)
        return cache.get(key) ?: render(key).also { cache.put(key, it) }
    }

    private fun render(key: Key): GlowCache.Glow {
        // A blurred edge fades out at roughly 1× the CSS blur radius beyond the spread.
        val margin = key.layers.maxOf { it.blurPx + it.spreadPx }
        val w = ((key.w + 2 * margin) * SCALE).roundToInt().coerceAtLeast(1)
        val h = ((key.h + 2 * margin) * SCALE).roundToInt().coerceAtLeast(1)
        // Each layer's coverage is drawn at full strength (255 levels) and the layers are composited in float:
        // a dim colour stored directly in 8 bits has only 15–60 levels per channel, so the tail shows bands
        // several px wide and R, G, B step at different places — a visible staircase with colour shifts.
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        canvas.scale(SCALE, SCALE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val stride = mask.rowBytes
        val coverage = ByteBuffer.allocate(stride * h)
        val premul = FloatArray(w * h * 4)
        for (layer in key.layers) {
            mask.eraseColor(0)
            // CSS blur radius B is a Gaussian with sigma B/2; BlurMaskFilter's radius r has sigma ≈ 0.57735·r + 0.5.
            val radius = ((layer.blurPx / 2 - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            val s = layer.spreadPx
            val rect = RectF(margin - s, margin - s, margin + key.w + s, margin + key.h + s)
            val corner = key.corner + s
            canvas.drawRoundRect(rect, corner, corner, paint)
            coverage.rewind()
            mask.copyPixelsToBuffer(coverage)
            val px = coverage.array()
            val c = layer.color
            for (y in 0 until h) for (x in 0 until w) {
                val v = px[y * stride + x].toInt() and 0xFF
                if (v == 0) continue
                val a = c.alpha * v / 255f
                val i = (y * w + x) * 4
                premul[i] = c.red * a + premul[i] * (1 - a)
                premul[i + 1] = c.green * a + premul[i + 1] * (1 - a)
                premul[i + 2] = c.blue * a + premul[i + 2] * (1 - a)
                premul[i + 3] = a + premul[i + 3] * (1 - a)
            }
        }
        // To 8 bits with ±1.5 levels of noise: invisible, but the average is smooth. White noise rather than
        // a finer pattern, since the bitmap is upscaled 2× and the filtering would average a fine pattern out.
        val out = ByteBuffer.allocate(w * h * 4)
        val bytes = out.array()
        for (i in 0 until w * h) {
            val o = i * 4
            if (premul[o + 3] <= 0f) continue
            val n = 3f * noise(i) - 1.5f
            val a = (premul[o + 3] * 255f + n).roundToInt().coerceIn(0, 255)
            for (k in 0..2) bytes[o + k] = (premul[o + k] * 255f + n).roundToInt().coerceIn(0, a).toByte()
            bytes[o + 3] = a.toByte()
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(out)
        return GlowCache.Glow(bitmap.asImageBitmap(), margin)
    }

    /** Hash-based white noise, 0..1. */
    private fun noise(i: Int): Float {
        var x = i * 0x27d4eb2d
        x = (x xor (x ushr 15)) * 0x2c1b3c6d
        x = (x xor (x ushr 12)) * 0x297a2d39
        x = x xor (x ushr 15)
        return (x ushr 8) / 16777216f
    }
}

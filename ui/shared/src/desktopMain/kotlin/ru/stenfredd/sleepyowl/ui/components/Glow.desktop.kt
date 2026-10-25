package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface
import kotlin.math.ceil

private data class GlowKey(val w: Int, val h: Int, val corner: Int, val layers: List<GlowLayer>)

// A few dozen glows at most: elements of one kind share a size.
private val glows = object : LinkedHashMap<GlowKey, GlowCache.Glow>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlowKey, GlowCache.Glow>) = size > 64
}

/** Skia's own blur at full resolution, layers drawn one over another: the desktop has GPU and memory to spare. */
internal actual fun cachedGlow(w: Int, h: Int, corner: Int, layers: List<GlowLayer>): GlowCache.Glow = synchronized(glows) {
    glows.getOrPut(GlowKey(w, h, corner, layers)) {
        // A blurred edge fades out at roughly 1× the CSS blur radius beyond the spread.
        val margin = layers.maxOf { it.blurPx + it.spreadPx }
        val side = ceil(margin).toInt()
        val surface = Surface.makeRasterN32Premul((w + 2 * side).coerceAtLeast(1), (h + 2 * side).coerceAtLeast(1))
        for (layer in layers) {
            val s = layer.spreadPx
            val paint = Paint().apply {
                isAntiAlias = true
                color = layer.color.toArgb()
                // CSS blur radius B is a Gaussian with sigma B/2.
                if (layer.blurPx > 0f) maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, layer.blurPx / 2)
            }
            surface.canvas.drawRRect(RRect.makeXYWH(side - s, side - s, w + 2 * s, h + 2 * s, corner + s), paint)
        }
        GlowCache.Glow(surface.makeImageSnapshot().toComposeImageBitmap(), side.toFloat())
    }
}

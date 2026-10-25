package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
    class Glow(val bitmap: ImageBitmap, val marginPx: Float)

    fun get(widthPx: Float, heightPx: Float, cornerPx: Float, layers: List<GlowLayer>): Glow =
        cachedGlow(widthPx.roundToInt(), heightPx.roundToInt(), cornerPx.roundToInt(), layers)
}

/**
 * The glow of a [w]×[h] px element with [corner] px corners, drawn [GlowCache.Glow.marginPx] beyond its edges:
 * rendered on the first request and cached. The TV bakes it with care for its 8-bit bands and weak GPU,
 * the desktop simply.
 */
internal expect fun cachedGlow(w: Int, h: Int, corner: Int, layers: List<GlowLayer>): GlowCache.Glow

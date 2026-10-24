package ru.stenfredd.sleepyowl.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.ui.theme.AppColors

/** A tile's background in its two looks: the selected library's mosaic is brighter. */
@Immutable
class TileArt(val selected: ImageBitmap, val normal: ImageBitmap)

/**
 * The library tile's background from the mockup — dark fill, three posters tilted on the right, a fade from the left —
 * baked into one bitmap (rounded corners included), so on screen a tile is a single image draw: on the test TV every
 * translucent layer costs GPU time on every frame. Null until the posters are loaded.
 */
@Composable
fun rememberTileArt(urls: List<String>, size: IntSize): State<TileArt?> {
    val context = LocalContext.current
    val k = LocalDensity.current.density / 2f
    return produceState(Baked.get(key(urls, size)), urls, size) {
        if (value != null || urls.isEmpty() || size == IntSize.Zero) return@produceState
        val posters = loadPosters(context, urls, (POSTER_W * k).toInt(), (POSTER_H * k).toInt())
        if (posters.isEmpty()) return@produceState
        val art = withContext(Dispatchers.Default) {
            TileArt(bake(posters, size, k, SELECTED_ALPHA).asImageBitmap(), bake(posters, size, k, NORMAL_ALPHA).asImageBitmap())
        }
        Baked.put(key(urls, size), art)
        value = art
    }
}

private val Baked = LruCache<String, TileArt>(12)

private fun key(urls: List<String>, size: IntSize) = "${size.width}x${size.height}:" + urls.joinToString("|")

private suspend fun loadPosters(context: Context, urls: List<String>, w: Int, h: Int): List<Bitmap> = coroutineScope {
    urls.map { url ->
        async {
            val request = ImageRequest.Builder(context).data(url).size(w, h).allowHardware(false).build()
            (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
        }
    }.awaitAll().filterNotNull()
}

/** Sizes in the mockup's px, [k] = device px per mockup px. */
private fun bake(posters: List<Bitmap>, size: IntSize, k: Float, alpha: Float): Bitmap {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val corner = CORNER * k
    canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), corner, corner, Path.Direction.CW) })
    canvas.drawColor(AppColors.Surface.toArgb())

    // `right:-20px; top:-20px; bottom:-20px; display:flex; gap:12px; transform:rotate(8deg)`, posters 120×180 px,
    // each next one 18 px lower.
    val pw = POSTER_W * k
    val ph = POSTER_H * k
    val gap = 12 * k
    val rowWidth = posters.size * pw + (posters.size - 1) * gap
    val left = w + 20 * k - rowWidth
    val top = -20 * k
    canvas.save()
    canvas.rotate(8f, left + rowWidth / 2, h / 2)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() }
    posters.forEachIndexed { i, poster ->
        val x = left + i * (pw + gap)
        val y = top + i * 18 * k
        val scale = maxOf(pw / poster.width, ph / poster.height)
        paint.shader = BitmapShader(poster, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(x + (pw - poster.width * scale) / 2, y + (ph - poster.height * scale) / 2)
                },
            )
        }
        canvas.drawRoundRect(RectF(x, y, x + pw, y + ph), 12 * k, 12 * k, paint)
    }
    canvas.restore()

    // `linear-gradient(90deg, #12141c 40%, rgba(18,20,28,.2))`
    val bg = AppColors.Surface.toArgb()
    val fade = Paint().apply {
        shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(bg, bg, (bg and 0xFFFFFF) or (0x33 shl 24)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, w, h, fade)
    return output
}

private const val POSTER_W = 120f
private const val POSTER_H = 180f
private const val CORNER = 22f
private const val SELECTED_ALPHA = 1f
private const val NORMAL_ALPHA = 0.55f

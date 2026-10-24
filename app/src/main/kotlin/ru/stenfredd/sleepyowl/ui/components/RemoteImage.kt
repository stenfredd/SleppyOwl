package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.ui.theme.AppColors

/** A server image: URL of exactly the displayed size and the BlurHash shown while it loads. */
@Immutable
data class RemoteImageSpec(val url: String, val blurHash: String? = null)

/**
 * Server image with rounded corners.
 *
 * Corners and the optional text [shade] are baked into the bitmap when it is decoded ([CardImageTransformation]),
 * instead of clipping and blending every frame. Until the image arrives the card shows its [BlurHash] placeholder
 * (decoded off the main thread), or a plain rounded rectangle. The image size comes from the URL, so Coil only
 * decodes. No crossfade: it would redraw every visible card for its duration.
 */
@Composable
fun RemoteImage(image: RemoteImageSpec?, corner: Dp, modifier: Modifier = Modifier, shade: ImageShade? = null, contentScale: ContentScale = ContentScale.Crop) {
    val url = image?.url
    var loaded by remember(url) { mutableStateOf(false) }
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val radius = with(LocalDensity.current) { corner.toPx() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val placeholder by produceState<ImageBitmap?>(null, image?.blurHash, boxSize, radius, shade) {
        val hash = image?.blurHash
        value = if (hash == null || boxSize == IntSize.Zero) null else withContext(Dispatchers.Default) { BlurHash.placeholder(hash, boxSize.width, boxSize.height, radius, shade)?.asImageBitmap() }
    }
    val background = when {
        loaded -> modifier
        placeholder != null -> modifier.drawBehind {
            placeholder?.let { drawImage(it, srcOffset = IntOffset.Zero, dstSize = boxSize) }
        }
        else -> modifier.background(AppColors.SurfaceRaised, shape)
    }
    Box(background.onSizeChanged { boxSize = it }) {
        if (url != null) {
            val context = LocalContext.current
            val request = remember(url, radius, shade) {
                ImageRequest.Builder(context).data(url).transformations(CardImageTransformation(radius, shade)).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = { loaded = true },
            )
        }
    }
}

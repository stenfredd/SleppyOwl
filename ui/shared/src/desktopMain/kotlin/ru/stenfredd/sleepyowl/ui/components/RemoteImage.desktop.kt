package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import kotlin.math.ceil

/**
 * The desktop's server image: clipped and shaded while drawing, which a desktop GPU doesn't notice. The BlurHash is
 * decoded small and stretched: its colour waves are defined over the whole picture, so stretching loses nothing.
 */
@Composable
actual fun RemoteImage(image: RemoteImageSpec?, corner: Dp, modifier: Modifier, shade: ImageShade?, contentScale: ContentScale) {
    val url = image?.url
    var loaded by remember(url) { mutableStateOf(false) }
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val placeholder by produceState<ImageBitmap?>(null, image?.blurHash) {
        val hash = image?.blurHash
        value = if (hash == null) null else withContext(Dispatchers.Default) {
            BlurHash.decode(hash, PLACEHOLDER_SIZE, PLACEHOLDER_SIZE)?.let { argbImageBitmap(it, PLACEHOLDER_SIZE, PLACEHOLDER_SIZE) }
        }
    }
    val background = when {
        loaded -> Modifier
        placeholder != null -> Modifier.drawBehind {
            placeholder?.let { drawImage(it, dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) }
        }
        else -> Modifier.background(AppColors.SurfaceRaised)
    }
    Box(modifier.clip(shape).then(background)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = { loaded = true },
            )
        }
        shade?.let { s ->
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val top = size.height * (1f - s.height)
                    drawRect(
                        Brush.verticalGradient(*s.stops.toTypedArray(), startY = size.height, endY = top),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height - top),
                    )
                },
            )
        }
    }
}

private const val PLACEHOLDER_SIZE = 32

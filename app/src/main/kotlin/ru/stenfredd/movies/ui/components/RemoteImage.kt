package ru.stenfredd.movies.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import ru.stenfredd.movies.ui.theme.AppColors

/**
 * Server image with rounded corners.
 *
 * Corners and the optional text [shade] are baked into the bitmap when it is decoded ([CardImageTransformation]),
 * instead of clipping and blending every frame; the rounded placeholder is drawn only until the image arrives.
 * The image size comes from the URL (the view model asks the server for exactly the displayed size),
 * so Coil only decodes. No crossfade: it would redraw every visible card for its duration.
 */
@Composable
fun RemoteImage(url: String?, corner: Dp, modifier: Modifier = Modifier, shade: ImageShade? = null, contentScale: ContentScale = ContentScale.Crop) {
    var loaded by remember(url) { mutableStateOf(false) }
    val shape = remember(corner) { RoundedCornerShape(corner) }
    Box(if (loaded) modifier else modifier.background(AppColors.SurfaceRaised, shape)) {
        if (url != null) {
            val context = LocalContext.current
            val radius = with(LocalDensity.current) { corner.toPx() }
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

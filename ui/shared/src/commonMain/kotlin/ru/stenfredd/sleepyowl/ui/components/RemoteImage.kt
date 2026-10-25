package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp

/** A server image: URL of exactly the displayed size and the BlurHash shown while it loads. */
@Immutable
data class RemoteImageSpec(val url: String, val blurHash: String? = null)

/**
 * Bottom shade under text over an image: [stops] go from the bottom edge (0) to the top of the shade (1),
 * which covers the bottom [height] fraction of the image.
 */
@Immutable
data class ImageShade(val height: Float, val stops: List<Pair<Float, Color>>) {
    val cacheKey = "$height:" + stops.joinToString(",") { "${it.first}=${it.second.toArgb()}" }
}

/**
 * Server image with rounded corners and an optional text [shade] at the bottom; while it loads, its BlurHash
 * placeholder or a plain rounded rectangle.
 */
@Composable
expect fun RemoteImage(image: RemoteImageSpec?, corner: Dp, modifier: Modifier = Modifier, shade: ImageShade? = null, contentScale: ContentScale = ContentScale.Crop)

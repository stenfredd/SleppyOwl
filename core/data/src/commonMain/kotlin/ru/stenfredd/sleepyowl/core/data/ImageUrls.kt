package ru.stenfredd.sleepyowl.core.data

import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import ru.stenfredd.sleepyowl.core.data.model.ImageKind
import ru.stenfredd.sleepyowl.core.data.model.ImageRef
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession

/**
 * Image URLs of exactly the displayed size, so the TV only decodes (no scaling, no oversized bitmaps).
 *
 * URLs are built on the server's public address: that is the image cache key, valid at home and away; at home the
 * request itself goes to the LAN address (`ServerRouting`).
 */
class ImageUrls(private val session: JellyfinSession) {
    fun url(ref: ImageRef, widthPx: Int, heightPx: Int, quality: Int = QUALITY): String =
        session.api.imageApi.getItemImageUrl(
            itemId = ref.itemId,
            imageType = ref.kind.toImageType(),
            fillWidth = widthPx,
            fillHeight = heightPx,
            quality = quality,
            tag = ref.tag,
        )

    private fun ImageKind.toImageType() = when (this) {
        ImageKind.Primary -> ImageType.PRIMARY
        ImageKind.Backdrop -> ImageType.BACKDROP
        ImageKind.Thumb -> ImageType.THUMB
        ImageKind.Logo -> ImageType.LOGO
    }

    private companion object {
        const val QUALITY = 90
    }
}

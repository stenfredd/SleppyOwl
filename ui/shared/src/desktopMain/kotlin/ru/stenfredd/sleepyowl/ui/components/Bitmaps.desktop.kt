package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun argbImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    // BGRA in memory is an ARGB int in little-endian order: the ints go in as they are.
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = p.toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = (p shr 16).toByte()
        bytes[i * 4 + 3] = (p ushr 24).toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

/** A picture from the classpath (the TV's drawables are there, see build.gradle.kts). */
internal fun classpathImage(path: String): ImageBitmap {
    val bytes = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) { "no resource $path" }.use { it.readBytes() }
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

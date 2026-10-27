package ru.stenfredd.sleepyowl.playback.mpv

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/** The latest frame of [video], filling the composable; the composable's pixel size is what mpv draws for. */
@Composable
fun MpvVideoSurface(video: MpvVideo, modifier: Modifier = Modifier) {
    val holder = remember(video) { ImageHolder() }
    DisposableEffect(holder) { onDispose { holder.close() } }
    Canvas(modifier.onSizeChanged { video.resize(it.width, it.height) }) {
        video.serial.intValue
        drawRect(Color.Black)
        val frame = video.frames.take() ?: return@Canvas
        val image = holder.imageFor(frame)
        drawIntoCanvas { canvas ->
            val exact = frame.width == size.width.toInt() && frame.height == size.height.toInt()
            // The frame matches the area: no resampling. Smaller (a small video in a big window, or a frame of the old
            // size while the window is resized): Skia scales it up.
            canvas.nativeCanvas.drawImageRect(
                image, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), Rect.makeWH(size.width, size.height),
                if (exact) SamplingMode.DEFAULT else SamplingMode.CATMULL_ROM, holder.paint, true,
            )
        }
    }
}

/** A Skia Image over the frame's memory, without a copy; made anew for every new frame. */
private class ImageHolder {
    val paint = Paint()
    private var image: Image? = null
    private var serial = -1L

    fun imageFor(frame: FramePool.Frame): Image {
        image?.let { if (serial == frame.serial) return it }
        image?.close()
        val info = ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val data = frame.data!!.makeSubset(0, 4 * frame.width * frame.height)
        return Image.makeRaster(info, data, 4 * frame.width).also { image = it; serial = frame.serial; data.close() }
    }

    fun close() {
        image?.close()
        image = null
        paint.close()
    }
}

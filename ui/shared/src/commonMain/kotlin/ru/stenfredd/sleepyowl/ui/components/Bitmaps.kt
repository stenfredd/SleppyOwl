package ru.stenfredd.sleepyowl.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** A bitmap of [width]×[height] ARGB pixels (not premultiplied), row by row: QR codes, BlurHash placeholders. */
expect fun argbImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap

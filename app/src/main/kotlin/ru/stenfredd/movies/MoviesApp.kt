package ru.stenfredd.movies

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MoviesApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }

    /**
     * Images: posters and stills are opaque, so RGB_565 halves their memory; the memory cache is capped
     * well below the heap (the video buffer needs it); the disk cache keeps posters across launches.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(200L * 1024 * 1024).build() }
        .allowRgb565(true)
        .crossfade(false)
        .build()
}

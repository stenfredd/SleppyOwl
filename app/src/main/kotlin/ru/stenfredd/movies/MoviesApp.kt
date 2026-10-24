package ru.stenfredd.movies

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MoviesApp : Application(), SingletonImageLoader.Factory {
    /** The API's client: images go to the server's LAN address at home too (`ServerRouting`), sharing its connections. */
    @Inject lateinit var httpClient: OkHttpClient
    @Inject lateinit var launcherFeed: Lazy<LauncherFeed>

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        // Built and run off the main thread: the first screen must not wait for it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { launcherFeed.get().run() }
    }

    /**
     * Images: posters and stills are opaque, so RGB_565 halves their memory; the memory cache is capped
     * well below the heap (the video buffer needs it); the disk cache keeps posters across launches. Cache keys are the
     * URLs on the server's public address, whichever address the image actually came from.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(200L * 1024 * 1024).build() }
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }
        .allowRgb565(true)
        .crossfade(false)
        .build()
}

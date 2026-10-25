package ru.stenfredd.sleepyowl

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import ru.stenfredd.sleepyowl.core.data.dataModule
import ru.stenfredd.sleepyowl.core.jellyfin.jellyfinModule
import ru.stenfredd.sleepyowl.playback.core.playbackModule
import ru.stenfredd.sleepyowl.playback.exo.exoEngineModule
import ru.stenfredd.sleepyowl.playback.vlc.vlcEngineModule
import ru.stenfredd.sleepyowl.ui.uiModule
import timber.log.Timber

class SleepyOwlApp : Application(), SingletonImageLoader.Factory {
    /** The API's client: images go to the server's LAN address at home too (`ServerRouting`), sharing its connections. */
    private val httpClient: OkHttpClient by inject()
    private val launcherFeed: LauncherFeed by inject()

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        startKoin {
            androidContext(this@SleepyOwlApp)
            modules(jellyfinModule, dataModule, playbackModule, uiModule, exoEngineModule, vlcEngineModule, appModule)
        }
        // Built and run off the main thread: the first screen must not wait for it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { launcherFeed.run() }
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

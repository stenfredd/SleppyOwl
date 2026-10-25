package ru.stenfredd.sleepyowl

import android.content.Context
import android.os.Build
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import ru.stenfredd.sleepyowl.core.common.PreferenceStores
import ru.stenfredd.sleepyowl.core.common.androidAppDirs
import ru.stenfredd.sleepyowl.core.jellyfin.AndroidJellyfinPlatform
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinPlatform
import ru.stenfredd.sleepyowl.playback.core.AndroidCapabilitiesProbe
import ru.stenfredd.sleepyowl.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.sleepyowl.ui.TourEntry
import ru.stenfredd.sleepyowl.ui.UiPlatform

/**
 * Koin: the Android parts of the shared core and UI, and the TV app's own classes. The shared modules and the engines
 * are listed in [SleepyOwlApp]; the view models are in `uiModule`.
 */
val appModule = module {
    single { androidAppDirs(androidContext()) }
    singleOf(::PreferenceStores)
    single<JellyfinPlatform> { AndroidJellyfinPlatform(androidContext()) }
    single<DeviceCapabilitiesProbe> { AndroidCapabilitiesProbe(androidContext()) }
    single<UiPlatform> { AndroidUiPlatform(androidContext()) }

    singleOf(::LauncherFeed)
}

private class AndroidUiPlatform(private val context: Context) : UiPlatform {
    override val density = context.resources.displayMetrics.density
    override val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}"
    override val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    // Read only when the settings open: listing the assets has no place on the way to the first screen.
    override val tourAvailable by lazy { BuildConfig.DEBUG && TourEntry.available(context) }
}

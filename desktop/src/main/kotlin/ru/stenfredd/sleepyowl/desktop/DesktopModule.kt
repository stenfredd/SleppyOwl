package ru.stenfredd.sleepyowl.desktop

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import ru.stenfredd.sleepyowl.core.common.AppDirs
import ru.stenfredd.sleepyowl.core.common.PreferenceStores
import ru.stenfredd.sleepyowl.core.common.desktopAppDirs
import ru.stenfredd.sleepyowl.core.jellyfin.DesktopJellyfinPlatform
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinPlatform
import ru.stenfredd.sleepyowl.playback.api.DeviceCapabilities
import ru.stenfredd.sleepyowl.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.sleepyowl.ui.UiPlatform
import java.io.File

/**
 * Koin: the desktop parts of the shared core and UI. The shared modules are listed in [main]; the mpv engine comes
 * with L6.
 */
fun desktopModule(scale: UiScale) = module {
    single { desktopAppDirs() }
    singleOf(::PreferenceStores)
    single<JellyfinPlatform> { DesktopJellyfinPlatform() }
    single<DeviceCapabilitiesProbe> { DesktopCapabilitiesProbe() }
    single<UiPlatform> { DesktopUiPlatform(scale) }
    singleOf(::ProtoPlayer)
}

/** The places of the app's files besides [AppDirs]: the log and the single-instance lock. */
object DesktopPaths {
    /** `~/.local/state/sleepyowl/log`. */
    val log: File = xdg("XDG_STATE_HOME", ".local/state").resolve("log")

    /**
     * `$XDG_RUNTIME_DIR/sleepyowl`, or the cache when there is no runtime dir. `SLEEPYOWL_RUNTIME_DIR` replaces it
     * for the test stand: its app runs beside the user's own, and the runtime dir also leads to PipeWire.
     */
    val runtime: File = System.getenv("SLEEPYOWL_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { File(it, "sleepyowl") }
        ?: xdg("XDG_CACHE_HOME", ".cache").resolve("run")

    private fun xdg(variable: String, default: String) =
        File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + default), "sleepyowl")
}

/**
 * The UI's size: 1 dp of the TV layouts is 1 px at 100 % (LINUX_PLAN.md §1.4), times the system's own scale (GDK_SCALE,
 * Xft.dpi). [factor] is "Масштаб интерфейса"; until the settings have it (L9) it comes from `SLEEPYOWL_SCALE`.
 */
class UiScale(val factor: Float) {
    companion object {
        fun fromEnvironment() = UiScale(System.getenv("SLEEPYOWL_SCALE")?.toFloatOrNull()?.coerceIn(0.5f, 3f) ?: 1f)
    }
}

private class DesktopUiPlatform(private val scale: UiScale) : UiPlatform {
    override val density: Float
        get() = scale.factor * systemScale()
    override val version = "${BuildInfo.VERSION} · desktop"
    override val device = "${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${hostname()}"
    override val tourAvailable = false
}

/** Until the mpv engine (L6): the probe has nothing to report, every format is mpv's. */
private class DesktopCapabilitiesProbe : DeviceCapabilitiesProbe {
    override val capabilities = DeviceCapabilities(
        manufacturer = "PC",
        model = hostname(),
        soc = System.getProperty("os.arch"),
        sdkInt = 0,
        abis = listOf(System.getProperty("os.arch")),
        videoDecoders = emptyList(),
        audioDecoders = emptyList(),
        displayHdrTypes = emptySet(),
        displayModes = emptyList(),
        passthroughEncodings = emptySet(),
    )
}

object BuildInfo {
    const val VERSION = "0.1.0"
}

/**
 * Images: the API's client (`ServerRouting` sends them to the LAN address at home), a disk cache in
 * `~/.cache/sleepyowl/images` that keeps posters across launches, a memory cache of a quarter of the heap.
 */
fun imageLoader(context: PlatformContext, http: OkHttpClient, dirs: AppDirs): ImageLoader = ImageLoader.Builder(context)
    .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
    .diskCache { DiskCache.Builder().directory(File(dirs.files, "images").toOkioPath()).maxSizeBytes(500L * 1024 * 1024).build() }
    .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
    .crossfade(false)
    .build()

internal fun hostname(): String = runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.ifEmpty { null } ?: "Linux"

/** Pixels per dp of the main screen as Java sees it (GDK_SCALE / Xft.dpi). */
internal fun systemScale(): Float = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
}.getOrDefault(1f)

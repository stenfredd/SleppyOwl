package ru.stenfredd.movies.ui.settings

import androidx.compose.runtime.Immutable

/** A component shipped in the app and its licence; [asset] is the licence text in `assets/licenses`. */
@Immutable
data class Component(val name: String, val note: String?, val license: String, val asset: String)

/** "О приложении": the app itself first, then what it is built from (playback first: it carries the copyleft licences). */
internal object Licenses {
    val app = Component("Movies", "это приложение", "GPL-3.0", "GPL-3.0.txt")

    val components = listOf(
        Component("libVLC", "VideoLAN, второй движок воспроизведения", "LGPL-2.1", "LGPL-2.1.txt"),
        Component("FFmpeg для Media3", "Jellyfin, декодеры звука", "GPL-3.0", "GPL-3.0.txt"),
        Component("Jellyfin SDK", "Jellyfin, работа с сервером", "LGPL-3.0", "LGPL-3.0.txt"),
        Component("AndroidX Media3 (ExoPlayer)", "Google, основной движок", "Apache 2.0", "Apache-2.0.txt"),
        Component("PsExtractor", "из Media3, изменён для DVD", "Apache 2.0", "Apache-2.0.txt"),
        Component("ass-media", "субтитры ASS в ExoPlayer", "MIT", "MIT.txt"),
        Component("libass", "внутри ass-media", "ISC", "ISC.txt"),
        Component("Jetpack Compose, AndroidX", "Google, интерфейс", "Apache 2.0", "Apache-2.0.txt"),
        Component("Kotlin, kotlinx", "JetBrains", "Apache 2.0", "Apache-2.0.txt"),
        Component("Dagger Hilt", "Google", "Apache 2.0", "Apache-2.0.txt"),
        Component("Coil", "загрузка картинок", "Apache 2.0", "Apache-2.0.txt"),
        Component("OkHttp", "Square, сеть", "Apache 2.0", "Apache-2.0.txt"),
        Component("ZXing", "QR-код для Quick Connect", "Apache 2.0", "Apache-2.0.txt"),
        Component("Timber", "журнал", "Apache 2.0", "Apache-2.0.txt"),
        Component("Шрифт Onest", null, "OFL 1.1", "Onest-OFL.txt"),
        Component("Шрифт JetBrains Mono", null, "OFL 1.1", "JetBrainsMono-OFL.txt"),
    )
}

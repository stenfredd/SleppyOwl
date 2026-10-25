package ru.stenfredd.sleepyowl.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// The TV's font resources (src/androidMain/res/font), on the classpath as `font/…`.
internal actual fun onestFontFamily() = FontFamily(
    Font("font/onest_400.ttf", FontWeight.Normal),
    Font("font/onest_500.ttf", FontWeight.Medium),
    Font("font/onest_600.ttf", FontWeight.SemiBold),
    Font("font/onest_700.ttf", FontWeight.Bold),
    Font("font/onest_800.ttf", FontWeight.ExtraBold),
)

internal actual fun monoFontFamily() = FontFamily(
    Font("font/jetbrains_mono_400.ttf", FontWeight.Normal),
    Font("font/jetbrains_mono_500.ttf", FontWeight.Medium),
    Font("font/jetbrains_mono_600.ttf", FontWeight.SemiBold),
)

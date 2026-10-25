package ru.stenfredd.sleepyowl.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ru.stenfredd.sleepyowl.ui.shared.R

internal actual fun onestFontFamily() = FontFamily(
    Font(R.font.onest_400, FontWeight.Normal),
    Font(R.font.onest_500, FontWeight.Medium),
    Font(R.font.onest_600, FontWeight.SemiBold),
    Font(R.font.onest_700, FontWeight.Bold),
    Font(R.font.onest_800, FontWeight.ExtraBold),
)

internal actual fun monoFontFamily() = FontFamily(
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
    Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
)

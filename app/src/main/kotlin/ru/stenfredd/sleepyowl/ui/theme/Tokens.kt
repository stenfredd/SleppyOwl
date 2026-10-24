package ru.stenfredd.sleepyowl.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.R

/**
 * Design tokens from `design/…/Jellyfin TV.dc.html`. The mockup is drawn at 1920×1080 px,
 * the TV UI is 960×540 dp, so every size here is the mockup's px / 2.
 */
object AppColors {
    val Background = Color(0xFF0A0B10)
    val BackgroundDeep = Color(0xFF050609)
    val DotGrid = Color(0xFF1B1E2B)

    val Surface = Color(0xFF12141C)
    val SurfaceRaised = Color(0xFF151824)
    val SurfaceFocused = Color(0xFF1D2031)
    val Rail = Color(0xB30A0B10)
    val RailOpen = Color(0xFF0D0E16)

    val Border = Color(0xFF2A2E40)
    val BorderSubtle = Color(0xFF1D2030)
    val Hairline = Color(0x14FFFFFF)

    val Text = Color(0xFFECEEF6)
    val TextSecondary = Color(0xFFC3C7DD)
    val TextMuted = Color(0xFFA3A8C3)
    val TextDim = Color(0xFF8B90AD)
    val TextFaint = Color(0xFF5F6582)

    val Accent = Color(0xFFA594FF)
    val AccentStrong = Color(0xFF8F6BFF)
    val AccentGlow = Color(0xFF7C5CFF)
    val FocusFill = Color(0xFFECE9FF)
    val OnFocusFill = Color(0xFF0B0C12)
    val Selected = Color(0xFF1D1A3D)
    val SelectedBorder = Color(0xFF5F55E6)

    val Green = Color(0xFF7EE0A3)
    val Pink = Color(0xFFFF9CF9)
    val Yellow = Color(0xFFFFD166)
    val Blue = Color(0xFF64B5F6)
    val Orange = Color(0xFFFFB454)
    val Red = Color(0xFFFF6E6E)
    val NewBadge = Color(0xFF4D7CFF)

    val PrimaryGradient = Brush.linearGradient(listOf(Color(0xFF4D7CFF), Color(0xFF8A5CFF)))
    val ProgressGradient = Brush.horizontalGradient(listOf(Color(0xFF4D8BFF), Color(0xFF9B6BFF)))
    val ProgressTrack = Color(0x24FFFFFF)
}

object AppFonts {
    val Onest = FontFamily(
        Font(R.font.onest_400, FontWeight.Normal),
        Font(R.font.onest_500, FontWeight.Medium),
        Font(R.font.onest_600, FontWeight.SemiBold),
        Font(R.font.onest_700, FontWeight.Bold),
        Font(R.font.onest_800, FontWeight.ExtraBold),
    )
    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_400, FontWeight.Normal),
        Font(R.font.jetbrains_mono_500, FontWeight.Medium),
        Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
    )
}

/** Text styles used by the mockup, named by role. */
@Immutable
object AppType {
    private val base = TextStyle(
        fontFamily = AppFonts.Onest,
        color = AppColors.Text,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
    private val mono = base.copy(fontFamily = AppFonts.Mono)

    val Hero = base.copy(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em)
    val Display = base.copy(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em)
    val Section = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val CardTitle = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val PosterTitle = base.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    val Body = base.copy(fontSize = 11.sp, lineHeight = 16.sp, color = AppColors.TextMuted)
    val BodyLarge = base.copy(fontSize = 13.sp, color = Color(0xFFD9DCEC))
    val Button = base.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    val Rail = base.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)

    val MonoCount = mono.copy(fontSize = 10.sp, color = AppColors.TextFaint)
    val MonoCaption = mono.copy(fontSize = 9.sp, color = AppColors.TextDim)
    val MonoMeta = mono.copy(fontSize = 9.5.sp, color = AppColors.TextSecondary)
    val MonoLabel = mono.copy(fontSize = 10.sp, letterSpacing = 0.12.em, color = AppColors.Accent)
    val MonoBadge = mono.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em)
    val Clock = mono.copy(fontSize = 13.sp, color = AppColors.TextMuted)
}

/** Layout constants (dp), mockup px / 2. */
object AppDimens {
    const val RAIL_COLLAPSED = 56
    const val RAIL_OPEN = 190
    /** Content starts at 64 px + 120 px padding in the mockup. */
    const val CONTENT_START = 60
    const val CONTENT_TOP = 44
    const val ROW_GAP = 14
    const val SECTION_GAP = 22
    const val POSTER_W = 110
    const val POSTER_H = 165
    const val WIDE_W = 190
    const val WIDE_H = 107
}

package ru.stenfredd.sleepyowl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.CircleButton
import ru.stenfredd.sleepyowl.ui.components.FavoriteBadge
import ru.stenfredd.sleepyowl.ui.components.FormatBadge
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.ImageShade
import ru.stenfredd.sleepyowl.ui.components.NewBadge
import ru.stenfredd.sleepyowl.ui.components.OwlMark
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.PosterModel
import ru.stenfredd.sleepyowl.ui.components.PrimaryButton
import ru.stenfredd.sleepyowl.ui.components.ProgressLine
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.RemoteImageSpec
import ru.stenfredd.sleepyowl.ui.components.SecondaryButton
import ru.stenfredd.sleepyowl.ui.components.StatusDot
import ru.stenfredd.sleepyowl.ui.components.WatchedBadge
import ru.stenfredd.sleepyowl.ui.components.Wordmark
import ru.stenfredd.sleepyowl.ui.components.dashedBorder
import ru.stenfredd.sleepyowl.ui.login.qrBitmap
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import java.io.File

/**
 * Draws the shared components with their desktop implementations (fonts and pictures from the classpath, Skia glow,
 * BlurHash, QR) at the TV's density, and saves the picture to `build/reports/ui-shared/components.png` to look at.
 */
class DesktopRenderTest {
    @Test
    fun componentsRender() {
        val scene = ImageComposeScene(960 * 2, 540 * 2, Density(2f)) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(Modifier.fillMaxSize().background(AppColors.Background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwlMark()
                    Wordmark()
                    BasicText("Главная · Медиатека", style = AppType.Display)
                    BasicText("01:23:45 · HEVC 10 бит", style = AppType.MonoMeta)
                    StatusDot(AppColors.Green, pulsing = false)
                    Avatar("Claude", "seed", 32.dp, AppType.CardTitle)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppIcon.entries.forEach { Icon(it, AppColors.TextSecondary, Modifier.size(18.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Focused: its glow and ring are the desktop GlowCache.
                    PrimaryButton("Смотреть", onClick = {}, modifier = Modifier.focusRequester(focus))
                    SecondaryButton("С начала", onClick = {})
                    CircleButton(AppIcon.Heart, onClick = {})
                    Chip("Русский", selected = true, onClick = {}, detail = "AC3 5.1")
                    Chip("English", selected = false, onClick = {})
                    FormatBadge("4K")
                    FormatBadge("HDR10")
                    FormatBadge("Atmos")
                    NewBadge()
                    WatchedBadge()
                    FavoriteBadge()
                }
                ProgressLine(0.4f, Modifier.width(300.dp).height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // No image loader for the URL here: the BlurHash placeholder stays, with the shade over it.
                    RemoteImage(
                        RemoteImageSpec("test://none", "LEHV6nWB2yk8pyo0adR*.7kCMdnj"),
                        8.dp,
                        Modifier.size(190.dp, 107.dp),
                        shade = ImageShade(0.7f, listOf(0f to AppColors.Background, 1f to AppColors.Background.copy(alpha = 0f))),
                    )
                    PosterCard(PosterModel("p", "Бойцовский клуб", "1999 · ★ 8.4", null, badge = "1080p", progress = 0.3f, isNew = true), onClick = {})
                    val qr = remember { qrBitmap("https://jellyfin.example.com/web/#/quickconnect?code=123456") }
                    Spacer(
                        Modifier.size(100.dp).drawBehind {
                            drawImage(qr, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
                        },
                    )
                    Box(Modifier.size(150.dp, 80.dp).dashedBorder(AppColors.Border, 2.dp, 10.dp))
                }
            }
        }
        var frame = 0L
        repeat(30) { scene.render(frame); frame += 16_000_000L }
        val image = scene.render(frame)
        val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        val out = File("build/reports/ui-shared/components.png")
        out.parentFile.mkdirs()
        out.writeBytes(png)
        scene.close()
        assertTrue(png.size > 10_000)
    }
}

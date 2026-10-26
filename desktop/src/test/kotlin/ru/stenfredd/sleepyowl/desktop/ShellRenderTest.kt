package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.shell.RailLibrary
import ru.stenfredd.sleepyowl.ui.shell.Tab
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import java.io.File
import java.util.UUID

/**
 * The side rail on a wide window (pinned open with labels) and on a narrow one (icons only), drawn without a window
 * into `build/reports/desktop/shell-<width>.png` to look at.
 */
class ShellRenderTest {
    @Test
    fun wideAndNarrow() {
        for (width in listOf(1280, 900)) render(width)
    }

    private fun render(width: Int) {
        val libraries = listOf(
            RailLibrary(UUID.randomUUID(), "Фильмы", AppIcon.Film),
            RailLibrary(UUID.randomUUID(), "Сериалы", AppIcon.Tv),
            RailLibrary(UUID.randomUUID(), "Аниме", AppIcon.Library),
        )
        val scene = ImageComposeScene(width, 640, Density(1f)) {
            Box(Modifier.fillMaxSize().background(AppColors.Background)) {
                Shell(Tab.Home, "Claude", "seed", libraries, onTab = {}, onLibrary = {}, onProfile = {}) {
                    BasicText("Главная", style = AppType.Hero)
                }
            }
        }
        var frame = 0L
        repeat(20) { scene.render(frame); frame += 16_000_000L }
        val png = checkNotNull(scene.render(frame).encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/reports/desktop").apply { mkdirs() }.resolve("shell-$width.png").writeBytes(png)
        scene.close()
    }
}

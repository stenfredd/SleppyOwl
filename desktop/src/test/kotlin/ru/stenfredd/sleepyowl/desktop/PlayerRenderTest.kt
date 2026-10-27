package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import ru.stenfredd.sleepyowl.core.common.AppDirs
import ru.stenfredd.sleepyowl.core.common.PreferenceStores
import ru.stenfredd.sleepyowl.core.data.model.MediaSegment
import ru.stenfredd.sleepyowl.core.data.model.SegmentKind
import ru.stenfredd.sleepyowl.playback.api.EngineState
import ru.stenfredd.sleepyowl.playback.core.PlaybackUiState
import ru.stenfredd.sleepyowl.ui.player.NextEpisode
import ru.stenfredd.sleepyowl.ui.player.PlayerInfo
import ru.stenfredd.sleepyowl.ui.player.SeasonEpisodes
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * The player's controls in the three layouts of §1.3 (full, compact, minimal) over a stand-in picture, with the skip
 * button, the "next episode" card and the right-click menu, drawn without a window into
 * `build/reports/desktop/player-<name>.png` to look at.
 */
class PlayerRenderTest {
    private val dir = Files.createTempDirectory("player-render").toFile()
    private val player = PreferenceStores(AppDirs(dir, dir)).let { DesktopPlayer(it, DesktopSettings(it)) }
    private val id = UUID.randomUUID()
    private val state = PlaybackUiState(
        title = "Угрюмый аптекарь",
        loading = false,
        engineState = EngineState(EngineState.Phase.READY, playWhenReady = true),
        positionMs = 754_000,
        durationMs = 1_370_000,
        bufferedMs = 900_000,
        itemId = id,
    )
    private val info = PlayerInfo(
        id = id, isEpisode = true, seriesId = UUID.randomUUID(), label = "СЕРИАЛ", title = "Монолог фармацевта",
        subtitle = "Сезон 1 · Серия 2 · Угрюмый аптекарь", chaptersMs = listOf(88_000, 1_290_000), poster = null, rating = "★ 8.6",
        age = "16+", year = "2023", runtime = "23 мин", genres = "Аниме, Драма", description = null, director = null, cast = emptyList(),
        seasonNumber = 1, qualities = emptyList(),
    )
    private val season = SeasonEpisodes(
        currentId = id, title = "СЕЗОН 1 · СЕРИИ", stat = "24 серии · 2 просмотрено", episodes = emptyList(),
        previous = UUID.randomUUID(), next = UUID.randomUUID(), nextEpisode = NextEpisode(UUID.randomUUID(), "S1 · E3 · Смута из-за духа", null),
    )
    private val spans = listOf(MediaSegment(SegmentKind.Intro, 0, 88_000), MediaSegment(SegmentKind.Outro, 1_290_000, 1_370_000))

    @Test
    fun layouts() {
        render("full", 1280, 720) {
            FullOsd(
                state, info, season, null, PlayerUiState(), state.positionMs, spans, playing = true, player, drawer = false,
                onToggle = {}, onSeek = {}, onScrub = { _, _ -> }, onEpisode = {}, onPanel = {}, onVolume = {}, onMute = {}, onFullscreen = {}, onOnTop = {}, onLibrary = {}, onClose = {},
            )
            SkipButton("Пропустить заставку", "0:44", PlayerUiState(), onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp))
        }
        render("compact", 720, 405) {
            CompactOsd(
                state, info, season, PlayerUiState(), state.positionMs, spans, playing = false, player,
                onToggle = {}, onSeek = {}, onScrub = { _, _ -> }, onEpisode = {}, onVolume = {}, onMute = {}, onMenu = {}, onFullscreen = {}, onOnTop = {}, onLibrary = {}, onClose = {},
            )
            val ui = PlayerUiState()
            PlayerMenu(listOf(listOf(MenuItem("Во весь экран", "F") {}), listOf(MenuItem("Аудио", "A") {}, MenuItem("Субтитры", "C") {}), listOf(MenuItem("Закрыть плеер", "Ctrl+W") {})), Offset(380f, 60f), ui, onDismiss = {})
        }
        render("minimal", 400, 225) {
            MinimalOsd(
                state, info, season, PlayerUiState(), state.positionMs, spans, playing = true, PlayerMode.Mini,
                onToggle = {}, onSeek = {}, onScrub = { _, _ -> }, onEpisode = {}, onMenu = {}, onOnTop = {}, onLibrary = {}, onClose = {},
            )
        }
        render("next", 400, 225) {
            NextEpisodeCard(season.nextEpisode!!, 9_000, 15_000, compact = true, PlayerUiState(), onPlay = {}, onStay = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            // A stand-in for the picture: the controls are drawn over video.
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A5A7A), Color(0xFF7A4A3A))))) { content() }
        }
        var frame = 0L
        repeat(20) { scene.render(frame); frame += 16_000_000L }
        val png = checkNotNull(scene.render(frame).encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/reports/desktop").apply { mkdirs() }.resolve("player-$name.png").writeBytes(png)
        scene.close()
    }
}

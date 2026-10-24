package ru.stenfredd.sleepyowl.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import ru.stenfredd.sleepyowl.playback.api.Codecs
import ru.stenfredd.sleepyowl.playback.api.DolbyVisionSwitchable
import ru.stenfredd.sleepyowl.playback.api.EngineId
import ru.stenfredd.sleepyowl.playback.api.VideoPathSwitchable
import ru.stenfredd.sleepyowl.playback.core.PlaybackSession
import ru.stenfredd.sleepyowl.playback.core.PlaybackUiState
import ru.stenfredd.sleepyowl.playback.core.describe
import ru.stenfredd.sleepyowl.playback.exo.ExoEngineProvider
import ru.stenfredd.sleepyowl.playback.vlc.VlcEngineProvider

/*
 * Debug builds only: the prototype's numbered playback variants (badge in the corner, digit keys 0–9, MENU opens the
 * variant/track/engine list, INFO cycles badge → diagnostics → nothing) and the debug tour. Release builds never call
 * into this file (every use is behind BuildConfig.DEBUG, R8 drops it).
 */

/** Debug tour context for [PlayerScreen] (when the OSD is hidden): ↑ cycles playback variants, ↓ goes to the next file. */
class TourInfo(val number: Int, val total: Int, val title: String, val label: String, val onNextFile: () -> Unit)

/** Variants ↑ steps through in the tour: auto first, then each engine/path, then a server transcode. */
internal val TOUR_VARIANTS = listOf(0) + Variant.entries.map { it.number } + TRANSCODE_KEY

/**
 * Numbered playback variants for A/B testing on the TV: the badge in the corner shows the number, so the tester can
 * note "file — variant — impression". 0 returns to the automatic choice. Picked from the ↓ menu or with digit keys.
 */
private enum class Variant(
    val number: Int,
    val label: String,
    val engine: EngineId,
    val path: VideoPathSwitchable.Mode? = null,
    /** For Exo: decode Dolby Vision files through the DV decoder instead of the base layer. */
    val dolbyVision: Boolean = false,
) {
    EXO_PLANE(1, "Exo · видеослой ТВ", ExoEngineProvider.ID, VideoPathSwitchable.Mode.VIDEO_PLANE),
    EXO_GPU(2, "Exo · через GPU", ExoEngineProvider.ID, VideoPathSwitchable.Mode.GPU),
    VLC(3, "VLC", VlcEngineProvider.ID),
    EXO_DV(4, "Exo · DV-декодер", ExoEngineProvider.ID, VideoPathSwitchable.Mode.VIDEO_PLANE, dolbyVision = true),
}

internal const val TRANSCODE_KEY = 9

/** Returns false if [number] is not a variant. */
internal fun applyVariant(number: Int, session: PlaybackSession): Boolean {
    when (number) {
        0 -> session.switchToAuto()
        TRANSCODE_KEY -> session.forceTranscode()
        else -> {
            val v = Variant.entries.firstOrNull { it.number == number } ?: return false
            val current = session.activeEngine
            val sameDecoder = (current as? DolbyVisionSwitchable)?.useDolbyVisionDecoder?.let { it == v.dolbyVision } ?: true
            if (current?.id == v.engine && current is VideoPathSwitchable && v.path != null && sameDecoder) current.setVideoPathMode(v.path)
            else session.switchEngine(v.engine, videoPath = v.path, dolbyVision = v.dolbyVision.takeIf { v.engine == ExoEngineProvider.ID })
        }
    }
    return true
}

/** The variant actually playing, e.g. "2" (Exo through GPU) or "3Т" (VLC on a server transcode). */
private fun currentVariantCode(state: PlaybackUiState, session: PlaybackSession): String {
    val engine = session.activeEngine
    val gpu = (engine as? VideoPathSwitchable)?.usingGpuPath
    val dv = (engine as? DolbyVisionSwitchable)?.useDolbyVisionDecoder == true
    val v = Variant.entries.firstOrNull {
        it.engine == state.engine && it.dolbyVision == dv && (it.path == null || gpu == (it.path == VideoPathSwitchable.Mode.GPU))
    }
    val transcode = state.playMethod != null && state.playMethod != PlayMethod.DIRECT_PLAY
    return (v?.number?.toString() ?: "?") + if (transcode) "Т" else ""
}

@Composable
internal fun VariantBadge(state: PlaybackUiState, session: PlaybackSession, tour: TourInfo?, modifier: Modifier) {
    val engine = session.activeEngine
    val path = (engine as? VideoPathSwitchable)?.let { if (it.usingGpuPath) "GPU" else "видеослой" }
        ?.plus(if ((engine as? DolbyVisionSwitchable)?.useDolbyVisionDecoder == true) " · DV" else "")
    val transcode = state.playMethod != null && state.playMethod != PlayMethod.DIRECT_PLAY
    val head = listOfNotNull(state.engine?.value?.uppercase(), path, if (transcode) "ТРАНСКОД" else null).joinToString(" · ")
    val mode = when {
        state.manualEngine -> "вручную"
        state.engineReason.startsWith("fallback") || "не умеет" in state.engineReason || "все движки" in state.engineReason -> "авто, запасной"
        else -> "авто"
    }
    Column(
        modifier.background(Color(0xC0000000)).padding(16.dp, 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        tour?.let {
            Text("Отладка: файл ${it.number} из ${it.total}", fontSize = 14.sp, color = Color(0xFF80CBC4))
        }
        Text(
            "${currentVariantCode(state, session)}  $head",
            fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFFFFD54F),
        )
        Text(
            "$mode · ${PlayerLabels.time(state.positionMs)} · пропуски ${state.stats.droppedFrames}",
            fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color.White,
        )
        Text(tour?.title ?: state.title, fontSize = if (tour != null) 16.sp else 12.sp, color = Color.LightGray)
        tour?.let { Text(it.label, fontSize = 14.sp, color = Color.White, modifier = Modifier.widthIn(max = 900.dp)) }
    }
}

@Composable
internal fun DebugPanel(state: PlaybackUiState, vm: PlayerViewModel, modifier: Modifier) {
    val caps = vm.probe.capabilities
    val lines = buildList {
        add("Движок: ${state.engine} — ${state.engineReason}")
        add("Метод: ${state.playMethod?.serialName ?: "?"}" + (state.transcodeReasons?.let { " ($it)" } ?: ""))
        state.stats.videoDecoder?.let { add("Видеодекодер: $it") }
        state.stats.videoFormat?.let { add("Видео: $it") }
        state.stats.audioDecoder?.let { add("Аудиодекодер: $it") }
        state.stats.audioFormat?.let { add("Аудио: $it") }
        state.stats.audioOutput?.let { add("Аудиовыход: $it") }
        add("Пропущено кадров: ${state.stats.droppedFrames}")
        state.stats.extra.forEach { (k, v) -> add("$k: $v") }
        state.ranking.forEach { (id, verdict) -> add("· $id: ${verdict.describe()}") }
        add("Устройство: ${caps.manufacturer} ${caps.model} (${caps.soc}), HDR ${caps.displayHdrTypes}, passthrough ${caps.passthroughEncodings.map(Codecs::encodingName)}")
        add("— журнал —")
        addAll(state.history.takeLast(8))
    }
    Column(modifier.width(900.dp).background(Color(0xB0000000)).padding(12.dp)) {
        lines.forEach { Text(it, fontSize = 11.sp, color = Color.White, lineHeight = 13.sp) }
    }
}

@Composable
internal fun DebugMenu(
    state: PlaybackUiState,
    session: PlaybackSession,
    vm: PlayerViewModel,
    modifier: Modifier,
    close: () -> Unit,
) {
    val source = state.source ?: return
    fun mark(selected: Boolean) = if (selected) "● " else "   "
    LazyColumn(
        modifier.fillMaxHeight().width(520.dp).background(Color(0xE0101010)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Вариант (сейчас ${currentVariantCode(state, session)})", style = MaterialTheme.typography.titleMedium) }
        val variants = listOf(0 to "Авто") + Variant.entries.map { it.number to it.label } + listOf(TRANSCODE_KEY to "Транскод сервером (текущим движком)")
        variants.forEach { (number, label) ->
            item {
                Button(onClick = { applyVariant(number, session); close() }) { Text("$number  $label") }
            }
        }
        item { Text("Аудио", style = MaterialTheme.typography.titleMedium) }
        source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }.forEach { s ->
            item {
                Button(onClick = { session.selectAudio(s.index); close() }) {
                    Text(mark(state.selection.audioStreamIndex == s.index) + (s.displayTitle ?: s.codec.orEmpty()))
                }
            }
        }
        item { Text("Субтитры", style = MaterialTheme.typography.titleMedium) }
        item {
            Button(onClick = { session.selectSubtitle(null); close() }) { Text(mark(state.selection.subtitleStreamIndex == null) + "Выключены") }
        }
        source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }.forEach { s ->
            item {
                Button(onClick = { session.selectSubtitle(s.index); close() }) {
                    Text(mark(state.selection.subtitleStreamIndex == s.index) + (s.displayTitle ?: s.codec.orEmpty()))
                }
            }
        }
        item { Text("Движок", style = MaterialTheme.typography.titleMedium) }
        vm.selector.engines.forEach { p ->
            item { Button(onClick = { session.switchEngine(p.id); close() }) { Text(mark(state.engine == p.id) + p.displayName) } }
        }
        item { Button(onClick = { session.forceTranscode(); close() }) { Text("   Транскод на сервере") } }
        (session.activeEngine as? VideoPathSwitchable)?.let { engine ->
            item { Text("Вывод видео", style = MaterialTheme.typography.titleMedium) }
            val modes = listOf(
                VideoPathSwitchable.Mode.AUTO to "Авто (сейчас: ${if (engine.usingGpuPath) "через GPU" else "видеослой ТВ"})",
                VideoPathSwitchable.Mode.VIDEO_PLANE to "Видеослой ТВ (с HDR, обработка ТВ)",
                VideoPathSwitchable.Mode.GPU to "Через GPU, как VLC (без HDR, до 1080p)",
            )
            modes.forEach { (mode, label) ->
                item {
                    Button(onClick = { engine.setVideoPathMode(mode); close() }) {
                        Text(mark(engine.videoPathMode == mode) + label)
                    }
                }
            }
        }
    }
}


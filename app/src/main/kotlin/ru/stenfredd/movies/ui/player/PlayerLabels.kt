package ru.stenfredd.movies.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.jellyfin.sdk.model.api.PlayMethod
import ru.stenfredd.movies.core.data.model.MediaInfo
import ru.stenfredd.movies.core.data.model.VideoRange
import ru.stenfredd.movies.playback.api.DolbyVisionSwitchable
import ru.stenfredd.movies.playback.api.PlaybackEngine
import ru.stenfredd.movies.playback.api.VideoPathSwitchable
import ru.stenfredd.movies.playback.core.PlaybackUiState
import ru.stenfredd.movies.ui.details.MediaLabels
import ru.stenfredd.movies.ui.details.TrackOption
import ru.stenfredd.movies.ui.theme.AppColors
import java.util.Locale

/** Audio and subtitle choices of the file being played. Subtitles start with "Выкл" (index null). */
@Immutable
data class PlayerTracks(val audio: List<TrackOption>, val subtitles: List<TrackOption>) {
    companion object {
        fun of(media: MediaInfo?): PlayerTracks {
            media ?: return PlayerTracks(emptyList(), emptyList())
            return PlayerTracks(
                audio = media.audio.map { TrackOption("audio/${it.index}", it.index, MediaLabels.audioLabel(it), MediaLabels.audioDetail(it)) },
                subtitles = listOf(TrackOption("sub/off", null, "Выкл", null)) +
                    media.subtitles.map { TrackOption("sub/${it.index}", it.index, MediaLabels.subtitleLabel(it), MediaLabels.subtitleDetail(it)) },
            )
        }
    }
}

/** One line of "Информация о потоке"; [color] is the dot: video blue, audio pink, subtitles yellow, the rest green. */
@Immutable
data class InfoRow(val key: String, val value: String, val color: Color)

/** The verdict at the top of "Информация о потоке". */
@Immutable
data class StreamMode(val direct: Boolean, val title: String, val reason: String)

internal object PlayerLabels {
    /** "Русский 5.1"; a track without a language: its own title, else the format ("AC3 5.1"). */
    fun audioChip(media: MediaInfo?, index: Int?): String {
        val track = media?.audio?.firstOrNull { it.index == index } ?: return "Аудио"
        val name = MediaLabels.language(track.language) ?: track.name ?: return MediaLabels.audioDetail(track) ?: "Аудио"
        return listOfNotNull(name, channels(track.channels)).joinToString(" ")
    }

    /** "Русский" or "Субтитры выкл" */
    fun subtitleChip(media: MediaInfo?, index: Int?): String {
        val track = media?.subtitles?.firstOrNull { it.index == index } ?: return "Субтитры выкл"
        return MediaLabels.language(track.language) ?: track.name ?: "Субтитры"
    }

    fun isDirect(state: PlaybackUiState) = state.playMethod == null || state.playMethod == PlayMethod.DIRECT_PLAY

    fun mode(state: PlaybackUiState): StreamMode {
        if (isDirect(state)) return StreamMode(true, "Прямое воспроизведение", "Файл воспроизводится без изменений")
        val reasons = state.transcodeReasons?.split(',')?.mapNotNull { reason(it.trim()) }?.distinct().orEmpty()
        val why = when {
            reasons.isNotEmpty() -> reasons.joinToString(", ")
            state.maxBitrate != null -> "ограничение качества"
            else -> "выбрано вручную"
        }
        return StreamMode(false, "Транскодирование", "Причина: $why")
    }

    /** "ExoPlayer · видеослой ТВ", "VLC · вручную"; the DV decoder is only mentioned for a Dolby Vision file. */
    fun engine(state: PlaybackUiState, engine: PlaybackEngine?, dolbyVisionFile: Boolean): String {
        val name = when (val id = state.engine?.value) {
            "exo" -> "ExoPlayer"
            "vlc" -> "VLC"
            null -> "—"
            else -> id
        }
        val path = (engine as? VideoPathSwitchable)?.let { if (it.usingGpuPath) "через GPU" else "видеослой ТВ" }
        val dv = "DV-декодер".takeIf { dolbyVisionFile && (engine as? DolbyVisionSwitchable)?.useDolbyVisionDecoder == true }
        return listOfNotNull(name, path, dv, "вручную".takeIf { state.manualEngine }).joinToString(" · ")
    }

    fun rows(state: PlaybackUiState, media: MediaInfo?, engine: PlaybackEngine?): List<InfoRow> = buildList {
        val video = media?.video
        val transcode = !isDirect(state)
        media?.container?.let { add(InfoRow("Контейнер", it.uppercase() + if (transcode) " → HLS" else "", AppColors.Green)) }
        if (video != null) {
            val format = listOfNotNull(
                MediaLabels.videoCodec(video.codec),
                video.bitDepth?.takeIf { it > 8 }?.let { "$it бит" },
                if (video.width != null && video.height != null) "${video.width}×${video.height}" else null,
                range(video.range, video.dvProfile),
            ).joinToString(" · ")
            add(InfoRow("Видео", format, AppColors.Blue))
            video.frameRate?.takeIf { it > 0 }?.let { add(InfoRow("Частота", String.format(Locale.US, "%.3f к/с", it).replace(".000", ""), AppColors.Blue)) }
        }
        media?.bitrate?.takeIf { it > 0 }?.let {
            val cap = state.maxBitrate?.let { c -> " → до " + mbps(c) }.orEmpty()
            add(InfoRow("Битрейт", String.format(Locale.US, "%.1f Мбит/с", it / 1e6) + cap, AppColors.Green))
        }
        media?.audio?.firstOrNull { it.index == state.selection.audioStreamIndex }?.let {
            add(InfoRow("Аудио", listOfNotNull(MediaLabels.audioDetail(it), MediaLabels.audioLabel(it)).joinToString(" · "), AppColors.Pink))
        }
        val subtitle = media?.subtitles?.firstOrNull { it.index == state.selection.subtitleStreamIndex }
        add(InfoRow("Субтитры", subtitle?.let { listOfNotNull(MediaLabels.subtitleLabel(it), MediaLabels.subtitleDetail(it)).joinToString(" · ") } ?: "выкл", AppColors.Yellow))
        add(InfoRow("Движок", engine(state, engine, video?.range == VideoRange.DolbyVision), AppColors.Green))
        state.stats.videoDecoder?.let { add(InfoRow("Декодер", it, AppColors.Blue)) }
        state.stats.audioOutput?.let { add(InfoRow("Звук", it, AppColors.Pink)) }
        if (state.bufferedMs > state.positionMs) add(InfoRow("Буфер", "${(state.bufferedMs - state.positionMs) / 1000} с", AppColors.Green))
        add(InfoRow("Пропущено", "${state.stats.droppedFrames} кадров", AppColors.Blue))
    }

    /** "20 Мбит/с", "1.5 Мбит/с" */
    fun mbps(bitrate: Int): String {
        val m = bitrate / 1e6
        return if (m >= 10) "${m.toInt()} Мбит/с" else String.format(Locale.US, "%.1f Мбит/с", m)
    }

    /** "1:24:25" */
    fun time(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
    }

    private fun channels(n: Int?) = when (n) {
        null, 0 -> null
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${n}ch"
    }

    private fun range(range: VideoRange, dvProfile: Int?) = when (range) {
        VideoRange.SDR -> null
        VideoRange.HDR10 -> "HDR10"
        VideoRange.HDR10Plus -> "HDR10+"
        VideoRange.HLG -> "HLG"
        VideoRange.DolbyVision -> dvProfile?.let { "DV P$it" } ?: "DV"
        VideoRange.Other -> "HDR"
    }

    /** Jellyfin's TranscodeReason names in plain Russian; unknown ones are dropped. */
    private fun reason(name: String): String? = when (name) {
        "ContainerNotSupported" -> "контейнер не поддерживается"
        "VideoCodecNotSupported", "VideoCodecTagNotSupported" -> "видеокодек не поддерживается"
        "AudioCodecNotSupported" -> "аудиокодек не поддерживается"
        "SubtitleCodecNotSupported" -> "субтитры нужно вшить"
        "AudioIsExternal" -> "внешняя аудиодорожка"
        "SecondaryAudioNotSupported" -> "вторая аудиодорожка"
        "VideoProfileNotSupported" -> "профиль видео"
        "VideoLevelNotSupported" -> "уровень видео"
        "VideoResolutionNotSupported" -> "разрешение видео"
        "VideoBitDepthNotSupported" -> "глубина цвета"
        "VideoFramerateNotSupported" -> "частота кадров"
        "RefFramesNotSupported" -> "опорные кадры"
        "AnamorphicVideoNotSupported" -> "анаморфное видео"
        "InterlacedVideoNotSupported" -> "чересстрочное видео"
        "AudioChannelsNotSupported" -> "число каналов звука"
        "AudioProfileNotSupported" -> "профиль звука"
        "AudioSampleRateNotSupported" -> "частота дискретизации"
        "AudioBitDepthNotSupported" -> "разрядность звука"
        "ContainerBitrateExceedsLimit", "VideoBitrateNotSupported", "AudioBitrateNotSupported" -> "ограничение битрейта"
        "VideoRangeTypeNotSupported" -> "HDR не поддерживается"
        "DirectPlayError" -> "ошибка прямого воспроизведения"
        "UnknownVideoStreamInfo", "UnknownAudioStreamInfo" -> "неизвестный формат потока"
        else -> null
    }
}

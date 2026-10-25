package ru.stenfredd.sleepyowl.ui.details

import ru.stenfredd.sleepyowl.core.data.model.AudioTrack
import ru.stenfredd.sleepyowl.core.data.model.MediaInfo
import ru.stenfredd.sleepyowl.core.data.model.SubtitleTrack
import ru.stenfredd.sleepyowl.core.data.model.VideoRange
import java.util.Locale

/** How the item page names languages, codecs and formats: "Русский", "TrueHD Atmos 7.1", "HEVC 2160p HDR10 · 62.4 ГБ". */
object MediaLabels {
    private val russian = Locale.forLanguageTag("ru")

    // The server gives ISO 639-2 codes; Locale knows most, but not the bibliographic variants (fre, ger, chi …).
    private val languages = mapOf(
        "rus" to "Русский", "eng" to "English", "ukr" to "Українська", "jpn" to "日本語", "kor" to "한국어",
        "chi" to "中文", "zho" to "中文", "fre" to "Français", "fra" to "Français", "ger" to "Deutsch", "deu" to "Deutsch",
        "spa" to "Español", "ita" to "Italiano", "por" to "Português", "pol" to "Polski", "tur" to "Türkçe",
    )

    /** Language in its own name for the common ones (as the mockup: "Русский", "English"), else in Russian. */
    fun language(code: String?): String? {
        val c = code?.lowercase()?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        languages[c]?.let { return it }
        val name = Locale.forLanguageTag(c).getDisplayLanguage(russian)
        return name.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }?.replaceFirstChar { it.titlecase(russian) } ?: c
    }

    fun audioLabel(track: AudioTrack): String =
        listOfNotNull(language(track.language), track.name).joinToString(" · ").ifEmpty { track.title }

    /** "TrueHD Atmos 7.1" */
    fun audioDetail(track: AudioTrack): String? = listOfNotNull(
        audioCodec(track.codec, track.profile),
        "Atmos".takeIf { track.atmos },
        channels(track.channels),
    ).joinToString(" ").ifEmpty { null }

    fun subtitleLabel(track: SubtitleTrack): String {
        val name = track.name ?: "надписи".takeIf { track.forced }
        return listOfNotNull(language(track.language), name).joinToString(" · ").ifEmpty { track.title }
    }

    fun subtitleDetail(track: SubtitleTrack): String? = when (val codec = track.codec?.lowercase()) {
        null -> null
        "subrip", "srt" -> "SRT"
        "ass", "ssa" -> "ASS"
        "pgssub", "hdmv_pgs_subtitle" -> "PGS"
        "dvdsub", "dvd_subtitle", "vobsub" -> "VOBSUB"
        "webvtt", "vtt" -> "VTT"
        "mov_text" -> "TX3G"
        else -> codec.uppercase()
    }

    /** "4K", "HDR10", "Atmos": resolution, dynamic range and the best sound of the file. */
    fun badges(media: MediaInfo?): List<String> {
        media ?: return emptyList()
        val video = media.video
        val width = video?.width ?: 0
        val height = video?.height ?: 0
        return buildList {
            when {
                width >= 3200 || height >= 2000 -> add("4K")
                width >= 1800 || height >= 1000 -> add("1080p")
                width >= 1200 || height >= 700 -> add("720p")
            }
            range(video?.range)?.let(::add)
            val audio = media.audio
            when {
                audio.any { it.atmos } -> add("Atmos")
                audio.any { (it.channels ?: 0) >= 8 } -> add("7.1")
                audio.any { (it.channels ?: 0) >= 6 } -> add("5.1")
            }
        }
    }

    /** "HEVC 2160p HDR10 · 62.4 ГБ" */
    fun fileLine(media: MediaInfo?): String? {
        media ?: return null
        val video = media.video
        val format = listOfNotNull(
            videoCodec(video?.codec),
            video?.height?.let { "${it}p" },
            range(video?.range)?.let { if (it == "DV" && video?.dvProfile != null) "DV P${video.dvProfile}" else it },
        ).joinToString(" ")
        val size = media.sizeBytes?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f ГБ", it / 1e9) }
        return listOfNotNull(format.ifEmpty { null }, size).joinToString(" · ").ifEmpty { null }
    }

    /** "2 ч 14 мин" */
    fun runtime(ms: Long?): String? {
        val minutes = ((ms ?: return null) / 60_000).toInt().takeIf { it > 0 } ?: return null
        return if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else "$minutes мин"
    }

    private fun range(range: VideoRange?): String? = when (range) {
        VideoRange.HDR10 -> "HDR10"
        VideoRange.HDR10Plus -> "HDR10+"
        VideoRange.HLG -> "HLG"
        VideoRange.DolbyVision -> "DV"
        VideoRange.Other -> "HDR"
        VideoRange.SDR, null -> null
    }

    fun videoCodec(codec: String?): String? = when (codec?.lowercase()) {
        null -> null
        "hevc", "h265" -> "HEVC"
        "h264", "avc" -> "H.264"
        "mpeg2video" -> "MPEG-2"
        "mpeg4" -> "MPEG-4"
        "vc1" -> "VC-1"
        else -> codec.uppercase()
    }

    private fun audioCodec(codec: String?, profile: String?): String? = when (codec?.lowercase()) {
        null -> null
        "ac3" -> "AC3"
        "eac3" -> "E-AC3"
        "truehd" -> "TrueHD"
        "dts" -> when {
            profile == null -> "DTS"
            profile.contains("DTS:X") -> "DTS:X"
            profile.contains("MA") -> "DTS-HD MA"
            profile.contains("HRA") || profile.contains("HD") -> "DTS-HD"
            else -> "DTS"
        }
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        "pcm_s16le", "pcm_s24le", "pcm_bluray" -> "PCM"
        else -> codec.uppercase()
    }

    private fun channels(n: Int?): String? = when (n) {
        null, 0 -> null
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${n}ch"
    }
}

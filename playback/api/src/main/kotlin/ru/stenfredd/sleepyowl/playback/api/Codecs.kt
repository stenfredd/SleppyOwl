package ru.stenfredd.sleepyowl.playback.api

import android.media.AudioFormat

/** Mapping between Jellyfin (ffprobe) codec names and Android MIME types / encodings. */
object Codecs {
    fun videoMime(codec: String?): String? = when (codec?.lowercase()) {
        "h264", "avc" -> "video/avc"
        "hevc", "h265" -> "video/hevc"
        "vp8" -> "video/x-vnd.on2.vp8"
        "vp9" -> "video/x-vnd.on2.vp9"
        "av1" -> "video/av01"
        "mpeg2video", "mpeg1video" -> "video/mpeg2"
        "mpeg4" -> "video/mp4v-es"
        "h263" -> "video/3gpp"
        "vc1" -> "video/wvc1"
        else -> null
    }

    fun audioMime(codec: String?): String? = when (codec?.lowercase()) {
        "aac" -> "audio/mp4a-latm"
        "mp3" -> "audio/mpeg"
        "mp2" -> "audio/mpeg-L2"
        "ac3" -> "audio/ac3"
        "eac3" -> "audio/eac3"
        "truehd" -> "audio/true-hd"
        "dts" -> "audio/vnd.dts"
        "flac" -> "audio/flac"
        "opus" -> "audio/opus"
        "vorbis" -> "audio/vorbis"
        "alac" -> "audio/alac"
        "pcm_s16le", "pcm_s24le", "pcm_s16be", "pcm_s24be" -> "audio/raw"
        else -> null
    }

    /** Encoding that can be passed through to the audio output, for compressed surround formats. */
    fun passthroughEncoding(codec: String?, profile: String?): Int? = when (codec?.lowercase()) {
        "ac3" -> AudioFormat.ENCODING_AC3
        "eac3" -> if (profile?.contains("Atmos", true) == true) AudioFormat.ENCODING_E_AC3_JOC else AudioFormat.ENCODING_E_AC3
        "truehd" -> AudioFormat.ENCODING_DOLBY_TRUEHD
        "dts" -> if (profile?.contains("HD", true) == true || profile?.contains("X", false) == true) {
            AudioFormat.ENCODING_DTS_HD
        } else {
            AudioFormat.ENCODING_DTS
        }
        else -> null
    }

    fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "E-AC3"
        AudioFormat.ENCODING_E_AC3_JOC -> "E-AC3 JOC"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_AC4 -> "AC4"
        AudioFormat.ENCODING_DOLBY_MAT -> "Dolby MAT"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        else -> "enc#$encoding"
    }
}

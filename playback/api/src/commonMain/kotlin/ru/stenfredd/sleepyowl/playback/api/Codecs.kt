package ru.stenfredd.sleepyowl.playback.api

/** Mapping between Jellyfin (ffprobe) codec names and MIME types; Android audio encodings are in `Codecs.android.kt`. */
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
}

package ru.stenfredd.sleepyowl.playback.api

import android.media.AudioFormat

/** Encoding that can be passed through to the audio output, for compressed surround formats. */
fun Codecs.passthroughEncoding(codec: String?, profile: String?): Int? = when (codec?.lowercase()) {
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

fun Codecs.encodingName(encoding: Int): String = when (encoding) {
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

package ru.stenfredd.movies.playback.api

/** Snapshot of what the device can decode and output. Probed once by `:playback:core`. */
data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val sdkInt: Int,
    val abis: List<String>,
    val videoDecoders: List<DecoderInfo>,
    val audioDecoders: List<DecoderInfo>,
    /** `Display.HdrCapabilities.HDR_TYPE_*`. */
    val displayHdrTypes: Set<Int>,
    val displayModes: List<String>,
    /** `AudioFormat.ENCODING_*` that can be sent to the output undecoded. */
    val passthroughEncodings: Set<Int>,
) {
    fun hardwareVideoDecoders(mime: String) =
        videoDecoders.filter { it.hardware && it.mime.equals(mime, ignoreCase = true) }

    fun hasHardwareVideo(mime: String, width: Int? = null, height: Int? = null) =
        hardwareVideoDecoders(mime).any { d ->
            (width == null || height == null) || (width <= d.maxWidth && height <= d.maxHeight) ||
                (height <= d.maxWidth && width <= d.maxHeight)
        }

    fun hasHardwareProfile(mime: String, profile: Int) =
        hardwareVideoDecoders(mime).any { profile in it.profiles }

    fun hasAudioDecoder(mime: String) = audioDecoders.any { it.mime.equals(mime, ignoreCase = true) }

    fun displaySupportsHdr(type: Int) = type in displayHdrTypes
}

data class DecoderInfo(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    /** `MediaCodecInfo.CodecProfileLevel` profile constants. */
    val profiles: Set<Int>,
)

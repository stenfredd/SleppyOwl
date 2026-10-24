package ru.stenfredd.sleepyowl.playback.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.stenfredd.sleepyowl.playback.api.DecoderInfo
import ru.stenfredd.sleepyowl.playback.api.DeviceCapabilities
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilitiesProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val capabilities: DeviceCapabilities by lazy { probe().also { Timber.i("Device capabilities: %s", it) } }

    private fun probe(): DeviceCapabilities {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val video = mutableListOf<DecoderInfo>()
        val audio = mutableListOf<DecoderInfo>()
        for (info in codecs) for (type in info.supportedTypes) {
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
            val vc = caps.videoCapabilities
            val decoder = DecoderInfo(
                name = info.name,
                mime = type,
                hardware = isHardware(info),
                maxWidth = vc?.supportedWidths?.upper ?: 0,
                maxHeight = vc?.supportedHeights?.upper ?: 0,
                profiles = caps.profileLevels.map { it.profile }.toSet(),
            )
            if (type.startsWith("video/")) video += decoder else if (type.startsWith("audio/")) audio += decoder
        }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val hdrTypes = if (Build.VERSION.SDK_INT >= 34) {
            display.mode.supportedHdrTypes.toSet()
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        }
        return DeviceCapabilities(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            videoDecoders = video,
            audioDecoders = audio,
            displayHdrTypes = hdrTypes,
            displayModes = display.supportedModes.map { "${it.physicalWidth}x${it.physicalHeight}@${"%.3f".format(it.refreshRate)}" },
            passthroughEncodings = probePassthrough(),
        )
    }

    private fun isHardware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        info.isHardwareAccelerated
    } else {
        val n = info.name.lowercase()
        !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("sw"))
    }

    private fun probePassthrough(): Set<Int> {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val candidates = listOf(
            AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC,
            AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.ENCODING_DTS, AudioFormat.ENCODING_DTS_HD,
            AudioFormat.ENCODING_AC4, AudioFormat.ENCODING_DOLBY_MAT,
        )
        return candidates.filter { encoding ->
            listOf(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO)
                .any { mask ->
                    runCatching {
                        @Suppress("DEPRECATION")
                        AudioTrack.isDirectPlaybackSupported(
                            AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000).setChannelMask(mask).build(),
                            attributes,
                        )
                    }.getOrDefault(false)
                }
        }.toSet()
    }
}

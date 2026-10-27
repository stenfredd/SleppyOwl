package ru.stenfredd.sleepyowl.playback.mpv

import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile
import org.koin.dsl.bind
import org.koin.dsl.module
import ru.stenfredd.sleepyowl.playback.api.DeviceCapabilities
import ru.stenfredd.sleepyowl.playback.api.EngineId
import ru.stenfredd.sleepyowl.playback.api.EngineProvider
import ru.stenfredd.sleepyowl.playback.api.PlaybackEngine
import ru.stenfredd.sleepyowl.playback.api.StreamSelection
import ru.stenfredd.sleepyowl.playback.api.Verdict
import ru.stenfredd.sleepyowl.playback.api.videoStream
import ru.stenfredd.sleepyowl.playback.core.DeviceCapabilitiesProbe

/** Koin: the desktop's only engine and the desktop's device probe. */
val mpvEngineModule = module {
    single { MpvEngineProvider() } bind EngineProvider::class
    single<DeviceCapabilitiesProbe> { PcCapabilitiesProbe() }
}

/**
 * mpv plays everything ffmpeg demuxes and decodes, in hardware where the GPU can (NVDEC, VAAPI), otherwise on the CPU,
 * which a PC can afford. HDR and Dolby Vision are tone-mapped to SDR (the desktop has no HDR output, see docs/desktop.md).
 */
class MpvEngineProvider : EngineProvider {
    override val id = ID
    override val displayName = "mpv"
    override val priority = 0

    override fun evaluate(source: MediaSourceInfo, selection: StreamSelection, device: DeviceCapabilities): Verdict {
        val range = source.videoStream?.videoRangeType?.serialName
        val notes = listOfNotNull(range?.takeIf { it != "SDR" && it != "Unknown" }?.let { "$it → SDR" })
        return Verdict.Direct(notes)
    }

    override fun deviceProfile(device: DeviceCapabilities): DeviceProfile = buildDeviceProfile {
        name = "SleepyOwl / mpv"
        maxStreamingBitrate = MAX_BITRATE
        maxStaticBitrate = MAX_BITRATE
        directPlayProfile { type = DlnaProfileType.VIDEO }
        directPlayProfile { type = DlnaProfileType.AUDIO }
        transcodingProfile {
            type = DlnaProfileType.VIDEO
            context = EncodingContext.STREAMING
            protocol = MediaStreamProtocol.HLS
            container = "ts"
            videoCodec("h264", "hevc")
            audioCodec("aac", "ac3", "eac3", "mp3", "opus", "flac")
            minSegments = 1
        }
        for (format in listOf("srt", "subrip", "ass", "ssa", "pgssub", "dvdsub", "dvbsub", "mov_text", "vtt", "webvtt", "sub", "sup", "idx")) {
            subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
        }
        for (format in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "sub", "sup", "pgssub")) {
            subtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)
        }
    }

    override fun create(device: DeviceCapabilities): PlaybackEngine = MpvEngine()

    companion object {
        val ID = EngineId("mpv")
        private const val MAX_BITRATE = 400_000_000
    }
}

package ru.stenfredd.movies.playback.exo

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.view.Display.HdrCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile
import ru.stenfredd.movies.playback.api.Codecs
import ru.stenfredd.movies.playback.api.DeviceCapabilities
import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.EngineProvider
import ru.stenfredd.movies.playback.api.Loss
import ru.stenfredd.movies.playback.api.PlaybackEngine
import ru.stenfredd.movies.playback.api.StreamSelection
import ru.stenfredd.movies.playback.api.Verdict
import ru.stenfredd.movies.playback.api.stream
import ru.stenfredd.movies.playback.api.videoStream
import javax.inject.Inject

class ExoEngineProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : EngineProvider {
    override val id = ID
    override val displayName = "ExoPlayer"
    override val priority = 0

    override fun evaluate(source: MediaSourceInfo, selection: StreamSelection, device: DeviceCapabilities): Verdict {
        val no = mutableListOf<String>()
        val losses = mutableSetOf<Loss>()
        val notes = mutableListOf<String>()

        val containers = source.container.orEmpty().lowercase().split(',').map(String::trim)
        if (containers.none { it in CONTAINERS }) no += "контейнер ${source.container}"

        source.videoStream?.let { v ->
            val mime = Codecs.videoMime(v.codec)
            when {
                mime == null -> no += "видеокодек ${v.codec}"
                v.codec.equals("h264", true) && (v.bitDepth ?: 8) > 8 &&
                    !device.hasHardwareProfile(mime, CodecProfileLevel.AVCProfileHigh10) -> no += "H.264 10-bit без аппаратного декодера"
                !device.hasHardwareVideo(mime, v.width, v.height) ->
                    if (device.videoDecoders.any { it.mime.equals(mime, true) }) losses += Loss.SOFTWARE_DECODE_HEAVY
                    else no += "нет декодера ${v.codec} ${v.width}x${v.height}"
            }
            evaluateRange(v.videoRangeType, v.dvProfile, device, no, losses, notes)
            if (v.isInterlaced) losses += Loss.NO_DEINTERLACE
        }

        source.stream(selection.audioStreamIndex)?.let { a ->
            val mime = Codecs.audioMime(a.codec)
            val passthrough = Codecs.passthroughEncoding(a.codec, a.profile)?.let { it in device.passthroughEncodings } == true
            if (!passthrough && (mime == null || !device.hasAudioDecoder(mime))) no += "аудио ${a.codec}"
            if (passthrough) notes += "${a.codec}: passthrough"
        }

        source.stream(selection.subtitleStreamIndex)?.let { s ->
            when (s.codec?.lowercase()) {
                "ass", "ssa" -> losses += Loss.ASS_SIMPLIFIED
                "pgssub", "dvdsub", "dvbsub" -> if (s.isExternal) no += "внешние ${s.codec}"
            }
        }

        return when {
            no.isNotEmpty() -> Verdict.No(no)
            losses.isNotEmpty() -> Verdict.Degraded(losses, notes)
            else -> Verdict.Direct(notes)
        }
    }

    private fun evaluateRange(
        range: VideoRangeType,
        dvProfile: Int?,
        device: DeviceCapabilities,
        no: MutableList<String>,
        losses: MutableSet<Loss>,
        notes: MutableList<String>,
    ) {
        val dvCapable = dvProfileConstant(dvProfile)?.let { device.hasHardwareProfile(DV_MIME, it) } == true &&
            device.displaySupportsHdr(HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        // Profiles with a compatible base layer are decoded as that base layer unless the DV decoder is enabled.
        val dv = dvCapable && (USE_DOLBY_VISION_DECODER || range == VideoRangeType.DOVI)
        fun base(type: Int) {
            if (!device.displaySupportsHdr(type)) losses += Loss.HDR_LOST
        }
        when (range) {
            VideoRangeType.DOVI -> if (!dv) no += "Dolby Vision P$dvProfile без DV-декодера"
            VideoRangeType.DOVI_WITH_EL, VideoRangeType.DOVI_WITH_ELHDR10_PLUS ->
                if (!dv) no += "Dolby Vision P7 без DV-декодера (media3 не откатывается на HEVC)"
            VideoRangeType.DOVI_WITH_HDR10, VideoRangeType.DOVI_WITH_HLG, VideoRangeType.DOVI_WITH_SDR -> if (!dv) {
                losses += Loss.DV_TO_BASE_LAYER
                if (range != VideoRangeType.DOVI_WITH_SDR) base(if (range == VideoRangeType.DOVI_WITH_HLG) HdrCapabilities.HDR_TYPE_HLG else HdrCapabilities.HDR_TYPE_HDR10)
            }
            VideoRangeType.DOVI_WITH_HDR10_PLUS -> {
                if (!dv) losses += Loss.DV_TO_BASE_LAYER
                notes += "DV+HDR10+: известный баг media3 на MediaTek (#1895)"
            }
            VideoRangeType.HDR10 -> base(HdrCapabilities.HDR_TYPE_HDR10)
            VideoRangeType.HDR10_PLUS -> base(HdrCapabilities.HDR_TYPE_HDR10)
            VideoRangeType.HLG -> base(HdrCapabilities.HDR_TYPE_HLG)
            else -> Unit
        }
    }

    override fun deviceProfile(device: DeviceCapabilities): DeviceProfile {
        val videoCodecs = VIDEO_CODECS.filter { c -> Codecs.videoMime(c)?.let { m -> device.videoDecoders.any { it.mime.equals(m, true) } } == true }
        val audioCodecs = AUDIO_CODECS.filter { c ->
            Codecs.audioMime(c)?.let(device::hasAudioDecoder) == true ||
                Codecs.passthroughEncoding(c, null)?.let { it in device.passthroughEncodings } == true
        }
        val hasDv = device.hardwareVideoDecoders(DV_MIME).isNotEmpty() && device.displaySupportsHdr(HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        val hasP7 = device.hasHardwareProfile(DV_MIME, CodecProfileLevel.DolbyVisionProfileDvheDtb)
        val ranges = buildList {
            add(VideoRangeType.SDR)
            add(VideoRangeType.HDR10)
            add(VideoRangeType.HDR10_PLUS)
            add(VideoRangeType.HLG)
            add(VideoRangeType.DOVI_WITH_HDR10)
            add(VideoRangeType.DOVI_WITH_HLG)
            add(VideoRangeType.DOVI_WITH_SDR)
            add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
            if (hasDv) add(VideoRangeType.DOVI)
            if (hasDv && hasP7) {
                add(VideoRangeType.DOVI_WITH_EL)
                add(VideoRangeType.DOVI_WITH_ELHDR10_PLUS)
            }
        }.map { it.serialName }
        val avcHigh10 = device.hasHardwareProfile("video/avc", CodecProfileLevel.AVCProfileHigh10)

        return buildDeviceProfile {
            name = "Movies / ExoPlayer"
            maxStreamingBitrate = MAX_BITRATE
            maxStaticBitrate = MAX_BITRATE
            directPlayProfile {
                type = DlnaProfileType.VIDEO
                container(*CONTAINERS.toTypedArray())
                videoCodec(*videoCodecs.toTypedArray())
                audioCodec(*audioCodecs.toTypedArray())
            }
            if (!avcHigh10) {
                codecProfile {
                    type = CodecType.VIDEO
                    codec = "h264"
                    conditions { ProfileConditionValue.VIDEO_BIT_DEPTH lowerThanOrEquals 8 }
                }
            }
            for (codec in listOf("hevc", "av1", "vp9")) {
                codecProfile {
                    type = CodecType.VIDEO
                    this.codec = codec
                    conditions { ProfileConditionValue.VIDEO_RANGE_TYPE inCollection ranges }
                }
            }
            transcodingProfile {
                type = DlnaProfileType.VIDEO
                context = EncodingContext.STREAMING
                protocol = MediaStreamProtocol.HLS
                container = "mp4"
                videoCodec("hevc", "h264")
                audioCodec("aac", "ac3", "eac3")
                minSegments = 1
                breakOnNonKeyFrames = false
            }
            for (format in listOf("srt", "subrip", "ass", "ssa", "pgssub", "dvdsub", "dvbsub", "mov_text", "vtt", "webvtt")) {
                subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
            }
            for (format in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml")) {
                subtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)
            }
        }
    }

    override fun create(device: DeviceCapabilities): PlaybackEngine = ExoEngine(context)

    companion object {
        val ID = EngineId("exo")
        private const val DV_MIME = "video/dolby-vision"

        /**
         * On the Xiaomi MT9689 the DV decoder (`c2.mtk.dvhe.st`) plays, but the picture is visibly duller than the
         * same file's HDR10/HDR10+ base layer (user comparison against VLC, 2026-10-12): the TV apparently does not
         * switch into Dolby Vision mode. Until that is solved (tunneling? see NEXT_SESSION.md §4.3) profiles with a
         * base layer (8.x, 9, 10) are played as HEVC/AVC/AV1 HDR. Profile 5 always needs the DV decoder.
         */
        const val USE_DOLBY_VISION_DECODER = false
        private const val MAX_BITRATE = 400_000_000
        private val CONTAINERS = listOf("mkv", "webm", "mp4", "m4v", "mov", "ts", "mpegts", "avi", "mpeg", "ogg", "flv", "3gp")
        private val VIDEO_CODECS = listOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg2video", "mpeg4")
        private val AUDIO_CODECS = listOf("aac", "mp3", "mp2", "ac3", "eac3", "truehd", "dts", "flac", "opus", "vorbis", "alac", "pcm_s16le", "pcm_s24le")

        private fun dvProfileConstant(profile: Int?): Int? = when (profile) {
            4 -> CodecProfileLevel.DolbyVisionProfileDvheDtr
            5 -> CodecProfileLevel.DolbyVisionProfileDvheStn
            7 -> CodecProfileLevel.DolbyVisionProfileDvheDtb
            8 -> CodecProfileLevel.DolbyVisionProfileDvheSt
            9 -> CodecProfileLevel.DolbyVisionProfileDvavSe
            10 -> CodecProfileLevel.DolbyVisionProfileDvav110
            else -> null
        }
    }
}

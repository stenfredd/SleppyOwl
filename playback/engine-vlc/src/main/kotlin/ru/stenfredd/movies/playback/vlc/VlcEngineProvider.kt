package ru.stenfredd.movies.playback.vlc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRange
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
import ru.stenfredd.movies.playback.api.videoStream
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VlcEngineProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : EngineProvider {
    override val id = ID
    override val displayName = "VLC"
    override val priority = 10

    /** Shared by every [VlcEngine]'s proxy so connections to the server are pooled across playbacks. */
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun evaluate(source: MediaSourceInfo, selection: StreamSelection, device: DeviceCapabilities): Verdict {
        val losses = mutableSetOf<Loss>()
        val notes = mutableListOf<String>()
        source.videoStream?.let { v ->
            val mime = Codecs.videoMime(v.codec)
            val hardware = mime != null && device.hasHardwareVideo(mime, v.width, v.height) &&
                !(v.codec.equals("h264", true) && (v.bitDepth ?: 8) > 8)
            // Hardware decoding is only used by this engine for >1080p (see VlcEngine), so treat the rest as software.
            if (!hardware || (v.width ?: 0) * (v.height ?: 0) <= 1920 * 1088) {
                val pixels = (v.width ?: 0) * (v.height ?: 0)
                losses += if (pixels > 1920 * 1088 || (v.bitDepth ?: 8) > 8) Loss.SOFTWARE_DECODE_HEAVY else Loss.SOFTWARE_DECODE
                notes += v.codec.orEmpty()
            }
            when {
                v.videoRangeType.serialName.startsWith("DOVI") -> losses += Loss.DV_TO_BASE_LAYER
                v.videoRange == VideoRange.HDR -> losses += if (hardware) Loss.HDR_UNVERIFIED else Loss.HDR_LOST
            }
            if (v.videoRangeType == VideoRangeType.DOVI) losses += Loss.HDR_LOST
        }
        return if (losses.isEmpty()) Verdict.Direct(notes) else Verdict.Degraded(losses, notes)
    }

    override fun deviceProfile(device: DeviceCapabilities): DeviceProfile = buildDeviceProfile {
        name = "Movies / VLC"
        maxStreamingBitrate = MAX_BITRATE
        maxStaticBitrate = MAX_BITRATE
        // libVLC demuxes and decodes practically anything: no container/codec restrictions.
        directPlayProfile { type = DlnaProfileType.VIDEO }
        directPlayProfile { type = DlnaProfileType.AUDIO }
        transcodingProfile {
            type = DlnaProfileType.VIDEO
            context = EncodingContext.STREAMING
            protocol = MediaStreamProtocol.HLS
            container = "ts"
            videoCodec("h264", "hevc")
            audioCodec("aac", "ac3", "eac3", "mp3")
            minSegments = 1
        }
        for (format in listOf("srt", "subrip", "ass", "ssa", "pgssub", "dvdsub", "dvbsub", "mov_text", "vtt", "webvtt", "sub", "sup", "idx")) {
            subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
        }
        for (format in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "sub", "sup")) {
            subtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)
        }
    }

    override fun create(device: DeviceCapabilities): PlaybackEngine = VlcEngine(context, httpClient, device)

    companion object {
        val ID = EngineId("vlc")
        private const val MAX_BITRATE = 400_000_000
    }
}

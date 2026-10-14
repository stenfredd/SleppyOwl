package ru.stenfredd.movies.playback.exo

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import timber.log.Timber
import java.io.EOFException
import java.nio.ByteBuffer

/**
 * Re-frames AC3/E-AC3/DTS audio from [androidx.media3.extractor.avi.AviExtractor].
 *
 * AviExtractor hands every AVI chunk to the decoder as one sample, but muxers cut compressed audio into chunks of
 * their own size (1280 bytes there) that don't match the codec frames (1024 bytes for that AC3). The audio sink
 * then takes each sample for one frame (32 ms) while the chunk times advance by 40 ms: endless
 * `UnexpectedDiscontinuityException`, stutter and rebuffering, and through passthrough frames split across samples.
 *
 * AviExtractor's chunk times come from the chunk index (duration × index / chunk count), which drifts when chunk
 * sizes vary (a large preload chunk at the start: 0.5 s jump). The audio is CBR, so only the first chunk after
 * start or seek is used as the anchor, and each frame gets `anchor + bytes since it × frame duration / frame size`.
 * Other tracks pass straight through.
 */
@UnstableApi
internal class AviAudioFramingExtractor(private val delegate: Extractor) : Extractor {
    private val framers = mutableListOf<FramingTrackOutput>()

    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)
    override fun getUnderlyingImplementation() = delegate
    override fun release() = delegate.release()

    override fun init(output: ExtractorOutput) = delegate.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = output.track(id, type)
            return if (type == C.TRACK_TYPE_AUDIO) FramingTrackOutput(track).also(framers::add) else track
        }
        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
    })

    override fun seek(position: Long, timeUs: Long) {
        framers.forEach(FramingTrackOutput::reset)
        delegate.seek(position, timeUs)
    }

    private class FramingTrackOutput(private val out: TrackOutput) : TrackOutput {
        private enum class Codec { AC3, DTS }

        private var codec: Codec? = null
        private var sampleRate = 0
        /** Bytes of the current chunk followed by the unframed rest of the previous ones. */
        private var pending = ByteArray(64 * 1024)
        private var pendingLength = 0
        /** Stream byte position of pending[0]. */
        private var pendingStart = 0L
        /** Time of the first chunk after start or seek, which begins at stream byte 0. */
        private var anchorTimeUs = C.TIME_UNSET
        private var resyncs = 0L

        fun reset() {
            pendingLength = 0
            pendingStart = 0
            anchorTimeUs = C.TIME_UNSET
        }

        override fun format(format: Format) {
            codec = when (format.sampleMimeType) {
                MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3 -> Codec.AC3
                MimeTypes.AUDIO_DTS -> Codec.DTS
                else -> null
            }
            sampleRate = format.sampleRate
            if (sampleRate <= 0) codec = null
            out.format(format)
        }

        override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (codec == null) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
            ensureCapacity(length)
            val read = input.read(pending, pendingLength, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return read
                throw EOFException()
            }
            pendingLength += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (codec == null) return out.sampleData(data, length, sampleDataPart)
            ensureCapacity(length)
            data.readBytes(pending, pendingLength, length)
            pendingLength += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val codec = codec ?: return out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            if (anchorTimeUs == C.TIME_UNSET) anchorTimeUs = timeUs
            var i = 0
            while (pendingLength - i >= HEADER_BYTES) {
                val frameSize = frameSize(codec, i)
                if (frameSize <= 0) {
                    if (resyncs++ % 1000 == 0L) Timber.w("AVI %s: no sync word, skipping a byte (%d)", codec, resyncs)
                    i++
                    continue
                }
                if (pendingLength - i < frameSize) break
                val frameTimeUs = anchorTimeUs + (pendingStart + i) * frameDurationUs(codec, i) / frameSize
                out.sampleData(ParsableByteArray(pending.copyOfRange(i, i + frameSize)), frameSize)
                out.sampleMetadata(frameTimeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null)
                i += frameSize
            }
            System.arraycopy(pending, i, pending, 0, pendingLength - i)
            pendingLength -= i
            pendingStart += i
        }

        private fun frameSize(codec: Codec, at: Int): Int = when (codec) {
            Codec.AC3 -> if (pending[at] == 0x0B.toByte() && pending[at + 1] == 0x77.toByte()) {
                Ac3Util.parseAc3SyncframeSize(pending.copyOfRange(at, at + HEADER_BYTES))
            } else {
                C.LENGTH_UNSET
            }
            Codec.DTS -> if (ByteBuffer.wrap(pending, at, 4).int in DTS_SYNC_WORDS) {
                DtsUtil.getDtsFrameSize(pending.copyOfRange(at, at + HEADER_BYTES))
            } else {
                C.LENGTH_UNSET
            }
        }

        private fun frameDurationUs(codec: Codec, at: Int): Long {
            val samples = when (codec) {
                Codec.AC3 -> Ac3Util.parseAc3SyncframeAudioSampleCount(ByteBuffer.wrap(pending, at, HEADER_BYTES).slice())
                Codec.DTS -> DtsUtil.parseDtsAudioSampleCount(pending.copyOfRange(at, at + HEADER_BYTES))
            }
            return samples * C.MICROS_PER_SECOND / sampleRate
        }

        private fun ensureCapacity(extra: Int) {
            if (pendingLength + extra > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLength + extra))
        }

        private companion object {
            /** Enough for the AC3/E-AC3 and DTS (incl. 14-bit) header fields the size/duration parsers read. */
            const val HEADER_BYTES = 16
            /** Core sync word: big-endian, little-endian, 14-bit big-endian, 14-bit little-endian. */
            val DTS_SYNC_WORDS = setOf(0x7FFE8001, 0xFE7F0180.toInt(), 0x1FFFE800, 0xFF1F00E8.toInt())
        }
    }
}

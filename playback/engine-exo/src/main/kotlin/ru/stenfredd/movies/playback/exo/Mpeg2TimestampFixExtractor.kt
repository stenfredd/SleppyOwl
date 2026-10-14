package ru.stenfredd.movies.playback.exo

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import timber.log.Timber
import java.io.EOFException
import kotlin.math.abs

/**
 * Fixes MPEG-2 video timestamps from [androidx.media3.extractor.ts.PsExtractor] (DVD rips).
 *
 * media3's H262Reader gives a picture the PTS of its PES packet, or, if the packet has none, the previous picture's
 * time plus one frame. That is done in decode order, which is wrong with B-frames: in DVD VOBs the P-pictures often
 * start mid-packet and get "previous + 40 ms", colliding with the B-pictures that follow (I .28 → P .32, B .32,
 * B .36 → P .40 ...), and the decoder outputs duplicate and backwards timestamps.
 *
 * Worse, PsExtractor passes 0 (not "unset") as the time of a PES packet without a PTS, so in the same VOBs 14 of
 * 15 pictures of every GOP came out at time 0: the renderer skipped them as before the start position, and the
 * picture ran at ~1.5 fps (one keyframe per GOP) while the position advanced normally.
 *
 * Every MPEG-2 picture header carries its display index within the GOP (temporal_reference), so such times
 * (extrapolated, or more than a second off) are replaced by `keyframe time + (temporal_reference - keyframe's) ×
 * frame duration`. Plausible times are kept, which also keeps pulldown material (uneven real PTS) as the file has it.
 */
@UnstableApi
internal class Mpeg2TimestampFixExtractor(private val delegate: Extractor) : Extractor {
    private val fixers = mutableListOf<FixingTrackOutput>()

    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)
    override fun getUnderlyingImplementation() = delegate
    override fun release() = delegate.release()

    override fun init(output: ExtractorOutput) = delegate.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = output.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) FixingTrackOutput(track).also(fixers::add) else track
        }
        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
    })

    override fun seek(position: Long, timeUs: Long) {
        fixers.forEach(FixingTrackOutput::reset)
        delegate.seek(position, timeUs)
    }

    private class FixingTrackOutput(private val out: TrackOutput) : TrackOutput {
        private var frameDurationUs = 0L
        /** Sample bytes written since the end of the last emitted sample. */
        private var pending = ByteArray(256 * 1024)
        private var pendingLength = 0
        private var previousTimeUs = C.TIME_UNSET
        private var anchorTimeUs = C.TIME_UNSET
        private var anchorReference = 0
        private var fixedCount = 0L

        fun reset() {
            pendingLength = 0
            previousTimeUs = C.TIME_UNSET
            anchorTimeUs = C.TIME_UNSET
        }

        override fun format(format: Format) {
            // H262Reader keeps the frame rate to itself; read frame_rate_code from the sequence header (CSD) again.
            val code = format.initializationData.firstOrNull()?.takeIf { it.size > 7 }?.let { (it[7].toInt() and 0x0F) - 1 } ?: -1
            frameDurationUs = if (format.sampleMimeType == MimeTypes.VIDEO_MPEG2 && code in FRAME_RATES.indices) {
                (C.MICROS_PER_SECOND / FRAME_RATES[code]).toLong()
            } else {
                0
            }
            out.format(format)
        }

        override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (frameDurationUs == 0L) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
            ensureCapacity(length)
            val read = input.read(pending, pendingLength, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return read
                throw EOFException()
            }
            out.sampleData(ParsableByteArray(pending.copyOfRange(pendingLength, pendingLength + read)), read, sampleDataPart)
            pendingLength += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (frameDurationUs != 0L) {
                ensureCapacity(length)
                System.arraycopy(data.data, data.position, pending, pendingLength, length)
                pendingLength += length
            }
            out.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (frameDurationUs == 0L) {
                out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                return
            }
            val start = pendingLength - offset - size
            val reference = if (start >= 0) temporalReference(start, size) else -1
            val extrapolated = previousTimeUs != C.TIME_UNSET && abs(timeUs - (previousTimeUs + frameDurationUs)) <= 1
            previousTimeUs = timeUs
            var fixedTimeUs = timeUs
            when {
                reference < 0 -> Unit
                flags and C.BUFFER_FLAG_KEY_FRAME != 0 -> {
                    anchorTimeUs = timeUs
                    anchorReference = reference
                }
                anchorTimeUs != C.TIME_UNSET && abs(reference - anchorReference) <= MAX_GOP_FRAMES -> {
                    val expectedUs = anchorTimeUs + (reference - anchorReference) * frameDurationUs
                    if (extrapolated || abs(timeUs - expectedUs) > MAX_PTS_DEVIATION_US) fixedTimeUs = expectedUs
                    if (fixedTimeUs != timeUs && fixedCount++ == 0L) {
                        Timber.i("MPEG-2: rebuilding broken timestamps from temporal_reference")
                    }
                }
            }
            // Keep only the bytes written past this sample (they belong to the next one).
            if (start >= 0) {
                val consumed = start + size
                System.arraycopy(pending, consumed, pending, 0, pendingLength - consumed)
                pendingLength -= consumed
            } else {
                pendingLength = 0
            }
            out.sampleMetadata(fixedTimeUs, flags, size, offset, cryptoData)
        }

        /** temporal_reference of the first picture header in the sample, or -1. */
        private fun temporalReference(start: Int, size: Int): Int {
            val end = start + size - 6
            var i = start
            while (i <= end) {
                if (pending[i].toInt() == 0 && pending[i + 1].toInt() == 0 && pending[i + 2].toInt() == 1 && pending[i + 3].toInt() == 0) {
                    return ((pending[i + 4].toInt() and 0xFF) shl 2) or ((pending[i + 5].toInt() and 0xFF) ushr 6)
                }
                i++
            }
            return -1
        }

        private fun ensureCapacity(extra: Int) {
            if (pendingLength + extra > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLength + extra))
        }

        private companion object {
            /** Larger jumps mean a broken header or a GOP we didn't see start: leave the time alone. */
            const val MAX_GOP_FRAMES = 60
            /** A real PTS this far from the GOP's own picture order is not a PTS (PsExtractor's 0, see above). */
            const val MAX_PTS_DEVIATION_US = 1_000_000L
            val FRAME_RATES = doubleArrayOf(24000 / 1001.0, 24.0, 25.0, 30000 / 1001.0, 30.0, 50.0, 60000 / 1001.0, 60.0)
        }
    }
}

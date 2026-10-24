package ru.stenfredd.sleepyowl.playback.exo

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.PsExtractor
import ru.stenfredd.sleepyowl.playback.exo.ps.DvdPsExtractor
import timber.log.Timber

/**
 * [DefaultExtractorsFactory] with fixes: [MatroskaExtractor] wrapped in [SeekHeadChainExtractor], [PsExtractor]
 * replaced by [DvdPsExtractor] wrapped in [Mpeg2TimestampFixExtractor], [AviExtractor] wrapped in
 * [AviAudioFramingExtractor].
 */
@UnstableApi
internal class ExoExtractorsFactory(
    /** Replaces media3's MatroskaExtractor (ASS support), still wrapped in [SeekHeadChainExtractor]. */
    private val matroska: (() -> MatroskaExtractor)? = null,
) : ExtractorsFactory {
    private val inner = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = wrap(inner.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(inner.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory) =
        apply { inner.setSubtitleParserFactory(subtitleParserFactory) }

    @Deprecated("Deprecated in media3")
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean) =
        apply { inner.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled) }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int) =
        apply { inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean) = apply { inner.setParseHagcMetadata(parseHagcMetadata) }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map {
            when (it) {
                is MatroskaExtractor -> SeekHeadChainExtractor(matroska?.invoke() ?: it)
                is PsExtractor -> Mpeg2TimestampFixExtractor(DvdPsExtractor())
                is AviExtractor -> AviAudioFramingExtractor(it)
                else -> it
            }
        }.toTypedArray()
}

/**
 * Lets media3 seek in Matroska files whose first SeekHead only points to a second SeekHead at the end of the file
 * (mkvpropedit/MakeMKV), which in turn points to Cues. [MatroskaExtractor] follows one level only, finds no Cues and
 * reports the file as unseekable.
 *
 * Before handing the input over, reads the second SeekHead and then rewrites the first one's entry
 * "SeekHead at X" into "Cues at Y" on the fly. Both fields keep their byte length, so nothing else in the file moves.
 * Costs two extra range requests, only for such files; everything else passes straight through.
 */
@UnstableApi
internal class SeekHeadChainExtractor(private val delegate: Extractor) : Extractor {
    private enum class Phase { PROBE, READ_TAIL, PASS }

    private class SeekEntry(val id: Long, val position: Long, val idOffset: Long, val idSize: Int, val positionOffset: Long, val positionSize: Int)
    private class Patch(val offset: Long, val bytes: ByteArray)

    private var phase = Phase.PROBE
    private var segmentStart = 0L
    private var chain: SeekEntry? = null
    private var patches: List<Patch> = emptyList()

    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: ExtractorOutput) = delegate.init(output)
    override fun getUnderlyingImplementation() = delegate
    override fun release() = delegate.release()

    override fun seek(position: Long, timeUs: Long) {
        if (phase != Phase.PASS && position != 0L) phase = Phase.PASS
        delegate.seek(position, timeUs)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        when (phase) {
            Phase.PROBE -> {
                phase = Phase.PASS
                if (input.position == 0L) {
                    val target = runCatching { probe(input) }.onFailure { Timber.w(it, "MKV SeekHead probe failed") }.getOrNull()
                    input.resetPeekPosition()
                    if (target != null) {
                        phase = Phase.READ_TAIL
                        seekPosition.position = target
                        return Extractor.RESULT_SEEK
                    }
                }
            }
            Phase.READ_TAIL -> {
                phase = Phase.PASS
                runCatching { readTail(input) }.onFailure { Timber.w(it, "MKV tail SeekHead read failed") }
                seekPosition.position = 0
                return Extractor.RESULT_SEEK
            }
            Phase.PASS -> Unit
        }
        return delegate.read(if (patches.isEmpty()) input else PatchingInput(input, patches), seekPosition)
    }

    /** Returns the position of the second SeekHead if the first one points only there, without consuming input. */
    private fun probe(input: ExtractorInput): Long? {
        val r = EbmlReader(input, peek = true)
        var e = r.element()
        if (e.id != ID_EBML) return null
        r.skip(e.size)
        e = r.element()
        if (e.id != ID_SEGMENT) return null
        segmentStart = r.position
        repeat(MAX_ELEMENTS_BEFORE_SEEK_HEAD) {
            e = r.element()
            when (e.id) {
                ID_SEEK_HEAD -> {
                    val entries = parseSeekHead(r, e.size)
                    if (entries.any { it.id == ID_CUES }) return null
                    val link = entries.firstOrNull { it.id == ID_SEEK_HEAD && it.idSize == 4 } ?: return null
                    chain = link
                    return segmentStart + link.position
                }
                ID_CLUSTER -> return null
                else -> r.skip(e.size)
            }
        }
        return null
    }

    private fun readTail(input: ExtractorInput) {
        val link = chain ?: return
        val r = EbmlReader(input, peek = false)
        val e = r.element()
        if (e.id != ID_SEEK_HEAD) return
        val cues = parseSeekHead(r, e.size).firstOrNull { it.id == ID_CUES } ?: return
        val size = link.positionSize
        if (size < 8 && cues.position ushr (8 * size) != 0L) return
        patches = listOf(
            Patch(link.idOffset, byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)),
            Patch(link.positionOffset, ByteArray(size) { i -> (cues.position ushr (8 * (size - 1 - i))).toByte() }),
        )
        Timber.i("MKV SeekHead chain: Cues at %d via second SeekHead, first SeekHead patched", segmentStart + cues.position)
    }

    private fun parseSeekHead(r: EbmlReader, size: Long): List<SeekEntry> {
        val entries = mutableListOf<SeekEntry>()
        val end = r.position + size
        while (r.position < end) {
            val seek = r.element()
            if (seek.id != ID_SEEK) {
                r.skip(seek.size)
                continue
            }
            val seekEnd = r.position + seek.size
            var id = -1L
            var idOffset = 0L
            var idSize = 0
            var position = -1L
            var positionOffset = 0L
            var positionSize = 0
            while (r.position < seekEnd) {
                val c = r.element()
                when {
                    c.size !in 1..8 -> r.skip(c.size)
                    c.id == ID_SEEK_ID -> { idOffset = r.position; idSize = c.size.toInt(); id = r.uint(idSize) }
                    c.id == ID_SEEK_POSITION -> { positionOffset = r.position; positionSize = c.size.toInt(); position = r.uint(positionSize) }
                    else -> r.skip(c.size)
                }
            }
            if (id >= 0 && position >= 0) entries += SeekEntry(id, position, idOffset, idSize, positionOffset, positionSize)
        }
        return entries
    }

    /** Minimal EBML reading over either the peek or the read position of an [ExtractorInput]. */
    private class EbmlReader(private val input: ExtractorInput, private val peek: Boolean) {
        class Element(val id: Long, val size: Long)

        private val buf = ByteArray(8)
        val position get() = if (peek) input.peekPosition else input.position

        fun element(): Element {
            val id = vint(keepMarker = true)
            val size = vint(keepMarker = false)
            require(size >= 0) { "unknown element size" }
            return Element(id, size)
        }

        fun skip(size: Long) {
            require(size in 0..MAX_SKIP) { "element too large to skip: $size" }
            if (peek) input.advancePeekPosition(size.toInt()) else input.skipFully(size.toInt())
        }

        fun uint(size: Int): Long {
            fill(size)
            return (0 until size).fold(0L) { acc, i -> (acc shl 8) or (buf[i].toLong() and 0xFF) }
        }

        private fun vint(keepMarker: Boolean): Long {
            fill(1)
            val first = buf[0].toInt() and 0xFF
            val length = Integer.numberOfLeadingZeros(first) - 23
            require(length in 1..8) { "bad EBML varint" }
            var value = if (keepMarker) first.toLong() else (first and (0xFF ushr length)).toLong()
            var allOnes = value == (0xFF ushr length).toLong()
            if (length > 1) {
                fill(length - 1)
                for (i in 0 until length - 1) {
                    val b = buf[i].toLong() and 0xFF
                    allOnes = allOnes && b == 0xFFL
                    value = (value shl 8) or b
                }
            }
            return if (!keepMarker && allOnes) -1 else value
        }

        private fun fill(n: Int) {
            if (peek) input.peekFully(buf, 0, n) else input.readFully(buf, 0, n)
        }
    }

    /** Replaces bytes at absolute file offsets in everything read or peeked through it. */
    private class PatchingInput(input: ExtractorInput, private val patches: List<Patch>) : ForwardingExtractorInput(input) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val at = position
            return super.read(buffer, offset, length).also { if (it > 0) apply(at, buffer, offset, it) }
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = position
            return super.readFully(target, offset, length, allowEndOfInput).also { if (it) apply(at, target, offset, length) }
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val at = position
            super.readFully(target, offset, length)
            apply(at, target, offset, length)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            val at = peekPosition
            return super.peek(target, offset, length).also { if (it > 0) apply(at, target, offset, it) }
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val at = peekPosition
            return super.peekFully(target, offset, length, allowEndOfInput).also { if (it) apply(at, target, offset, length) }
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            val at = peekPosition
            super.peekFully(target, offset, length)
            apply(at, target, offset, length)
        }

        private fun apply(at: Long, buffer: ByteArray, offset: Int, length: Int) {
            for (p in patches) {
                for (i in p.bytes.indices) {
                    val abs = p.offset + i
                    if (abs >= at && abs < at + length) buffer[offset + (abs - at).toInt()] = p.bytes[i]
                }
            }
        }
    }

    private companion object {
        const val ID_EBML = 0x1A45DFA3L
        const val ID_SEGMENT = 0x18538067L
        const val ID_SEEK_HEAD = 0x114D9B74L
        const val ID_SEEK = 0x4DBBL
        const val ID_SEEK_ID = 0x53ABL
        const val ID_SEEK_POSITION = 0x53ACL
        const val ID_CUES = 0x1C53BB6BL
        const val ID_CLUSTER = 0x1F43B675L

        /** The SeekHead is normally the first child of the Segment; don't buffer half a file looking for it. */
        const val MAX_ELEMENTS_BEFORE_SEEK_HEAD = 8
        const val MAX_SKIP = 1L shl 20
    }
}

package ru.stenfredd.movies.playback.vlc

import java.io.OutputStream
import java.util.TreeMap

/**
 * Bytes recently fetched from the server, per URL, bounded by [capacity] (oldest blocks evicted first).
 *
 * libVLC's prefetch filter drops data as soon as it is read and fills its 16 MiB buffer ahead, so any small
 * backward seek (the AVI demuxer does them constantly, MKV re-reads its tail) becomes a new request that
 * would download the same ~16 MiB again. With this cache such requests are answered from memory and only
 * the part that was never fetched goes to the server.
 */
internal class RecentBytesCache(private val capacity: Long) {
    /** What is needed to answer a Range request without asking the server. */
    data class Meta(val totalLength: Long, val headers: List<Pair<String, String>>)

    private class Entry {
        var meta: Meta? = null
        val blocks = TreeMap<Long, ByteArray>()
    }

    private val entries = HashMap<String, Entry>()
    /** Insertion order for eviction. */
    private val order = ArrayDeque<Pair<String, Long>>()
    private var size = 0L

    @Synchronized
    fun meta(url: String): Meta? = entries[url]?.meta

    @Synchronized
    fun putMeta(url: String, meta: Meta) {
        val entry = entries.getOrPut(url, ::Entry)
        if (entry.meta?.totalLength != meta.totalLength) {
            size -= entry.blocks.values.sumOf { it.size.toLong() }
            entry.blocks.clear()
            order.removeAll { it.first == url }
        }
        entry.meta = meta
    }

    @Synchronized
    fun put(url: String, offset: Long, data: ByteArray) {
        val entry = entries[url] ?: return
        val floor = entry.blocks.floorEntry(offset)
        // Already covered (two connections fetched the same bytes).
        if (floor != null && floor.key + floor.value.size >= offset + data.size) return
        entry.blocks.put(offset, data)?.let { old ->
            size -= old.size
            order.remove(url to offset)
        }
        size += data.size
        order.addLast(url to offset)
        while (size > capacity) {
            val (oldUrl, oldOffset) = order.removeFirstOrNull() ?: break
            entries[oldUrl]?.blocks?.remove(oldOffset)?.let { size -= it.size }
        }
    }

    /**
     * Writes the cached bytes contiguous from [offset] to [output] (outside the lock, blocks are immutable)
     * and returns how many were written.
     */
    fun writeFrom(url: String, offset: Long, output: OutputStream): Long {
        val run = synchronized(this) { contiguousRun(url, offset) }
        var position = offset
        for ((start, data) in run) {
            val skip = (position - start).toInt()
            output.write(data, skip, data.size - skip)
            position += data.size - skip
        }
        return position - offset
    }

    private fun contiguousRun(url: String, offset: Long): List<Pair<Long, ByteArray>> {
        val blocks = entries[url]?.blocks ?: return emptyList()
        val run = mutableListOf<Pair<Long, ByteArray>>()
        var position = offset
        while (true) {
            val block = blocks.floorEntry(position) ?: break
            val end = block.key + block.value.size
            if (end <= position) break
            run += block.key to block.value
            position = end
        }
        return run
    }
}

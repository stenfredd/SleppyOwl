package ru.stenfredd.sleepyowl.desktop

import ru.stenfredd.sleepyowl.core.common.Log
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * One app per user: the first instance holds a lock on `sleepyowl.lock` and listens on the socket `sleepyowl.sock`
 * next to it; a second start finds the lock taken, asks the first one to show its window and exits.
 */
class SingleInstance private constructor(private val lock: FileLock, private val server: ServerSocketChannel) {
    /** [onShow] runs on a background thread for every later start of the app. */
    fun listen(onShow: () -> Unit) {
        thread(name = "single-instance", isDaemon = true) {
            while (server.isOpen) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                client.use { runCatching { it.read(ByteBuffer.allocate(16)) } }
                Log.i("Started again: showing the window")
                onShow()
            }
        }
    }

    fun release() {
        runCatching { server.close() }
        runCatching { lock.release() }
    }

    companion object {
        /** This process is the app, or null when another one is running (it has been asked to show itself). */
        fun acquire(dir: File): SingleInstance? {
            dir.mkdirs()
            val channel = FileChannel.open(File(dir, "sleepyowl.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = runCatching { channel.tryLock() }.getOrNull()
            val socket = File(dir, "sleepyowl.sock")
            if (lock == null) {
                channel.close()
                runCatching {
                    SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).use { it.write(ByteBuffer.wrap("show\n".toByteArray())) }
                }.onFailure { Log.w(it, "The running instance did not answer") }
                return null
            }
            // Left by a run that crashed: the lock is ours, so nobody listens on it.
            socket.delete()
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.toPath())) }
            return SingleInstance(lock, server)
        }
    }
}

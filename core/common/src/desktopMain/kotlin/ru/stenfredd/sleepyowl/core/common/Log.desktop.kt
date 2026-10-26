package ru.stenfredd.sleepyowl.core.common

import java.io.File
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val LEVELS = mapOf(Log.DEBUG to 'D', Log.INFO to 'I', Log.WARN to 'W', Log.ERROR to 'E')

/** The log file of this run, see [logToFile]; written next to stderr. */
@Volatile
private var file: PrintStream? = null

/**
 * Also writes the log into `dir/sleepyowl.log`; the previous run's file is kept as `sleepyowl.1.log`.
 * Called once by the desktop app at start.
 */
fun logToFile(dir: File) {
    runCatching {
        dir.mkdirs()
        val current = File(dir, "sleepyowl.log")
        if (current.exists()) current.renameTo(File(dir, "sleepyowl.1.log"))
        file = PrintStream(current.outputStream().buffered(), true, Charsets.UTF_8).also {
            it.println("---- ${LocalDateTime.now()}")
        }
    }.onFailure { System.err.println("Log file not opened in $dir: $it") }
}

internal actual fun logEnabled() = true

internal actual fun platformLog(priority: Int, tag: String, t: Throwable?, message: String, args: Array<out Any?>) {
    val text = if (args.isEmpty()) message else message.format(*args)
    val line = "${LocalTime.now().format(TIME)} ${LEVELS[priority]} $tag: $text"
    synchronized(System.err) {
        System.err.println(line)
        t?.printStackTrace()
        file?.let { out ->
            out.println(line)
            t?.printStackTrace(out)
        }
    }
}

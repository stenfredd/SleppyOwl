package ru.stenfredd.sleepyowl.core.common

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val LEVELS = mapOf(Log.DEBUG to 'D', Log.INFO to 'I', Log.WARN to 'W', Log.ERROR to 'E')

internal actual fun logEnabled() = true

internal actual fun platformLog(priority: Int, tag: String, t: Throwable?, message: String, args: Array<out Any?>) {
    val text = if (args.isEmpty()) message else message.format(*args)
    val line = "${LocalTime.now().format(TIME)} ${LEVELS[priority]} $tag: $text"
    synchronized(System.err) {
        System.err.println(line)
        t?.printStackTrace()
    }
}

package ru.stenfredd.sleepyowl.core.common

/**
 * The app's log on every platform. Like Timber: [message] is a `String.format` pattern, formatted only when [args]
 * are given, and the tag is the calling class. Android writes through Timber, desktop to stderr.
 */
object Log {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    fun d(message: String, vararg args: Any?) = write(DEBUG, null, message, args)
    fun d(t: Throwable?, message: String, vararg args: Any?) = write(DEBUG, t, message, args)
    fun i(message: String, vararg args: Any?) = write(INFO, null, message, args)
    fun i(t: Throwable?, message: String, vararg args: Any?) = write(INFO, t, message, args)
    fun w(message: String, vararg args: Any?) = write(WARN, null, message, args)
    fun w(t: Throwable?, message: String, vararg args: Any?) = write(WARN, t, message, args)
    fun e(message: String, vararg args: Any?) = write(ERROR, null, message, args)
    fun e(t: Throwable?, message: String, vararg args: Any?) = write(ERROR, t, message, args)

    private fun write(priority: Int, t: Throwable?, message: String, args: Array<out Any?>) {
        if (logEnabled()) platformLog(priority, callerTag(), t, message, args)
    }

    /**
     * Simple name of the class that called [Log], without `$1` of lambdas — the tag Timber's DebugTree gave. Our own
     * frames are told apart by the class name at run time, as Timber does: R8 may merge [Log] into another class.
     */
    private fun callerTag(): String {
        val own = javaClass.name
        val caller = Throwable().stackTrace.firstOrNull { it.className != own } ?: return "SleepyOwl"
        return caller.className.substringAfterLast('.').replace(ANONYMOUS_CLASS, "")
    }

    private val ANONYMOUS_CLASS = Regex("(\\$\\d+)+$")
}

internal expect fun logEnabled(): Boolean

internal expect fun platformLog(priority: Int, tag: String, t: Throwable?, message: String, args: Array<out Any?>)

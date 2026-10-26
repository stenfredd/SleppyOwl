package ru.stenfredd.sleepyowl.desktop

import com.sun.jna.Native
import ru.stenfredd.sleepyowl.core.common.Log

/** libc functions the app needs. */
object CLib {
    init {
        Native.register("c")
    }

    /** Exit without atexit handlers and library destructors, see [main]. */
    @JvmStatic external fun _exit(status: Int)
}

object X11App {
    /**
     * WM_CLASS of every AWT window: Java takes the main class's name by default, and the panel then groups the
     * windows apart from the `.desktop` file (`StartupWMClass=sleepyowl`). Called before the first window.
     */
    fun setClassName(name: String) {
        runCatching {
            val f = Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName")
            f.isAccessible = true
            f.set(null, name)
        }.onFailure { Log.w(it, "WM_CLASS not set") }
    }
}

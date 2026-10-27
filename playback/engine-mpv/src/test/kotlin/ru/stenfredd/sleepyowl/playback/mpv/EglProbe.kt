package ru.stenfredd.sleepyowl.playback.mpv

/**
 * Manual check: `java -cp … ru.stenfredd.sleepyowl.playback.mpv.EglProbeKt [mpv] [awt]` — creates mpv's EGL context
 * alone, after an mpv core (`mpv`), after an AWT window (`awt`) or a Compose one (`compose`); `prepare` calls
 * [MpvVideo.prepareProcess] first.
 */
fun main(args: Array<String>) {
    if ("prepare" in args) MpvVideo.prepareProcess()
    if ("awt" in args) {
        javax.swing.SwingUtilities.invokeAndWait {
            javax.swing.JFrame("probe").apply { setSize(200, 100); isVisible = true }
        }
    }
    if ("compose" in args) {
        javax.swing.SwingUtilities.invokeAndWait {
            androidx.compose.ui.awt.ComposeWindow().apply {
                setSize(300, 200)
                focusableWindowState = false
                setLocation(3700, 1500)
                setContent { androidx.compose.foundation.text.BasicText("probe") }
                isVisible = true
            }
        }
        Thread.sleep(1500)
    }
    if ("mpv" in args) {
        val lib = LibMpv.INSTANCE
        val h = lib.mpv_create()!!
        lib.mpv_set_option_string(h, "vo", "libmpv")
        println("mpv init ${lib.mpv_initialize(h)}")
    }
    val t = Thread {
        val c = MpvVideo.EglContext.create()
        println("OK ${c.info} surface=${c.surface}")
        c.destroy()
    }
    t.start()
    t.join()
    System.exit(0)
}

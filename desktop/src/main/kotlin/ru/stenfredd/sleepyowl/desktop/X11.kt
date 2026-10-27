package ru.stenfredd.sleepyowl.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import ru.stenfredd.sleepyowl.core.common.Log
import java.awt.Window

/** The bits of Xlib the player's window needs (the L1 prototype's `x11/X11.kt`). */
private object Xlib {
    init {
        Native.register("X11")
    }

    @JvmStatic external fun XOpenDisplay(name: String?): Pointer?
    @JvmStatic external fun XDefaultRootWindow(dpy: Pointer): NativeLong
    @JvmStatic external fun XInternAtom(dpy: Pointer, name: String, onlyIfExists: Int): NativeLong
    @JvmStatic external fun XSendEvent(dpy: Pointer, w: NativeLong, propagate: Int, mask: NativeLong, event: Pointer): Int
    @JvmStatic external fun XFlush(dpy: Pointer): Int
    @JvmStatic external fun XUngrabPointer(dpy: Pointer, time: NativeLong): Int
    @JvmStatic external fun XMoveResizeWindow(dpy: Pointer, w: NativeLong, x: Int, y: Int, width: Int, height: Int): Int
    @JvmStatic external fun XGetGeometry(
        dpy: Pointer, w: NativeLong, root: NativeLongByReference, x: IntByReference, y: IntByReference,
        width: IntByReference, height: IntByReference, border: IntByReference, depth: IntByReference,
    ): Int
    @JvmStatic external fun XTranslateCoordinates(
        dpy: Pointer, src: NativeLong, dst: NativeLong, x: Int, y: Int, rx: IntByReference, ry: IntByReference, child: NativeLongByReference,
    ): Int
    @JvmStatic external fun XGetWMNormalHints(dpy: Pointer, w: NativeLong, hints: Pointer, supplied: NativeLongByReference): Int
    @JvmStatic external fun XSetWMNormalHints(dpy: Pointer, w: NativeLong, hints: Pointer)
    @JvmStatic external fun XQueryTree(
        dpy: Pointer, w: NativeLong, root: NativeLongByReference, parent: NativeLongByReference, children: PointerByReference, n: IntByReference,
    ): Int
    @JvmStatic external fun XFree(data: Pointer): Int
    @JvmStatic external fun XChangeProperty(dpy: Pointer, w: NativeLong, property: NativeLong, type: NativeLong, format: Int, mode: Int, data: Pointer, n: Int): Int
    @JvmStatic external fun XGetWindowProperty(
        dpy: Pointer, w: NativeLong, property: NativeLong, offset: NativeLong, length: NativeLong, delete: Int, reqType: NativeLong,
        actualType: NativeLongByReference, actualFormat: IntByReference, nItems: NativeLongByReference, bytesAfter: NativeLongByReference,
        prop: PointerByReference,
    ): Int
}

/**
 * An AWT top-level window as X11 sees it, on a connection of its own (used from the AWT thread only).
 *
 * Full screen goes through the window manager (`_NET_WM_STATE_FULLSCREEN`, as mpv does it), on the monitor the window
 * is on. Muffin takes a decorated AWT window into full screen but never out of it (Compose's
 * `WindowPlacement.Fullscreen` and a plain `java.awt.Frame` alike); an undecorated one whose frame comes from
 * `_MOTIF_WM_HINTS` ([setDecorated]) goes both ways — the L1 prototype's way.
 */
class X11Window private constructor(private val dpy: Pointer, val xid: Long) {
    private val root = Xlib.XDefaultRootWindow(dpy)

    private fun atom(name: String) = Xlib.XInternAtom(dpy, name, 0)

    fun setFullscreen(on: Boolean) = setState("_NET_WM_STATE_FULLSCREEN", on)

    /** On every workspace (`_NET_WM_STATE_STICKY`): the mini window's setting. */
    fun setSticky(on: Boolean) = setState("_NET_WM_STATE_STICKY", on)

    private fun setState(name: String, on: Boolean) = sendClientMessage("_NET_WM_STATE", if (on) 1 else 0, atom(name).toLong(), 0, 1)

    /**
     * The window manager moves or resizes the window (`_NET_WM_MOVERESIZE`, as mpv and GTK do): smoothly, across
     * monitors, snapping to edges. AWT grabbed the pointer on the press; the window manager can't grab it while AWT
     * holds it, so that grab goes first.
     */
    fun startMoveResize(rootX: Int, rootY: Int, direction: Int) {
        ungrabAwtPointer()
        // x, y, direction, button 1, source: an application
        sendClientMessage("_NET_WM_MOVERESIZE", rootX.toLong(), rootY.toLong(), direction.toLong(), 1, 1)
    }

    /** The client area on the screen, without the window manager's frame. AWT's own bounds lag behind the window manager. */
    fun geometry(): java.awt.Rectangle {
        val r = NativeLongByReference()
        val x = IntByReference()
        val y = IntByReference()
        val w = IntByReference()
        val h = IntByReference()
        val b = IntByReference()
        val depth = IntByReference()
        Xlib.XGetGeometry(dpy, NativeLong(xid), r, x, y, w, h, b, depth)
        val rx = IntByReference()
        val ry = IntByReference()
        val child = NativeLongByReference()
        Xlib.XTranslateCoordinates(dpy, NativeLong(xid), root, 0, 0, rx, ry, child)
        return java.awt.Rectangle(rx.value, ry.value, w.value, h.value)
    }

    /** Straight to X11: AWT's cached bounds may be stale after the window manager moved the window. */
    fun moveResize(r: java.awt.Rectangle) {
        Xlib.XMoveResizeWindow(dpy, NativeLong(xid), r.x, r.y, r.width, r.height)
        Xlib.XFlush(dpy)
    }

    /**
     * The window's proportions for the window manager (WM_NORMAL_HINTS, PAspect; none when [w] or [h] is 0) and its
     * minimum size, over the hints AWT set. AWT rewrites the hints wholesale on each of its reshapes
     * (`XBaseWindow.setSizeHints`), so this is called again after them; returns false when the hints were already so.
     */
    fun setSizeHints(w: Int, h: Int, minW: Int, minH: Int): Boolean {
        val hints = Memory(80).apply { clear() }
        Xlib.XGetWMNormalHints(dpy, NativeLong(xid), hints, NativeLongByReference())
        val before = hints.getByteArray(0, 80)
        var flags = hints.getNativeLong(0).toLong()
        if (w > 0 && h > 0) {
            flags = flags or P_ASPECT
            hints.setInt(48, w)
            hints.setInt(52, h)
            hints.setInt(56, w)
            hints.setInt(60, h)
        } else {
            flags = flags and P_ASPECT.inv()
        }
        flags = flags or P_MIN_SIZE
        hints.setInt(24, minW)
        hints.setInt(28, minH)
        hints.setNativeLong(0, NativeLong(flags))
        if (hints.getByteArray(0, 80).contentEquals(before)) return false
        Xlib.XSetWMNormalHints(dpy, NativeLong(xid), hints)
        Xlib.XFlush(dpy)
        return true
    }

    /**
     * The window manager's frame on a live window, as mpv's `border`. AWT made the window undecorated and doesn't know:
     * its insets stay zero and Compose fills the client area.
     */
    fun setDecorated(decorated: Boolean) {
        val hints = atom("_MOTIF_WM_HINTS")
        val data = Memory(5L * 8).apply { clear() }
        data.setNativeLong(0, NativeLong(MWM_HINTS_DECORATIONS))
        data.setNativeLong(16, NativeLong(if (decorated) 1 else 0))
        Xlib.XChangeProperty(dpy, NativeLong(xid), hints, hints, 32, PROP_MODE_REPLACE, data, 5)
        Xlib.XFlush(dpy)
    }

    val isFullscreen: Boolean get() = hasState("_NET_WM_STATE_FULLSCREEN")

    /** The window manager has taken the window (set WM_STATE): before that, state requests get lost. */
    val isManaged: Boolean get() = hasProperty(dpy, xid, atom("WM_STATE"))

    /** A `_NET_WM_STATE` the window manager keeps on the window. */
    fun hasState(name: String): Boolean {
        val type = NativeLongByReference()
        val format = IntByReference()
        val items = NativeLongByReference()
        val after = NativeLongByReference()
        val data = PointerByReference()
        Xlib.XGetWindowProperty(dpy, NativeLong(xid), atom("_NET_WM_STATE"), NativeLong(0), NativeLong(64), 0, NativeLong(XA_ATOM), type, format, items, after, data)
        val p = data.value ?: return false
        val want = atom(name).toLong()
        val found = (0 until items.value.toInt()).any { p.getNativeLong(8L * it).toLong() == want }
        Xlib.XFree(p)
        return found
    }

    /** A ClientMessage to the root window, as EWMH asks for state changes. */
    private fun sendClientMessage(type: String, vararg l: Long) {
        val ev = Memory(192).apply { clear() }
        ev.setInt(0, CLIENT_MESSAGE)
        ev.setInt(16, 1)
        ev.setPointer(24, dpy)
        ev.setNativeLong(32, NativeLong(xid))
        ev.setNativeLong(40, atom(type))
        ev.setInt(48, 32)
        l.forEachIndexed { i, v -> ev.setNativeLong(56 + 8L * i, NativeLong(v)) }
        Xlib.XSendEvent(dpy, root, 0, NativeLong(SUBSTRUCTURE_NOTIFY or SUBSTRUCTURE_REDIRECT), ev)
        Xlib.XFlush(dpy)
    }

    companion object {
        /** `_NET_WM_MOVERESIZE` directions: the window's sides and corners clockwise from the top left, then a move. */
        const val SIZE_TOPLEFT = 0
        const val SIZE_TOP = 1
        const val SIZE_TOPRIGHT = 2
        const val SIZE_RIGHT = 3
        const val SIZE_BOTTOMRIGHT = 4
        const val SIZE_BOTTOM = 5
        const val SIZE_BOTTOMLEFT = 6
        const val SIZE_LEFT = 7
        const val MOVE = 8

        private const val CLIENT_MESSAGE = 33
        private const val P_MIN_SIZE = 16L
        private const val P_ASPECT = 128L
        private const val MWM_HINTS_DECORATIONS = 2L
        private const val PROP_MODE_REPLACE = 0
        private const val XA_ATOM = 4L
        private const val SUBSTRUCTURE_NOTIFY = 1L shl 19
        private const val SUBSTRUCTURE_REDIRECT = 1L shl 20

        private val display: Pointer? by lazy { runCatching { Xlib.XOpenDisplay(null) }.onFailure { Log.w(it, "No X11 display") }.getOrNull() }

        /** The window the window manager manages: up from AWT's own window to the one with WM_STATE (its frame's child). */
        fun of(window: Window): X11Window? {
            val d = display ?: return null
            val own = runCatching { Native.getWindowID(window) }.getOrNull() ?: return null
            val wmState = Xlib.XInternAtom(d, "WM_STATE", 0)
            val rootId = Xlib.XDefaultRootWindow(d).toLong()
            var w = own
            repeat(8) {
                if (hasProperty(d, w, wmState)) return X11Window(d, w)
                val parent = parentOf(d, w) ?: return X11Window(d, own)
                if (parent == rootId) return X11Window(d, w)
                w = parent
            }
            return X11Window(d, own)
        }

        /** Releases the pointer grab on AWT's own X connection, under AWT's lock. */
        private fun ungrabAwtPointer() {
            runCatching {
                val display = Class.forName("sun.awt.X11.XToolkit").getMethod("getDisplay").invoke(null) as Long
                val sun = Class.forName("sun.awt.SunToolkit")
                sun.getMethod("awtLock").invoke(null)
                try {
                    Xlib.XUngrabPointer(Pointer(display), NativeLong(0))
                    Xlib.XFlush(Pointer(display))
                } finally {
                    sun.getMethod("awtUnlock").invoke(null)
                }
            }.onFailure { Log.w(it, "X11: pointer not released") }
        }

        private fun parentOf(d: Pointer, w: Long): Long? {
            val root = NativeLongByReference()
            val parent = NativeLongByReference()
            val children = PointerByReference()
            val n = IntByReference()
            if (Xlib.XQueryTree(d, NativeLong(w), root, parent, children, n) == 0) return null
            children.value?.let { Xlib.XFree(it) }
            return parent.value.toLong()
        }

        private fun hasProperty(d: Pointer, w: Long, prop: NativeLong): Boolean {
            val type = NativeLongByReference()
            val format = IntByReference()
            val items = NativeLongByReference()
            val after = NativeLongByReference()
            val data = PointerByReference()
            Xlib.XGetWindowProperty(d, NativeLong(w), prop, NativeLong(0), NativeLong(1), 0, NativeLong(0), type, format, items, after, data)
            data.value?.let { Xlib.XFree(it) }
            return type.value.toLong() != 0L
        }
    }
}

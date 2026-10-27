package ru.stenfredd.sleepyowl.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.common.monotonicMs
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The player's window in its three modes (§1.1, §2.4), through the window manager ([X11Window]):
 *
 * - **mini** — no frame, on top of everything, moved by the video and resized by its edges (`_NET_WM_MOVERESIZE`),
 *   keeping the video's proportions (size hints);
 * - **window** — the window manager's frame (`_MOTIF_WM_HINTS`; AWT keeps the window undecorated, L7);
 * - **full screen** — `_NET_WM_STATE_FULLSCREEN` on the window's monitor.
 *
 * Each windowed mode keeps its own place ([PlayerPlacement]). The window takes the focus once, when it opens; mode
 * changes and new episodes never take it back from another program (§1.2).
 */
internal class PlayerFrame(private val player: DesktopPlayer) {
    lateinit var window: ComposeWindow
        private set
    private var x11: X11Window? = null

    /** The mode the window has been put in; null until the window manager has taken it. */
    private var applied: PlayerMode? = null

    /** Until then the window's moves and resizes are a mode change settling, not the user's. */
    private var busyUntil = Long.MAX_VALUE
    private val busy get() = monotonicMs() < busyUntil

    private var video = IntSize.Zero
    private var hintsRestored = 0
    private val saveTimer = javax.swing.Timer(SAVE_DELAY_MS) { savePlace() }.apply { isRepeats = false }

    fun create(title: String): ComposeWindow = ComposeWindow().also { w ->
        window = w
        w.title = title
        // The frame comes from the window manager (setDecorated): full screen can be left again (L7).
        w.isUndecorated = true
        w.focusableWindowState = !TestMode.passive
        w.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        w.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = player.close()
        })
        w.minimumSize = java.awt.Dimension(MIN_WIDTH, MIN_HEIGHT)
        Icon?.let { w.iconImage = it }
        val mode = player.mode
        w.isAlwaysOnTop = mode == PlayerMode.Mini
        w.bounds = TestMode.playerBounds?.let { (x, y, width, height) -> Rectangle(x, y, width, height) }
            ?: player.placement.bounds(if (mode == PlayerMode.Fullscreen) player.windowedMode else mode)
    }

    fun dispose() {
        saveTimer.stop()
        savePlace()
        // A player with the focus (closed by ×, Ctrl+W, the menu, or a movie watched to its end) hands it to the
        // library, not to whatever window the window manager picks. Played in the background, the focus stays put.
        val hadFocus = window.isActive
        window.dispose()
        if (hadFocus) player.showLibrary()
    }

    /** Puts the window in the player's mode as it changes, and the mini window in the video's proportions. */
    @Composable
    fun Modes(videoSize: IntSize) {
        LaunchedEffect(Unit) { attach() }
        LaunchedEffect(player.mode) { if (applied != null) apply(player.mode) }
        LaunchedEffect(videoSize) { onVideoSize(videoSize) }
        LaunchedEffect(player.placement.sticky) {
            if (applied == PlayerMode.Mini) x11?.setSticky(player.placement.sticky)
        }
        DisposableEffect(Unit) {
            val listener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = onMovedOrResized()
                override fun componentResized(e: ComponentEvent) = onMovedOrResized()
            }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }
    }

    /** The mini window is dragged by the video: the window manager moves it from here on. */
    fun startMove(e: MouseEvent, moved: Offset) {
        // The window manager moves the window by the pointer's moves from the given point: from the press, the window
        // keeps the spot under the pointer (from here it would lag by the way to the drag threshold).
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        startOp(e, X11Window.MOVE, e.xOnScreen - (moved.x / scale).roundToInt(), e.yOnScreen - (moved.y / scale).roundToInt())
    }

    /** One of `X11Window.SIZE_*`: the window manager resizes it from here on. */
    fun startResize(e: MouseEvent, direction: Int) = startOp(e, direction)

    private suspend fun attach() {
        val x = X11Window.of(window) ?: return
        // Requests to a window the window manager hasn't taken yet get lost.
        withTimeoutOrNull(1_000) { while (!x.isManaged) delay(16) }
        x11 = x
        apply(player.mode, first = true)
        if (player.mode != applied) apply(player.mode)
        // The player takes the focus once, when it opens (§1.2); an undecorated AWT window doesn't get it on its own.
        if (!TestMode.passive) {
            window.toFront()
            window.requestFocus()
            // The frame itself gets the focus, not Compose's canvas in it (a click moves it there): keys would be lost.
            canvasOf(window)?.requestFocusInWindow()
        }
    }

    private suspend fun apply(new: PlayerMode, first: Boolean = false) {
        val x = x11 ?: return
        val old = applied
        if (old == new && !first) return
        if (old != null && old != PlayerMode.Fullscreen && !busy && !x.isFullscreen) player.placement.save(old, x.geometry())
        applied = new
        busyUntil = Long.MAX_VALUE
        Log.i("Player window: %s", new)
        try {
            if (new == PlayerMode.Fullscreen) {
                window.isAlwaysOnTop = false
                x.setSizeHints(0, 0, MIN_WIDTH, MIN_HEIGHT)
                if (!x.isFullscreen) {
                    x.setFullscreen(true)
                    waitFor { x.isFullscreen }
                }
            } else {
                if (x.isFullscreen) {
                    // The window manager puts back the size from before full screen, and later than us: wait till it has.
                    x.setFullscreen(false)
                    waitFor { !x.isFullscreen }
                    delay(30)
                }
                // The frame and the hints first, then the place: else the window manager fits the size to the old ones.
                x.setDecorated(new == PlayerMode.Window)
                window.isAlwaysOnTop = new == PlayerMode.Mini
                x.setSticky(new == PlayerMode.Mini && player.placement.sticky)
                setHints(new)
                val target = if (first && TestMode.playerBounds != null) x.geometry() else player.placement.bounds(new)
                place(x, if (new == PlayerMode.Mini) fit(target) else target)
            }
        } finally {
            busyUntil = monotonicMs() + SETTLE_MS
        }
    }

    /**
     * Puts the client area at [target]. While the window manager adds or takes away the frame it may ignore the
     * request, so it is repeated until the window is there; Muffin places the frame's corner rather than the client
     * area's, so the request moves by the difference (L1).
     */
    private suspend fun place(x: X11Window, target: Rectangle) {
        val request = Rectangle(target)
        x.moveResize(request)
        repeat(PLACE_TRIES) {
            delay(PLACE_RETRY_MS)
            val g = x.geometry()
            if (g.location == target.location && abs(g.width - target.width) <= 1 && abs(g.height - target.height) <= 1) return
            if (abs(g.width - target.width) <= 1 && abs(g.height - target.height) <= 1) request.translate(target.x - g.x, target.y - g.y)
            x.moveResize(request)
        }
        Log.w("Player window at %s, wanted %s", x.geometry(), target)
    }

    /** The mini window takes the video's proportions: same width, the bottom right corner stays (§1.1). */
    private fun fit(r: Rectangle): Rectangle {
        val v = video
        if (v.width <= 0 || v.height <= 0) return r
        var w = r.width
        var h = (w.toLong() * v.height / v.width).toInt()
        if (h < MIN_HEIGHT) {
            h = MIN_HEIGHT
            w = (h.toLong() * v.width / v.height).toInt()
        }
        return Rectangle(r.x + r.width - w, r.y + r.height - h, w, h)
    }

    private suspend fun onVideoSize(size: IntSize) {
        if (size == video || size.width <= 0 || size.height <= 0) return
        video = size
        val x = x11 ?: return
        if (applied != PlayerMode.Mini || busy) return
        setHints(PlayerMode.Mini)
        val g = x.geometry()
        val fitted = fit(g)
        if (abs(fitted.height - g.height) > 2 || abs(fitted.width - g.width) > 2) {
            busyUntil = Long.MAX_VALUE
            try {
                place(x, fitted)
            } finally {
                busyUntil = monotonicMs() + SETTLE_MS
            }
        }
    }

    /** The mini window keeps the video's proportions while resized; the others are free. Returns true if they were rewritten. */
    private fun setHints(mode: PlayerMode): Boolean {
        val x = x11 ?: return false
        val v = video.takeIf { mode == PlayerMode.Mini && it.width > 0 && it.height > 0 } ?: IntSize.Zero
        return x.setSizeHints(v.width, v.height, MIN_WIDTH, MIN_HEIGHT)
    }

    private fun onMovedOrResized() {
        val x = x11 ?: return
        val mode = applied ?: return
        // AWT rewrites the size hints wholesale on each of its reshapes, the proportions with them; its write may still
        // be in its connection's buffer.
        Toolkit.getDefaultToolkit().sync()
        if (setHints(mode) && hintsRestored++ % 20 == 0) Log.d("Player window: size hints restored (%d)", hintsRestored)
        if (busy) return
        val fullscreen = x.isFullscreen
        when {
            // The window manager left full screen or went into it on its own (a desktop shortcut).
            mode == PlayerMode.Fullscreen && !fullscreen -> player.switchTo(player.windowedMode)
            mode != PlayerMode.Fullscreen && fullscreen -> player.switchTo(PlayerMode.Fullscreen)
            else -> saveTimer.restart()
        }
    }

    /** Keeps where the windowed mode is now: after a move or resize settles, before a mode change, on close. */
    private fun savePlace() {
        val x = x11 ?: return
        val mode = applied ?: return
        if (mode == PlayerMode.Fullscreen || busy || x.isFullscreen) return
        player.placement.save(mode, x.geometry())
    }

    private fun startOp(e: MouseEvent, direction: Int, fromX: Int = e.xOnScreen, fromY: Int = e.yOnScreen) {
        val x = x11 ?: return
        x.startMoveResize(fromX, fromY, direction)
        // The window manager has the pointer now: Compose never sees the button go up, so it gets a release.
        SwingUtilities.invokeLater {
            val src = e.component
            src.dispatchEvent(
                MouseEvent(src, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, e.x, e.y, e.xOnScreen, e.yOnScreen, 1, false, MouseEvent.BUTTON1),
            )
        }
    }

    /** Waits up to a second for the window manager to get the window into a state. */
    private suspend fun waitFor(done: () -> Boolean) {
        withTimeoutOrNull(1_000) { while (!done()) delay(16) }
    }

    private companion object {
        /** After a mode change, the window manager's own moves and resizes. */
        const val SETTLE_MS = 400L
        const val PLACE_TRIES = 8
        const val PLACE_RETRY_MS = 60L
        const val SAVE_DELAY_MS = 800

        val Icon by lazy {
            runCatching { PlayerFrame::class.java.getResourceAsStream("/icons/sleepyowl-256.png")?.use(ImageIO::read) }.getOrNull()
        }
    }
}

/** The innermost component of [window]: Skiko's canvas, which Compose takes keys from. */
private fun canvasOf(window: java.awt.Component): java.awt.Component? {
    if (window !is java.awt.Container || window.componentCount == 0) return window.takeIf { it.isFocusable }
    return window.components.asSequence().mapNotNull(::canvasOf).lastOrNull()
}

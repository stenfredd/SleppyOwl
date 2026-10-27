package ru.stenfredd.sleepyowl.playback.mpv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.sun.jna.CallbackReference
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.Data
import ru.stenfredd.sleepyowl.core.common.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * mpv's picture, variant B of docs/desktop.md: mpv draws in its own invisible OpenGL context (EGL pbuffer) on its
 * own thread, into an FBO; the frame is read back through a persistently mapped PBO into memory that Compose shows
 * ([MpvVideoSurface]). mpv knows nothing about the window, so the window can be rebuilt freely.
 *
 * The FBO is the size of the video area, but never larger than the video itself (L1: Skia uploads every frame, a
 * 1080p film drawn 3440×1440 costs 2.6 times the bytes for nothing): above the video's own size Skia scales it up.
 */
class MpvVideo internal constructor(
    private val mpv: Pointer,
    private val onReady: () -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    internal val frames = FramePool()

    /** Grows with every new frame; read while drawing, it makes Compose redraw. */
    internal val serial = mutableIntStateOf(0)

    /** The video's display size, [IntSize.Zero] until known: the mini window keeps its proportions. */
    var displaySize by mutableStateOf(IntSize.Zero)
        private set

    // Written by the render thread, read for the stream info.
    internal val rendered = AtomicLong()
    internal val renderNs = AtomicLong()
    internal val readbackNs = AtomicLong()
    @Volatile internal var glInfo = ""
    @Volatile internal var renderSize = ""

    private val lock = Object()
    private var areaW = 0
    private var areaH = 0
    private var videoW = 0
    private var videoH = 0
    private var pendingUpdate = false
    private var pendingResize = false
    private var stopping = false
    private var firstFrameSent = false
    private var thread: Thread? = null

    private val updateCallback = LibMpv.UpdateCallback {
        synchronized(lock) { pendingUpdate = true; lock.notifyAll() }
    }
    private val getProcAddress = LibMpv.GetProcAddress { _, name -> Egl.eglGetProcAddress(name) }

    /** Starts the render thread; [onReady] is called on it once mpv has its render context (before that loadfile fails). */
    internal fun start() {
        // mpv calls back from its own threads: keep them attached to the JVM instead of attaching on every call.
        Native.setCallbackThreadInitializer(updateCallback, CallbackThreadInitializer(true, false, "mpv-update"))
        thread = thread(name = "mpv-render", isDaemon = true) {
            try {
                run()
            } catch (t: Throwable) {
                Log.e(t, "mpv render thread failed")
                onFailure(t)
            }
        }
    }

    /** Size of the video area in window pixels. */
    fun resize(w: Int, h: Int) = synchronized(lock) {
        if (w == areaW && h == areaH) return
        areaW = w; areaH = h; pendingResize = true; lock.notifyAll()
    }

    /** The video's display size (mpv's `dwidth`/`dheight`); 0 until known. A known size also arms the first-frame event. */
    internal fun setVideoSize(w: Int, h: Int) = synchronized(lock) {
        if (w == videoW && h == videoH) return
        videoW = w; videoH = h; pendingResize = true; lock.notifyAll()
        displaySize = IntSize(w, h)
    }

    /** Frees the render context and the GL objects; must run before `mpv_terminate_destroy`. Blocks up to 3 s. */
    internal fun stop() {
        synchronized(lock) { stopping = true; lock.notifyAll() }
        thread?.join(3000)
    }

    private fun run() {
        val egl = EglContext.create()
        glInfo = egl.info
        val lib = LibMpv.INSTANCE
        val initParams = Memory(16).apply {
            setPointer(0, CallbackReference.getFunctionPointer(getProcAddress))
            setPointer(8, null)
        }
        val one = Memory(4).apply { setInt(0, 1) }
        val apiType = Memory(8).apply { setString(0, "opengl") }
        val createParams = params(
            LibMpv.RENDER_PARAM_API_TYPE to apiType,
            LibMpv.RENDER_PARAM_OPENGL_INIT_PARAMS to initParams,
            // Allowed: this thread only calls mpv_render_* and never waits on the core.
            LibMpv.RENDER_PARAM_ADVANCED_CONTROL to one,
        )
        val ref = PointerByReference()
        val rc = lib.mpv_render_context_create(ref, mpv, createParams)
        if (rc < 0) {
            egl.destroy()
            error("mpv_render_context_create: ${lib.mpv_error_string(rc)}")
        }
        val ctx = ref.value
        lib.mpv_render_context_set_update_callback(ctx, updateCallback, null)
        onReady()

        val fbo = Memory(16)
        // No FLIP_Y: glReadPixels reads bottom-up, so the frame lands in memory top-down, as Skia wants it.
        val renderParams = params(LibMpv.RENDER_PARAM_OPENGL_FBO to fbo)
        val target = RenderTarget()
        try {
            while (true) {
                var update: Boolean
                var resized: Boolean
                var w: Int
                var h: Int
                var hasVideo: Boolean
                synchronized(lock) {
                    while (!pendingUpdate && !pendingResize && !stopping) lock.wait()
                    if (stopping) return
                    update = pendingUpdate; resized = pendingResize
                    pendingUpdate = false; pendingResize = false
                    val size = renderSize(areaW, areaH, videoW, videoH)
                    w = size.first; h = size.second
                    hasVideo = videoW > 0 && videoH > 0
                }
                var newFrame = false
                if (update) newFrame = lib.mpv_render_context_update(ctx) and LibMpv.RENDER_UPDATE_FRAME != 0L
                if (!(newFrame || resized) || w <= 0 || h <= 0) continue

                target.ensure(w, h)
                val sizeText = "${w}x$h"
                if (sizeText != renderSize && hasVideo) Log.d("mpv: frame %s for the area %dx%d", sizeText, areaW, areaH)
                renderSize = sizeText
                fbo.setInt(0, target.fbo); fbo.setInt(4, w); fbo.setInt(8, h); fbo.setInt(12, Gl.RGBA8)
                val t0 = System.nanoTime()
                lib.mpv_render_context_render(ctx, renderParams)
                val t1 = System.nanoTime()
                val frame = frames.acquire(w, h)
                target.readInto(frame.pointer!!, w, h)
                val t2 = System.nanoTime()
                frames.publish(frame)
                lib.mpv_render_context_report_swap(ctx)
                renderNs.addAndGet(t1 - t0); readbackNs.addAndGet(t2 - t1); rendered.incrementAndGet()
                serial.intValue++
                if (newFrame && hasVideo && !firstFrameSent) {
                    firstFrameSent = true
                    onFirstFrame()
                }
            }
        } finally {
            lib.mpv_render_context_set_update_callback(ctx, null, null)
            lib.mpv_render_context_free(ctx)
            target.release()
            egl.destroy()
        }
    }

    private fun params(vararg p: Pair<Int, Pointer>): Memory {
        val m = Memory(16L * (p.size + 1)).apply { clear() }
        p.forEachIndexed { i, (type, data) -> m.setInt(16L * i, type); m.setPointer(16L * i + 8, data) }
        return m
    }

    /** FBO with its texture and the readback buffer. Lives on the render thread. */
    private class RenderTarget {
        var fbo = 0
        private var tex = 0
        private var pbo = 0
        /** The PBO stays mapped for its whole life (GL 4.4 buffer storage); null when the driver refused that. */
        private var mapped: Pointer? = null
        private var w = 0
        private var h = 0

        fun ensure(nw: Int, nh: Int) {
            if (nw == w && nh == h && fbo != 0) return
            release()
            w = nw; h = nh
            val id = IntArray(1)
            Gl.glGenTextures(1, id); tex = id[0]
            Gl.glBindTexture(Gl.TEXTURE_2D, tex)
            Gl.glTexParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MIN_FILTER, Gl.LINEAR)
            Gl.glTexParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MAG_FILTER, Gl.LINEAR)
            Gl.glTexImage2D(Gl.TEXTURE_2D, 0, Gl.RGBA8, w, h, 0, Gl.BGRA, Gl.UNSIGNED_INT_8_8_8_8_REV, null)
            Gl.glGenFramebuffers(1, id); fbo = id[0]
            Gl.glBindFramebuffer(Gl.FRAMEBUFFER, fbo)
            Gl.glFramebufferTexture2D(Gl.FRAMEBUFFER, Gl.COLOR_ATTACHMENT0, Gl.TEXTURE_2D, tex, 0)
            val status = Gl.glCheckFramebufferStatus(Gl.FRAMEBUFFER)
            check(status == Gl.FRAMEBUFFER_COMPLETE) { "FBO incomplete: 0x${status.toString(16)}" }
            val size = 4L * w * h
            val flags = Gl.MAP_READ_BIT or Gl.MAP_PERSISTENT_BIT or Gl.MAP_COHERENT_BIT
            Gl.glGenBuffers(1, id); pbo = id[0]
            Gl.glBindBuffer(Gl.PIXEL_PACK_BUFFER, pbo)
            // Client storage: the driver keeps it in cached system memory, which the CPU reads fast.
            Gl.glBufferStorage(Gl.PIXEL_PACK_BUFFER, size, null, flags or Gl.CLIENT_STORAGE_BIT)
            mapped = Gl.glMapBufferRange(Gl.PIXEL_PACK_BUFFER, 0, size, flags)
            if (mapped == null) Log.w("mpv: the readback buffer can't stay mapped, mapping it per frame")
            Gl.glBindBuffer(Gl.PIXEL_PACK_BUFFER, 0)
        }

        /** Synchronous: the frame is needed now, or the picture runs a frame behind the sound. */
        fun readInto(dst: Pointer, w: Int, h: Int) {
            val size = 4L * w * h
            Gl.glBindFramebuffer(Gl.FRAMEBUFFER, fbo)
            Gl.glBindBuffer(Gl.PIXEL_PACK_BUFFER, pbo)
            Gl.glPixelStorei(Gl.PACK_ALIGNMENT, 4)
            Gl.glReadPixels(0, 0, w, h, Gl.BGRA, Gl.UNSIGNED_INT_8_8_8_8_REV, 0L)
            val sync = Gl.glFenceSync(Gl.SYNC_GPU_COMMANDS_COMPLETE, 0)
            if (sync != null) {
                Gl.glClientWaitSync(sync, Gl.SYNC_FLUSH_COMMANDS_BIT, 200_000_000L)
                Gl.glDeleteSync(sync)
            }
            val src = mapped
            if (src != null) {
                CLib.memcpy(dst, src, size)
            } else {
                Gl.glMapBufferRange(Gl.PIXEL_PACK_BUFFER, 0, size, Gl.MAP_READ_BIT)?.let {
                    CLib.memcpy(dst, it, size)
                    Gl.glUnmapBuffer(Gl.PIXEL_PACK_BUFFER)
                }
            }
            Gl.glBindBuffer(Gl.PIXEL_PACK_BUFFER, 0)
        }

        fun release() {
            if (fbo != 0) Gl.glDeleteFramebuffers(1, intArrayOf(fbo))
            if (tex != 0) Gl.glDeleteTextures(1, intArrayOf(tex))
            if (pbo != 0) {
                if (mapped != null) {
                    Gl.glBindBuffer(Gl.PIXEL_PACK_BUFFER, pbo)
                    Gl.glUnmapBuffer(Gl.PIXEL_PACK_BUFFER)
                    Gl.glBindBuffer(Gl.PIXEL_PACK_BUFFER, 0)
                }
                Gl.glDeleteBuffers(1, intArrayOf(pbo))
            }
            fbo = 0; tex = 0; pbo = 0; mapped = null
        }
    }

    /** An invisible OpenGL context through EGL: all drawing goes to our own FBOs, the 16×16 pbuffer is only there to bind. */
    internal class EglContext(val dpy: Pointer, val surface: Pointer?, val ctx: Pointer, val info: String) {
        fun destroy() {
            Egl.eglMakeCurrent(dpy, null, null, null)
            surface?.let { Egl.eglDestroySurface(dpy, it) }
            Egl.eglDestroyContext(dpy, ctx)
        }

        companion object {
            fun create(): EglContext {
                val dpy = Egl.eglGetDisplay(null) ?: error("eglGetDisplay")
                check(Egl.eglInitialize(dpy, IntByReference(), IntByReference()) != 0) { "eglInitialize 0x${Egl.eglGetError().toString(16)}" }
                check(Egl.eglBindAPI(Egl.OPENGL_API) != 0) { "eglBindAPI" }
                val egl = "EGL ${Egl.eglQueryString(dpy, Egl.VERSION)} ${Egl.eglQueryString(dpy, Egl.VENDOR)}"
                val attribs = intArrayOf(
                    Egl.SURFACE_TYPE, Egl.PBUFFER_BIT, Egl.RENDERABLE_TYPE, Egl.OPENGL_BIT,
                    Egl.RED_SIZE, 8, Egl.GREEN_SIZE, 8, Egl.BLUE_SIZE, 8, Egl.NONE,
                )
                val configs = PointerByReference()
                val num = IntByReference()
                check(Egl.eglChooseConfig(dpy, attribs, configs, 1, num) != 0 && num.value > 0) { "eglChooseConfig ($egl)" }
                val config = configs.value
                val ctx = Egl.eglCreateContext(dpy, config, null, intArrayOf(Egl.NONE)) ?: error("eglCreateContext 0x${Egl.eglGetError().toString(16)} ($egl)")
                var surface = Egl.eglCreatePbufferSurface(dpy, config, intArrayOf(Egl.WIDTH, 16, Egl.HEIGHT, 16, Egl.NONE))
                if (surface == null || Egl.eglMakeCurrent(dpy, surface, surface, ctx) == 0) {
                    Log.w("mpv: EGL pbuffer refused (0x%s, %s), trying without a surface", Egl.eglGetError().toString(16), egl)
                    surface?.let { Egl.eglDestroySurface(dpy, it) }
                    surface = null
                    check(Egl.eglMakeCurrent(dpy, null, null, ctx) != 0) { "eglMakeCurrent 0x${Egl.eglGetError().toString(16)} ($egl)" }
                }
                val info = "${Gl.glGetString(Gl.RENDERER)} · GL ${Gl.glGetString(Gl.VERSION)?.substringBefore(' ')}"
                return EglContext(dpy, surface, ctx, info)
            }
        }
    }

    companion object {
        /**
         * Call first thing in `main`, before any window. libglvnd rewrites the GL entry points for the first vendor that
         * makes a context current — Skiko's GLX context of the first Compose window — and then refuses to make current a
         * context of another API: on NVIDIA mpv's EGL context fails in eglMakeCurrent with no error code (Mesa doesn't
         * mind, the L1 prototype made its EGL context before its window). Disallowing the rewrite costs nothing
         * measurable; libGLdispatch reads the variable when it loads, which is when Skiko first draws.
         */
        fun prepareProcess() {
            CLib.setenv("__GLVND_DISALLOW_PATCHING", "1", 1)
        }

        /**
         * The FBO for a video area of [areaW]×[areaH]: the area itself, scaled down when the video would be drawn larger
         * than its own size, so mpv never upscales into extra bytes. The area's proportions are kept: mpv still draws
         * the black bars, and Skia stretches the frame evenly.
         */
        internal fun renderSize(areaW: Int, areaH: Int, videoW: Int, videoH: Int): Pair<Int, Int> {
            if (areaW <= 0 || areaH <= 0 || videoW <= 0 || videoH <= 0) return areaW to areaH
            val scale = min(areaW.toDouble() / videoW, areaH.toDouble() / videoH)
            if (scale <= 1.0) return areaW to areaH
            return max(1, (areaW / scale).roundToInt()) to max(1, (areaH / scale).roundToInt())
        }
    }
}

/**
 * Three frame buffers: the one shown (held by the UI), the latest ready one and the one being written. A frame replaced
 * before the UI got to show it counts as [lost].
 *
 * The memory is Skia's own [Data], not the mapped PBO: an Image over the PBO would outlive the buffer whenever Compose
 * replays a recorded picture after the engine is gone; Data is reference-counted by the images using it.
 */
internal class FramePool {
    class Frame {
        var data: Data? = null
        var pointer: Pointer? = null
        var width = 0
        var height = 0
        var serial = 0L
    }

    private val all = List(3) { Frame() }
    private var latest: Frame? = null
    private var shown: Frame? = null
    private var nextSerial = 1L
    var lost = 0L
        private set

    @Synchronized fun acquire(w: Int, h: Int): Frame {
        val f = all.first { it !== latest && it !== shown }
        val size = 4 * w * h
        if (f.data == null || f.data!!.size < size) {
            f.data?.close() // an Image in the UI keeps its own reference to the old Data
            val d = Data.makeUninitialized(size)
            f.data = d
            f.pointer = Pointer(d.writableData())
        }
        f.width = w
        f.height = h
        return f
    }

    @Synchronized fun publish(f: Frame) {
        if (latest != null && latest !== shown) lost++
        f.serial = nextSerial++
        latest = f
    }

    /** For the UI: the latest frame. The one shown before goes back to the writer. */
    @Synchronized fun take(): Frame? {
        latest?.let { shown = it }
        return shown
    }
}

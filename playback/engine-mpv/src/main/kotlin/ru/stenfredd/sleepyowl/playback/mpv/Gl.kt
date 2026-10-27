package ru.stenfredd.sleepyowl.playback.mpv

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** EGL 1.5 of libEGL (glvnd): just enough for an invisible OpenGL context. */
internal object Egl {
    const val NONE = 0x3038
    const val SURFACE_TYPE = 0x3033
    const val PBUFFER_BIT = 0x0001
    const val RENDERABLE_TYPE = 0x3040
    const val OPENGL_BIT = 0x0008
    const val RED_SIZE = 0x3024
    const val GREEN_SIZE = 0x3023
    const val BLUE_SIZE = 0x3022
    const val WIDTH = 0x3057
    const val HEIGHT = 0x3056
    const val OPENGL_API = 0x30A2
    const val VENDOR = 0x3053
    const val VERSION = 0x3054

    init { Native.register("EGL") }

    @JvmStatic external fun eglGetDisplay(nativeDisplay: Pointer?): Pointer?
    @JvmStatic external fun eglInitialize(dpy: Pointer, major: IntByReference?, minor: IntByReference?): Int
    @JvmStatic external fun eglBindAPI(api: Int): Int
    @JvmStatic external fun eglChooseConfig(dpy: Pointer, attribs: IntArray, configs: PointerByReference, size: Int, num: IntByReference): Int
    @JvmStatic external fun eglCreateContext(dpy: Pointer, config: Pointer, share: Pointer?, attribs: IntArray): Pointer?
    @JvmStatic external fun eglCreatePbufferSurface(dpy: Pointer, config: Pointer, attribs: IntArray): Pointer?
    @JvmStatic external fun eglMakeCurrent(dpy: Pointer, draw: Pointer?, read: Pointer?, ctx: Pointer?): Int
    @JvmStatic external fun eglDestroyContext(dpy: Pointer, ctx: Pointer): Int
    @JvmStatic external fun eglDestroySurface(dpy: Pointer, surface: Pointer): Int
    @JvmStatic external fun eglGetProcAddress(name: String): Pointer?
    @JvmStatic external fun eglGetError(): Int
    @JvmStatic external fun eglQueryString(dpy: Pointer, name: Int): String?
}

/** OpenGL of libOpenGL (glvnd), dispatched to the driver of the current context. */
internal object Gl {
    const val FRAMEBUFFER = 0x8D40
    const val FRAMEBUFFER_COMPLETE = 0x8CD5
    const val COLOR_ATTACHMENT0 = 0x8CE0
    const val TEXTURE_2D = 0x0DE1
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_MAG_FILTER = 0x2800
    const val LINEAR = 0x2601
    const val RGBA8 = 0x8058
    const val BGRA = 0x80E1
    const val UNSIGNED_INT_8_8_8_8_REV = 0x8367
    const val PIXEL_PACK_BUFFER = 0x88EB
    const val MAP_READ_BIT = 0x0001
    const val MAP_PERSISTENT_BIT = 0x0040
    const val MAP_COHERENT_BIT = 0x0080
    const val CLIENT_STORAGE_BIT = 0x0200
    const val PACK_ALIGNMENT = 0x0D05
    const val SYNC_GPU_COMMANDS_COMPLETE = 0x9117
    const val SYNC_FLUSH_COMMANDS_BIT = 0x1
    const val RENDERER = 0x1F01
    const val VERSION = 0x1F02

    init { Native.register("OpenGL") }

    @JvmStatic external fun glGetString(name: Int): String?
    @JvmStatic external fun glGenFramebuffers(n: Int, ids: IntArray)
    @JvmStatic external fun glDeleteFramebuffers(n: Int, ids: IntArray)
    @JvmStatic external fun glBindFramebuffer(target: Int, fb: Int)
    @JvmStatic external fun glFramebufferTexture2D(target: Int, attachment: Int, texTarget: Int, tex: Int, level: Int)
    @JvmStatic external fun glCheckFramebufferStatus(target: Int): Int
    @JvmStatic external fun glGenTextures(n: Int, ids: IntArray)
    @JvmStatic external fun glDeleteTextures(n: Int, ids: IntArray)
    @JvmStatic external fun glBindTexture(target: Int, tex: Int)
    @JvmStatic external fun glTexParameteri(target: Int, name: Int, value: Int)
    @JvmStatic external fun glTexImage2D(target: Int, level: Int, internalFormat: Int, w: Int, h: Int, border: Int, format: Int, type: Int, data: Pointer?)
    @JvmStatic external fun glGenBuffers(n: Int, ids: IntArray)
    @JvmStatic external fun glDeleteBuffers(n: Int, ids: IntArray)
    @JvmStatic external fun glBindBuffer(target: Int, buf: Int)
    /** GL 4.4 / ARB_buffer_storage: immutable storage that can stay mapped. */
    @JvmStatic external fun glBufferStorage(target: Int, size: Long, data: Pointer?, flags: Int)
    @JvmStatic external fun glMapBufferRange(target: Int, offset: Long, length: Long, access: Int): Pointer?
    @JvmStatic external fun glUnmapBuffer(target: Int): Byte
    @JvmStatic external fun glPixelStorei(name: Int, value: Int)
    /** [offset] is into the bound PIXEL_PACK_BUFFER. */
    @JvmStatic external fun glReadPixels(x: Int, y: Int, w: Int, h: Int, format: Int, type: Int, offset: Long)
    @JvmStatic external fun glFenceSync(condition: Int, flags: Int): Pointer?
    @JvmStatic external fun glClientWaitSync(sync: Pointer, flags: Int, timeoutNs: Long): Int
    @JvmStatic external fun glDeleteSync(sync: Pointer)
}

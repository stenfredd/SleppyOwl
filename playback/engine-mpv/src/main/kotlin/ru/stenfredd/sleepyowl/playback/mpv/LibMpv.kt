package ru.stenfredd.sleepyowl.playback.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** The part of libmpv the engine uses (client.h, render.h, render_gl.h; API 2.x, mpv 0.37 of Mint's repository). */
@Suppress("FunctionName")
internal interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_error_string(error: Int): String
    fun mpv_free(data: Pointer?)

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    /** [args] ends with null. */
    fun mpv_command_async(ctx: Pointer, userdata: Long, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, userdata: Long, name: String, format: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_report_swap(ctx: Pointer)
    fun mpv_render_context_free(ctx: Pointer)

    fun interface UpdateCallback : Callback { fun invoke(ctx: Pointer?) }
    fun interface GetProcAddress : Callback { fun invoke(ctx: Pointer?, name: String): Pointer? }

    companion object {
        val INSTANCE: LibMpv by lazy {
            // libmpv needs LC_NUMERIC=C; the JVM sets the user's locale (ru_RU writes decimals with a comma).
            CLib.setlocale(CLib.LC_NUMERIC, "C")
            Native.load("mpv", LibMpv::class.java)
        }

        const val FORMAT_NONE = 0
        const val FORMAT_STRING = 1
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_SEEK = 20
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        const val END_FILE_REASON_ERROR = 4

        const val ERROR_LOADING_FAILED = -13
        const val ERROR_AO_INIT_FAILED = -15
        const val ERROR_VO_INIT_FAILED = -14
        const val ERROR_NOTHING_TO_PLAY = -16
        const val ERROR_UNKNOWN_FORMAT = -17

        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_OPENGL_INIT_PARAMS = 2
        const val RENDER_PARAM_OPENGL_FBO = 3
        const val RENDER_PARAM_ADVANCED_CONTROL = 10

        const val RENDER_UPDATE_FRAME = 1L
    }
}

/** The libc functions the engine needs. */
internal object CLib {
    const val LC_NUMERIC = 1

    init { Native.register("c") }

    @JvmStatic external fun setlocale(category: Int, locale: String?): String?
    @JvmStatic external fun memcpy(dst: Pointer, src: Pointer, n: Long): Pointer
    @JvmStatic external fun setenv(name: String, value: String, overwrite: Int): Int
}

package ru.stenfredd.sleepyowl.desktop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import ru.stenfredd.sleepyowl.core.common.AppDirs
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.common.monotonicMs
import ru.stenfredd.sleepyowl.playback.mpv.MpvEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the player window plays, for the system: the sound applet's title and cover, the media keys' targets. */
@Immutable
data class NowPlaying(
    val itemId: UUID,
    val title: String,
    /** The series of an episode, the year and genres of a movie. */
    val artist: String?,
    /** The server's poster (the series' one for an episode). */
    val artUrl: String?,
    val durationMs: Long,
    /** Playing or about to (buffering); false on pause. */
    val playing: Boolean,
    val canNext: Boolean,
    val canPrevious: Boolean,
)

/** The player window's controls from outside it (media keys, the sound applet): called on the UI thread. */
interface PlayerCommands {
    /** Play (true), pause (false) or toggle (null); the controls show for a moment (§1.3). */
    fun playPause(play: Boolean?)
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun changeVolume(percent: Int)
    /** Brings the player window up. */
    fun bringUp()
    /** Any thread. */
    fun positionMs(): Long
}

/**
 * The player in the system, over the session's D-Bus (§2.5):
 *
 * - **MPRIS** — `org.mpris.MediaPlayer2.sleepyowl` while a player window is open: the media keys (Cinnamon passes them
 *   to the MPRIS player even when another window has the focus) and the sound applet's title, cover, position and
 *   buttons. The cover is fetched through the app's own client (LAN routing) into `~/.cache/sleepyowl/mpris`.
 * - **The screensaver** — `org.freedesktop.ScreenSaver.Inhibit` while the video plays, not on pause. The inhibition
 *   belongs to our D-Bus connection: the system drops it if the app dies.
 *
 * D-Bus calls run on one thread of their own; without a session bus (or with an error) the player simply goes without.
 */
class SystemMedia(private val player: DesktopPlayer, private val http: OkHttpClient, dirs: AppDirs) {
    private val bus = Executors.newSingleThreadExecutor { Thread(it, "dbus").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val covers = File(dirs.files, "mpris")

    private var connection: DBusConnection? = null
    private var failed = false
    private var busName: String? = null
    private val mpris = MprisObject()
    private var inhibitCookie: UInt32? = null

    // What D-Bus clients read (on the bus's threads); set on the UI thread.
    @Volatile private var now: NowPlaying? = null
    @Volatile private var coverFile: File? = null
    @Volatile private var volume = 100
    @Volatile private var muted = false

    /** The properties last announced (PropertiesChanged): only changes are sent. */
    private var announced: Map<String, Variant<*>> = emptyMap()

    fun start() {
        scope.launch {
            snapshotFlow { Triple(player.nowPlaying, player.audio.volume, player.audio.muted) }.collect { (n, v, m) ->
                val old = now
                now = n
                volume = v
                muted = m
                if (n?.artUrl != old?.artUrl || n?.itemId != old?.itemId) fetchCover(n)
                onBus { publish() }
            }
        }
        // MPRIS has no position updates: a jump (a seek, here or from the applet) is announced by `Seeked`.
        scope.launch {
            var lastPos = -1L
            var lastAt = 0L
            var lastItem: UUID? = null
            while (true) {
                delay(1_000)
                val n = now
                val pos = player.commands?.positionMs()
                if (n == null || pos == null) {
                    lastPos = -1
                    continue
                }
                val at = monotonicMs()
                val expected = lastPos + if (n.playing) at - lastAt else 0
                if (lastPos >= 0 && n.itemId == lastItem && abs(pos - expected) > SEEK_JUMP_MS) onBus { seeked(pos) }
                lastPos = pos
                lastAt = at
                lastItem = n.itemId
            }
        }
    }

    /** Before the process ends: the screensaver back, the name released. */
    fun close() {
        onBus {
            uninhibit()
            connection?.let { c ->
                busName?.let { runCatching { c.releaseBusName(it) } }
                runCatching { c.close() }
            }
            connection = null
        }
        bus.shutdown()
        bus.awaitTermination(1, TimeUnit.SECONDS)
    }

    private fun onBus(block: () -> Unit) {
        if (bus.isShutdown) return
        bus.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(e, "D-Bus")
            }
        }
    }

    private fun connection(): DBusConnection? {
        connection?.let { return it }
        if (failed) return null
        return try {
            DBusConnectionBuilder.forSessionBus().withShared(false).build().also {
                connection = it
                it.exportObject(MPRIS_PATH, mpris)
            }
        } catch (e: Exception) {
            failed = true
            Log.w(e, "No session D-Bus: no MPRIS, no screensaver inhibition")
            null
        }
    }

    // --- On the bus thread ---

    private fun publish() {
        val n = now
        val c = connection() ?: return
        if (n?.playing == true) inhibit() else uninhibit()
        if (n == null) {
            busName?.let {
                Log.i("MPRIS: %s released", it)
                runCatching { c.releaseBusName(it) }
            }
            busName = null
            announced = emptyMap()
            return
        }
        if (busName == null) {
            // A second copy of the app (the test stand next to the user's own) takes an instance name, as the spec allows.
            busName = listOf(MPRIS_NAME, "$MPRIS_NAME.instance${ProcessHandle.current().pid()}").firstOrNull { name ->
                runCatching { c.requestBusName(name) }.isSuccess
            }
            Log.i("MPRIS: %s", busName ?: "no name")
            announced = playerProperties()
            return
        }
        val props = playerProperties()
        val changed = props.filter { (k, v) -> k != "Position" && announced[k]?.let(::contentOf) != contentOf(v) }
        announced = props
        if (changed.isNotEmpty()) c.sendMessage(Properties.PropertiesChanged(MPRIS_PATH, PLAYER_INTERFACE, changed, emptyList()))
    }

    private fun seeked(positionMs: Long) {
        if (busName == null) return
        connection()?.sendMessage(MprisPlayer.Seeked(MPRIS_PATH, positionMs * 1000))
    }

    private fun inhibit() {
        if (inhibitCookie != null) return
        val c = connection() ?: return
        inhibitCookie = try {
            c.getRemoteObject(SCREENSAVER_NAME, SCREENSAVER_PATH, ScreenSaver::class.java).Inhibit("SleepyOwl", "Воспроизведение видео")
                .also { Log.i("Screensaver inhibited (%d)", it.toLong()) }
        } catch (e: Exception) {
            Log.w("Screensaver not inhibited: %s", e.message)
            UInt32(0)
        }
    }

    private fun uninhibit() {
        val cookie = inhibitCookie ?: return
        inhibitCookie = null
        if (cookie.toLong() == 0L) return
        try {
            connection()?.getRemoteObject(SCREENSAVER_NAME, SCREENSAVER_PATH, ScreenSaver::class.java)?.UnInhibit(cookie)
            Log.i("Screensaver allowed (%d)", cookie.toLong())
        } catch (e: Exception) {
            Log.w("Screensaver inhibition not lifted: %s", e.message)
        }
    }

    // --- The cover ---

    private fun fetchCover(n: NowPlaying?) {
        coverFile = null
        val url = n?.artUrl ?: return
        scope.launch(Dispatchers.IO) {
            val file = File(covers, "%08x.jpg".format(url.hashCode()))
            try {
                if (!file.isFile) {
                    covers.mkdirs()
                    // Only the current cover is kept.
                    covers.listFiles()?.forEach { it.delete() }
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        check(r.isSuccessful) { "HTTP ${r.code}" }
                        val tmp = File(covers, file.name + ".part")
                        tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                        tmp.renameTo(file)
                    }
                }
                if (now?.artUrl == url) {
                    coverFile = file
                    onBus { publish() }
                }
            } catch (e: Exception) {
                Log.w("MPRIS: cover not fetched: %s", e.message)
            }
        }
    }

    // --- MPRIS properties ---

    private fun rootProperties(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(false),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("SleepyOwl"),
        "DesktopEntry" to Variant("sleepyowl"),
        "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"),
        "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
    )

    private fun playerProperties(): Map<String, Variant<*>> {
        val n = now
        return mapOf(
            "PlaybackStatus" to Variant(
                when {
                    n == null -> "Stopped"
                    n.playing -> "Playing"
                    else -> "Paused"
                },
            ),
            "Rate" to Variant(1.0),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "Metadata" to Variant(metadata(n), "a{sv}"),
            "Volume" to Variant(if (muted) 0.0 else volume / 100.0),
            "Position" to Variant((player.commands?.positionMs() ?: 0L) * 1000),
            "CanGoNext" to Variant(n?.canNext == true),
            "CanGoPrevious" to Variant(n?.canPrevious == true),
            "CanPlay" to Variant(n != null),
            "CanPause" to Variant(n != null),
            "CanSeek" to Variant(n != null && n.durationMs > 0),
            "CanControl" to Variant(true),
        )
    }

    private fun metadata(n: NowPlaying?): Map<String, Variant<*>> {
        n ?: return mapOf("mpris:trackid" to Variant(DBusPath(NO_TRACK)))
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(trackPath(n.itemId))))
            if (n.durationMs > 0) put("mpris:length", Variant(n.durationMs * 1000))
            put("xesam:title", Variant(n.title))
            n.artist?.let { put("xesam:artist", Variant(arrayOf(it), "as")) }
            coverFile?.let { put("mpris:artUrl", Variant(it.toPath().toUri().toString())) }
        }
    }

    /** A variant's value comparable by content (Variant.equals compares arrays by reference). */
    private fun contentOf(v: Variant<*>): String = when (val value = v.value) {
        is Array<*> -> value.contentDeepToString()
        is Map<*, *> -> value.entries.joinToString { (k, x) -> "$k=" + ((x as? Variant<*>)?.let(::contentOf) ?: x) }
        else -> value.toString()
    }

    private fun trackPath(id: UUID) = "/ru/stenfredd/sleepyowl/track/" + id.toString().replace("-", "")

    /** Commands from D-Bus go to the window on the UI thread. */
    private fun command(block: PlayerCommands.() -> Unit) = SwingUtilities.invokeLater { player.commands?.block() }

    private inner class MprisObject : MprisRoot, MprisPlayer, Properties {
        override fun getObjectPath() = MPRIS_PATH

        override fun Raise() = command { bringUp() }
        override fun Quit() = Unit

        override fun Next() = command { next() }
        override fun Previous() = command { previous() }
        override fun Pause() = command { playPause(false) }
        override fun PlayPause() = command { playPause(null) }
        // A player window has nothing between "paused" and "closed": Stop pauses (closing is the window's ×).
        override fun Stop() = command { playPause(false) }
        override fun Play() = command { playPause(true) }
        override fun Seek(offset: Long) = command { seekTo(positionMs() + offset / 1000) }
        override fun SetPosition(trackId: DBusPath, position: Long) {
            val n = now ?: return
            if (trackId.path == trackPath(n.itemId) && position >= 0 && position / 1000 <= n.durationMs) command { seekTo(position / 1000) }
        }
        override fun OpenUri(uri: String) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            ROOT_INTERFACE -> rootProperties()
            PLAYER_INTERFACE -> playerProperties()
            else -> emptyMap()
        }

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            if (interfaceName != PLAYER_INTERFACE || propertyName != "Volume") return
            val v = ((value as? Variant<*>)?.value ?: value) as? Double ?: return
            command { changeVolume((v * 100).roundToInt().coerceIn(0, MpvEngine.MAX_VOLUME)) }
        }
    }

    private companion object {
        const val MPRIS_NAME = "org.mpris.MediaPlayer2.sleepyowl"
        const val MPRIS_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
        const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"
        const val SCREENSAVER_NAME = "org.freedesktop.ScreenSaver"
        const val SCREENSAVER_PATH = "/org/freedesktop/ScreenSaver"

        /** A position this far from where it should be was a seek. */
        const val SEEK_JUMP_MS = 2_000
    }
}

// D-Bus interfaces: dbus-java takes method names as they are, hence the capitals.

@Suppress("FunctionName")
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@Suppress("FunctionName")
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    /** The position jumped, µs. */
    class Seeked(path: String, position: Long) : DBusSignal(path, position)
}

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.ScreenSaver")
interface ScreenSaver : DBusInterface {
    fun Inhibit(applicationName: String, reason: String): UInt32
    fun UnInhibit(cookie: UInt32)
}

package ru.stenfredd.sleepyowl.desktop

import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.core.jellyfin.ServerRouting
import ru.stenfredd.sleepyowl.ui.Screen
import java.io.File

/**
 * "Смотреть" until the desktop player (L6–L7): the L1 prototype (`desktop-proto`) in its own process, playing the file
 * straight from the server in its mini window. It knows nothing of the app: no tracks chosen on the page, no progress
 * reported, no next episode. Needs the repository (the prototype's classes and `tools/linux/l1/proto.sh`): the path
 * comes in the system property `sleepyowl.repo`, set by `desktop.sh` and `./gradlew :desktop:run`.
 */
class ProtoPlayer(private val session: JellyfinSession, private val routing: ServerRouting) {
    private var process: Process? = null

    fun play(screen: Screen.Player) {
        val script = System.getProperty("sleepyowl.repo")?.let { File(it, "tools/linux/l1/proto.sh") }
        if (script == null || !script.canExecute()) {
            Log.w("Player: the prototype is not found (sleepyowl.repo=%s)", System.getProperty("sleepyowl.repo"))
            return
        }
        val base = routing.localUrl ?: session.session.value?.serverUrl?.trimEnd('/') ?: return
        val id = screen.itemId.toString().replace("-", "")
        // Static streams are served without a token.
        val url = "$base/Videos/$id/stream?static=true&mediaSourceId=$id"
        val start = screen.startFraction?.let { "%.1f%%".format(java.util.Locale.ROOT, it * 100) } ?: "%.3f".format(java.util.Locale.ROOT, screen.startPositionMs / 1000.0)
        // One player at a time, as the desktop player will be.
        process?.takeIf { it.isAlive }?.destroy()
        Log.i("Player: prototype for %s from %s", id, start)
        val dir = DesktopPaths.log.apply { mkdirs() }
        process = runCatching {
            ProcessBuilder(script.path, "--url", url, "--start", start)
                .apply { environment()["PROTO_NOBUILD"] = "1" }
                // Its crash reports (hs_err_pid*.log) go beside the logs, not into the current directory.
                .directory(dir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "proto.log")))
                .start()
        }.onFailure { Log.w(it, "Player: prototype did not start") }.getOrNull()
    }
}

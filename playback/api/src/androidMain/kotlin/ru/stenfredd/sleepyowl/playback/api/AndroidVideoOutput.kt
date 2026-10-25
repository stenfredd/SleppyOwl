package ru.stenfredd.sleepyowl.playback.api

import android.widget.FrameLayout

/** Engines that show video as Android views (surface, subtitle overlay) inside the player's container. */
interface AndroidVideoOutput {
    /** Adds the engine's own video views into [container]. */
    fun attachVideo(container: FrameLayout)
    fun detachVideo()
}

/** [VideoHost] of the TV player: each engine puts its views into [container]. */
class FrameLayoutVideoHost(private val container: FrameLayout) : VideoHost {
    override fun show(engine: PlaybackEngine) {
        (engine as? AndroidVideoOutput)?.attachVideo(container)
    }

    override fun hide(engine: PlaybackEngine) {
        (engine as? AndroidVideoOutput)?.detachVideo()
    }
}

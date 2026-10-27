package ru.stenfredd.sleepyowl.playback.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderSizeTest {
    @Test
    fun smallVideoInBigWindowIsDrawnAtItsOwnSize() {
        // A 2.4:1 film on 3440×1440 (2.39:1): the FBO keeps the window's shape, the film fills its width.
        assertEquals(1728 to 723, MpvVideo.renderSize(3440, 1440, 1728, 720))
        // 16:9 1080p: the film fills the height, the bars are left and right.
        assertEquals(2580 to 1080, MpvVideo.renderSize(3440, 1440, 1920, 1080))
    }

    @Test
    fun windowSmallerThanVideoIsDrawnAtWindowSize() {
        assertEquals(640 to 360, MpvVideo.renderSize(640, 360, 3840, 2160))
        assertEquals(3440 to 1440, MpvVideo.renderSize(3440, 1440, 3840, 1600))
    }

    @Test
    fun unknownVideoSizeTakesTheWindow() {
        assertEquals(1024 to 576, MpvVideo.renderSize(1024, 576, 0, 0))
    }
}

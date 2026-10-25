package ru.stenfredd.sleepyowl.ui

/** What the shared view models need to know about the platform; the TV app and the desktop app provide it. */
interface UiPlatform {
    /** Pixels per dp: server images are requested at exactly the size they are shown. */
    val density: Float

    /** Version line of "О приложении": "0.1.0 (1) · release". */
    val version: String

    /** The device line of "О приложении": "Xiaomi MiTV-MOEU1 · Android 14". */
    val device: String

    /** The debug tour can be started from the settings (a debug build with its list of files). */
    val tourAvailable: Boolean
}

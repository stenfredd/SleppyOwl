package ru.stenfredd.sleepyowl.core.jellyfin

import org.jellyfin.sdk.JellyfinOptions

/** What [JellyfinSession] takes from the platform it runs on. */
interface JellyfinPlatform {
    /** The device's name in the server's list of devices. */
    val deviceName: String

    /** The platform's part of the SDK setup. */
    fun configure(options: JellyfinOptions.Builder) {}
}

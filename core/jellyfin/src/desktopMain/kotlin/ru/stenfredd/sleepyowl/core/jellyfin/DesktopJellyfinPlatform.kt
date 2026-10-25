package ru.stenfredd.sleepyowl.core.jellyfin

import java.io.File

class DesktopJellyfinPlatform : JellyfinPlatform {
    // /etc/hostname rather than InetAddress: that one may wait for DNS.
    override val deviceName = "SleepyOwl · " + (runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.ifEmpty { null } ?: "Linux")
}

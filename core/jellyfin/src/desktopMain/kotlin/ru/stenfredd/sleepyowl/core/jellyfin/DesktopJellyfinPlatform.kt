package ru.stenfredd.sleepyowl.core.jellyfin

import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.model.DeviceInfo
import java.io.File
import java.util.UUID

class DesktopJellyfinPlatform : JellyfinPlatform {
    // /etc/hostname rather than InetAddress: that one may wait for DNS.
    override val deviceName = "SleepyOwl · " + (readLine("/etc/hostname") ?: "Linux")

    /**
     * The SDK's default device: on Android it comes from the system, on the desktop it has to be given — server
     * discovery and probing (`ServerLogin.find`) create their API clients with it. Signed-in clients use the
     * account's saved device id instead. The id is this machine's (`/etc/machine-id`), hashed.
     */
    override fun configure(options: JellyfinOptions.Builder) {
        val machine = readLine("/etc/machine-id") ?: deviceName
        options.deviceInfo = DeviceInfo(id = UUID.nameUUIDFromBytes(machine.toByteArray()).toString(), name = deviceName)
    }

    private fun readLine(path: String) = runCatching { File(path).readText().trim() }.getOrNull()?.ifEmpty { null }
}

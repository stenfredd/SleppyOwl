package ru.stenfredd.sleepyowl.playback.core

import ru.stenfredd.sleepyowl.playback.api.DeviceCapabilities

/** What this device can decode and output; probed once, on first use. */
interface DeviceCapabilitiesProbe {
    val capabilities: DeviceCapabilities
}

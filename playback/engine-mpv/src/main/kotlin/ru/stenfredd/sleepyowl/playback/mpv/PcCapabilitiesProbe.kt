package ru.stenfredd.sleepyowl.playback.mpv

import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.playback.api.DeviceCapabilities
import ru.stenfredd.sleepyowl.playback.core.DeviceCapabilitiesProbe
import java.io.File

/**
 * The PC as the stream info shows it. Decoders are mpv's business (it picks NVDEC/VAAPI or the CPU per file), so
 * there is nothing to probe for the engine choice: only names for the diagnostics.
 */
class PcCapabilitiesProbe : DeviceCapabilitiesProbe {
    override val capabilities: DeviceCapabilities by lazy { probe().also { Log.i("Device capabilities: %s", it) } }

    private fun probe() = DeviceCapabilities(
        manufacturer = "PC",
        model = read("/etc/hostname") ?: "Linux",
        soc = read("/proc/cpuinfo")?.lineSequence()?.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
            ?: System.getProperty("os.arch"),
        sdkInt = 0,
        abis = listOf(System.getProperty("os.arch")),
        videoDecoders = emptyList(),
        audioDecoders = emptyList(),
        displayHdrTypes = emptySet(),
        displayModes = emptyList(),
        passthroughEncodings = emptySet(),
    )

    private fun read(path: String) = runCatching { File(path).readText().trim() }.getOrNull()?.ifEmpty { null }
}

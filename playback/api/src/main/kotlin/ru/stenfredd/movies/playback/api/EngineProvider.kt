package ru.stenfredd.movies.playback.api

import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSourceInfo

/**
 * Plugin entry point of an engine module. Bound into a Hilt `Set<EngineProvider>` with `@IntoSet`,
 * so adding an engine means adding a module, nothing else changes.
 */
interface EngineProvider {
    val id: EngineId
    val displayName: String

    /** Lower value wins when two engines are equally good. */
    val priority: Int

    /** Can this engine play [source] with [selection] directly on this device, and at what cost? */
    fun evaluate(source: MediaSourceInfo, selection: StreamSelection, device: DeviceCapabilities): Verdict

    /** Profile sent to the server in PlaybackInfo when this engine is used. */
    fun deviceProfile(device: DeviceCapabilities): DeviceProfile

    fun create(device: DeviceCapabilities): PlaybackEngine
}

data class StreamSelection(
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
)

sealed interface Verdict {
    data class Direct(val notes: List<String> = emptyList()) : Verdict
    data class Degraded(val losses: Set<Loss>, val notes: List<String> = emptyList()) : Verdict
    data class No(val reasons: List<String>) : Verdict
}

enum class Loss(val weight: Int, val label: String) {
    HDR_LOST(100, "HDR будет потерян"),
    SOFTWARE_DECODE_HEAVY(40, "тяжёлое программное декодирование"),
    SOFTWARE_DECODE(15, "программное декодирование"),
    DV_TO_BASE_LAYER(30, "Dolby Vision → базовый слой"),
    HDR_UNVERIFIED(25, "HDR-вывод не проверен"),
    ASS_SIMPLIFIED(20, "ASS упрощён"),
    /** Small: TV SoCs usually deinterlace in the display pipeline after the hardware decoder. */
    NO_DEINTERLACE(5, "без деинтерлейса в плеере"),
}

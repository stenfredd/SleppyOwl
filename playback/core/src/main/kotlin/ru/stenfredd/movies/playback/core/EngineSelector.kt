package ru.stenfredd.movies.playback.core

import org.jellyfin.sdk.model.api.MediaSourceInfo
import ru.stenfredd.movies.playback.api.DeviceCapabilities
import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.EngineProvider
import ru.stenfredd.movies.playback.api.Loss
import ru.stenfredd.movies.playback.api.StreamSelection
import ru.stenfredd.movies.playback.api.Verdict
import javax.inject.Inject

data class RankedEngine(val provider: EngineProvider, val verdict: Verdict)

/**
 * Orders engines for a given file: direct play first (by priority), then degraded ones by the weight
 * of what is lost (HDR loss is the worst), then engines that say "no" — those can still play a
 * server transcode as the last resort.
 */
class EngineSelector @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards EngineProvider>,
) {
    val engines: List<EngineProvider> get() = providers.sortedBy { it.priority }

    fun provider(id: EngineId) = providers.first { it.id == id }

    fun rank(source: MediaSourceInfo, selection: StreamSelection, device: DeviceCapabilities): List<RankedEngine> =
        providers.map { RankedEngine(it, it.evaluate(source, selection, device)) }
            .sortedWith(compareBy<RankedEngine> { score(it.verdict) }.thenBy { it.provider.priority })

    fun score(verdict: Verdict): Int = when (verdict) {
        is Verdict.Direct -> 0
        is Verdict.Degraded -> verdict.losses.sumOf(Loss::weight)
        is Verdict.No -> 10_000
    }
}

fun Verdict.describe(): String = when (this) {
    is Verdict.Direct -> "напрямую" + notes.joinToString(prefix = if (notes.isEmpty()) "" else ": ")
    is Verdict.Degraded -> "с потерями: " + (losses.map { it.label } + notes).joinToString()
    is Verdict.No -> "не может: " + reasons.joinToString()
}

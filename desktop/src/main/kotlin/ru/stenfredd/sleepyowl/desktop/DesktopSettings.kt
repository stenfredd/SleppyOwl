package ru.stenfredd.sleepyowl.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.common.PreferenceStores

/** "Открывать плеер": in the last mode (§1.1) or always in one. */
enum class PlayerOpening(val mode: PlayerMode?) { Last(null), Mini(PlayerMode.Mini), Window(PlayerMode.Window), Fullscreen(PlayerMode.Fullscreen) }

/**
 * The desktop's own settings (L9) in `desktop.preferences_pb`: read once before the first window, applied at once when
 * changed. "На всех рабочих столах" lives with the player's places ([PlayerPlacement.sticky]).
 */
class DesktopSettings(stores: PreferenceStores) {
    private val store = stores["desktop"]
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val saved: Preferences? = try {
        runBlocking(Dispatchers.IO) { withTimeoutOrNull(2_000) { store.data.first() } }
    } catch (e: Exception) {
        Log.w(e, "Desktop settings not read")
        null
    }

    /** "Масштаб интерфейса", %: 100 = the TV's dp as pixels (§1.4), times the system's own scale. */
    var scalePercent by mutableIntStateOf(saved?.get(KEY_SCALE)?.takeIf { it in SCALES } ?: 100)
        private set

    /** `SLEEPYOWL_SCALE=1.25` overrides the setting (test runs). */
    val scaleOverride: Float? = System.getenv("SLEEPYOWL_SCALE")?.toFloatOrNull()?.coerceIn(0.5f, 3f)

    val scaleFactor: Float get() = scaleOverride ?: (scalePercent / 100f)

    var playerOpening by mutableStateOf(saved?.get(KEY_PLAYER_OPENING)?.let { n -> PlayerOpening.entries.firstOrNull { it.name == n } } ?: PlayerOpening.Last)
        private set

    fun changeScale(percent: Int) {
        scalePercent = percent
        write { it[KEY_SCALE] = percent }
    }

    fun changePlayerOpening(opening: PlayerOpening) {
        playerOpening = opening
        write { it[KEY_PLAYER_OPENING] = opening.name }
    }

    private fun write(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch {
            try {
                store.edit(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(e, "Desktop settings not saved")
            }
        }
    }

    companion object {
        val SCALES = listOf(80, 90, 100, 110, 125, 150)
        private val KEY_SCALE = intPreferencesKey("ui_scale")
        private val KEY_PLAYER_OPENING = stringPreferencesKey("player_opening")
    }
}

/** A window's content at "Масштаб интерфейса": changes apply at once. */
@Composable
fun ScaledContent(settings: DesktopSettings, content: @Composable () -> Unit) {
    val system = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(system.density * settings.scaleFactor, system.fontScale), content = content)
}

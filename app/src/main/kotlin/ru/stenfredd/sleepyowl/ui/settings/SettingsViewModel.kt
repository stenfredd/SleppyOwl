package ru.stenfredd.sleepyowl.ui.settings

import android.content.Context
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.sleepyowl.BuildConfig
import ru.stenfredd.sleepyowl.core.data.LanguageSettings
import ru.stenfredd.sleepyowl.core.data.ServerInfo
import ru.stenfredd.sleepyowl.core.data.UserSettingsRepository
import ru.stenfredd.sleepyowl.core.jellyfin.Account
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.sleepyowl.playback.core.PlaybackDefaults
import ru.stenfredd.sleepyowl.playback.core.PlaybackPreferences
import ru.stenfredd.sleepyowl.ui.TourEntry
import timber.log.Timber
import javax.inject.Inject

/** Server-side language settings: loading, loaded or failed (with the reason). */
@Immutable
data class LanguagesState(val settings: LanguageSettings? = null, val error: String? = null, val saving: Boolean = false)

@Immutable
data class AccountsState(
    val current: Account? = null,
    val others: List<Account> = emptyList(),
    /** LAN address the app talks to at home, null = through the public address. */
    val localUrl: String? = null,
    val server: ServerInfo? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val session: JellyfinSession,
    private val prefs: PlaybackPreferences,
    private val userSettings: UserSettingsRepository,
    private val probe: DeviceCapabilitiesProbe,
) : ViewModel() {
    val defaults: StateFlow<PlaybackDefaults> = prefs.defaults.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackDefaults())
    val subtitleStyle: StateFlow<SubtitleStyle> = prefs.subtitleStyle.stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleStyle())

    private val _languages = MutableStateFlow(LanguagesState())
    val languages: StateFlow<LanguagesState> = _languages.asStateFlow()

    private val server = MutableStateFlow<ServerInfo?>(null)
    val accounts: StateFlow<AccountsState> = combine(session.session, session.accounts, server) { s, all, info ->
        AccountsState(
            current = all.firstOrNull { it.id == s?.accountId },
            others = all.filter { it.id != s?.accountId },
            localUrl = session.localUrl,
            server = info,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountsState())

    /** The TV can show Dolby Vision through its own decoder: only then the choice is offered. */
    var dolbyVisionCapable by mutableStateOf(false)
        private set

    val tourAvailable = BuildConfig.DEBUG && TourEntry.available(context)

    val about = About(
        version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
        device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
    )

    init {
        loadLanguages()
        viewModelScope.launch {
            server.value = runCatching { userSettings.server() }.onFailure { Timber.w(it, "Settings: server info") }.getOrNull()
        }
        viewModelScope.launch {
            dolbyVisionCapable = withContext(Dispatchers.Default) {
                val device = probe.capabilities
                device.hardwareVideoDecoders(DV_MIME).isNotEmpty() && device.displaySupportsHdr(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
            }
        }
    }

    fun loadLanguages() {
        _languages.value = LanguagesState()
        viewModelScope.launch {
            _languages.value = try {
                LanguagesState(userSettings.languages())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Settings: languages")
                LanguagesState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Shown at once, saved on the server in the background; on failure the old settings come back with the error. */
    fun setLanguages(settings: LanguageSettings) {
        val old = _languages.value.settings ?: return
        if (settings == old) return
        _languages.value = LanguagesState(settings, saving = true)
        viewModelScope.launch {
            _languages.value = try {
                userSettings.setLanguages(settings)
                LanguagesState(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Settings: saving languages")
                LanguagesState(old, error = "Не удалось сохранить: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun setDefaults(defaults: PlaybackDefaults) {
        viewModelScope.launch { prefs.setDefaults(defaults) }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        viewModelScope.launch { prefs.setSubtitleStyle(style) }
    }

    suspend fun logout() = session.logout()

    suspend fun switchTo(accountId: String) = session.switchTo(accountId)

    fun forget(accountId: String) {
        viewModelScope.launch { session.forget(accountId) }
    }

    private companion object {
        const val DV_MIME = "video/dolby-vision"
    }
}

@Immutable
data class About(val version: String, val device: String)

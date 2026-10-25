package ru.stenfredd.sleepyowl.core.data

import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.jellyfin.sdk.model.api.UserConfiguration
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession

/**
 * Default audio and subtitle languages. They are the user's settings on the server (every Jellyfin app of the user
 * uses them): the server applies them when it picks a file's default tracks, which the movie page and the player start with.
 */
data class LanguageSettings(
    /** ISO 639-2 ("rus", "eng"), null = the file's default track. */
    val audio: String?,
    val subtitleMode: SubtitleMode,
    /** ISO 639-2, null = any language. */
    val subtitleLanguage: String?,
)

enum class SubtitleMode { Default, Always, OnlyForced, Smart, None }

/** Server name, version and address for the settings screen. */
data class ServerInfo(val name: String, val version: String?)

class UserSettingsRepository(
    private val session: JellyfinSession,
    private val cache: SessionCache,
) {
    suspend fun languages(): LanguageSettings = configuration().toLanguages()

    /**
     * Saves [settings] on the server; cached details (with the default tracks picked by the old settings) are dropped.
     * Only what changed is written: other apps may keep values this screen doesn't show (a language with "default track").
     */
    suspend fun setLanguages(settings: LanguageSettings) {
        val userId = session.requireSession().userId
        val old = configuration()
        val shown = old.toLanguages()
        var config = old
        if (settings.audio != shown.audio) {
            config = config.copy(
                audioLanguagePreference = settings.audio.orEmpty(),
                // With this on the server takes the file's default track whatever the language.
                playDefaultAudioTrack = settings.audio == null,
            )
        }
        if (settings.subtitleMode != shown.subtitleMode) config = config.copy(subtitleMode = settings.subtitleMode.toApi())
        if (settings.subtitleLanguage != shown.subtitleLanguage) config = config.copy(subtitleLanguagePreference = settings.subtitleLanguage.orEmpty())
        if (config == old) return
        session.call { userApi.updateUserConfiguration(userId, config) }
        cache.dropUserData()
    }

    suspend fun server(): ServerInfo {
        val info = cache.get("server", SessionCache.Kind.Stable) { session.call { systemApi.getPublicSystemInfo().content } }
        return ServerInfo(info.serverName.orEmpty(), info.version)
    }

    private fun UserConfiguration.toLanguages() = LanguageSettings(
        audio = audioLanguagePreference?.takeIf { it.isNotBlank() && !playDefaultAudioTrack },
        subtitleMode = subtitleMode.toMode(),
        subtitleLanguage = subtitleLanguagePreference?.takeIf { it.isNotBlank() },
    )

    private suspend fun configuration(): UserConfiguration =
        checkNotNull(session.call { userApi.getCurrentUser().content }.configuration) { "Сервер не вернул настройки пользователя" }

    private fun SubtitlePlaybackMode.toMode() = when (this) {
        SubtitlePlaybackMode.ALWAYS -> SubtitleMode.Always
        SubtitlePlaybackMode.ONLY_FORCED -> SubtitleMode.OnlyForced
        SubtitlePlaybackMode.SMART -> SubtitleMode.Smart
        SubtitlePlaybackMode.NONE -> SubtitleMode.None
        SubtitlePlaybackMode.DEFAULT -> SubtitleMode.Default
    }

    private fun SubtitleMode.toApi() = when (this) {
        SubtitleMode.Always -> SubtitlePlaybackMode.ALWAYS
        SubtitleMode.OnlyForced -> SubtitlePlaybackMode.ONLY_FORCED
        SubtitleMode.Smart -> SubtitlePlaybackMode.SMART
        SubtitleMode.None -> SubtitlePlaybackMode.NONE
        SubtitleMode.Default -> SubtitlePlaybackMode.DEFAULT
    }
}

package ru.stenfredd.sleepyowl.playback.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.playback.api.VideoPathSwitchable
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackStore by preferencesDataStore("playback")

/**
 * A remembered track, by what identifies it across files of one series: an episode's stream indexes differ from the
 * next one's, but "Русский · HDRezka, AC3" stays the same.
 */
data class TrackPick(val language: String?, val name: String?, val codec: String?) {
    /** The candidate that is the same track: language must agree, then the track's own title, then the codec. */
    fun <T> bestOf(candidates: List<T>, describe: (T) -> TrackPick): T? = candidates
        .map { it to describe(it) }
        .filter { (_, c) -> c.language.equals(language, ignoreCase = true) || (language == null && c.name != null && c.name == name) }
        .maxByOrNull { (_, c) -> (if (c.name == name) 4 else 0) + (if (c.codec.equals(codec, ignoreCase = true)) 2 else 0) }
        ?.first
}

/**
 * Playback defaults from the settings screen, for everyone on this TV (they depend on the TV and the network).
 * [maxBitrate] null = the original file; [dolbyVision] = decode Dolby Vision files with the DV decoder.
 * [skipIntro]: openings, recaps, previews, ads; [skipCredits]: credits (then the next episode).
 */
data class PlaybackDefaults(
    val maxBitrate: Int? = null,
    val videoPath: VideoPathSwitchable.Mode = VideoPathSwitchable.Mode.AUTO,
    val dolbyVision: Boolean = false,
    val skipIntro: SkipMode = SkipMode.Button,
    val skipCredits: SkipMode = SkipMode.Button,
)

/** What the player does with a part of the video the server marked as skippable. */
enum class SkipMode {
    /** Offers "Пропустить …". */
    Button,
    /** Skips it on its own. */
    Auto,
    /** Plays it as is. */
    Off,
}

/** What the user picked last for a movie or a series. [subtitle] null with [subtitleOff] = subtitles off. */
data class RememberedTracks(val audio: TrackPick?, val subtitle: TrackPick?, val subtitleOff: Boolean)

/**
 * Player settings kept on the device: the look of text subtitles and the playback defaults (for everyone on this TV),
 * and the tracks chosen per movie or series (per account: family members watch with different tracks).
 */
@Singleton
class PlaybackPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: JellyfinSession,
) {
    val subtitleStyle: Flow<SubtitleStyle> = context.playbackStore.data.map { parseStyle(it[KEY_SUBTITLE_STYLE]) }

    suspend fun subtitleStyle(): SubtitleStyle = subtitleStyle.first()

    suspend fun setSubtitleStyle(style: SubtitleStyle) {
        context.playbackStore.edit { it[KEY_SUBTITLE_STYLE] = "${style.size}|${style.color}|${style.background}" }
    }

    val defaults: Flow<PlaybackDefaults> = context.playbackStore.data.map { prefs ->
        PlaybackDefaults(
            maxBitrate = prefs[KEY_MAX_BITRATE]?.takeIf { it > 0 },
            videoPath = prefs[KEY_VIDEO_PATH]?.let { runCatching { VideoPathSwitchable.Mode.valueOf(it) }.getOrNull() } ?: VideoPathSwitchable.Mode.AUTO,
            dolbyVision = prefs[KEY_DOLBY_VISION] ?: false,
            skipIntro = prefs[KEY_SKIP_INTRO].toSkipMode(),
            skipCredits = prefs[KEY_SKIP_CREDITS].toSkipMode(),
        )
    }

    suspend fun defaults(): PlaybackDefaults = defaults.first()

    suspend fun setDefaults(defaults: PlaybackDefaults) {
        context.playbackStore.edit {
            it[KEY_MAX_BITRATE] = defaults.maxBitrate ?: 0
            it[KEY_VIDEO_PATH] = defaults.videoPath.name
            it[KEY_DOLBY_VISION] = defaults.dolbyVision
            it[KEY_SKIP_INTRO] = defaults.skipIntro.name
            it[KEY_SKIP_CREDITS] = defaults.skipCredits.name
        }
    }

    private fun String?.toSkipMode(): SkipMode = this?.let { runCatching { SkipMode.valueOf(it) }.getOrNull() } ?: SkipMode.Button

    /** [key]: the series of an episode, else the item itself. */
    suspend fun tracks(key: UUID): RememberedTracks? {
        val raw = context.playbackStore.data.first()[tracksKey(key) ?: return null] ?: return null
        return runCatching {
            val json = JSONObject(raw)
            RememberedTracks(
                audio = json.optJSONObject("a")?.toPick(),
                subtitle = json.optJSONObject("s")?.toPick(),
                subtitleOff = json.optBoolean("off"),
            )
        }.onFailure { Timber.w(it, "Bad remembered tracks %s", raw) }.getOrNull()
    }

    suspend fun rememberAudio(key: UUID, pick: TrackPick) = update(key) { it.put("a", pick.toJson()) }

    /** null = subtitles off. */
    suspend fun rememberSubtitle(key: UUID, pick: TrackPick?) = update(key) {
        if (pick == null) it.remove("s") else it.put("s", pick.toJson())
        it.put("off", pick == null)
    }

    private suspend fun update(key: UUID, change: (JSONObject) -> Unit) {
        val prefKey = tracksKey(key) ?: return
        context.playbackStore.edit { prefs ->
            val json = prefs[prefKey]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            change(json)
            prefs[prefKey] = json.toString()
        }
    }

    private fun tracksKey(key: UUID): Preferences.Key<String>? {
        val account = session.session.value?.accountId ?: return null
        return stringPreferencesKey("tracks/$account/$key")
    }

    private fun parseStyle(raw: String?): SubtitleStyle {
        val parts = raw?.split('|') ?: return SubtitleStyle()
        return runCatching {
            SubtitleStyle(SubtitleStyle.Size.valueOf(parts[0]), parts[1].toInt(), SubtitleStyle.Background.valueOf(parts[2]))
        }.getOrDefault(SubtitleStyle())
    }

    private fun TrackPick.toJson() = JSONObject().apply {
        language?.let { put("l", it) }
        name?.let { put("n", it) }
        codec?.let { put("c", it) }
    }

    private fun JSONObject.toPick() = TrackPick(optString("l").ifEmpty { null }, optString("n").ifEmpty { null }, optString("c").ifEmpty { null })

    private companion object {
        val KEY_SUBTITLE_STYLE = stringPreferencesKey("subtitle_style")
        val KEY_MAX_BITRATE = intPreferencesKey("max_bitrate")
        val KEY_VIDEO_PATH = stringPreferencesKey("video_path")
        val KEY_DOLBY_VISION = booleanPreferencesKey("dolby_vision")
        val KEY_SKIP_INTRO = stringPreferencesKey("skip_intro")
        val KEY_SKIP_CREDITS = stringPreferencesKey("skip_credits")
    }
}

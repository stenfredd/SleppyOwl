package ru.stenfredd.sleepyowl.playback.core

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.common.PreferenceStores
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.playback.api.VideoPathSwitchable
import java.util.UUID

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
class PlaybackPreferences(
    stores: PreferenceStores,
    private val session: JellyfinSession,
) {
    private val playbackStore = stores["playback"]

    val subtitleStyle: Flow<SubtitleStyle> = playbackStore.data.map { parseStyle(it[KEY_SUBTITLE_STYLE]) }

    suspend fun subtitleStyle(): SubtitleStyle = subtitleStyle.first()

    suspend fun setSubtitleStyle(style: SubtitleStyle) {
        playbackStore.edit { it[KEY_SUBTITLE_STYLE] = "${style.size}|${style.color}|${style.background}" }
    }

    val defaults: Flow<PlaybackDefaults> = playbackStore.data.map { prefs ->
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
        playbackStore.edit {
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
        val raw = playbackStore.data.first()[tracksKey(key) ?: return null] ?: return null
        return runCatching {
            val json = Json.parseToJsonElement(raw).jsonObject
            RememberedTracks(
                audio = (json["a"] as? JsonObject)?.toPick(),
                subtitle = (json["s"] as? JsonObject)?.toPick(),
                subtitleOff = json["off"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.onFailure { Log.w(it, "Bad remembered tracks %s", raw) }.getOrNull()
    }

    suspend fun rememberAudio(key: UUID, pick: TrackPick) = update(key) { it["a"] = pick.toJson() }

    /** null = subtitles off. */
    suspend fun rememberSubtitle(key: UUID, pick: TrackPick?) = update(key) {
        if (pick == null) it.remove("s") else it["s"] = pick.toJson()
        it["off"] = JsonPrimitive(pick == null)
    }

    private suspend fun update(key: UUID, change: (MutableMap<String, JsonElement>) -> Unit) {
        val prefKey = tracksKey(key) ?: return
        playbackStore.edit { prefs ->
            val json = prefs[prefKey]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }.orEmpty().toMutableMap()
            change(json)
            prefs[prefKey] = JsonObject(json).toString()
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

    private fun TrackPick.toJson() = buildJsonObject {
        language?.let { put("l", it) }
        name?.let { put("n", it) }
        codec?.let { put("c", it) }
    }

    private fun JsonObject.toPick() = TrackPick(string("l"), string("n"), string("c"))

    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull?.ifEmpty { null }

    private companion object {
        val KEY_SUBTITLE_STYLE = stringPreferencesKey("subtitle_style")
        val KEY_MAX_BITRATE = intPreferencesKey("max_bitrate")
        val KEY_VIDEO_PATH = stringPreferencesKey("video_path")
        val KEY_DOLBY_VISION = booleanPreferencesKey("dolby_vision")
        val KEY_SKIP_INTRO = stringPreferencesKey("skip_intro")
        val KEY_SKIP_CREDITS = stringPreferencesKey("skip_credits")
    }
}

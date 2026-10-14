package ru.stenfredd.movies.playback.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import ru.stenfredd.movies.playback.api.SubtitleStyle
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

/** What the user picked last for a movie or a series. [subtitle] null with [subtitleOff] = subtitles off. */
data class RememberedTracks(val audio: TrackPick?, val subtitle: TrackPick?, val subtitleOff: Boolean)

/**
 * Player settings kept on the device: the look of text subtitles (for everyone on this TV) and the tracks chosen
 * per movie or series (per account: family members watch with different tracks).
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
    }
}

package ru.stenfredd.sleepyowl

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.json.JSONArray
import org.json.JSONObject
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.data.ImageUrls
import ru.stenfredd.sleepyowl.core.data.SessionCache
import ru.stenfredd.sleepyowl.core.data.WatchRepository
import ru.stenfredd.sleepyowl.core.data.model.ItemKind
import ru.stenfredd.sleepyowl.core.data.model.ItemSummary
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.ui.model.landscape
import ru.stenfredd.sleepyowl.ui.model.remaining
import java.util.UUID
import kotlin.math.roundToInt

/**
 * "Продолжить просмотр" on the TV's own home screen (the `ru.stenfredd.tvhome` shell): the app sends the shell the
 * ready list whenever it changes, and the shell keeps and shows it without waking this app on every Home press.
 *
 * Contract v1: `call(content://ru.stenfredd.tvhome.feed, "continue_watching", <JSON array of cards>)`; the shell
 * accepts it only from an app signed with its own key. An empty array hides the row. No shell on the device (another
 * TV, an emulator) → nothing is computed or sent.
 *
 * Recomputed once per process start, when the account changes (sign-in, profile switch, sign-out) and when watch
 * state changes ([SessionCache.invalidations]: playback stopped or left with Home, marked watched or favourite).
 * Progress made on other devices shows up only at the next start: no periodic background work on this TV.
 */
class LauncherFeed(
    private val context: Context,
    private val session: JellyfinSession,
    private val cache: SessionCache,
    private val watch: WatchRepository,
    private val images: ImageUrls,
) {
    /** What the shell has from this process; it is sent again after a restart in case the shell lost it. */
    private var lastSent: String? = null

    /** Runs for the life of the process; call off the main thread. */
    @OptIn(FlowPreview::class)
    suspend fun run() {
        session.restored.first { it }
        // Home loads the same lists at start: after it they come from the cache, and the start isn't slowed down.
        delay(START_DELAY_MS)
        merge(session.session.map { it?.accountId }.distinctUntilChanged(), cache.invalidations)
            .debounce(DEBOUNCE_MS)
            .collectLatest { publish() }
    }

    @Suppress("DEPRECATION") // the flags overload is API 33+
    private suspend fun publish() {
        if (context.packageManager.resolveContentProvider(AUTHORITY, 0) == null) return
        val account = session.session.value?.accountId
        val items = if (account == null) {
            emptyList()
        } else {
            try {
                // The home screen's own call (same cache entries), so usually no request at all.
                watch.continueWatching().take(MAX_ITEMS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The shell keeps the previous list: better than an empty row.
                Log.w(e, "Launcher feed: not updated")
                return
            }
        }
        if (session.session.value?.accountId != account) return
        val json = JSONArray(items.map(::card)).toString()
        if (json == lastSent) return
        try {
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD, json, null)
            lastSent = json
            Log.i("Launcher feed: sent %d cards", items.size)
        } catch (e: IllegalArgumentException) {
            Log.d("Launcher feed: no shell (%s)", e.message)
        } catch (e: SecurityException) {
            // The shell takes lists only from apps signed with its key: sign both with the same release key.
            Log.w(e, "Launcher feed: refused by the shell")
        }
    }

    private fun card(item: ItemSummary): JSONObject {
        val started = item.user.progress != null
        return JSONObject()
            .put("id", item.id.hex())
            .put("title", title(item))
            .putOpt("subtitle", if (started) item.remaining() else if (item.kind == ItemKind.Episode) "следующая серия" else null)
            .putOpt("progress", item.user.progress?.let { (it * 1000).roundToInt() / 1000.0 })
            .putOpt("image", item.landscape()?.let { direct(images.url(it, IMAGE_W, IMAGE_H)) })
            .put("intent", intent(item).toUri(Intent.URI_INTENT_SCHEME))
    }

    /** One line of ~28 characters: "Сериал · S2 E5" or the movie's name (an episode's name doesn't fit). */
    private fun title(item: ItemSummary): String {
        val series = item.seriesName?.takeIf { item.kind == ItemKind.Episode } ?: return item.name
        val number = listOfNotNull(item.seasonNumber?.let { "S$it" }, item.episodeNumber?.let { "E$it" }).joinToString(" ")
        return if (number.isEmpty()) series else "$series · $number"
    }

    /**
     * Plays the item from where it was left (a next episode from the start), with the item's page under the player:
     * a movie's page, an episode's series page on that episode. See `MainActivity.intentTarget`.
     */
    private fun intent(item: ItemSummary): Intent = Intent()
        .setClassName(context.packageName, MainActivity::class.java.name)
        .putExtra("item", item.id.hex())
        .putExtra("play", true)
        .apply {
            if (item.kind == ItemKind.Episode) item.seriesId?.let { putExtra("series", it.hex()) }
            if (item.user.positionMs > 0) putExtra("start", item.user.positionMs)
        }

    /**
     * The shell downloads covers itself, without the app's routing (`ServerRouting`): at home give it the LAN
     * address the app uses, else the public one. The tag stays in the URL, so a changed image is a new URL.
     */
    private fun direct(url: String): String {
        val local = session.localUrl ?: return url
        val public = session.session.value?.serverUrl?.trimEnd('/') ?: return url
        return if (url.startsWith(public)) local + url.removePrefix(public) else url
    }

    /** Jellyfin's own form of ids: no dashes. */
    private fun UUID.hex() = toString().replace("-", "")

    private companion object {
        const val AUTHORITY = "ru.stenfredd.tvhome.feed"
        const val METHOD = "continue_watching"
        /** The shell keeps up to 20 and shows 5. */
        const val MAX_ITEMS = 10
        /** The shell's card image, px. */
        const val IMAGE_W = 326
        const val IMAGE_H = 184
        const val START_DELAY_MS = 3_000L
        const val DEBOUNCE_MS = 1_000L
    }
}

package ru.stenfredd.movies.core.data

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.stenfredd.movies.core.data.model.ItemQuery
import ru.stenfredd.movies.core.data.model.ItemSummary
import ru.stenfredd.movies.core.data.model.LibraryView
import ru.stenfredd.movies.core.data.model.itemKinds
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class HomeContent(
    val continueWatching: List<ItemSummary>,
    val latest: List<ItemSummary>,
    val libraries: List<LibraryRow>,
)

/** A library's row on home: its newest items and, once counted, how many movies and series it has. */
@Serializable
data class LibraryRow(val library: LibraryView, val items: List<ItemSummary>, val total: Int? = null)

/**
 * Home screen content.
 *
 * Shown at once from what is known: the last content of this run, else the snapshot saved on disk at the previous
 * run; then refreshed from the server (all requests in parallel, ~0.5 s) and saved again. Library sizes ("N фильмов")
 * cost the server ~0.2 s each, so they come last, in a separate update.
 */
@Singleton
class HomeRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val session: JellyfinSession,
    private val cache: SessionCache,
    private val library: LibraryRepository,
    private val watch: WatchRepository,
) {
    private class Remembered(val content: HomeContent, val fetchedAt: Long)

    private val remembered = ConcurrentHashMap<String, Remembered>()
    private val dir = File(context.filesDir, "home")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Watch state changed: what we have is still worth showing at once, but it must be fetched again.
        scope.launch { cache.invalidations.collect { remembered.replaceAll { _, r -> Remembered(r.content, 0) } } }
        // A signed-out account's home must not stay on the device.
        scope.launch {
            var previous = emptySet<String>()
            session.accounts.collect { list ->
                val current = list.mapTo(HashSet()) { it.id }
                (previous - current).forEach { gone ->
                    remembered.remove(gone)
                    File(dir, "${fileName(gone)}.json").delete()
                }
                previous = current
            }
        }
    }

    /** Home content, updated again whenever watch state changes on the server (e.g. after playback). */
    fun home(): Flow<HomeContent> = flow {
        val account = session.requireSession().accountId
        val known = remembered[account]
        var shown = known?.content ?: readSnapshot(account)
        if (shown != null) emit(shown)
        if (known == null || SystemClock.elapsedRealtime() - known.fetchedAt >= FRESH_MS) shown = refresh(account, shown)
        cache.invalidations.collect { shown = refresh(account, shown) }
    }.flowOn(Dispatchers.Default)

    /** With something already on screen a failed refresh only gets logged; with nothing to show it is the error. */
    private suspend fun FlowCollector<HomeContent>.refresh(account: String, shown: HomeContent?): HomeContent = try {
        fetch(account, shown)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (shown == null) throw e
        Timber.w(e, "Home refresh failed, keeping what is shown")
        shown
    }

    private suspend fun FlowCollector<HomeContent>.fetch(account: String, shown: HomeContent?): HomeContent {
        val started = SystemClock.elapsedRealtime()
        val oldTotals = shown?.libraries.orEmpty().associate { it.library.id to it.total }
        val fresh = coroutineScope {
            val continueWatching = async { watch.continueWatching() }
            val latest = async { library.latest() }
            val rows = library.views().map { view -> async { LibraryRow(view, library.latest(view.id), oldTotals[view.id]) } }
            HomeContent(continueWatching.await(), latest.await(), rows.awaitAll())
        }
        Timber.i("Home fetched in %d ms", SystemClock.elapsedRealtime() - started)
        emit(fresh)
        remember(account, fresh)

        val counted = fresh.copy(
            libraries = coroutineScope {
                fresh.libraries.map { row ->
                    async { row.copy(total = runCatching { library.count(ItemQuery(parentId = row.library.id, kinds = row.library.itemKinds)) }.getOrNull() ?: row.total) }
                }.awaitAll()
            },
        )
        if (counted == fresh) return fresh
        emit(counted)
        remember(account, counted)
        return counted
    }

    private suspend fun remember(account: String, content: HomeContent) {
        remembered[account] = Remembered(content, SystemClock.elapsedRealtime())
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "${fileName(account)}.tmp")
                tmp.writeText(json.encodeToString(HomeContent.serializer(), content))
                tmp.renameTo(File(dir, "${fileName(account)}.json"))
            }.onFailure { Timber.w(it, "Home snapshot not saved") }
        }
    }

    private suspend fun readSnapshot(account: String): HomeContent? = withContext(Dispatchers.IO) {
        val file = File(dir, "${fileName(account)}.json")
        if (!file.exists()) return@withContext null
        val started = SystemClock.elapsedRealtime()
        runCatching { json.decodeFromString(HomeContent.serializer(), file.readText()) }
            .onSuccess { Timber.i("Home snapshot read in %d ms (%d KB)", SystemClock.elapsedRealtime() - started, file.length() / 1024) }
            .onFailure { Timber.w(it, "Home snapshot unreadable") }
            .getOrNull()
    }

    private fun fileName(account: String) = account.replace(Regex("[^A-Za-z0-9-]"), "_")

    private companion object {
        /** Coming back to home within this time shows what was loaded without asking the server again. */
        const val FRESH_MS = 60_000L
    }
}

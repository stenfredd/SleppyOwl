package ru.stenfredd.movies.core.data

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server responses kept in memory while the app runs, so going back to a screen or reopening a tab doesn't wait
 * for the network again. Identical requests in flight share one call.
 *
 * Everything that depends on watch state is dropped when the server reports a change ([JellyfinSession.userDataChanges]:
 * playback stopped, marked watched or favourite); [invalidations] then tells screens to reload. Library lists and
 * counts ([Kind.Stable]) only expire with time. Switching the account drops everything.
 */
@Singleton
class SessionCache @Inject constructor(private val session: JellyfinSession) {
    enum class Kind(val ttlMs: Long) {
        /** Lists with watch state: resume, next up, latest, details. */
        UserData(10 * 60_000L),
        /** Libraries, item counts. */
        Stable(30 * 60_000L),
    }

    private class Entry(val kind: Kind, val createdAt: Long, val value: CompletableDeferred<Any?>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after user-data entries were dropped: screens showing such data should reload. */
    val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

    init {
        scope.launch {
            session.userDataChanges.collect {
                drop(Kind.UserData)
                _invalidations.tryEmit(Unit)
            }
        }
        scope.launch {
            session.session.distinctUntilChangedBy { it?.accountId }.drop(1).collect { entries.clear() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String, kind: Kind, load: suspend () -> T): T {
        // Per account, so a call of the previous account finishing after a switch can't land in the new one's cache.
        val scoped = "${session.session.value?.accountId}:$key"
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val mine = Entry(kind, now, CompletableDeferred())
            val entry = entries.compute(scoped) { _, old -> if (old != null && now - old.createdAt < old.kind.ttlMs) old else mine }!!
            if (entry !== mine) {
                try {
                    return entry.value.await() as T
                } catch (e: CancellationException) {
                    // The caller that was loading it went away; load it ourselves unless we are cancelled too.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            return try {
                // Off the main thread: turning DTOs into models of a big response takes a few ms.
                withContext(Dispatchers.Default) { load() }.also { mine.value.complete(it) }
            } catch (e: Throwable) {
                entries.remove(scoped, mine)
                mine.value.completeExceptionally(e)
                throw e
            }
        }
    }

    /** Drops what depends on the user's data or settings (default tracks in details) and tells screens to reload. */
    fun dropUserData() {
        drop(Kind.UserData)
        _invalidations.tryEmit(Unit)
    }

    private fun drop(kind: Kind) {
        val before = entries.size
        entries.entries.removeAll { it.value.kind == kind }
        Timber.d("Cache: dropped %d entries (%s)", before - entries.size, kind)
    }
}

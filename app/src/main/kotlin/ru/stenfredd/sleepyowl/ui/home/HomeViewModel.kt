package ru.stenfredd.sleepyowl.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.stenfredd.sleepyowl.core.data.HomeContent
import ru.stenfredd.sleepyowl.core.data.HomeRepository
import ru.stenfredd.sleepyowl.core.data.model.ItemSummary
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.FeaturedModel
import ru.stenfredd.sleepyowl.ui.components.PosterModel
import ru.stenfredd.sleepyowl.ui.components.WideModel
import ru.stenfredd.sleepyowl.ui.model.CardMapper
import timber.log.Timber
import javax.inject.Inject

@Immutable
data class HomeRow(val key: String, val title: String, val count: Int?, val items: List<PosterModel>)

@Immutable
data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val featured: FeaturedModel? = null,
    val continueItems: List<WideModel> = emptyList(),
    val continueCount: Int = 0,
    val rows: List<HomeRow> = emptyList(),
)

/** Home screen: [HomeRepository] content (shown at once from the last snapshot, then refreshed) as card models. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val home: HomeRepository,
    private val cards: CardMapper,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** Card key → where it leads; replaced with every update. */
    private var targets: Map<String, Screen> = emptyMap()

    init {
        val started = System.nanoTime()
        var first = true
        viewModelScope.launch {
            home.home()
                // ~100 cards with their image URLs: built off the main thread.
                .map { build(it) }
                .flowOn(Dispatchers.Default)
                .catch {
                    Timber.w(it, "Home load failed")
                    _state.value = HomeState(loading = false, error = it.message ?: it.toString())
                }
                .collect { (state, newTargets) ->
                    if (first) Timber.i("Home shown in %d ms", (System.nanoTime() - started) / 1_000_000)
                    first = false
                    targets = newTargets
                    _state.value = state
                }
        }
    }

    fun target(key: String): Screen? = targets[key]

    private fun build(content: HomeContent): Pair<HomeState, Map<String, Screen>> {
        val newTargets = HashMap<String, Screen>()
        fun <T> ItemSummary.card(row: String, map: (ItemSummary, String) -> T): T {
            newTargets[cards.key(row, this)] = cards.target(this)
            return map(this, row)
        }
        val rows = buildList {
            if (content.latest.isNotEmpty()) add(HomeRow("latest", "Недавно добавлено", null, content.latest.map { it.card("latest", cards::poster) }))
            content.libraries.forEach { lib ->
                val key = "lib-${lib.library.id}"
                if (lib.items.isNotEmpty()) add(HomeRow(key, lib.library.name, lib.total, lib.items.map { it.card(key, cards::poster) }))
            }
        }
        val cont = content.continueWatching
        val state = HomeState(
            loading = false,
            featured = cont.firstOrNull()?.card(CONTINUE, cards::featured),
            continueItems = cont.drop(1).take(4).map { it.card(CONTINUE, cards::wide) },
            continueCount = cont.size,
            rows = rows,
        )
        return state to newTargets
    }

    private companion object {
        const val CONTINUE = "continue"
    }
}

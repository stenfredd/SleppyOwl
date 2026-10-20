package ru.stenfredd.movies.ui.person

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenfredd.movies.core.data.LibraryRepository
import ru.stenfredd.movies.core.data.SessionCache
import ru.stenfredd.movies.core.data.model.ItemKind
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.PosterModel
import ru.stenfredd.movies.ui.components.RemoteImageSpec
import ru.stenfredd.movies.ui.model.CardMapper
import ru.stenfredd.movies.ui.model.plural
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class PersonState(
    val loading: Boolean = true,
    val error: String? = null,
    val name: String = "",
    /** Initials when there is no photo. */
    val letters: String = "",
    val photo: RemoteImageSpec? = null,
    /** "12 фильмов · 3 сериала". */
    val summary: String = "",
    val items: List<PosterModel> = emptyList(),
)

/** A person's page: who it is and the movies and series of the server they are in, newest first. */
@HiltViewModel(assistedFactory = PersonViewModel.Factory::class)
class PersonViewModel @AssistedInject constructor(
    @Assisted private val personId: UUID,
    private val library: LibraryRepository,
    private val cards: CardMapper,
    cache: SessionCache,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(personId: UUID): PersonViewModel
    }

    private val _state = MutableStateFlow(PersonState())
    val state: StateFlow<PersonState> = _state.asStateFlow()
    private val targets = ConcurrentHashMap<String, Screen>()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { cache.invalidations.collect { load() } }
    }

    fun target(key: String): Screen? = targets[key]

    private suspend fun load() {
        try {
            coroutineScope {
                val person = async { library.person(personId) }
                // A guest role in an episode stands for its series, once, unless the series itself is in the list.
                val all = library.filmography(personId)
                val seriesIds = all.filter { it.kind == ItemKind.Series }.mapTo(HashSet()) { it.id }
                val items = all.filter { it.kind != ItemKind.Episode || (it.seriesId != null && it.seriesId !in seriesIds) }
                    .distinctBy { if (it.kind == ItemKind.Episode) it.seriesId else it.id }
                val p = person.await()
                val posters = withContext(Dispatchers.Default) {
                    items.map { item ->
                        targets[cards.key(ROW, item)] = cards.target(item)
                        if (item.kind == ItemKind.Episode) cards.poster(item, ROW) else cards.kindPoster(item, ROW)
                    }
                }
                val movies = items.count { it.kind == ItemKind.Movie }
                val series = items.size - movies
                _state.value = PersonState(
                    loading = false,
                    name = p.name,
                    letters = p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                    photo = cards.image(p.image, PersonLayout.PHOTO, PersonLayout.PHOTO),
                    summary = listOfNotNull(
                        movies.takeIf { it > 0 }?.let { "$it ${plural(it, "фильм", "фильма", "фильмов")}" },
                        series.takeIf { it > 0 }?.let { "$it ${plural(it, "сериал", "сериала", "сериалов")}" },
                    ).joinToString(" · ").ifEmpty { "На сервере нет фильмов с этим человеком" },
                    items = posters,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Person failed")
            if (_state.value.loading) _state.value = PersonState(loading = false, error = e.message ?: e.toString())
        }
    }

    private companion object {
        const val ROW = "person"
    }
}

internal object PersonLayout {
    const val PHOTO = 64f
}

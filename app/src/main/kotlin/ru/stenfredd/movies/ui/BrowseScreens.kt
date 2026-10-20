package ru.stenfredd.movies.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import ru.stenfredd.movies.ui.components.TextInput
import ru.stenfredd.movies.ui.components.rememberTextInputState
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppType
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(val library: Library, val session: JellyfinSession) : ViewModel()

private fun BaseItemDto.target(): Screen = when {
    type == BaseItemKind.SERIES -> Screen.Series(id)
    isPlayable -> Screen.Detail(id)
    else -> Screen.Items(id, name.orEmpty())
}

@Composable
fun ItemsScreen(screen: Screen.Items, onOpen: (Screen) -> Unit, vm: BrowseViewModel = hiltViewModel()) {
    var items by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.parentId) {
        runCatching { items = vm.library.children(screen.parentId) }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize().padding(48.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(screen.title, style = MaterialTheme.typography.headlineMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (val list = items) {
            null -> Text("Загрузка…")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(list, key = { it.id }) { item -> Poster(item, vm.library, Modifier.fillMaxWidth()) { onOpen(item.target()) } }
            }
        }
    }
}

@Composable
private fun Poster(item: BaseItemDto, library: Library, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize()) {
                val url = remember(item.id) { library.posterUrl(item) }
                if (url != null) {
                    AsyncImage(model = url, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(item.name.orEmpty(), modifier = Modifier.padding(8.dp))
                }
            }
        }
        Text(item.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

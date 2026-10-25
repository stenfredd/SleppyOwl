package ru.stenfredd.sleepyowl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.RemoteImageSpec
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

private fun BaseItemDto.target(): Screen = when {
    type == BaseItemKind.SERIES -> Screen.Series(id)
    isPlayable -> Screen.Detail(id)
    else -> Screen.Items(id, name.orEmpty())
}

@Composable
fun ItemsScreen(screen: Screen.Items, onOpen: (Screen) -> Unit, vm: BrowseViewModel = koinViewModel()) {
    var items by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.parentId) {
        runCatching { items = vm.library.children(screen.parentId) }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize().padding(48.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText(screen.title, style = AppType.Display.copy(fontSize = 26.sp))
        error?.let { BasicText(it, style = AppType.Body.copy(color = AppColors.Red)) }
        when (val list = items) {
            null -> BasicText("Загрузка…", style = AppType.Body)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(list, key = { it.id }) { item -> Poster(item, vm.library, Modifier.fillMaxWidth()) { onOpen(item.target()) } }
            }
        }
    }
}

@Composable
private fun Poster(item: BaseItemDto, library: Library, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .focusCard(FocusStyle.Poster)
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clickable(interactionSource = null, indication = null, onClick = onClick),
        ) {
            val url = remember(item.id) { library.posterUrl(item) }
            RemoteImage(url?.let { RemoteImageSpec(it) }, 7.dp, Modifier.fillMaxSize())
            if (url == null) BasicText(item.name.orEmpty(), Modifier.padding(8.dp), style = AppType.PosterTitle)
        }
        BasicText(item.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = AppType.MonoCaption)
    }
}

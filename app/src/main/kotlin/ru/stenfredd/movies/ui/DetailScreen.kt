package ru.stenfredd.movies.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import ru.stenfredd.movies.playback.api.StreamSelection
import ru.stenfredd.movies.playback.core.DeviceCapabilitiesProbe
import ru.stenfredd.movies.playback.core.EngineSelector
import ru.stenfredd.movies.playback.core.RankedEngine
import ru.stenfredd.movies.playback.core.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    val library: Library,
    val selector: EngineSelector,
    val probe: DeviceCapabilitiesProbe,
) : ViewModel()

@Composable
fun DetailScreen(screen: Screen.Detail, onOpen: (Screen) -> Unit, vm: DetailViewModel = hiltViewModel()) {
    var item by remember { mutableStateOf<BaseItemDto?>(null) }
    var ranking by remember { mutableStateOf(emptyList<RankedEngine>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(screen.itemId) {
        runCatching {
            Timber.d("Detail: loading %s", screen.itemId)
            val loaded = vm.library.item(screen.itemId)
            Timber.d("Detail: loaded %s", loaded.name)
            item = loaded
            val caps = withContext(Dispatchers.Default) { vm.probe.capabilities }
            loaded.mediaSources?.firstOrNull()?.let { src ->
                ranking = vm.selector.rank(src, StreamSelection(src.defaultAudioStreamIndex, src.defaultSubtitleStreamIndex), caps)
            }
        }.onFailure {
            Timber.e(it, "Detail: failed")
            error = it.message
        }
    }

    val it = item
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(48.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        if (it == null) {
            item { Text("Загрузка…") }
            return@LazyColumn
        }
        item { Text(it.label, style = MaterialTheme.typography.headlineMedium) }
        item {
            val resumeMs = (it.userData?.playbackPositionTicks ?: 0) / 10_000
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (resumeMs > 0) {
                    Button(onClick = { onOpen(Screen.Player(it.id, null, resumeMs)) }, modifier = Modifier.focusRequester(focus)) {
                        Text("Продолжить с ${formatTime(resumeMs)}")
                    }
                }
                Button(
                    onClick = { onOpen(Screen.Player(it.id, null, 0)) },
                    modifier = if (resumeMs > 0) Modifier else Modifier.focusRequester(focus),
                ) { Text("Смотреть (авто)") }
                vm.selector.engines.forEach { p ->
                    Button(onClick = { onOpen(Screen.Player(it.id, p.id, resumeMs)) }) { Text(p.displayName) }
                }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
        item { Text("Выбор движка", style = MaterialTheme.typography.titleLarge) }
        ranking.forEachIndexed { i, r ->
            item { Text("${i + 1}. ${r.provider.displayName}: ${r.verdict.describe()}") }
        }
        it.mediaSources?.firstOrNull()?.let { src -> mediaInfo(src) }
        it.overview?.let { o -> item { Text(o, style = MaterialTheme.typography.bodySmall) } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.mediaInfo(src: MediaSourceInfo) {
    item { Text("Файл", style = MaterialTheme.typography.titleLarge) }
    item {
        Text(
            "${src.container} · ${src.size?.let { "%.1f ГБ".format(it / 1e9) } ?: "?"} · " +
                "${src.bitrate?.let { "%.1f Мбит/с".format(it / 1e6) } ?: "?"}",
        )
    }
    src.mediaStreams.orEmpty().forEach { s -> item { Text(describeStream(s, src)) } }
}

fun describeStream(s: MediaStream, src: MediaSourceInfo): String {
    val default = if (s.index == src.defaultAudioStreamIndex || s.index == src.defaultSubtitleStreamIndex) " [по умолчанию]" else ""
    return when (s.type) {
        MediaStreamType.VIDEO -> "Видео #${s.index}: ${s.codec} ${s.profile.orEmpty()} ${s.width}x${s.height} " +
            "${s.bitDepth ?: 8}bit ${s.videoRangeType.serialName}" + (s.dvProfile?.let { " DV P$it" } ?: "") +
            (if (s.isInterlaced) " interlaced" else "") + " ${s.realFrameRate ?: ""}fps"
        MediaStreamType.AUDIO -> "Аудио #${s.index}: ${s.displayTitle ?: s.codec}$default"
        MediaStreamType.SUBTITLE -> "Субтитры #${s.index}: ${s.displayTitle ?: s.codec} (${s.codec})" +
            (if (s.isExternal) " внешние" else "") + default
        else -> "${s.type.serialName} #${s.index}: ${s.codec}"
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}

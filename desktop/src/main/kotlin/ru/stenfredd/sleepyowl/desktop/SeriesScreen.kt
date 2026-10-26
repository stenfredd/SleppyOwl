package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.CircleButton
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.PrimaryButton
import ru.stenfredd.sleepyowl.ui.components.ProgressLine
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.menu.LocalWatchMenu
import ru.stenfredd.sleepyowl.ui.menu.clickableWithMenu
import ru.stenfredd.sleepyowl.ui.player.SubtitleChoices
import ru.stenfredd.sleepyowl.ui.player.toChoices
import ru.stenfredd.sleepyowl.ui.series.EpisodeState
import ru.stenfredd.sleepyowl.ui.series.SeriesEpisode
import ru.stenfredd.sleepyowl.ui.series.SeriesLayout
import ru.stenfredd.sleepyowl.ui.series.SeriesModel
import ru.stenfredd.sleepyowl.ui.series.SeriesSeason
import ru.stenfredd.sleepyowl.ui.series.SeriesState
import ru.stenfredd.sleepyowl.ui.series.SeriesViewModel
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Series page, as on the TV: header with "Продолжить S2 E4", the audio/subtitle choice and ♥; the season map; season
 * chips; every episode of every season in one row (a divider before each season); the pointed (or focused) episode's
 * description under it. A click on a season chip turns the row to that season; pointing along the row lights up its
 * season. Scrolls as a whole when the window is low.
 */
@Composable
fun SeriesScreen(
    screen: Screen.Series,
    onOpen: (Screen) -> Unit,
    vm: SeriesViewModel = koinViewModel { parametersOf(screen.seriesId) },
) {
    val state by vm.state.collectAsState()
    val focus = rememberScreenFocus()
    val item = state.item
    when {
        item != null -> SeriesPage(screen, item, state, focus, vm, onOpen)
        state.error != null -> Message("Не удалось загрузить: ${state.error}")
    }
    focus.Entry(ready = item != null)
}

private const val KEY_PLAY = "play"
private const val KEY_TRACKS = "tracks"
private const val KEY_WATCHED = "watched"

/**
 * What the page highlights: the season of the chips and the map, the episode described at the bottom.
 * [episode] is read only by the parts that show it, so moving along the row doesn't recompose the page.
 */
@Stable
private class SeriesSelection(season: Int, episode: Int) {
    var season by mutableIntStateOf(season)
    var episode by mutableIntStateOf(episode)

    companion object {
        val Saver = listSaver<SeriesSelection, Int>({ listOf(it.season, it.episode) }, { SeriesSelection(it[0], it[1]) })
    }
}

@Composable
private fun SeriesPage(screen: Screen.Series, item: SeriesModel, state: SeriesState, focus: ScreenFocus, vm: SeriesViewModel, onOpen: (Screen) -> Unit) {
    // Opened on an episode (a new one, continue watching): it is shown first, else the next one to watch.
    val start = remember(item.id) { item.episodes.indexOfFirst { it.id == screen.episodeId }.takeIf { it >= 0 } }
    val first = start ?: item.nextUp
    val selection = rememberSaveable(saver = SeriesSelection.Saver) { SeriesSelection(item.episodes.getOrNull(first)?.season ?: 0, first) }
    // The data can change on reload (a new episode): stay in range.
    val seasonIndex = selection.season.coerceIn(0, (item.seasons.size - 1).coerceAtLeast(0))

    val episodesRow = rememberLazyListState(initialFirstVisibleItemIndex = item.episodes.getOrNull(first)?.rowIndex?.minus(1)?.coerceAtLeast(0) ?: 0)
    val chipsRow = rememberLazyListState(initialFirstVisibleItemIndex = (seasonIndex - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    var trayOpen by rememberSaveable { mutableStateOf(false) }

    fun selectSeason(season: Int) {
        selection.season = season
        val s = item.seasons[season]
        selection.episode = s.target
        // The row follows the chips: the season's divider (or its next episode, deeper in the season) at the start.
        val target = if (s.target == s.first) s.dividerIndex else item.episodes[s.target].rowIndex - 1
        scope.launch { episodesRow.animateScrollToItem(target.coerceAtLeast(0)) }
    }

    fun onEpisodeFocused(index: Int) {
        selection.episode = index
        val season = item.episodes[index].season
        if (season != selection.season) {
            selection.season = season
            if (chipsRow.layoutInfo.visibleItemsInfo.none { it.index == season }) scope.launch { chipsRow.animateScrollToItem((season - 1).coerceAtLeast(0)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        item.backdropUrl?.let { Backdrop(it) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 32.dp, bottom = 24.dp)) {
            Header(
                item,
                state,
                focus,
                isDefault = start == null,
                onPlay = { item.episodes.getOrNull(item.nextUp)?.let { onOpen(vm.player(it)) } },
                onTracks = { trayOpen = true },
                onFavorite = vm::toggleFavorite,
            )
            SeasonMap(item, seasonIndex, current = { selection.episode }, Modifier.padding(top = 16.dp).padding(horizontal = AppDimens.CONTENT_START.dp).fillMaxWidth())
            SeasonChips(item, seasonIndex, chipsRow, focus, onClick = ::selectSeason, modifier = Modifier.padding(top = 5.dp))
            EpisodeRow(
                item,
                episodesRow,
                focus,
                defaultKey = start?.let { item.episodes[it].key },
                onFocused = ::onEpisodeFocused,
                onClick = { onOpen(vm.player(it)) },
            )
            SelectedEpisode(item, selection)
        }
        if (trayOpen) {
            val style by vm.subtitleStyle.collectAsState()
            BackHandler { trayOpen = false; true }
            // A click past the tray closes it.
            Spacer(Modifier.fillMaxSize().background(Color(0x80050609)).pointerInput(Unit) { detectTapGestures { trayOpen = false } })
            SeriesTray(item.title, state, style, vm, onClose = { trayOpen = false }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

private val TitleStyle = AppType.Display.copy(fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-0.03).em)

@Composable
private fun Header(
    item: SeriesModel,
    state: SeriesState,
    focus: ScreenFocus,
    isDefault: Boolean,
    onPlay: () -> Unit,
    onTracks: () -> Unit,
    onFavorite: () -> Unit,
) {
    val left = AppDimens.CONTENT_START.dp
    Row(Modifier.padding(horizontal = left)) {
        RemoteImage(
            item.poster,
            8.dp,
            Modifier.size(SeriesLayout.POSTER_W.dp, SeriesLayout.POSTER_H.dp).border(0.5.dp, Color(0x1AFFFFFF), RoundedCornerShape(8.dp)),
        )
        Column(Modifier.padding(start = 20.dp)) {
            BasicText(item.label, style = AppType.MonoLabel)
            BasicText(item.title, Modifier.padding(top = 9.dp), style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Meta(item.rating, item.age, listOfNotNull(item.year, item.genres), Modifier.padding(top = 9.dp), badges = state.badges)
            FlowRow(Modifier.padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                PrimaryButton(item.playLabel, onPlay, Modifier.hoverFocus().remembered(focus, KEY_PLAY, isDefault = isDefault))
                if (state.audioLabel != null || state.subtitleLabel != null) {
                    TracksButton(state.audioLabel, state.subtitleLabel, onTracks, Modifier.hoverFocus().remembered(focus, KEY_TRACKS))
                }
                CircleButton(
                    if (item.favorite) AppIcon.HeartFilled else AppIcon.Heart,
                    onFavorite,
                    Modifier.hoverFocus().remembered(focus, "favorite"),
                    tint = if (item.favorite) AppColors.Pink else Color(0xFFE3E5F2),
                )
                // The whole series at once, so through the menu (it says how many episodes) rather than a toggle.
                val menu = LocalWatchMenu.current
                val watchedButton = remember { FocusRequester() }
                CircleButton(
                    AppIcon.Check,
                    { menu?.open(item.watch, watchedButton) },
                    Modifier.hoverFocus().focusRequester(watchedButton).remembered(focus, KEY_WATCHED),
                    tint = if (item.watch.played) AppColors.Green else Color(0xFFE3E5F2),
                )
            }
        }
    }
}

private val TracksFocus = FocusStyle(scale = 1.05f, corner = 21.dp, glows = FocusStyle.ButtonGlows)

/** "● Русский 5.1  ● без субтитров": opens the tray. */
@Composable
private fun TracksButton(audio: String?, subtitle: String?, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(21.dp)
    val color = if (focused) AppColors.OnFocusFill else Color(0xFFE3E5F2)
    Row(
        modifier
            .focusCard(TracksFocus)
            .height(42.dp)
            .background(if (focused) AppColors.FocusFill else AppColors.SurfaceRaised, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else AppColors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = AppType.Button.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = color)
        audio?.let {
            Box(Modifier.size(5.dp).background(AppColors.Pink, CircleShape))
            BasicText(it, Modifier.padding(start = 6.dp), style = style, maxLines = 1)
        }
        subtitle?.let {
            Box(Modifier.padding(start = if (audio != null) 10.dp else 0.dp).size(5.dp).background(AppColors.Yellow, CircleShape))
            BasicText(it, Modifier.padding(start = 6.dp), style = style, maxLines = 1)
        }
    }
}

private object MapColors {
    val Watched = Color(0xFF3F8F63)
    val Empty = Color(0xFF33384D)
    val Label = Color(0xFF6F7592)
}

/**
 * Season map: a bar per episode — watched green, next violet, new outlined pink — the selected episode tall and
 * white. Bars shrink for long seasons; if the series still doesn't fit, the seasons around the selected one are shown.
 */
@Composable
private fun SeasonMap(item: SeriesModel, selectedSeason: Int, current: () -> Int, modifier: Modifier) {
    val layout = remember(item.seasons, selectedSeason) { mapLayout(item.seasons, selectedSeason) }
    Row(modifier.height(MAP_H.dp), verticalAlignment = Alignment.Bottom) {
        Row(Modifier.width(MAP_W.dp), horizontalArrangement = Arrangement.spacedBy(MAP_GAP.dp), verticalAlignment = Alignment.Bottom) {
            if (layout.from > 0) BasicText("…", style = AppType.MonoCaption.copy(color = MapColors.Label))
            for (s in layout.from until layout.to) {
                val season = item.seasons[s]
                val width = (layout.bar + layout.gap) * season.count - layout.gap
                Column {
                    BasicText(
                        season.mapLabel,
                        style = AppType.MonoCaption.copy(fontSize = 8.5.sp, color = if (s == selectedSeason) AppColors.Text else MapColors.Label),
                        maxLines = 1,
                    )
                    Box(Modifier.padding(top = 4.dp).size(width.coerceAtLeast(1f).dp, BAR_TALL.dp)) {
                        Spacer(Modifier.fillMaxSize().drawWithCache { onDrawBehind { drawBars(item, season, layout) } })
                        Spacer(
                            Modifier
                                .size(layout.bar.dp, BAR_TALL.dp)
                                .graphicsLayer {
                                    val i = current() - season.first
                                    alpha = if (i in 0 until season.count) 1f else 0f
                                    translationX = i.coerceAtLeast(0) * floor((layout.bar + layout.gap).dp.toPx()).coerceAtLeast(floor(layout.bar.dp.toPx()).coerceAtLeast(1f) + 1f)
                                }
                                .drawBehind { drawRect(Color.White, size = Size(floor(size.width).coerceAtLeast(1f), size.height)) },
                        )
                    }
                }
            }
            if (layout.to < item.seasons.size) BasicText("…", style = AppType.MonoCaption.copy(color = MapColors.Label))
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            val legend = AppType.MonoCaption
            BasicText("■ просмотрено", style = legend.copy(color = AppColors.Green))
            BasicText("■ следующая", style = legend.copy(color = AppColors.Accent))
            BasicText("□ новая", style = legend.copy(color = AppColors.Pink))
        }
    }
}

/** Bars of one season: plain rectangles, outlines as four 1 px sides. */
private fun DrawScope.drawBars(item: SeriesModel, season: SeriesSeason, layout: MapLayout) {
    val bar = floor(layout.bar.dp.toPx()).coerceAtLeast(1f)
    val step = floor((layout.bar + layout.gap).dp.toPx()).coerceAtLeast(bar + 1f)
    val low = floor(BAR_H.dp.toPx())
    val top = size.height - low
    fun outline(color: Color, x: Float) {
        drawRect(color, Offset(x, top), Size(bar, 1f))
        drawRect(color, Offset(x, size.height - 1f), Size(bar, 1f))
        drawRect(color, Offset(x, top), Size(1f, low))
        drawRect(color, Offset(x + bar - 1f, top), Size(1f, low))
    }
    for (i in 0 until season.count) {
        val index = season.first + i
        val x = i * step
        when (item.episodes[index].state) {
            EpisodeState.Watched -> drawRect(MapColors.Watched, Offset(x, top), Size(bar, low))
            EpisodeState.InProgress, EpisodeState.Next -> drawRect(AppColors.Accent, Offset(x, top), Size(bar, low))
            EpisodeState.New -> outline(AppColors.Pink, x)
            EpisodeState.None -> if (bar >= 3f) outline(MapColors.Empty, x) else drawRect(MapColors.Empty, Offset(x, top), Size(bar, low))
        }
    }
}

private const val MAP_W = 600f
private const val MAP_H = 32f
private const val MAP_GAP = 14f
private const val BAR_H = 10f
private const val BAR_TALL = 17f
/** Width (dp) of one character of the map labels (JetBrains Mono advance is 0.6 em). */
private const val LABEL_CHAR = 8.5f * 0.6f

private data class MapLayout(val bar: Float, val gap: Float, val from: Int, val to: Int)

/** Bar size that fits all seasons in [MAP_W], else the widest window of seasons around [selected] at the smallest size. */
private fun mapLayout(seasons: List<SeriesSeason>, selected: Int): MapLayout {
    if (seasons.isEmpty()) return MapLayout(7f, 2f, 0, 0)
    fun width(s: SeriesSeason, bar: Float, gap: Float) = maxOf(s.count * (bar + gap) - gap, s.mapLabel.length * LABEL_CHAR)
    fun total(range: IntRange, bar: Float, gap: Float) = range.sumOf { width(seasons[it], bar, gap).toDouble() }.toFloat() + MAP_GAP * (range.count() - 1)
    for ((bar, gap) in listOf(7f to 2f, 5f to 1.5f, 3f to 1f, 2f to 1f)) {
        if (total(seasons.indices, bar, gap) <= MAP_W) return MapLayout(bar, gap, 0, seasons.size)
    }
    val (bar, gap) = 2f to 1f
    // Room for the "…" marks at both ends.
    val room = MAP_W - 2 * (MAP_GAP + LABEL_CHAR)
    var from = selected.coerceIn(seasons.indices)
    var to = from + 1
    while (true) {
        val grown = when {
            to < seasons.size && total(from..to, bar, gap) <= room -> from until to + 1
            from > 0 && total(from - 1 until to, bar, gap) <= room -> from - 1 until to
            else -> break
        }
        from = grown.first
        to = grown.last + 1
    }
    // A single season longer than the map: its bars get thinner still.
    val single = if (to - from == 1) (room / seasons[from].count).coerceIn(1f, bar + gap) else bar + gap
    return if (to - from == 1 && single < bar + gap) MapLayout(single * 0.6f, single * 0.4f, from, to) else MapLayout(bar, gap, from, to)
}

private val SeasonChipFocus = FocusStyle(scale = 1f, corner = 15.dp, glows = FocusStyle.ButtonGlows)

@Composable
private fun SeasonChips(item: SeriesModel, selected: Int, state: LazyListState, focus: ScreenFocus, onClick: (Int) -> Unit, modifier: Modifier) {
    ScrollRow(modifier, state = state, contentPadding = PaddingValues(horizontal = AppDimens.CONTENT_START.dp, vertical = 6.dp), spacing = 6.dp) {
        itemsIndexed(item.seasons, key = { _, s -> s.key }) { i, s ->
            SeasonChip(s, i == selected, onClick = { onClick(i) }, modifier = Modifier.hoverFocus().remembered(focus, s.key))
        }
    }
}

@Composable
private fun SeasonChip(season: SeriesSeason, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(15.dp)
    val color = when {
        focused -> AppColors.OnFocusFill
        selected -> AppColors.Text
        else -> AppColors.TextSecondary
    }
    Row(
        modifier
            .focusCard(SeasonChipFocus)
            .height(30.dp)
            .background(if (focused) AppColors.FocusFill else if (selected) Color(0xFF211D4A) else AppColors.SurfaceRaised, shape)
            .border(0.5.dp, if (focused) AppColors.FocusFill else if (selected) Color(0xFF7A6CFF) else AppColors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickableWithMenu(season.watch, onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(season.label, style = AppType.Button.copy(color = color), maxLines = 1)
        BasicText(season.stat, style = AppType.MonoCaption.copy(color = color.copy(alpha = 0.75f)), maxLines = 1)
    }
}

private val EpisodeFocus = FocusStyle(scale = 1.06f, corner = 8.dp)

/** Every episode of the series, a "СЕЗОН N" divider before each season. */
@Composable
private fun EpisodeRow(
    item: SeriesModel,
    state: LazyListState,
    focus: ScreenFocus,
    defaultKey: String?,
    onFocused: (Int) -> Unit,
    onClick: (SeriesEpisode) -> Unit,
) {
    ScrollRow(state = state, contentPadding = PaddingValues(horizontal = AppDimens.CONTENT_START.dp, vertical = 12.dp), spacing = 12.dp) {
        for (season in item.seasons) {
            item(key = "div/${season.key}", contentType = "divider") { SeasonDivider(season.label.uppercase()) }
            itemsIndexed(item.episodes.subList(season.first, season.first + season.count), key = { _, e -> e.key }, contentType = { _, _ -> "episode" }) { i, e ->
                val index = season.first + i
                EpisodeCard(
                    e,
                    onClick = { onClick(e) },
                    modifier = Modifier
                        .hoverFocus()
                        .remembered(focus, e.key, isDefault = e.key == defaultKey)
                        .onFocusChanged { if (it.isFocused) onFocused(index) },
                )
            }
        }
    }
}

/** Vertical "СЕЗОН 2" on a dashed line. */
@Composable
private fun SeasonDivider(label: String) {
    Box(
        Modifier
            .size(22.dp, SeriesLayout.EPISODE_H.dp)
            .drawBehind {
                val dash = 3.dp.toPx().roundToInt().toFloat()
                var y = 0f
                while (y < size.height) {
                    drawRect(MapColors.Empty, Offset(0f, y), Size(1f, dash.coerceAtMost(size.height - y)))
                    y += dash * 2
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            Modifier.wrapContentSize(unbounded = true).rotate(-90f),
            style = AppType.MonoCaption.copy(letterSpacing = 0.14.em),
            maxLines = 1,
        )
    }
}

@Composable
private fun EpisodeCard(e: SeriesEpisode, onClick: () -> Unit, modifier: Modifier) {
    Column(Modifier.width(SeriesLayout.EPISODE_W.dp)) {
        Box(
            modifier
                .focusCard(EpisodeFocus)
                .size(SeriesLayout.EPISODE_W.dp, SeriesLayout.EPISODE_H.dp)
                .clickableWithMenu(e.watch, onClick),
        ) {
            RemoteImage(e.image, 8.dp, Modifier.fillMaxSize())
            BasicText(
                e.code,
                Modifier.padding(start = 8.dp, top = 6.dp),
                style = AppType.MonoMeta.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xE0FFFFFF)),
            )
            badge(e)?.let { (text, color) ->
                BasicText(
                    text,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .background(Color(0xBF0A0B10), RoundedCornerShape(3.5.dp))
                        .border(0.5.dp, color, RoundedCornerShape(3.5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    style = AppType.MonoBadge.copy(fontSize = 8.5.sp, color = color),
                )
            }
            e.progress?.let {
                ProgressLine(it, Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 4.dp).fillMaxWidth().height(3.dp))
            }
        }
        BasicText(
            e.title,
            Modifier.padding(top = 7.dp),
            style = AppType.PosterTitle.copy(fontSize = 12.sp, color = if (e.state == EpisodeState.Watched) AppColors.TextMuted else AppColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        e.meta?.let { BasicText(it, Modifier.padding(top = 3.dp), style = AppType.MonoCaption, maxLines = 1) }
    }
}

private fun badge(e: SeriesEpisode): Pair<String, Color>? = when (e.state) {
    EpisodeState.Watched -> "✓" to AppColors.Green
    EpisodeState.InProgress -> "${((e.progress ?: 0f) * 100).roundToInt()}%" to AppColors.Accent
    EpisodeState.Next -> "далее" to AppColors.Accent
    EpisodeState.New -> "новая" to AppColors.Pink
    EpisodeState.None -> null
}

@Composable
private fun SelectedEpisode(item: SeriesModel, selection: SeriesSelection) {
    item.episodes.getOrNull(selection.episode.coerceIn(0, (item.episodes.size - 1).coerceAtLeast(0)))?.let { EpisodeDetail(it) }
}

/** The selected episode under the row: "СЕЗОН 2 · СЕРИЯ 4", title, description, duration and air date. */
@Composable
private fun EpisodeDetail(e: SeriesEpisode) {
    Row(
        Modifier
            .padding(horizontal = AppDimens.CONTENT_START.dp)
            .padding(top = 6.dp)
            .fillMaxWidth()
            .drawBehind { drawRect(AppColors.BorderSubtle, size = Size(size.width, 1f)) }
            .padding(top = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText(e.longCode, Modifier.width(126.dp).padding(top = 3.dp), style = AppType.MonoLabel.copy(letterSpacing = 0.06.em))
        Column(Modifier.weight(1f)) {
            BasicText(e.title, style = AppType.Section.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.overview?.let {
                BasicText(it, Modifier.padding(top = 4.dp), style = AppType.Body.copy(fontSize = 11.sp, lineHeight = 15.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(top = 2.dp)) {
            val style = AppType.MonoMeta.copy(fontSize = 10.sp, color = AppColors.TextDim, textAlign = TextAlign.End)
            e.duration?.let { BasicText(it, Modifier.align(Alignment.End), style = style) }
            e.airDate?.let { BasicText(it, Modifier.align(Alignment.End).padding(top = 5.dp), style = style) }
        }
    }
}

/**
 * "Аудио и субтитры" tray at the bottom of the page: audio, subtitles and their look, applied at once and remembered
 * for the whole series. Esc or a click past it closes it.
 */
@Composable
private fun SeriesTray(title: String, state: SeriesState, style: SubtitleStyle, vm: SeriesViewModel, onClose: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .height(TRAY_H.dp)
            .background(AppColors.Background)
            // Clicks inside stay inside (the scrim behind closes the tray).
            .pointerInput(Unit) { detectTapGestures { } }
            .drawBehind { drawRect(Color(0xFF262A3A), size = Size(size.width, 1f)) }
            .padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).background(AppColors.Accent, CircleShape))
            BasicText(
                "АУДИО И СУБТИТРЫ · " + title.uppercase(),
                Modifier.weight(1f),
                style = AppType.MonoLabel.copy(color = AppColors.TextSecondary, letterSpacing = 0.1.em),
                maxLines = 1,
            )
            BasicText("Запоминается для всего сериала · Esc — закрыть", style = AppType.MonoCaption.copy(color = Color(0xFF6F7592)))
            BackArrow(onClose, Modifier.rotate(-90f))
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.audio.isNotEmpty()) {
                TrayColumn("АУДИО", AppColors.Pink) {
                    PanelList(state.audio.toChoices(state.selectedAudio)) { c -> vm.selectAudio(state.audio.firstOrNull { it.key == c.key }?.index) }
                }
            }
            if (state.subtitles.isNotEmpty()) {
                TrayColumn("СУБТИТРЫ", AppColors.Yellow) {
                    PanelList(state.subtitles.toChoices(state.selectedSubtitle)) { c -> vm.selectSubtitle(state.subtitles.firstOrNull { it.key == c.key }?.index) }
                }
            }
            TrayColumn("ВИД СУБТИТРОВ", AppColors.Yellow) {
                PanelSection("РАЗМЕР") { PanelChips(SubtitleChoices.sizes(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
                PanelSection("ЦВЕТ") { PanelChips(SubtitleChoices.colors(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
                PanelSection("ФОН") { PanelChips(SubtitleChoices.backgrounds(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            }
        }
    }
}

private const val TRAY_H = 300f

@Composable
private fun RowScope.TrayColumn(title: String, dot: Color, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(PanelColors.Background, shape)
            .border(0.5.dp, PanelColors.Edge, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(25.dp)
                .background(PanelColors.Header, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(5.dp).background(dot, CircleShape))
            BasicText(title, style = AppType.MonoCaption.copy(color = AppColors.TextMuted, letterSpacing = 0.1.em))
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

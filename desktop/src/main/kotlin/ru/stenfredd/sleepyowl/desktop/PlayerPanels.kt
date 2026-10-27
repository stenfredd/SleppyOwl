package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenfredd.sleepyowl.core.data.model.MediaInfo
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.playback.core.PlaybackSession
import ru.stenfredd.sleepyowl.playback.core.PlaybackUiState
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.ImageShade
import ru.stenfredd.sleepyowl.ui.components.ProgressLine
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.player.EpisodeModel
import ru.stenfredd.sleepyowl.ui.player.InfoRow
import ru.stenfredd.sleepyowl.ui.player.PanelChoice
import ru.stenfredd.sleepyowl.ui.player.PlayerInfo
import ru.stenfredd.sleepyowl.ui.player.PlayerLabels
import ru.stenfredd.sleepyowl.ui.player.PlayerLayout
import ru.stenfredd.sleepyowl.ui.player.PlayerTracks
import ru.stenfredd.sleepyowl.ui.player.PlayerViewModel
import ru.stenfredd.sleepyowl.ui.player.SeasonEpisodes
import ru.stenfredd.sleepyowl.ui.player.StreamMode
import ru.stenfredd.sleepyowl.ui.player.SubtitleChoices
import ru.stenfredd.sleepyowl.ui.player.toChoices
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import java.util.UUID

/** Panels of the desktop player: the TV's four and its drawer ("Серии" / "О фильме"). */
internal enum class Panel { Audio, Subtitles, Quality, Info, Episodes }

/** Height of the drawer of the full layout; the controls move up by this much while it is open. */
internal const val DRAWER_HEIGHT = 210

/** Wheel and clicks inside stay inside: the wheel over the video sets the volume, a click on it pauses. */
private fun Modifier.keepsPointer(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Scroll || event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/**
 * A panel: on the right, full height, as on the TV; [sheet] (a small window) — over the whole window. The header has
 * the title and ×; the content scrolls.
 */
@Composable
internal fun PanelFrame(
    title: String,
    dot: Color,
    sheet: Boolean,
    ui: PlayerUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .then(if (sheet) Modifier.fillMaxSize() else Modifier.fillMaxHeight().width(350.dp))
            .holdsControls(ui, "panel")
            .keepsPointer()
            .background(PanelColors.Background)
            .drawBehind { if (!sheet) drawRect(PanelColors.Edge, size = Size(0.5.dp.toPx(), size.height)) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(PanelColors.Header)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            BasicText(title, Modifier.weight(1f), style = AppType.MonoLabel.copy(color = AppColors.TextSecondary, letterSpacing = 0.1.em), maxLines = 1)
            GlyphButton(Glyph.Close, 32.dp, 14.dp, onClick = onClose, bare = true)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
            content = content,
        )
    }
}

@Composable
internal fun PanelNote(text: String) {
    BasicText(text, style = AppType.Body.copy(fontSize = 10.5.sp, lineHeight = 15.sp, color = AppColors.TextDim))
}

/** Audio, subtitles, quality, stream info: the TV's panels, picked with the mouse. */
@Composable
internal fun PanelContent(
    panel: Panel,
    state: PlaybackUiState,
    info: PlayerInfo?,
    media: MediaInfo?,
    tracks: PlayerTracks,
    style: SubtitleStyle,
    session: PlaybackSession,
    vm: PlayerViewModel,
    sheet: Boolean,
    ui: PlayerUiState,
    close: () -> Unit,
    modifier: Modifier,
) {
    val selection = state.selection
    when (panel) {
        Panel.Audio -> PanelFrame("АУДИОДОРОЖКА", AppColors.Pink, sheet, ui, close, modifier) {
            PanelSection("ДОРОЖКИ" + (info?.title?.let { " · " + it.uppercase() } ?: "")) {
                PanelList(tracks.audio.toChoices(selection.audioStreamIndex)) { c ->
                    tracks.audio.firstOrNull { it.key == c.key }?.index?.let(session::selectAudio)
                    close()
                }
            }
            PanelNote(if (info?.isEpisode == true) "Выбор запоминается для всего сериала." else "Выбор запоминается для этого фильма.")
        }
        Panel.Subtitles -> PanelFrame("СУБТИТРЫ", AppColors.Yellow, sheet, ui, close, modifier) {
            val selected = media?.subtitles?.firstOrNull { it.index == selection.subtitleStreamIndex }
            SubtitlePreview(style)
            PanelSection("ДОРОЖКА") {
                PanelList(tracks.subtitles.toChoices(selection.subtitleStreamIndex)) { c ->
                    session.selectSubtitle(tracks.subtitles.firstOrNull { it.key == c.key }?.index)
                }
            }
            PanelSection("РАЗМЕР") { PanelChips(SubtitleChoices.sizes(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            PanelSection("ЦВЕТ") { PanelChips(SubtitleChoices.colors(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            PanelSection("ФОН") { PanelChips(SubtitleChoices.backgrounds(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            val codec = selected?.codec?.lowercase()
            when {
                codec in GRAPHIC_SUBTITLES -> PanelNote("Это графические субтитры (картинки): они показываются как есть, размер и цвет к ним не применяются.")
                codec == "ass" || codec == "ssa" -> PanelNote("У субтитров ASS своё оформление из файла: здесь меняется только их размер.")
                else -> PanelNote("Вид применяется к текстовым субтитрам (SRT, VTT) и запоминается на этом компьютере.")
            }
        }
        Panel.Quality -> PanelFrame("КАЧЕСТВО", AppColors.Blue, sheet, ui, close, modifier) {
            val options = info?.qualities.orEmpty()
            PanelSection("БИТРЕЙТ") {
                PanelList(options.map { PanelChoice(it.key, it.label, it.detail, it.bitrate == state.maxBitrate) }) { c ->
                    vm.setQuality(options.firstOrNull { it.key == c.key }?.bitrate)
                    close()
                }
            }
            PanelNote("Всё, что ниже оригинала, сервер перекодирует на лету. Это нагружает процессор сервера.")
        }
        Panel.Info -> PanelFrame("ИНФОРМАЦИЯ О ПОТОКЕ", AppColors.Green, sheet, ui, close, modifier) {
            StreamModeCard(PlayerLabels.mode(state))
            InfoRows(PlayerLabels.rows(state, media, session.activeEngine))
            // The engine's own figures: information only, the desktop has a single engine (§1.4).
            val extra = state.stats.extra.map { (k, v) -> InfoRow(k, v, AppColors.TextDim) } +
                listOfNotNull(state.stats.videoFormat?.let { InfoRow("Поток", it, AppColors.Blue) })
            if (extra.isNotEmpty()) PanelSection("ДОПОЛНИТЕЛЬНО") { InfoRows(extra) }
        }
        Panel.Episodes -> Unit
    }
}

private val GRAPHIC_SUBTITLES = setOf("pgssub", "hdmv_pgs_subtitle", "dvdsub", "dvd_subtitle", "vobsub", "dvbsub")

/** "Прямое воспроизведение / Файл воспроизводится без изменений" in a green (or orange) frame. */
@Composable
private fun StreamModeCard(mode: StreamMode) {
    val color = if (mode.direct) AppColors.Green else AppColors.Orange
    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, color, RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        BasicText(mode.title, style = AppType.Section.copy(fontWeight = FontWeight.Bold, color = color))
        BasicText(mode.reason, Modifier.padding(top = 4.dp), style = AppType.Body.copy(color = AppColors.TextMuted))
    }
}

@Composable
private fun InfoRows(rows: List<InfoRow>) {
    Column {
        for (r in rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val line = 0.5.dp.toPx()
                        drawRect(AppColors.BorderSubtle, Offset(0f, size.height - line), Size(size.width, line))
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).background(r.color, CircleShape))
                BasicText(r.key, Modifier.padding(start = 7.dp).width(95.dp), style = AppType.MonoMeta.copy(fontSize = 10.sp, color = PanelColors.SectionTitle))
                BasicText(r.value, Modifier.weight(1f), style = AppType.MonoMeta.copy(fontSize = 10.sp, color = OsdColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val DrawerBackground = Color(0xFF0C0D14)
private val EpisodeShade = ImageShade(0.6f, listOf(0f to Color(0xB306070C), 1f to Color.Transparent))

/**
 * The full layout's drawer under the controls: the season's episodes (a click plays one without leaving the player)
 * or "О фильме", and the way to the series or movie page in the library window.
 */
@Composable
internal fun PlayerDrawer(
    info: PlayerInfo?,
    season: SeasonEpisodes?,
    currentId: UUID?,
    playedFraction: Float,
    ui: PlayerUiState,
    onClose: () -> Unit,
    onPage: () -> Unit,
    onEpisode: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodes = season?.takeIf { info?.isEpisode == true }
    Column(
        modifier
            .fillMaxWidth()
            .height(DRAWER_HEIGHT.dp)
            .holdsControls(ui, "drawer")
            .keepsPointer()
            .background(DrawerBackground, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .drawBehind { drawRect(Color(0xFF2A2E48), size = Size(size.width, 0.5.dp.toPx())) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 36.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(6.dp).background(if (episodes != null) AppColors.Blue else AppColors.Accent, CircleShape))
            BasicText(if (episodes != null) episodes.title else "О ФИЛЬМЕ", style = AppType.MonoLabel.copy(color = AppColors.TextSecondary, letterSpacing = 0.1.em))
            BasicText(
                (if (episodes != null) episodes.stat else info?.title).orEmpty(),
                Modifier.weight(1f),
                style = AppType.MonoCaption.copy(fontSize = 9.5.sp, color = PanelColors.SectionTitle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PagePill(if (episodes != null) "К сериалу" else "Страница фильма", onClick = onPage)
            GlyphButton(Glyph.Close, 30.dp, 13.dp, onClick = onClose, bare = true)
        }
        when {
            episodes != null -> {
                val currentIndex = episodes.episodes.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                ScrollRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0)),
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    spacing = 12.dp,
                ) {
                    items(episodes.episodes, key = { it.key }) { e ->
                        val current = e.id == currentId
                        EpisodeCard(e, current, if (current) playedFraction else e.progress, onClick = { onEpisode(e.id) })
                    }
                }
            }
            info != null -> AboutMovie(info, Modifier.padding(start = 48.dp, end = 48.dp, top = 14.dp))
        }
    }
}

/**
 * The drawer of a small window: over the whole window, episodes as a list (still, number and title, length) or
 * "О фильме".
 */
@Composable
internal fun EpisodesSheet(
    info: PlayerInfo?,
    season: SeasonEpisodes?,
    currentId: UUID?,
    playedFraction: Float,
    ui: PlayerUiState,
    onClose: () -> Unit,
    onPage: () -> Unit,
    onEpisode: (UUID) -> Unit,
) {
    val episodes = season?.takeIf { info?.isEpisode == true }
    PanelFrame(if (episodes != null) episodes.title else "О ФИЛЬМЕ", if (episodes != null) AppColors.Blue else AppColors.Accent, sheet = true, ui, onClose) {
        PagePill(if (episodes != null) "К сериалу" else "Страница фильма", onClick = onPage)
        if (episodes != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in episodes.episodes) {
                    val current = e.id == currentId
                    EpisodeLine(e, current, if (current) playedFraction else e.progress, onClick = { onEpisode(e.id) })
                }
            }
        } else if (info != null) {
            AboutMovie(info, Modifier, narrow = true)
        }
    }
}

@Composable
private fun PagePill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .height(30.dp)
            .onHover { hovered = it }
            .background(if (hovered) AppColors.FocusFill else OsdColors.Fill, shape)
            .border(0.5.dp, if (hovered) AppColors.FocusFill else OsdColors.Border, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(start = 12.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (hovered) AppColors.OnFocusFill else OsdColors.Text
        BasicText(label, style = AppType.Button.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color))
        Icon(AppIcon.ChevronRight, color, Modifier.size(10.dp), strokeWidth = 2.4f)
    }
}

@Composable
private fun AboutMovie(info: PlayerInfo, modifier: Modifier, narrow: Boolean = false) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (!narrow) RemoteImage(info.poster, 7.dp, Modifier.offset(y = (-3).dp).size(PlayerLayout.POSTER_W.dp, PlayerLayout.POSTER_H.dp))
        Column(Modifier.weight(1f)) {
            val meta = AppType.BodyLarge.copy(fontSize = 11.5.sp, color = AppColors.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                info.rating?.let { BasicText(it, style = meta.copy(color = AppColors.Yellow, fontWeight = FontWeight.SemiBold)) }
                info.age?.let {
                    BasicText(
                        it,
                        Modifier.border(0.5.dp, Color(0xFF4A4F68), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        style = meta.copy(fontSize = 9.5.sp),
                    )
                }
                listOfNotNull(info.year, info.runtime).forEach { BasicText(it, style = meta, maxLines = 1) }
            }
            info.genres?.let { BasicText(it, Modifier.padding(top = 5.dp), style = meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            info.description?.let {
                BasicText(
                    it,
                    Modifier.padding(top = 8.dp),
                    style = AppType.Body.copy(fontSize = 12.sp, lineHeight = 18.sp, color = AppColors.TextSecondary),
                    maxLines = if (narrow) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            info.director?.let {
                BasicText("Режиссёр: $it", Modifier.padding(top = 7.dp), style = AppType.Body.copy(fontSize = 10.5.sp, color = AppColors.TextDim), maxLines = 1)
            }
        }
        if (info.cast.isNotEmpty() && !narrow) {
            Column(Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("В РОЛЯХ", style = AppType.MonoCaption.copy(color = PanelColors.SectionTitle, letterSpacing = 0.1.em))
                Spacer(Modifier.height(1.dp))
                for (p in info.cast) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (p.image != null) {
                            RemoteImage(p.image, 12.dp, Modifier.size(PlayerLayout.AVATAR.dp))
                        } else {
                            Avatar(p.name, seed = p.key, size = PlayerLayout.AVATAR.dp, textStyle = AppType.MonoBadge.copy(fontSize = 9.sp, color = Color.White), letters = p.letters)
                        }
                        BasicText(p.name, style = AppType.CardTitle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(e: EpisodeModel, current: Boolean, progress: Float?, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Column(Modifier.width(PlayerLayout.EPISODE_W.dp)) {
        Box(
            Modifier
                .size(PlayerLayout.EPISODE_W.dp, PlayerLayout.EPISODE_H.dp)
                .onHover { hovered = it }
                .then(
                    when {
                        hovered -> Modifier.border(1.5.dp, AppColors.FocusFill, RoundedCornerShape(8.dp))
                        current -> Modifier.border(1.dp, Color(0xFF8B7DFF), RoundedCornerShape(8.dp))
                        else -> Modifier
                    },
                )
                .clickable(interactionSource = null, indication = null, onClick = onClick),
        ) {
            RemoteImage(e.image, 8.dp, Modifier.fillMaxSize(), shade = EpisodeShade)
            BasicText(e.number, Modifier.padding(start = 8.dp, top = 6.dp), style = AppType.MonoMeta.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xE0FFFFFF)))
            EpisodeBadge(e, current, Modifier.align(Alignment.TopEnd).padding(7.dp))
            e.duration?.let {
                BasicText(it, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp), style = AppType.MonoMeta.copy(fontSize = 9.sp, color = Color(0xFFD9DCEC)))
            }
            progress?.takeIf { it > 0f }?.let {
                ProgressLine(it, Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 4.dp).fillMaxWidth().height(3.dp))
            }
        }
        BasicText(
            e.title,
            Modifier.padding(top = 7.dp),
            style = AppType.PosterTitle.copy(color = if (e.watched && !current) AppColors.TextMuted else AppColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One episode in the sheet: small still, title and length, the badge. */
@Composable
private fun EpisodeLine(e: EpisodeModel, current: Boolean, progress: Float?, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .onHover { hovered = it }
            .background(if (hovered) PanelColors.Focused else if (current) PanelColors.Selected else PanelColors.Option, shape)
            .border(0.5.dp, if (hovered) AppColors.Accent else if (current) PanelColors.SelectedBorder else PanelColors.OptionBorder, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(96.dp, 54.dp)) {
            RemoteImage(e.image, 5.dp, Modifier.fillMaxSize())
            progress?.takeIf { it > 0f }?.let {
                ProgressLine(it, Modifier.align(Alignment.BottomStart).padding(horizontal = 4.dp, vertical = 3.dp).fillMaxWidth().height(2.5.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                e.title,
                style = AppType.PosterTitle.copy(color = if (e.watched && !current) AppColors.TextMuted else AppColors.Text),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            e.duration?.let { BasicText(it, Modifier.padding(top = 3.dp), style = AppType.MonoCaption) }
        }
        EpisodeBadge(e, current, Modifier)
    }
}

@Composable
private fun EpisodeBadge(e: EpisodeModel, current: Boolean, modifier: Modifier) {
    val badge = when {
        current -> Triple("СЕЙЧАС", AppColors.AccentStrong, Color.White)
        e.watched -> Triple("✓", Color(0xBF0A0B10), AppColors.Green)
        e.isNew -> Triple("НОВАЯ", Color(0xBF0A0B10), AppColors.Pink)
        else -> return
    }
    val (text, bg, fg) = badge
    BasicText(
        text,
        modifier
            .background(bg, RoundedCornerShape(3.5.dp))
            .then(if (current) Modifier else Modifier.border(0.5.dp, fg, RoundedCornerShape(3.5.dp)))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        style = AppType.MonoBadge.copy(color = fg),
    )
}

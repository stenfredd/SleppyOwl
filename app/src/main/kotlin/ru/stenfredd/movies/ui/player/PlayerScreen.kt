package ru.stenfredd.movies.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.stenfredd.movies.BuildConfig
import ru.stenfredd.movies.core.data.model.MediaInfo
import ru.stenfredd.movies.core.data.model.VideoRange
import ru.stenfredd.movies.core.data.toMediaInfo
import ru.stenfredd.movies.playback.api.DolbyVisionSwitchable
import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.EngineState
import ru.stenfredd.movies.playback.api.SubtitleStyle
import ru.stenfredd.movies.playback.api.VideoPathSwitchable
import ru.stenfredd.movies.playback.core.PlaybackSession
import ru.stenfredd.movies.playback.core.PlaybackUiState
import ru.stenfredd.movies.playback.exo.ExoEngineProvider
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.components.AppIcon
import ru.stenfredd.movies.ui.components.SecondaryButton
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The player: video with the controls of the mockup over it.
 *
 * Remote: with the controls hidden ←/→ seek by 10 s (held: 30 s, then 60 s) and show the timeline, OK pauses,
 * ↓ opens the drawer ("О фильме" / episodes), ↑ shows the controls. Seeks are collected while the keys are pressed
 * and sent to the engine once they stop. The controls hide after 6 s of no keys while playing.
 * Back: panel → drawer → controls → leave the player.
 */
@Composable
fun PlayerScreen(
    screen: Screen.Player,
    onExit: () -> Unit,
    onOpenDetails: (UUID) -> Unit = {},
    onOpenSeries: (seriesId: UUID, episodeId: UUID) -> Unit = { _, _ -> },
    tour: TourInfo? = null,
    vm: PlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(screen) { vm.start(screen) }
    DisposableEffect(vm) { onDispose { vm.release() } }
    val session by vm.session.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(session)
        session?.let { PlayerUi(it, vm, onExit, onOpenDetails, onOpenSeries, tour) }
    }
}

/** Engines put their own views (surface, subtitles) into this container. */
@Composable
private fun VideoSurface(session: PlaybackSession?) {
    var container by remember { mutableStateOf<FrameLayout?>(null) }
    AndroidView(factory = { ctx -> FrameLayout(ctx).also { container = it } }, modifier = Modifier.fillMaxSize())
    DisposableEffect(session, container) {
        val c = container
        if (session != null && c != null) session.attachVideo(c)
        onDispose { session?.detachVideo() }
    }
}

@Stable
private class PlayerUiState {
    var osd by mutableStateOf(true)
    var drawer by mutableStateOf(false)
    var panel by mutableStateOf<PlayerPanel?>(null)
    /** Bumped by every key: restarts the hide timer. */
    var activity by mutableIntStateOf(0)
    var timelineFocused by mutableStateOf(false)
    /** Where the user is seeking to; sent to the engine once the keys stop. */
    var scrubTarget by mutableStateOf<Long?>(null)
    var scrubVersion by mutableIntStateOf(0)
    /** Bumped when a panel action changes engine state the panel shows (video path): re-reads it. */
    var panelRevision by mutableIntStateOf(0)
    var debugOverlay by mutableIntStateOf(0)
    var debugMenu by mutableStateOf(false)
    var tourStep = 0
    /** Back went down while the player had focus; it acts on release. */
    var backPressed = false
    /** "Досмотреть титры" was chosen for this item: no "next episode" card for it any more. */
    var nextDismissedFor by mutableStateOf<UUID?>(null)
    /** The "next episode" card has focus: arrows and OK go to its buttons. */
    var promptFocused = false

    val root = FocusRequester()
    val playPause = FocusRequester()
    val timeline = FocusRequester()
    val panelInitial = FocusRequester()
    val drawerInitial = FocusRequester()
    val errorButton = FocusRequester()
    val nextPlay = FocusRequester()
    val debugMenuFocus = FocusRequester()
    val chips = PlayerPanel.entries.associateWith { FocusRequester() }
    var panelFrom: FocusRequester? = null

    var focusTarget: FocusRequester? = null
        private set
    var focusVersion by mutableIntStateOf(0)
        private set

    /** Focuses [target] once it is composed (next frame). */
    fun focus(target: FocusRequester) {
        focusTarget = target
        focusVersion++
    }

    fun poke() {
        activity++
    }
}

@Composable
private fun PlayerUi(
    session: PlaybackSession,
    vm: PlayerViewModel,
    onExit: () -> Unit,
    onOpenDetails: (UUID) -> Unit,
    onOpenSeries: (UUID, UUID) -> Unit,
    tour: TourInfo?,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    val style by vm.subtitleStyle.collectAsStateWithLifecycle()
    val ui = remember { PlayerUiState() }
    val media = remember(state.source) { state.source?.toMediaInfo() }
    val tracks = remember(media) { PlayerTracks.of(media) }
    val playing = state.engineState.playWhenReady
    val ended = state.engineState.phase == EngineState.Phase.ENDED
    val shownPosition = ui.scrubTarget ?: state.positionMs
    val remainingMs = state.durationMs - state.positionMs
    // "Следующая через N с" near the end of an episode, unless dismissed or something is open over the video.
    val next = season?.nextEpisode?.takeIf {
        info?.isEpisode == true && state.durationMs > 0 && remainingMs in 0..NEXT_PROMPT_MS && !ended && state.error == null &&
            ui.nextDismissedFor != state.itemId && ui.panel == null && !ui.drawer
    }

    fun hideOsd() {
        runCatching { ui.root.requestFocus() }
        ui.osd = false
        ui.drawer = false
        if (next != null) ui.focus(ui.nextPlay)
    }

    fun showOsd(focus: FocusRequester) {
        ui.osd = true
        ui.focus(focus)
    }

    fun scrub(direction: Int, repeat: Int) {
        val step = when {
            repeat < 8 -> 10_000L
            repeat < 24 -> 30_000L
            else -> 60_000L
        }
        val end = (state.durationMs - 1_000).coerceAtLeast(0)
        ui.scrubTarget = ((ui.scrubTarget ?: state.positionMs) + direction * step).coerceIn(0, end)
        ui.scrubVersion++
    }

    fun openDrawer() {
        if (info == null) return
        ui.osd = true
        ui.drawer = true
    }

    fun closeDrawer() {
        ui.drawer = false
        ui.focus(ui.playPause)
    }

    fun openPanel(panel: PlayerPanel) {
        ui.panelFrom = ui.chips[panel]
        ui.panel = panel
    }

    fun closePanel() {
        ui.panel = null
        ui.panelFrom?.let(ui::focus)
    }

    fun back() {
        ui.poke()
        when {
            ui.debugMenu -> {
                ui.debugMenu = false
                ui.focus(ui.root)
            }
            ui.panel != null -> closePanel()
            ui.drawer -> closeDrawer()
            ui.osd && state.error == null -> hideOsd()
            next != null -> {
                ui.nextDismissedFor = state.itemId
                ui.focus(ui.root)
            }
            else -> onExit()
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        val code = e.key.nativeKeyCode
        // Handled here, not only by BackHandler: an unconsumed Back first just clears Compose focus.
        // Acted on when released (as Android does): leaving on the press would send the release to the next screen.
        if (code == AndroidKeyEvent.KEYCODE_BACK || code == AndroidKeyEvent.KEYCODE_ESCAPE) {
            when (e.type) {
                KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) ui.backPressed = true
                KeyEventType.KeyUp -> if (ui.backPressed) {
                    ui.backPressed = false
                    back()
                }
            }
            return true
        }
        if (e.type != KeyEventType.KeyDown) return false
        ui.poke()
        val repeat = e.nativeKeyEvent.repeatCount
        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> session.togglePlayPause()
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (!playing) session.togglePlayPause()
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> session.pause()
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> scrub(1, repeat).also { if (!ui.osd) showOsd(ui.timeline) }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> scrub(-1, repeat).also { if (!ui.osd) showOsd(ui.timeline) }
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> season?.next?.let(vm::playEpisode)
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> season?.previous?.let(vm::playEpisode)
            else -> return onNavigationKey(code, repeat, ui, session, tour, ::scrub, ::showOsd, ::openDrawer, promptShown = next != null)
        }
        return true
    }

    LaunchedEffect(Unit) { ui.focus(ui.playPause) }
    LaunchedEffect(ui.focusVersion) {
        val target = ui.focusTarget ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { target.requestFocus() }
    }
    // Hide the controls after a while of no keys, only while playing and nothing is open on top.
    LaunchedEffect(ui.activity, ui.osd, ui.drawer, ui.panel, playing, state.loading, state.error) {
        if (!ui.osd || ui.drawer || ui.panel != null || ui.debugMenu || !playing || state.loading || state.error != null) return@LaunchedEffect
        delay(OSD_TIMEOUT_MS)
        hideOsd()
    }
    LaunchedEffect(ui.scrubVersion) {
        val target = ui.scrubTarget ?: return@LaunchedEffect
        delay(SCRUB_COMMIT_MS)
        session.seekTo(target)
        // Keep showing the target until the engine reports the new position.
        delay(SCRUB_SETTLE_MS)
        ui.scrubTarget = null
    }
    // Text subtitles move just above the controls while they are shown (fractions of the 540 dp screen, on top of
    // the subtitle view's own 8 % bottom padding).
    LaunchedEffect(session, ui.osd, ui.drawer) {
        session.setSubtitleLift(
            when {
                ui.drawer -> 0.58f
                ui.osd -> 0.22f
                else -> 0f
            },
        )
    }
    LaunchedEffect(state.error) {
        if (state.error != null) showOsd(ui.errorButton)
    }
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        // The end of an episode plays the next one (the card counted down to it, or was dismissed); else leave.
        season?.next?.let(vm::playEpisode) ?: onExit()
    }
    // Another episode started (⏭, media keys) with the drawer open: fold it, its focus belonged to the old list.
    LaunchedEffect(state.itemId) {
        if (ui.drawer) closeDrawer()
    }
    // The card takes focus when it appears over the bare video; with the controls shown they keep it.
    val nextShown = next != null
    LaunchedEffect(nextShown) {
        if (nextShown && !ui.osd) ui.focus(ui.nextPlay)
        if (!nextShown) ui.promptFocused = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.pause() }

    // Back while nothing in the player has focus (the key handler above doesn't see it then).
    BackHandler { back() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(ui.root)
            .onPreviewKeyEvent(::onKey)
            .focusable(),
    ) {
        if (!ui.osd && !playing && !state.loading && state.error == null && !ended) PausedBadge(Modifier.align(Alignment.Center))
        if (ui.osd) {
            OsdLayer(
                state = state,
                info = info,
                season = season,
                media = media,
                ui = ui,
                positionMs = shownPosition,
                onToggle = session::togglePlayPause,
                onSeek = { scrub(it, 0) },
                onEpisode = vm::playEpisode,
                onPanel = ::openPanel,
                onDrawer = ::openDrawer,
            )
        }
        if (ui.drawer) {
            PlayerDrawer(
                info = info,
                season = season,
                currentId = state.itemId,
                playedFraction = if (state.durationMs > 0) shownPosition.toFloat() / state.durationMs else 0f,
                initial = ui.drawerInitial,
                onClose = ::closeDrawer,
                onPage = {
                    val id = state.itemId
                    val seriesId = info?.seriesId
                    when {
                        id == null -> Unit
                        seriesId != null -> onOpenSeries(seriesId, id)
                        else -> onOpenDetails(id)
                    }
                },
                onEpisode = { id ->
                    if (id == state.itemId) {
                        closeDrawer()
                    } else {
                        hideOsd()
                        vm.playEpisode(id)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        next?.let { n ->
            NextEpisodeCard(
                n,
                remainingMs = remainingMs,
                playNow = ui.nextPlay,
                onPlay = {
                    hideOsd()
                    ui.focus(ui.root)
                    vm.playEpisode(n.id)
                },
                onStay = {
                    ui.nextDismissedFor = state.itemId
                    ui.focus(if (ui.osd) ui.playPause else ui.root)
                },
                onFocus = { ui.promptFocused = it },
                // Above the controls while they are shown.
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (ui.osd) 132.dp else 32.dp),
            )
        }
        if (state.loading && state.error == null) LoadingBadge(Modifier.align(Alignment.Center))
        state.error?.let { error ->
            ErrorCard(error.lineSequence().first(), Modifier.align(Alignment.Center)) {
                SecondaryButton("Назад", onClick = onExit, modifier = Modifier.focusRequester(ui.errorButton))
            }
        }
        ui.panel?.let { panel ->
            PanelLayer(panel, state, info, media, tracks, style, session, vm, ui, ::closePanel, Modifier.align(Alignment.CenterEnd))
        }
        if (BuildConfig.DEBUG) {
            if (ui.debugOverlay == 1) DebugPanel(state, vm, Modifier.align(Alignment.TopStart))
            if (ui.debugOverlay < 2 && !ui.osd) VariantBadge(state, session, tour, Modifier.align(Alignment.TopEnd).padding(24.dp))
            if (ui.debugMenu) {
                DebugMenu(state, session, vm, Modifier.align(Alignment.CenterEnd).focusRequester(ui.debugMenuFocus)) {
                    ui.debugMenu = false
                    ui.focus(ui.root)
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching { ui.debugMenuFocus.requestFocus() }
                }
            }
        }
    }
}

/** Keys not handled everywhere: arrows and OK while the controls are hidden, seeking on the timeline, debug keys. */
private fun onNavigationKey(
    code: Int,
    repeat: Int,
    ui: PlayerUiState,
    session: PlaybackSession,
    tour: TourInfo?,
    scrub: (Int, Int) -> Unit,
    showOsd: (FocusRequester) -> Unit,
    openDrawer: () -> Unit,
    promptShown: Boolean,
): Boolean {
    if (BuildConfig.DEBUG && !ui.debugMenu) {
        when (code) {
            AndroidKeyEvent.KEYCODE_INFO -> {
                ui.debugOverlay = (ui.debugOverlay + 1) % 3
                return true
            }
            AndroidKeyEvent.KEYCODE_MENU -> {
                ui.debugMenu = true
                return true
            }
            in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> return applyVariant(code - AndroidKeyEvent.KEYCODE_0, session)
        }
    }
    if (ui.panel != null || ui.drawer || ui.debugMenu) return false
    // The "next episode" card over the bare video: ←/→ and OK work its buttons.
    if (!ui.osd && promptShown && ui.promptFocused && code in PROMPT_KEYS) return false
    if (!ui.osd) {
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP if tour != null -> {
                ui.tourStep = (ui.tourStep + 1) % TOUR_VARIANTS.size
                applyVariant(TOUR_VARIANTS[ui.tourStep], session)
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN if tour != null -> tour.onNextFile()
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                scrub(-1, repeat)
                showOsd(ui.timeline)
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                scrub(1, repeat)
                showOsd(ui.timeline)
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                session.togglePlayPause()
                showOsd(ui.playPause)
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> openDrawer()
            AndroidKeyEvent.KEYCODE_DPAD_UP -> showOsd(ui.playPause)
            else -> return false
        }
        return true
    }
    if (ui.timelineFocused) {
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> scrub(-1, repeat)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> scrub(1, repeat)
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> session.togglePlayPause()
            else -> return false
        }
        return true
    }
    return false
}

@Composable
private fun OsdLayer(
    state: PlaybackUiState,
    info: PlayerInfo?,
    season: SeasonEpisodes?,
    media: MediaInfo?,
    ui: PlayerUiState,
    positionMs: Long,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onEpisode: (UUID) -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    onDrawer: () -> Unit,
) {
    val drawer = ui.drawer
    val episodes = season?.takeIf { info?.isEpisode == true }
    Box(Modifier.fillMaxSize()) {
        // The shades are most of the cost of a frame (~11 ms at the mockup's 130 + 230 dp on the test TV): only as tall
        // as the text over them needs.
        Box(Modifier.fillMaxWidth().height(TOP_SHADE.dp).verticalShade(OsdColors.TopShade))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height((if (drawer) DRAWER_HEIGHT + BOTTOM_SHADE else BOTTOM_SHADE).dp).verticalShade(OsdColors.BottomShade))
        val remaining = (state.durationMs - positionMs).coerceAtLeast(0)
        OsdTop(
            label = info?.label ?: "",
            title = info?.title ?: state.title,
            subtitle = info?.subtitle,
            endsAt = if (state.durationMs > 0) "закончится в " + LocalTime.now().plusSeconds(remaining / 1000).format(EndsFormat) else null,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = if (drawer) (DRAWER_HEIGHT + 16).dp else 32.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                BasicText(PlayerLabels.time(positionMs), style = AppType.MonoMeta.copy(fontSize = 12.sp))
                Spacer(Modifier.weight(1f))
                if (state.durationMs > 0) BasicText("−" + PlayerLabels.time(remaining), style = AppType.MonoMeta.copy(fontSize = 12.sp))
            }
            Timeline(
                positionMs = positionMs,
                durationMs = state.durationMs,
                bufferedMs = state.bufferedMs,
                chaptersMs = info?.chaptersMs.orEmpty(),
                focused = ui.timelineFocused,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .focusRequester(ui.timeline)
                    .focusProperties { down = ui.playPause }
                    .onFocusChanged { ui.timelineFocused = it.isFocused }
                    .focusable(),
            )
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && !drawer) {
                            onDrawer()
                            true
                        } else {
                            false
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                episodes?.previous?.let { id -> OsdCircleButton(AppIcon.Previous, 34.dp, 14.dp, onClick = { onEpisode(id) }) }
                OsdCircleButton(AppIcon.Replay, 38.dp, 17.dp, onClick = { onSeek(-1) })
                OsdPlayButton(state.engineState.playWhenReady, onClick = onToggle, modifier = Modifier.focusRequester(ui.playPause))
                OsdCircleButton(AppIcon.Forward, 38.dp, 17.dp, onClick = { onSeek(1) })
                episodes?.next?.let { id -> OsdCircleButton(AppIcon.Next, 34.dp, 14.dp, onClick = { onEpisode(id) }) }
                Spacer(Modifier.weight(1f))
                for ((panel, chip) in chips(state, info, media)) {
                    OsdChip(chip, onClick = { onPanel(panel) }, modifier = Modifier.focusRequester(ui.chips.getValue(panel)))
                }
            }
        }
        if (!drawer && info != null) {
            PeekHint(
                if (episodes != null) "Серии · сезон ${info.seasonNumber ?: ""}".trimEnd() else "О фильме",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

private val EndsFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Audio, subtitles, quality, Direct/Транскод: only the ones that have something to choose. */
private fun chips(state: PlaybackUiState, info: PlayerInfo?, media: MediaInfo?): List<Pair<PlayerPanel, OsdChipModel>> = buildList {
    val selection = state.selection
    if ((media?.audio?.size ?: 0) > 0) add(PlayerPanel.Audio to OsdChipModel("audio", AppColors.Pink, PlayerLabels.audioChip(media, selection.audioStreamIndex)))
    if ((media?.subtitles?.size ?: 0) > 0) {
        add(PlayerPanel.Subtitles to OsdChipModel("subs", AppColors.Yellow, PlayerLabels.subtitleChip(media, selection.subtitleStreamIndex)))
    }
    info?.qualities?.takeIf { it.size > 1 }?.let { options ->
        val label = options.firstOrNull { it.bitrate == state.maxBitrate }?.label ?: "Оригинал"
        add(PlayerPanel.Quality to OsdChipModel("quality", AppColors.Blue, label))
    }
    val direct = PlayerLabels.isDirect(state)
    add(PlayerPanel.Info to OsdChipModel("info", if (direct) AppColors.Green else AppColors.Orange, if (direct) "Direct" else "Транскод"))
}

@Composable
private fun PanelLayer(
    panel: PlayerPanel,
    state: PlaybackUiState,
    info: PlayerInfo?,
    media: MediaInfo?,
    tracks: PlayerTracks,
    style: SubtitleStyle,
    session: PlaybackSession,
    vm: PlayerViewModel,
    ui: PlayerUiState,
    close: () -> Unit,
    modifier: Modifier,
) {
    val selection = state.selection
    when (panel) {
        PlayerPanel.Audio -> SidePanel("АУДИОДОРОЖКА", AppColors.Pink, "Назад — закрыть", ui.panelInitial, modifier) {
            PanelSection("ДОРОЖКИ" + (info?.title?.let { " · " + it.uppercase() } ?: "")) {
                PanelList(tracks.audio.toChoices(selection.audioStreamIndex), ui.panelInitial) { c ->
                    tracks.audio.firstOrNull { it.key == c.key }?.index?.let(session::selectAudio)
                    close()
                }
            }
            PanelNote(if (info?.isEpisode == true) "Выбор запоминается для всего сериала." else "Выбор запоминается для этого фильма.")
        }
        PlayerPanel.Subtitles -> SidePanel("СУБТИТРЫ", AppColors.Yellow, "Назад — готово", ui.panelInitial, modifier) {
            val selected = media?.subtitles?.firstOrNull { it.index == selection.subtitleStreamIndex }
            SubtitlePreview(style, selected != null)
            PanelSection("ДОРОЖКА") {
                PanelList(tracks.subtitles.toChoices(selection.subtitleStreamIndex), ui.panelInitial) { c ->
                    session.selectSubtitle(tracks.subtitles.firstOrNull { it.key == c.key }?.index)
                }
            }
            PanelSection("РАЗМЕР") { PanelChips(SubtitleChoices.sizes(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            PanelSection("ЦВЕТ") { PanelChips(SubtitleChoices.colors(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            PanelSection("ФОН") { PanelChips(SubtitleChoices.backgrounds(style)) { vm.setSubtitleStyle(SubtitleChoices.apply(style, it)) } }
            val codec = selected?.codec?.lowercase()
            when {
                codec in GRAPHIC_SUBTITLES -> PanelNote("Это графические субтитры (картинки): они показываются как есть, размер и цвет к ним не применяются.")
                codec == "ass" || codec == "ssa" -> PanelNote("У субтитров ASS своё оформление из файла: размер и цвет здесь меняют только текстовые субтитры (SRT, VTT).")
                else -> PanelNote("Вид применяется к текстовым субтитрам (SRT, VTT) и запоминается на этом ТВ.")
            }
        }
        PlayerPanel.Quality -> SidePanel("КАЧЕСТВО", AppColors.Blue, "Назад — закрыть", ui.panelInitial, modifier) {
            val options = info?.qualities.orEmpty()
            PanelSection("БИТРЕЙТ") {
                PanelList(options.map { PanelChoice(it.key, it.label, it.detail, it.bitrate == state.maxBitrate) }, ui.panelInitial) { c ->
                    vm.setQuality(options.firstOrNull { it.key == c.key }?.bitrate)
                    close()
                }
            }
            PanelNote("Всё, что ниже оригинала, сервер перекодирует на лету. Это нагружает процессор сервера.")
        }
        PlayerPanel.Info -> SidePanel("ИНФОРМАЦИЯ О ПОТОКЕ", AppColors.Green, "Назад — закрыть", ui.panelInitial, modifier) {
            val engine = session.activeEngine
            StreamModeCard(PlayerLabels.mode(state))
            InfoRows(PlayerLabels.rows(state, media, engine))
            PanelCloseButton(onClick = close, modifier = Modifier.focusRequester(ui.panelInitial))
            PanelSection("ДОПОЛНИТЕЛЬНО · ДВИЖОК") {
                val choices = listOf(PanelChoice("engine/auto", "Авто", "выбор по файлу", !state.manualEngine)) +
                    vm.selector.engines.map { PanelChoice("engine/${it.id}", it.displayName, null, state.manualEngine && state.engine == it.id) }
                PanelList(choices, initial = null) { c ->
                    val id = c.key.substringAfter('/')
                    if (id == "auto") session.switchToAuto() else session.switchEngine(EngineId(id))
                    close()
                }
            }
            // Switching the path only changes the engine (no new state): [PlayerUiState.panelRevision] redraws the marks.
            (engine as? VideoPathSwitchable)?.let { e -> key(ui.panelRevision) {
                PanelSection("ВЫВОД ВИДЕО") {
                    val now = if (e.usingGpuPath) "сейчас через GPU" else "сейчас видеослой"
                    val choices = listOf(
                        PanelChoice("path/AUTO", "Авто", now, e.videoPathMode == VideoPathSwitchable.Mode.AUTO),
                        PanelChoice("path/VIDEO_PLANE", "Видеослой ТВ", "HDR, обработка ТВ", e.videoPathMode == VideoPathSwitchable.Mode.VIDEO_PLANE),
                        PanelChoice("path/GPU", "Через GPU", "без HDR, до 1080p", e.videoPathMode == VideoPathSwitchable.Mode.GPU),
                    )
                    PanelList(choices, initial = null) { c ->
                        e.setVideoPathMode(VideoPathSwitchable.Mode.valueOf(c.key.substringAfter('/')))
                        ui.panelRevision++
                    }
                }
            } }
            val dv = engine as? DolbyVisionSwitchable
            if (dv != null && media?.video?.range == VideoRange.DolbyVision) {
                PanelSection("DOLBY VISION") {
                    val choices = listOf(
                        PanelChoice("dv/false", "Базовый слой", "HDR10 / HDR10+", !dv.useDolbyVisionDecoder),
                        PanelChoice("dv/true", "DV-декодер", "режим Dolby Vision ТВ", dv.useDolbyVisionDecoder),
                    )
                    PanelList(choices, initial = null) { c ->
                        val on = c.key.endsWith("true")
                        if (on != dv.useDolbyVisionDecoder) {
                            session.switchEngine(ExoEngineProvider.ID, videoPath = (engine as? VideoPathSwitchable)?.videoPathMode, dolbyVision = on)
                        }
                        close()
                    }
                }
            }
        }
    }
}

private val GRAPHIC_SUBTITLES = setOf("pgssub", "hdmv_pgs_subtitle", "dvdsub", "dvd_subtitle", "vobsub", "dvbsub")

private val PROMPT_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

private const val OSD_TIMEOUT_MS = 6_000L
private const val TOP_SHADE = 105
private const val BOTTOM_SHADE = 170
private const val SCRUB_COMMIT_MS = 600L
private const val SCRUB_SETTLE_MS = 1_200L

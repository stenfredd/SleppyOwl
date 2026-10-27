package ru.stenfredd.sleepyowl.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.MediaStreamType
import ru.stenfredd.sleepyowl.core.common.monotonicMs
import ru.stenfredd.sleepyowl.core.data.model.MediaInfo
import ru.stenfredd.sleepyowl.core.data.model.MediaSegment
import ru.stenfredd.sleepyowl.core.data.toMediaInfo
import ru.stenfredd.sleepyowl.playback.api.EngineState
import ru.stenfredd.sleepyowl.playback.api.PlaybackEngine
import ru.stenfredd.sleepyowl.playback.api.streams
import ru.stenfredd.sleepyowl.playback.core.PlaybackSession
import ru.stenfredd.sleepyowl.playback.core.PlaybackUiState
import ru.stenfredd.sleepyowl.playback.mpv.MpvEngine
import ru.stenfredd.sleepyowl.playback.mpv.MpvVideoSurface
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.player.CREDITS_COUNTDOWN_MS
import ru.stenfredd.sleepyowl.ui.player.PlayerInfo
import ru.stenfredd.sleepyowl.ui.player.PlayerLabels
import ru.stenfredd.sleepyowl.ui.player.PlayerTracks
import ru.stenfredd.sleepyowl.ui.player.PlayerViewModel
import ru.stenfredd.sleepyowl.ui.player.SKIP_NOTICE_MS
import ru.stenfredd.sleepyowl.ui.player.SKIP_PROMPT_MS
import ru.stenfredd.sleepyowl.ui.player.SeasonEpisodes
import ru.stenfredd.sleepyowl.ui.player.SegmentSet
import ru.stenfredd.sleepyowl.ui.player.SkipLabels
import ru.stenfredd.sleepyowl.ui.player.SkipPlan
import ru.stenfredd.sleepyowl.ui.player.Skippable
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import androidx.compose.ui.unit.IntSize
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The controls by the window's width (§1.3): the TV's full OSD, one compact row, or the bare minimum. */
internal enum class OsdLayout { Full, Compact, Minimal }

@Stable
internal class PlayerUiState {
    var osd by mutableStateOf(true)
    /** Bumped by the pointer and keys: restarts the hide timer. */
    var activity by mutableIntStateOf(0)
    var pointerInside by mutableStateOf(false)
    /** Where the pointer last was in the window: the right-click menu opens there. */
    var pointer = Offset.Unspecified
    var cursorHidden by mutableStateOf(false)
    /** Controls under the pointer ([holdsControls]): they stay up meanwhile. */
    val holders = mutableStateMapOf<Any, Unit>()
    /** The timeline or the volume slider is being dragged. */
    var dragging by mutableStateOf(false)
    var panel by mutableStateOf<Panel?>(null)
    var menuAt by mutableStateOf<Offset?>(null)
    /** Where the user is seeking to; sent to the engine [scrubDelayMs] after the last key, or when the drag ends. */
    var scrubTarget by mutableStateOf<Long?>(null)
    var scrubVersion by mutableIntStateOf(0)
    var scrubDelayMs = SCRUB_COMMIT_MS
    var lastClickAt = 0L
    /** Height of the bottom controls: text subtitles move above them. */
    var bottomPx by mutableIntStateOf(0)

    /** "Досмотреть титры" was chosen for this item: no "next episode" card for it any more. */
    var nextDismissedFor by mutableStateOf<UUID?>(null)
    /** Where the "next episode" card appeared over known credits: its countdown runs from there. */
    var nextFrom by mutableStateOf<Long?>(null)
    /** Segments skipped in this playback (by the button or on their own): not offered again. */
    var skipped by mutableStateOf(SegmentSet())
    /** Segments the user seeked into: they went there on purpose, so these are not skipped on their own. */
    var seekedInto by mutableStateOf(SegmentSet())
    /** Segments whose button timed out over the bare video: it shows only with the controls. */
    var skipHidden by mutableStateOf(SegmentSet())
    /** "Заставка пропущена" after an automatic skip. */
    var notice by mutableStateOf<String?>(null)
    var noticeVersion by mutableIntStateOf(0)
    /** "Громкость 70 %" and the like, at the top. */
    var toast by mutableStateOf<String?>(null)
    var toastVersion by mutableIntStateOf(0)

    fun poke() {
        osd = true
        cursorHidden = false
        activity++
    }

    fun say(text: String) {
        toast = text
        toastVersion++
    }
}

/** The window's key handler is set once; it calls the player's current one. */
internal class PlayerKeys {
    var handler: (KeyEvent) -> Boolean = { false }
}

/**
 * The player over [session]: video and the controls of §1.3 — shown by the pointer and keys, hidden when the pointer
 * leaves or rests; panels, the drawer, skip and "next episode" as on the TV, the right-click menu, the keys' table.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerContent(
    session: PlaybackSession,
    vm: PlayerViewModel,
    engine: PlaybackEngine?,
    player: DesktopPlayer,
    frame: PlayerFrame?,
    keys: PlayerKeys,
    onClose: () -> Unit,
    onMarkWatched: (UUID, Boolean) -> Unit,
) {
    val state by session.state.collectAsState()
    val info by vm.info.collectAsState()
    val season by vm.season.collectAsState()
    val style by vm.subtitleStyle.collectAsState()
    val segments by vm.segments.collectAsState()
    val defaults by vm.defaults.collectAsState()
    val ui = remember { PlayerUiState() }
    val audio = player.audio
    val media = remember(state.source) { state.source?.toMediaInfo() }
    val tracks = remember(media) { PlayerTracks.of(media) }
    val ended = state.engineState.phase == EngineState.Phase.ENDED
    val shownPosition = ui.scrubTarget ?: state.positionMs
    val remainingMs = state.durationMs - state.positionMs
    val itemId = state.itemId
    val episodes = season?.takeIf { it.currentId == itemId && info?.isEpisode == true }
    val nextEpisode = episodes?.nextEpisode
    val plan = remember(segments, itemId, state.durationMs, defaults, nextEpisode != null) {
        segments?.takeIf { it.itemId == itemId }?.let { SkipPlan.of(it.segments, state.durationMs, defaults, hasNext = nextEpisode != null) }
    }
    val credits = plan?.credits
    val overVideo = !ended && state.error == null && ui.panel == null
    val nextDismissed = ui.nextDismissedFor == itemId
    // Known credits with "Сразу": the next episode starts with them, unless the user seeked into them.
    val creditsAuto = credits != null && plan?.creditsAuto == true && credits !in ui.seekedInto.of(itemId) && !nextDismissed
    // "Следующая через N с" when the credits start (or near the end), unless dismissed or a panel is open.
    val nextWindow = if (credits != null) shownPosition >= credits.startMs else remainingMs in 0..NEXT_PROMPT_MS
    val next = nextEpisode?.takeIf { state.durationMs > 0 && remainingMs >= 0 && nextWindow && overVideo && !nextDismissed && !creditsAuto }
    val nextCountdownMs = if (credits != null) CREDITS_COUNTDOWN_MS else NEXT_PROMPT_MS
    val nextLeftMs = if (credits != null) minOf((ui.nextFrom ?: state.positionMs) + CREDITS_COUNTDOWN_MS - state.positionMs, remainingMs) else remainingMs
    // "Пропустить заставку" while in a skippable part; over the bare video it goes after a while, the controls bring it back.
    val skip = plan?.skipAt(shownPosition)?.takeIf { s ->
        overVideo && !state.loading && next == null && s.segment !in ui.skipped.of(itemId) &&
            (!s.auto || s.segment in ui.seekedInto.of(itemId)) && (ui.osd || s.segment !in ui.skipHidden.of(itemId))
    }
    val autoSkip = plan?.skipAt(state.positionMs)?.takeIf { s ->
        s.auto && ui.scrubTarget == null && !state.loading && state.error == null && !ended &&
            s.segment !in ui.skipped.of(itemId) && s.segment !in ui.seekedInto.of(itemId)
    }
    val mpv = engine as? MpvEngine

    fun skipPart(part: Skippable, auto: Boolean) {
        ui.skipped = ui.skipped.plus(itemId, part.segment)
        session.seekTo(part.segment.endMs)
        if (auto) {
            ui.notice = SkipLabels.skipped(part.segment.kind)
            ui.noticeVersion++
        }
    }

    /** Keys add up: the engine gets one seek once they stop. */
    fun scrubBy(deltaMs: Long) {
        val end = (state.durationMs - 1_000).coerceAtLeast(0)
        ui.scrubTarget = ((ui.scrubTarget ?: state.positionMs) + deltaMs).coerceIn(0, end)
        ui.scrubDelayMs = SCRUB_COMMIT_MS
        ui.scrubVersion++
    }

    /** The timeline: [target] follows the pointer; with [now] it goes to the engine at once. */
    fun scrubTo(target: Long, now: Boolean) {
        ui.scrubTarget = target.coerceIn(0, (state.durationMs - 1_000).coerceAtLeast(0))
        ui.scrubDelayMs = 0
        if (now) ui.scrubVersion++
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, MpvEngine.MAX_VOLUME)
        audio.set(v, muted = false)
        mpv?.setVolume(v)
        mpv?.setMuted(false)
        ui.say("Громкость $v %")
    }

    fun toggleMute() {
        audio.set(audio.volume, !audio.muted)
        mpv?.setMuted(audio.muted)
        ui.say(if (audio.muted) "Без звука" else "Громкость ${audio.volume} %")
    }

    fun cycleAudio() {
        val options = state.source?.streams(MediaStreamType.AUDIO).orEmpty().map { it.index }
        if (options.size < 2) return
        val next = options[(options.indexOf(state.selection.audioStreamIndex) + 1) % options.size]
        session.selectAudio(next)
        ui.say("Аудио: " + PlayerLabels.audioChip(media, next))
    }

    fun cycleSubtitles() {
        val options: List<Int?> = state.source?.streams(MediaStreamType.SUBTITLE).orEmpty().map { it.index } + null
        if (options.size < 2) return
        val next = options[(options.indexOf(state.selection.subtitleStreamIndex) + 1) % options.size]
        session.selectSubtitle(next)
        ui.say(PlayerLabels.subtitleChip(media, next).let { if (next == null) it else "Субтитры: $it" })
    }

    fun toggleFullscreen() = player.toggleFullscreen()

    fun toggleOnTop() = player.toggleOnTop()

    fun showLibrary() {
        player.leaveFullscreen()
        player.showLibrary()
    }

    fun openPanel(panel: Panel) {
        ui.menuAt = null
        ui.panel = panel
        ui.poke()
    }

    fun openPage() {
        val id = state.itemId ?: return
        val seriesId = info?.seriesId
        player.leaveFullscreen()
        player.openPage(if (seriesId != null) Screen.Series(seriesId, id) else Screen.Detail(id))
    }

    fun playEpisode(id: UUID) {
        if (id == state.itemId) return
        ui.panel = null
        vm.playEpisode(id)
    }

    fun onVideoClick() {
        if (ui.panel != null) {
            ui.panel = null
            return
        }
        val now = monotonicMs()
        session.togglePlayPause()
        // The second click of a double click undoes the first one's pause and changes the window instead (§1.1).
        if (now - ui.lastClickAt < DoubleClickMs) {
            ui.lastClickAt = 0
            player.cycleMode()
        } else {
            ui.lastClickAt = now
        }
    }

    fun escape(): Boolean {
        when {
            ui.menuAt != null -> ui.menuAt = null
            ui.panel != null -> ui.panel = null
            player.fullscreen -> player.leaveFullscreen()
            else -> return false
        }
        return true
    }

    fun menuItems(): List<List<MenuItem>> {
        val isEpisode = info?.isEpisode == true
        val sticky = player.placement.sticky
        return listOf(
            listOfNotNull(
                MenuItem(if (player.mode == PlayerMode.Mini) "Обычное окно" else "Поверх всех окон", "T") { toggleOnTop() },
                MenuItem(if (player.fullscreen) "Выйти из полноэкранного" else "Во весь экран", "F") { toggleFullscreen() },
                MenuItem("На всех рабочих столах", if (sticky) "✓" else null) { player.placement.saveSticky(!sticky) }
                    .takeIf { player.mode == PlayerMode.Mini },
            ),
            listOfNotNull(
                MenuItem("Аудио", "A") { openPanel(Panel.Audio) }.takeIf { (media?.audio?.size ?: 0) > 0 },
                MenuItem("Субтитры", "C") { openPanel(Panel.Subtitles) }.takeIf { (media?.subtitles?.size ?: 0) > 0 },
                MenuItem("Качество") { openPanel(Panel.Quality) }.takeIf { (info?.qualities?.size ?: 0) > 1 },
                MenuItem("Информация о потоке") { openPanel(Panel.Info) },
            ),
            listOfNotNull(
                info?.let { MenuItem(if (isEpisode) "Серии" else "О фильме") { openPanel(Panel.Episodes) } },
                MenuItem(if (isEpisode) "К сериалу" else "Страница фильма") { openPage() },
                itemId?.let { id ->
                    MenuItem(if (isEpisode) "Отметить просмотренной" else "Отметить просмотренным") {
                        onMarkWatched(id, true)
                        ui.say("Отмечено как просмотренное")
                    }
                },
            ),
            listOf(MenuItem("К медиатеке") { showLibrary() }, MenuItem("Закрыть плеер", "Ctrl+W") { onClose() }),
        )
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val key = PlayerKey.of(e)
        if (key == Key.Escape) return escape()
        ui.poke()
        val shift = e.isShiftPressed
        when {
            e.isCtrlPressed && key == Key.W -> onClose()
            e.isCtrlPressed -> return false
            key == Key.Spacebar || key == Key.K -> session.togglePlayPause()
            key == Key.DirectionLeft -> scrubBy(if (shift) -60_000 else -10_000)
            key == Key.DirectionRight -> scrubBy(if (shift) 60_000 else 10_000)
            key == Key.DirectionUp -> setVolume((if (audio.muted) 0 else audio.volume) + VOLUME_STEP)
            key == Key.DirectionDown -> setVolume((if (audio.muted) 0 else audio.volume) - VOLUME_STEP)
            key == Key.M -> toggleMute()
            key == Key.S -> skip?.let { skipPart(it, auto = false) } ?: next?.let { playEpisode(it.id) } ?: return true
            key == Key.N && shift -> episodes?.previous?.let(::playEpisode)
            key == Key.N -> episodes?.next?.let(::playEpisode)
            key == Key.A -> cycleAudio()
            key == Key.C -> cycleSubtitles()
            key == Key.F -> toggleFullscreen()
            key == Key.T -> toggleOnTop()
            key in DigitKeys && state.durationMs > 0 -> scrubTo(state.durationMs * (DigitKeys.indexOf(key) + 1) / 10, now = true)
            else -> return false
        }
        return true
    }
    keys.handler = ::onKey

    // The system's view of the player (§2.5): MPRIS for the media keys and the sound applet, the screensaver.
    val nowPlaying = info?.takeIf { it.id == itemId }?.let { i ->
        NowPlaying(
            itemId = i.id,
            title = if (i.isEpisode) i.subtitle ?: i.title else i.title,
            artist = if (i.isEpisode) i.title else i.subtitle,
            artUrl = i.poster?.url,
            durationMs = state.durationMs,
            playing = state.engineState.playWhenReady && !ended && state.error == null,
            canNext = episodes?.next != null,
            canPrevious = episodes?.previous != null,
        )
    }
    // While the next episode's info loads the system keeps the last one: the player doesn't vanish from the applet.
    if (nowPlaying != null) SideEffect { player.nowPlaying = nowPlaying }
    player.commands = object : PlayerCommands {
        override fun playPause(play: Boolean?) {
            if (play == null || play != session.state.value.engineState.playWhenReady) session.togglePlayPause()
            ui.poke()
        }

        override fun next() {
            episodes?.next?.let(::playEpisode)
        }

        override fun previous() {
            episodes?.previous?.let(::playEpisode)
        }

        override fun seekTo(positionMs: Long) = scrubTo(positionMs, now = true)

        override fun changeVolume(percent: Int) = setVolume(percent)

        override fun bringUp() {
            frame?.let { raise(it.window) }
        }

        override fun positionMs() = session.state.value.positionMs
    }
    DisposableEffect(player) {
        onDispose {
            player.nowPlaying = null
            player.commands = null
        }
    }

    // Hide after a while of no pointer moves or keys, unless something holds the controls (§1.3).
    val hold = ui.holders.isNotEmpty() || ui.dragging || ui.panel != null || ui.menuAt != null || state.error != null
    LaunchedEffect(ui.activity, ui.osd, hold) {
        if (!ui.osd || hold) return@LaunchedEffect
        delay(if (player.mode == PlayerMode.Mini) MINI_IDLE_HIDE_MS else IDLE_HIDE_MS)
        ui.osd = false
        ui.cursorHidden = ui.pointerInside
    }
    // A panel or menu left open by the pointer leaving the window closes after a moment, unless it comes back.
    LaunchedEffect(ui.pointerInside, ui.panel, ui.menuAt) {
        if (ui.pointerInside || (ui.panel == null && ui.menuAt == null)) return@LaunchedEffect
        delay(PANEL_LEAVE_MS)
        ui.panel = null
        ui.menuAt = null
        ui.osd = false
    }
    LaunchedEffect(ui.scrubVersion) {
        val target = ui.scrubTarget ?: return@LaunchedEffect
        delay(ui.scrubDelayMs)
        plan?.spanAt(target)?.let { ui.seekedInto = ui.seekedInto.plus(state.itemId, it) }
        session.seekTo(target)
        // Keep showing the target until the engine reports the new position.
        delay(SCRUB_SETTLE_MS)
        if (!ui.dragging) ui.scrubTarget = null
    }
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        // The end of an episode plays the next one (the card counted down to it, or was dismissed); else the player closes.
        season?.next?.let(vm::playEpisode) ?: onClose()
    }
    LaunchedEffect(state.itemId) {
        if (ui.panel == Panel.Episodes) ui.panel = null
    }
    LaunchedEffect(autoSkip) {
        autoSkip?.let { skipPart(it, auto = true) }
    }
    val creditsGo = creditsAuto && nextEpisode != null && credits != null && state.positionMs >= credits.startMs &&
        ui.scrubTarget == null && overVideo && !state.loading
    val nextCountedDown = next != null && credits != null && nextLeftMs <= 0
    LaunchedEffect(creditsGo, nextCountedDown) {
        if (creditsGo || nextCountedDown) nextEpisode?.let { vm.playEpisode(it.id) }
    }
    LaunchedEffect(ui.noticeVersion) {
        if (ui.notice == null) return@LaunchedEffect
        delay(SKIP_NOTICE_MS)
        ui.notice = null
    }
    LaunchedEffect(ui.toastVersion) {
        if (ui.toast == null) return@LaunchedEffect
        delay(TOAST_MS)
        ui.toast = null
    }
    LaunchedEffect(skip?.segment, ui.osd) {
        val part = skip?.segment ?: return@LaunchedEffect
        if (ui.osd) return@LaunchedEffect
        delay(SKIP_PROMPT_MS)
        ui.skipHidden = ui.skipHidden.plus(itemId, part)
    }
    val nextShown = next != null
    LaunchedEffect(nextShown) {
        ui.nextFrom = if (nextShown) state.positionMs else null
    }

    // The mini window is resized by its edges (§1.1): the window manager does it from the press on.
    val mini = player.mode == PlayerMode.Mini
    var edge by remember { mutableStateOf<Int?>(null) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerHoverIcon(edge?.let(::edgeIcon) ?: if (ui.cursorHidden) BlankCursor else PointerIcon.Default, overrideDescendants = edge != null)
            // Initial pass: every move counts, over the controls too. Compose also sends a move without one when the
            // layout under a resting pointer changes (the controls fading out): those must not bring them back.
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) {
                val at = it.changes.first().position
                if (at != ui.pointer) ui.poke()
                ui.pointer = at
                ui.pointerInside = true
                edge = if (mini) edgeAt(at, size) else null
            }
            .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) {
                ui.pointer = it.changes.first().position
                ui.pointerInside = true
                ui.poke()
            }
            .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                ui.pointerInside = false
                edge = null
                ui.holders.clear()
                if (!ui.dragging && ui.panel == null && ui.menuAt == null) ui.osd = false
            }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                val at = it.changes.first().position
                ui.pointer = at
                ui.poke()
                val direction = if (mini && it.buttons.isPrimaryPressed) edgeAt(at, size) else null
                val awt = it.awtEventOrNull
                if (direction != null && awt != null && frame != null) {
                    it.changes.forEach { change -> change.consume() }
                    frame.startResize(awt, direction)
                }
            }
            // The wheel nobody else took (the timeline seeks, panels scroll): volume. Not past the end of the menu.
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val change = e.changes.first()
                if (change.isConsumed || change.scrollDelta.y == 0f || ui.menuAt != null) return@onPointerEvent
                setVolume((if (audio.muted) 0 else audio.volume) + if (change.scrollDelta.y < 0) VOLUME_STEP else -VOLUME_STEP)
            },
    ) {
        val heightPx = constraints.maxHeight
        val layout = when {
            maxWidth >= FULL_MIN_WIDTH.dp -> OsdLayout.Full
            maxWidth >= COMPACT_MIN_WIDTH.dp -> OsdLayout.Compact
            else -> OsdLayout.Minimal
        }
        // Small windows: panels over the whole window; the drawer of the full layout is a sheet there too.
        val sheets = layout != OsdLayout.Full || maxHeight < SHEET_MAX_HEIGHT.dp
        val drawer = ui.panel == Panel.Episodes && !sheets

        VideoLayer(
            mpv, onClick = ::onVideoClick, onSecondary = { at -> ui.menuAt = at },
            onDrag = frame?.takeIf { mini }?.let { f -> f::startMove },
        )

        // Text subtitles move above the controls while they are shown.
        val liftPx = if (ui.osd) ui.bottomPx else 0
        LaunchedEffect(session, liftPx, heightPx) {
            session.setSubtitleLift(if (heightPx > 0) liftPx.toFloat() / heightPx else 0f)
        }

        AnimatedVisibility(ui.osd || drawer, Modifier.fillMaxSize(), enter = fadeIn(tween(120)), exit = fadeOut(tween(FADE_MS))) {
            val playing = state.engineState.playWhenReady
            when (layout) {
                OsdLayout.Full -> FullOsd(
                    state, info, episodes, media, ui, shownPosition, plan?.spans.orEmpty(), playing, player, drawer,
                    onToggle = session::togglePlayPause, onSeek = ::scrubBy, onScrub = ::scrubTo, onEpisode = ::playEpisode,
                    onPanel = ::openPanel, onVolume = ::setVolume, onMute = ::toggleMute, onFullscreen = ::toggleFullscreen,
                    onOnTop = ::toggleOnTop, onLibrary = ::showLibrary, onClose = onClose,
                )
                OsdLayout.Compact -> CompactOsd(
                    state, info, ui, shownPosition, plan?.spans.orEmpty(), playing, player,
                    onToggle = session::togglePlayPause, onSeek = ::scrubBy, onScrub = ::scrubTo,
                    onVolume = ::setVolume, onMute = ::toggleMute, onMenu = { ui.menuAt = it }, onFullscreen = ::toggleFullscreen,
                    onOnTop = ::toggleOnTop, onLibrary = ::showLibrary, onClose = onClose,
                )
                OsdLayout.Minimal -> MinimalOsd(
                    state, info, episodes, ui, shownPosition, plan?.spans.orEmpty(), playing, player.mode,
                    onToggle = session::togglePlayPause, onSeek = ::scrubBy, onScrub = ::scrubTo, onEpisode = ::playEpisode,
                    onMenu = { ui.menuAt = it }, onOnTop = ::toggleOnTop, onLibrary = ::showLibrary, onClose = onClose,
                )
            }
        }
        if (drawer) {
            PlayerDrawer(
                info, season, state.itemId,
                playedFraction = if (state.durationMs > 0) shownPosition.toFloat() / state.durationMs else 0f,
                ui = ui, onClose = { ui.panel = null }, onPage = ::openPage, onEpisode = ::playEpisode,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Prompts sit in the bottom right corner, above the controls while they are shown.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val promptBottom = if (ui.osd) with(density) { ui.bottomPx.toDp() } + 12.dp else 24.dp
        val promptEnd = if (layout == OsdLayout.Full) 48.dp else 12.dp
        val promptModifier = Modifier.align(Alignment.BottomEnd).padding(end = promptEnd, bottom = promptBottom)
        skip?.let { part ->
            SkipButton(SkipLabels.button(part.segment.kind), PlayerLabels.time(part.segment.endMs - shownPosition), ui, onClick = { skipPart(part, auto = false) }, modifier = promptModifier)
        }
        ui.notice?.takeIf { skip == null && next == null }?.let { SkipNotice(it, promptModifier) }
        next?.let { n ->
            NextEpisodeCard(
                n, remainingMs = nextLeftMs, countdownMs = nextCountdownMs, compact = layout == OsdLayout.Minimal, ui = ui,
                onPlay = { playEpisode(n.id) },
                onStay = { ui.nextDismissedFor = state.itemId },
                modifier = promptModifier,
            )
        }
        if (state.loading && state.error == null) LoadingBadge(Modifier.align(Alignment.Center))
        state.error?.let { ErrorCard(it.lineSequence().first(), onClose, Modifier.align(Alignment.Center)) }
        ui.toast?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = if (layout == OsdLayout.Full) 96.dp else 44.dp)) }

        ui.panel?.takeIf { !drawer }?.let { panel ->
            if (panel == Panel.Episodes) {
                EpisodesSheet(
                    info, season, state.itemId,
                    playedFraction = if (state.durationMs > 0) shownPosition.toFloat() / state.durationMs else 0f,
                    ui = ui, onClose = { ui.panel = null }, onPage = ::openPage, onEpisode = ::playEpisode,
                )
            } else {
                PanelContent(
                    panel, state, info, media, tracks, style, session, vm, sheets, ui, close = { ui.panel = null },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        ui.menuAt?.let { at -> PlayerMenu(menuItems(), at, ui, onDismiss = { ui.menuAt = null }) }
    }
}

/**
 * The picture, and the mouse on it: a click pauses, a double click changes the window, the right button opens the
 * menu. With [onDrag] (the mini window) a press that moves drags the window instead.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VideoLayer(engine: MpvEngine?, onClick: () -> Unit, onSecondary: (Offset) -> Unit, onDrag: ((MouseEvent, Offset) -> Unit)?) {
    Box(
        Modifier
            .fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) onSecondary(it.changes.first().position) }
            .pointerInput(onDrag) {
                val slop = CLICK_SLOP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > slop) {
                            moved = true
                            val awt = event.awtEventOrNull
                            if (onDrag != null && awt != null) {
                                onDrag(awt, change.position - down.position)
                                break
                            }
                        }
                        if (!change.pressed) break
                    }
                    if (!moved) onClick()
                }
            },
    ) {
        engine?.let { MpvVideoSurface(it.video, Modifier.fillMaxSize()) }
    }
}

/** ≥ 900 px: the TV's OSD — title and clock on top; timeline, ⏮ ⟲ ⏯ ⟳ ⏭, chips, volume and the window at the bottom. */
@Composable
internal fun FullOsd(
    state: PlaybackUiState,
    info: PlayerInfo?,
    episodes: SeasonEpisodes?,
    media: MediaInfo?,
    ui: PlayerUiState,
    positionMs: Long,
    spans: List<MediaSegment>,
    playing: Boolean,
    player: DesktopPlayer,
    drawer: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: (Long, Boolean) -> Unit,
    onEpisode: (UUID) -> Unit,
    onPanel: (Panel) -> Unit,
    onVolume: (Int) -> Unit,
    onMute: () -> Unit,
    onFullscreen: () -> Unit,
    onOnTop: () -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(OsdColors.TopShade))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height((if (drawer) DRAWER_HEIGHT + 180 else 180).dp).background(OsdColors.BottomShade))
        val remaining = (state.durationMs - positionMs).coerceAtLeast(0)
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 24.dp, top = 28.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText(info?.label ?: "", style = AppType.MonoLabel)
                BasicText(
                    info?.title ?: state.title,
                    Modifier.padding(top = 5.dp),
                    style = AppType.Hero.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.em),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                info?.subtitle?.let {
                    BasicText(it, Modifier.padding(top = 4.dp), style = AppType.BodyLarge.copy(color = AppColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.padding(start = 24.dp), horizontalAlignment = Alignment.End) {
                Clock(AppType.Clock.copy(fontSize = 17.sp, color = AppColors.Text))
                if (state.durationMs > 0) {
                    BasicText("закончится в " + LocalTime.now().plusSeconds(remaining / 1000).format(HourMinute), Modifier.padding(top = 3.dp), style = AppType.MonoCaption.copy(fontSize = 10.sp))
                }
            }
            GlyphButton(Glyph.Library, 34.dp, 15.dp, onClick = onLibrary, modifier = Modifier.padding(start = 14.dp).holdsControls(ui, "library"), bare = true)
            GlyphButton(Glyph.Close, 34.dp, 15.dp, onClick = onClose, modifier = Modifier.padding(start = 4.dp).holdsControls(ui, "close"), bare = true)
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .onSizeChanged { ui.bottomPx = it.height }
                .holdsControls(ui, "bottom")
                .padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = if (drawer) (DRAWER_HEIGHT + 14).dp else 26.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                BasicText(PlayerLabels.time(positionMs), style = AppType.MonoMeta.copy(fontSize = 12.sp))
                Spacer(Modifier.weight(1f))
                if (state.durationMs > 0) BasicText("−" + PlayerLabels.time(remaining), style = AppType.MonoMeta.copy(fontSize = 12.sp))
            }
            Timeline(
                positionMs, state.durationMs, state.bufferedMs, info?.chaptersMs.orEmpty(), spans, ui,
                onScrub = { onScrub(it, false) }, onRelease = { ui.scrubTarget?.let { onScrub(it, true) } }, onWheel = { onSeek(it * WHEEL_SEEK_MS) },
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                episodes?.previous?.let { id -> IconButton(AppIcon.Previous, 34.dp, 14.dp, onClick = { onEpisode(id) }) }
                IconButton(AppIcon.Replay, 38.dp, 17.dp, onClick = { onSeek(-10_000) })
                PlayButton(playing, 46.dp, onClick = onToggle)
                IconButton(AppIcon.Forward, 38.dp, 17.dp, onClick = { onSeek(10_000) })
                episodes?.next?.let { id -> IconButton(AppIcon.Next, 34.dp, 14.dp, onClick = { onEpisode(id) }) }
                Spacer(Modifier.weight(1f))
                for (chip in chips(state, info, media, episodes)) OsdChip(chip, onClick = { onPanel(chip.panel) })
                VolumeControl(player.audio, ui, onChange = onVolume, onMute = onMute, modifier = Modifier.padding(start = 8.dp))
                GlyphButton(modeGlyph(player.mode), 34.dp, 16.dp, onClick = onOnTop, bare = true)
                GlyphButton(if (player.fullscreen) Glyph.ExitFullscreen else Glyph.Fullscreen, 34.dp, 16.dp, onClick = onFullscreen, bare = true)
            }
        }
    }
}

/** 480–900 px: a thin title line with × on top, one row at the bottom; panels from ⋯. */
@Composable
internal fun CompactOsd(
    state: PlaybackUiState,
    info: PlayerInfo?,
    ui: PlayerUiState,
    positionMs: Long,
    spans: List<MediaSegment>,
    playing: Boolean,
    player: DesktopPlayer,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: (Long, Boolean) -> Unit,
    onVolume: (Int) -> Unit,
    onMute: () -> Unit,
    onMenu: (Offset) -> Unit,
    onFullscreen: () -> Unit,
    onOnTop: () -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(64.dp).background(OsdColors.TopShade))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(90.dp).background(OsdColors.BottomShade))
        TopLine(info, state, ui, onLibrary, onClose)
        BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            val roomy = maxWidth >= 640.dp
            Row(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { ui.bottomPx = it.height }
                    .holdsControls(ui, "bottom")
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlayButton(playing, 36.dp, onClick = onToggle)
                IconButton(AppIcon.Replay, 32.dp, 15.dp, onClick = { onSeek(-10_000) }, bare = true)
                IconButton(AppIcon.Forward, 32.dp, 15.dp, onClick = { onSeek(10_000) }, bare = true)
                TimeLabel(positionMs, state.durationMs, Modifier.padding(horizontal = 4.dp))
                Timeline(
                    positionMs, state.durationMs, state.bufferedMs, info?.chaptersMs.orEmpty(), spans, ui,
                    onScrub = { onScrub(it, false) }, onRelease = { ui.scrubTarget?.let { onScrub(it, true) } }, onWheel = { onSeek(it * WHEEL_SEEK_MS) },
                    modifier = Modifier.weight(1f),
                )
                VolumeControl(player.audio, ui, onChange = onVolume, onMute = onMute, slider = roomy)
                GlyphButton(Glyph.More, 32.dp, 17.dp, onClick = { onMenu(ui.pointer) }, bare = true)
                GlyphButton(modeGlyph(player.mode), 32.dp, 15.dp, onClick = onOnTop, bare = true)
                GlyphButton(if (player.fullscreen) Glyph.ExitFullscreen else Glyph.Fullscreen, 32.dp, 15.dp, onClick = onFullscreen, bare = true)
            }
        }
    }
}

/** < 480 px: ⏯ in the middle with ⏮/⏭ (⟲/⟳ for a movie), a thin timeline at the bottom, ⋯ and × in the top corners. */
@Composable
internal fun MinimalOsd(
    state: PlaybackUiState,
    info: PlayerInfo?,
    episodes: SeasonEpisodes?,
    ui: PlayerUiState,
    positionMs: Long,
    spans: List<MediaSegment>,
    playing: Boolean,
    mode: PlayerMode,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: (Long, Boolean) -> Unit,
    onEpisode: (UUID) -> Unit,
    onMenu: (Offset) -> Unit,
    onOnTop: () -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Row(Modifier.align(Alignment.TopStart).padding(6.dp).holdsControls(ui, "top-start")) {
            GlyphButton(Glyph.More, 30.dp, 16.dp, onClick = { onMenu(ui.pointer) }, bare = true)
            GlyphButton(modeGlyph(mode), 30.dp, 14.dp, onClick = onOnTop, bare = true)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(6.dp).holdsControls(ui, "top-end")) {
            GlyphButton(Glyph.Library, 30.dp, 13.dp, onClick = onLibrary, bare = true)
            GlyphButton(Glyph.Close, 30.dp, 13.dp, onClick = onClose, bare = true)
        }
        Row(
            Modifier.align(Alignment.Center).holdsControls(ui, "center"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val previous = episodes?.previous
            val following = episodes?.next
            if (episodes != null) {
                IconButton(AppIcon.Previous, 32.dp, 13.dp, onClick = { previous?.let(onEpisode) })
            } else {
                IconButton(AppIcon.Replay, 32.dp, 14.dp, onClick = { onSeek(-10_000) })
            }
            PlayButton(playing, 46.dp, onClick = onToggle)
            if (episodes != null) {
                IconButton(AppIcon.Next, 32.dp, 13.dp, onClick = { following?.let(onEpisode) })
            } else {
                IconButton(AppIcon.Forward, 32.dp, 14.dp, onClick = { onSeek(10_000) })
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .onSizeChanged { ui.bottomPx = it.height }
                .holdsControls(ui, "bottom")
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            TimeLabel(positionMs, state.durationMs)
            Timeline(
                positionMs, state.durationMs, state.bufferedMs, info?.chaptersMs.orEmpty(), spans, ui,
                onScrub = { onScrub(it, false) }, onRelease = { ui.scrubTarget?.let { onScrub(it, true) } }, onWheel = { onSeek(it * WHEEL_SEEK_MS) },
                thin = true,
            )
        }
    }
}

/** "Название · Сезон 1 · Серия 4", "К медиатеке" and ×. */
@Composable
private fun TopLine(info: PlayerInfo?, state: PlaybackUiState, ui: PlayerUiState, onLibrary: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            listOfNotNull(info?.title ?: state.title, info?.subtitle).joinToString(" · "),
            Modifier.weight(1f),
            style = AppType.Section.copy(fontSize = 13.sp, color = AppColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        GlyphButton(Glyph.Library, 30.dp, 13.dp, onClick = onLibrary, modifier = Modifier.holdsControls(ui, "library"), bare = true)
        GlyphButton(Glyph.Close, 30.dp, 13.dp, onClick = onClose, modifier = Modifier.holdsControls(ui, "close"), bare = true)
    }
}

/** The window mode button: to the mini window on top, or out of it into the normal one. */
private fun modeGlyph(mode: PlayerMode) = if (mode == PlayerMode.Mini) Glyph.Windowed else Glyph.OnTop

/** The mini window's edge under [p] (8 px, corners 20 px), as an `X11Window.SIZE_*` direction; null inside. */
private fun edgeAt(p: Offset, size: IntSize): Int? {
    val l = p.x < EDGE
    val r = p.x > size.width - EDGE
    val t = p.y < EDGE
    val b = p.y > size.height - EDGE
    val lc = p.x < CORNER
    val rc = p.x > size.width - CORNER
    val tc = p.y < CORNER
    val bc = p.y > size.height - CORNER
    return when {
        (t && lc) || (l && tc) -> X11Window.SIZE_TOPLEFT
        (t && rc) || (r && tc) -> X11Window.SIZE_TOPRIGHT
        (b && lc) || (l && bc) -> X11Window.SIZE_BOTTOMLEFT
        (b && rc) || (r && bc) -> X11Window.SIZE_BOTTOMRIGHT
        t -> X11Window.SIZE_TOP
        b -> X11Window.SIZE_BOTTOM
        l -> X11Window.SIZE_LEFT
        r -> X11Window.SIZE_RIGHT
        else -> null
    }
}

private fun edgeIcon(direction: Int) = PointerIcon(
    Cursor(
        when (direction) {
            X11Window.SIZE_TOPLEFT -> Cursor.NW_RESIZE_CURSOR
            X11Window.SIZE_TOP -> Cursor.N_RESIZE_CURSOR
            X11Window.SIZE_TOPRIGHT -> Cursor.NE_RESIZE_CURSOR
            X11Window.SIZE_RIGHT -> Cursor.E_RESIZE_CURSOR
            X11Window.SIZE_BOTTOMRIGHT -> Cursor.SE_RESIZE_CURSOR
            X11Window.SIZE_BOTTOM -> Cursor.S_RESIZE_CURSOR
            X11Window.SIZE_BOTTOMLEFT -> Cursor.SW_RESIZE_CURSOR
            else -> Cursor.W_RESIZE_CURSOR
        },
    ),
)

/** "Серии", audio, subtitles, quality, Direct/Транскод: only the ones that have something to show. */
private fun chips(state: PlaybackUiState, info: PlayerInfo?, media: MediaInfo?, episodes: SeasonEpisodes?): List<OsdChipModel> = buildList {
    val selection = state.selection
    if (info != null) {
        add(OsdChipModel(Panel.Episodes, if (episodes != null) AppColors.Blue else AppColors.Accent, if (episodes != null) "Серии" else "О фильме"))
    }
    if ((media?.audio?.size ?: 0) > 0) add(OsdChipModel(Panel.Audio, AppColors.Pink, PlayerLabels.audioChip(media, selection.audioStreamIndex)))
    if ((media?.subtitles?.size ?: 0) > 0) add(OsdChipModel(Panel.Subtitles, AppColors.Yellow, PlayerLabels.subtitleChip(media, selection.subtitleStreamIndex)))
    info?.qualities?.takeIf { it.size > 1 }?.let { options ->
        add(OsdChipModel(Panel.Quality, AppColors.Blue, options.firstOrNull { it.bitrate == state.maxBitrate }?.label ?: "Оригинал"))
    }
    val direct = PlayerLabels.isDirect(state)
    add(OsdChipModel(Panel.Info, if (direct) AppColors.Green else AppColors.Orange, if (direct) "Direct" else "Транскод"))
}

/** HH:mm, recomposes once a minute and only itself. */
@Composable
private fun Clock(style: androidx.compose.ui.text.TextStyle) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            now = LocalTime.now()
        }
    }
    BasicText(now.format(HourMinute), style = style)
}

/**
 * The key the player acts on. The table of §1.3 is by physical keys: in the Russian layout "Ь" is still M. AWT names
 * a key by the character of its layout, and for Cyrillic it has no names; the X key code (the place on the keyboard)
 * gives the Latin one.
 */
internal object PlayerKey {
    fun of(e: KeyEvent): Key {
        val awt = e.awtEventOrNull ?: return e.key
        if (awt.keyCode != java.awt.event.KeyEvent.VK_UNDEFINED && awt.keyCode < 0x01000000) return e.key
        return byX11Code[rawCode(awt)] ?: e.key
    }

    /** X11 key codes (evdev + 8) of the US layout's letters and digits the player uses. */
    private val byX11Code = mapOf(
        38 to Key.A, 54 to Key.C, 45 to Key.K, 58 to Key.M, 57 to Key.N, 39 to Key.S, 41 to Key.F, 28 to Key.T, 25 to Key.W,
        10 to Key.One, 11 to Key.Two, 12 to Key.Three, 13 to Key.Four, 14 to Key.Five, 15 to Key.Six, 16 to Key.Seven, 17 to Key.Eight, 18 to Key.Nine,
    )

    /** AWT keeps the X key code in a private field, closed by the module system; its public description shows it. */
    private fun rawCode(e: java.awt.event.KeyEvent): Int = RawCode.find(e.paramString())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private val RawCode = Regex("""rawCode=(\d+)""")
}

private val DigitKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

private val BlankCursor by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none"))
}

/** The system's double click, at most 0.5 s. */
private val DoubleClickMs: Long by lazy {
    ((Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 400).coerceIn(200, 500).toLong()
}

private const val FULL_MIN_WIDTH = 900
private const val COMPACT_MIN_WIDTH = 480
/** Lower windows get their panels as sheets: a 350 px column next to the video would be too cramped. */
private const val SHEET_MAX_HEIGHT = 420
private const val IDLE_HIDE_MS = 3_000L
private const val MINI_IDLE_HIDE_MS = 2_000L
private const val EDGE = 8f
private const val CORNER = 20f
private const val PANEL_LEAVE_MS = 1_500L
private const val FADE_MS = 150
private const val TOAST_MS = 1_200L
private const val SCRUB_COMMIT_MS = 400L
private const val SCRUB_SETTLE_MS = 1_200L
private const val WHEEL_SEEK_MS = 5_000L
private const val VOLUME_STEP = 5
/** A press that moves further is a drag, not a click. */
private const val CLICK_SLOP = 4

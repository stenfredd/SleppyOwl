package ru.stenfredd.sleepyowl.ui.details

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AnchoredScrolling
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.CircleButton
import ru.stenfredd.sleepyowl.ui.components.ContentRow
import ru.stenfredd.sleepyowl.ui.components.DefaultScrolling
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.FormatBadge
import ru.stenfredd.sleepyowl.ui.components.PosterCard
import ru.stenfredd.sleepyowl.ui.components.PrimaryButton
import ru.stenfredd.sleepyowl.ui.components.RemoteImage
import ru.stenfredd.sleepyowl.ui.components.SecondaryButton
import ru.stenfredd.sleepyowl.ui.components.SectionTitle
import ru.stenfredd.sleepyowl.ui.components.anchorViewport
import ru.stenfredd.sleepyowl.ui.components.anchoredRow
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.focus.Restore
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType
import ru.stenfredd.sleepyowl.ui.details.DetailsLayout as L

/** Movie (or episode) page, the mockup's "Фильм" screen. Full screen, without the side rail. */
@Composable
fun DetailsScreen(
    screen: Screen.Detail,
    onOpen: (Screen) -> Unit,
    vm: DetailsViewModel = koinViewModel { parametersOf(screen.itemId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = rememberScreenFocus()
    val item = state.item

    when {
        item != null -> AnchoredScrolling(rememberScrollState(), anchor = AppDimens.CONTENT_TOP.dp) { anchored ->
            Column(
                Modifier
                    .fillMaxSize()
                    .anchorViewport(anchored)
                    .verticalScroll(anchored.scroll)
                    .padding(bottom = 200.dp),
            ) {
                Hero(item, state, focus, vm, onOpen, Modifier.anchoredRow(anchored))
                if (item.cast.isNotEmpty()) Cast(item, focus, onOpen, Modifier.anchoredRow(anchored))
                if (state.similar.isNotEmpty()) {
                    ContentRow("Похожие", null, Modifier.anchoredRow(anchored).padding(top = 6.dp)) {
                        items(state.similar, key = { it.key }, contentType = { "poster" }) { p ->
                            PosterCard(p, { vm.similarTarget(p.key)?.let(onOpen) }, Modifier.remembered(focus, p.key))
                        }
                    }
                }
            }
        }
        state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Не удалось загрузить: ${state.error}", style = AppType.Body)
        }
    }

    // "С начала" disappears once the movie is watched to the end: focus "Смотреть" instead.
    val restartShown = (item?.resumeMs ?: 0) > 0
    LaunchedEffect(restartShown) {
        if (!restartShown && focus.lastKey == KEY_RESTART) focus.lastKey = KEY_PLAY
    }
    focus.Restore(ready = item != null)
}

private const val KEY_PLAY = "play"
private const val KEY_RESTART = "restart"

/** First screen of the page: backdrop, poster with formats, title, actions and the track choice. */
@Composable
private fun Hero(item: DetailsModel, state: DetailsState, focus: ScreenFocus, vm: DetailsViewModel, onOpen: (Screen) -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(L.PAGE_H.dp)) {
        item.backdropUrl?.let { Backdrop(it) }
        Column(Modifier.offset(L.POSTER_X.dp, L.POSTER_Y.dp).width(L.POSTER_W.dp)) {
            val shape = RoundedCornerShape(L.POSTER_CORNER.dp)
            RemoteImage(
                item.poster,
                L.POSTER_CORNER.dp,
                Modifier.size(L.POSTER_W.dp, L.POSTER_H.dp).border(0.5.dp, Color(0x1AFFFFFF), shape),
            )
            if (item.badges.isNotEmpty()) {
                Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.badges.forEach { FormatBadge(it) }
                }
            }
            item.fileLine?.let { BasicText(it, Modifier.padding(top = 7.dp), style = AppType.MonoMeta.copy(color = AppColors.TextDim)) }
        }
        Column(Modifier.padding(start = 320.dp, end = 60.dp, top = L.POSTER_Y.dp)) {
            BasicText(item.label, style = AppType.MonoLabel)
            BasicText(item.title, Modifier.padding(top = 12.dp), style = TitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { BasicText(it, Modifier.padding(top = 8.dp), style = AppType.BodyLarge.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Meta(item, Modifier.padding(top = 11.dp))
            item.description?.let {
                BasicText(
                    it,
                    Modifier.padding(top = 11.dp).widthIn(max = 520.dp),
                    style = AppType.Body.copy(fontSize = 13.sp, lineHeight = 19.5.sp, color = AppColors.TextSecondary),
                    maxLines = if (item.subtitle != null) 3 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Actions(item, focus, vm, onOpen, Modifier.padding(top = 18.dp))
            if (item.audio.isNotEmpty() || item.subtitles.isNotEmpty()) PlaybackOptions(item, state, focus, vm, Modifier.padding(top = 20.dp))
        }
    }
}

private val TitleStyle = AppType.Display.copy(fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-0.03).em)

/** Backdrop, dimmed and faded at its edges ([BackdropTransformation]), at the top right of the first screen. */
@Composable
internal fun Backdrop(url: String) {
    val context = LocalContext.current
    val area = remember { RectF(L.PAGE_W - L.BACKDROP_W, 0f, L.PAGE_W, L.BACKDROP_H) }
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .transformations(BackdropTransformation(area, L.PAGE_H))
            .build()
    }
    AsyncImage(
        request,
        contentDescription = null,
        modifier = Modifier.offset(area.left.dp, area.top.dp).size(area.width().dp, area.height().dp),
        contentScale = ContentScale.FillBounds,
    )
}

/** "★ 7.9  16+  2024  2 ч 14 мин  Драма, Приключения" */
@Composable
private fun Meta(item: DetailsModel, modifier: Modifier) {
    val text = AppType.Body.copy(fontSize = 12.sp, lineHeight = 12.sp, color = AppColors.TextSecondary)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        item.rating?.let { BasicText(it, style = text.copy(color = AppColors.Yellow, fontWeight = FontWeight.SemiBold)) }
        item.age?.let {
            BasicText(
                it,
                Modifier.border(0.5.dp, Color(0xFF4A4F68), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                style = text.copy(fontSize = 10.sp, lineHeight = 10.sp),
            )
        }
        listOfNotNull(item.year, item.runtime, item.genres).forEach { BasicText(it, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Actions(item: DetailsModel, focus: ScreenFocus, vm: DetailsViewModel, onOpen: (Screen) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val resume = item.resumeMs > 0
        PrimaryButton(
            when {
                !resume -> "Смотреть"
                item.remainingMinutes != null -> "Продолжить · ещё ${item.remainingMinutes} мин"
                else -> "Продолжить"
            },
            { vm.player(fromStart = false)?.let(onOpen) },
            Modifier.remembered(focus, KEY_PLAY, isDefault = true),
        )
        if (resume) SecondaryButton("С начала", { vm.player(fromStart = true)?.let(onOpen) }, Modifier.remembered(focus, KEY_RESTART))
        CircleButton(
            if (item.favorite) AppIcon.HeartFilled else AppIcon.Heart,
            vm::toggleFavorite,
            Modifier.remembered(focus, "favorite"),
            tint = if (item.favorite) AppColors.Pink else Color(0xFFE3E5F2),
        )
        CircleButton(AppIcon.Check, vm::togglePlayed, Modifier.remembered(focus, "watched"), tint = if (item.played) AppColors.Green else Color(0xFFE3E5F2))
    }
}

/** "Параметры воспроизведения": audio and subtitle chips, passed to the player when it starts. */
@Composable
private fun PlaybackOptions(item: DetailsModel, state: DetailsState, focus: ScreenFocus, vm: DetailsViewModel, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .widthIn(max = 580.dp)
            .fillMaxWidth()
            .background(PanelColor, shape)
            .border(0.5.dp, Color(0xFF262A3A), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(Color(0xFF181B27), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(6.dp).background(AppColors.Accent, CircleShape))
            BasicText(
                "ПАРАМЕТРЫ ВОСПРОИЗВЕДЕНИЯ",
                Modifier.padding(start = 6.dp).weight(1f),
                style = AppType.MonoCaption.copy(letterSpacing = 0.1.em, color = AppColors.TextMuted),
            )
            BasicText("применятся при запуске", style = AppType.MonoCaption.copy(color = Color(0xFF6F7592)))
        }
        Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF232637)))
        Column(Modifier.padding(vertical = 5.dp)) {
            if (item.audio.isNotEmpty()) OptionRow("Аудио", AppColors.Pink, item.audio, state.audio, focus, vm::selectAudio)
            if (item.subtitles.isNotEmpty()) OptionRow("Субтитры", AppColors.Yellow, item.subtitles, state.subtitle, focus, vm::selectSubtitle)
        }
    }
}

private val PanelColor = Color(0xFF111319)

@Composable
private fun OptionRow(title: String, color: Color, options: List<TrackOption>, selected: Int?, focus: ScreenFocus, onPick: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.padding(start = 12.dp).width(78.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(5.dp).background(color, CircleShape))
            BasicText(title, Modifier.padding(start = 5.dp), style = AppType.Body.copy(fontSize = 11.sp, lineHeight = 11.sp, color = AppColors.TextDim))
        }
        // Files can have a dozen subtitle tracks: the chips scroll instead of wrapping. The row opens on the chosen
        // track, and coming into the row from above focuses it.
        val chosen = remember { FocusRequester() }
        val selectedAt = options.indexOfFirst { it.index == selected }
        DefaultScrolling {
            LazyRow(
                state = rememberLazyListState(initialFirstVisibleItemIndex = if (selectedAt > 1) selectedAt - 1 else 0),
                modifier = Modifier.focusRestorer(chosen),
                contentPadding = PaddingValues(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(options, key = { it.key }) { o ->
                    val modifier = if (o.index == selected) Modifier.focusRequester(chosen) else Modifier
                    Chip(o.label, o.index == selected, { onPick(o.index) }, modifier.remembered(focus, o.key), detail = o.detail)
                }
            }
        }
    }
}

@Composable
private fun Cast(item: DetailsModel, focus: ScreenFocus, onOpen: (Screen) -> Unit, modifier: Modifier) {
    Column(modifier.padding(top = 10.dp)) {
        SectionTitle(item.castTitle, null)
        DefaultScrolling {
            LazyRow(
                state = rememberLazyListState(),
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = AppDimens.CONTENT_START.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(item.cast, key = { it.key }) { p -> CastChip(p, { p.personId?.let { onOpen(Screen.Person(it)) } }, Modifier.remembered(focus, p.key)) }
            }
        }
    }
}

private val CastFocus = FocusStyle(scale = 1.03f, corner = 27.5.dp, ring = 1.dp, glows = listOf(FocusStyle.Glow(17.dp, 0.dp, Color(0x737C5CFF))))

/** Actor pill: photo (or initials), name, role; opens the person's page. */
@Composable
private fun CastChip(person: CastModel, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(27.5.dp)
    Row(
        modifier
            .focusCard(CastFocus)
            .height(55.dp)
            .background(if (focused) Color(0xFF1F2238) else AppColors.Surface, shape)
            .border(0.5.dp, if (focused) AppColors.Accent else Color(0xFF262A3A), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (person.image != null) {
            RemoteImage(person.image, (L.AVATAR / 2).dp, Modifier.size(L.AVATAR.dp))
        } else {
            Avatar(person.name, person.key, L.AVATAR.dp, AppType.CardTitle.copy(fontSize = 14.sp), letters = person.letters)
        }
        Column {
            BasicText(person.name, style = AppType.CardTitle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
            person.role?.let {
                BasicText(it, Modifier.padding(top = 2.dp).widthIn(max = 160.dp), style = AppType.Body.copy(fontSize = 9.5.sp, lineHeight = 11.sp, color = AppColors.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

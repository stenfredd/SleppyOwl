package ru.stenfredd.sleepyowl.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.core.data.LanguageSettings
import ru.stenfredd.sleepyowl.core.data.SubtitleMode
import ru.stenfredd.sleepyowl.core.jellyfin.Account
import ru.stenfredd.sleepyowl.playback.api.SubtitleStyle
import ru.stenfredd.sleepyowl.playback.api.VideoPathSwitchable
import ru.stenfredd.sleepyowl.playback.core.PlaybackDefaults
import ru.stenfredd.sleepyowl.playback.core.SkipMode
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.Chip
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.MinimalScrolling
import ru.stenfredd.sleepyowl.ui.components.OwlMark
import ru.stenfredd.sleepyowl.ui.components.SecondaryButton
import ru.stenfredd.sleepyowl.ui.components.Wordmark
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.focus.Restore
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.rememberScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.player.PlayerLabels
import ru.stenfredd.sleepyowl.ui.player.QualityCaps
import ru.stenfredd.sleepyowl.ui.player.SubtitleChoices
import ru.stenfredd.sleepyowl.ui.player.SubtitlePreview
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppDimens
import ru.stenfredd.sleepyowl.ui.theme.AppType

/** Sections on the left; the focused one is shown on the right. */
private enum class Section(val title: String, val dot: Color) {
    Playback("Воспроизведение", AppColors.Blue),
    Languages("Языки", AppColors.Pink),
    Subtitles("Вид субтитров", AppColors.Yellow),
    Account("Аккаунт и сервер", AppColors.Green),
    About("О приложении", AppColors.TextDim),
    Debug("Отладка", AppColors.Orange),
}

/** One chip of a setting. */
@Immutable
private data class Option<T>(val value: T, val label: String, val detail: String? = null, val swatch: Color? = null)

/**
 * Settings tab (not in the mockup; built from its parts): sections on the left, the focused section's settings on
 * the right as rows of chips, like the playback options on a movie page. Every choice applies at once.
 */
@Composable
fun SettingsScreen(
    onOpen: (Screen) -> Unit,
    /** Signed out: the app goes to the login screen. */
    onSignedOut: () -> Unit,
    /** Another saved account became current: the app restarts on its home screen. */
    onSwitched: () -> Unit,
    vm: SettingsViewModel = koinViewModel(),
) {
    val focus = rememberScreenFocus()
    var section by rememberSaveable { mutableStateOf(Section.Playback) }
    val sections = remember(vm.tourAvailable) { Section.entries.filter { it != Section.Debug || vm.tourAvailable } }
    // The focused section is shown after a short pause: quick scrolling through the list doesn't build every section.
    var focused by remember { mutableStateOf<Section?>(null) }
    LaunchedEffect(focused) {
        val target = focused ?: return@LaunchedEffect
        if (target != section) {
            delay(SECTION_DELAY_MS)
            section = target
        }
    }

    Column(Modifier.fillMaxSize().padding(start = AppDimens.CONTENT_START.dp, top = AppDimens.CONTENT_TOP.dp)) {
        BasicText("Настройки", style = AppType.Display.copy(fontSize = 28.sp))
        Row(Modifier.padding(top = 22.dp).fillMaxSize()) {
            val focusManager = LocalFocusManager.current
            val scope = rememberCoroutineScope()
            Column(
                Modifier
                    .width(190.dp)
                    // → before the pause is over: show the focused section now and go into it once it is composed.
                    .onPreviewKeyEvent { e ->
                        val pending = focused?.takeIf { it != section }
                        if (pending == null || e.type != KeyEventType.KeyDown || e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                        section = pending
                        scope.launch {
                            withFrameNanos { }
                            withFrameNanos { }
                            focusManager.moveFocus(FocusDirection.Right)
                        }
                        true
                    }
                    .focusRestorer()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (s in sections) {
                    SectionItem(
                        s,
                        selected = s == section,
                        onFocused = { focused = s },
                        onClick = { section = s },
                        Modifier.remembered(focus, "section/${s.name}", isDefault = s == Section.Playback),
                    )
                }
            }
            // Each section scrolls on its own and starts from the top.
            Box(Modifier.weight(1f).fillMaxHeight()) { key(section) {
                MinimalScrolling(margin = 30.dp) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .focusRestorer()
                            .focusGroup()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 40.dp, end = AppDimens.CONTENT_START.dp, top = 6.dp, bottom = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(26.dp),
                    ) {
                        when (section) {
                            Section.Playback -> PlaybackSection(vm, focus)
                            Section.Languages -> LanguagesSection(vm, focus)
                            Section.Subtitles -> SubtitlesSection(vm, focus)
                            Section.Account -> AccountSection(vm, focus, onOpen, onSignedOut, onSwitched)
                            Section.About -> AboutSection(vm, focus, onOpen)
                            Section.Debug -> Setting("Режим обхода", "Проигрывает подготовленный список файлов со всеми форматами сервера: проверка движков.") {
                                SecondaryButton("Запустить", { onOpen(Screen.Tour()) }, Modifier.remembered(focus, "debug/tour"))
                            }
                        }
                    }
                }
            } }
        }
    }
    focus.Restore(ready = true)
}

private const val SECTION_DELAY_MS = 300L

private val SectionFocus = FocusStyle(scale = 1f, corner = 8.dp, glows = FocusStyle.ButtonGlows)

/** Section in the left column: light fill when focused, a violet frame for the one shown. */
@Composable
private fun SectionItem(section: Section, selected: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .focusCard(SectionFocus)
            .fillMaxWidth()
            .height(36.dp)
            .background(
                when {
                    focused -> AppColors.FocusFill
                    selected -> AppColors.Selected
                    else -> Color.Transparent
                },
                shape,
            )
            .border(0.5.dp, if (selected && !focused) AppColors.SelectedBorder else Color.Transparent, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(6.dp).background(section.dot, CircleShape))
        BasicText(
            section.title,
            style = AppType.Rail.copy(fontSize = 13.sp, color = if (focused) AppColors.OnFocusFill else if (selected) AppColors.Text else AppColors.TextMuted),
            maxLines = 1,
        )
    }
}

/** A setting: title, an optional explanation, then its controls. */
@Composable
private fun Setting(title: String, note: String?, content: @Composable () -> Unit) {
    Column {
        BasicText(title, style = AppType.Section.copy(fontSize = 14.sp))
        note?.let { BasicText(it, Modifier.padding(top = 5.dp).widthIn(max = 520.dp), style = AppType.Body) }
        Box(Modifier.padding(top = 11.dp)) { content() }
    }
}

/** A row of chips, one of them chosen. Coming into the row from above or below focuses the chosen chip. */
@Composable
private fun <T> Choices(id: String, options: List<Option<T>>, selected: T, focus: ScreenFocus, onPick: (T) -> Unit) {
    val chosen = remember { FocusRequester() }
    Row(Modifier.focusRestorer(chosen).focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, o) in options.withIndex()) {
            val isSelected = o.value == selected
            Chip(
                o.label,
                isSelected,
                { onPick(o.value) },
                Modifier.then(if (isSelected) Modifier.focusRequester(chosen) else Modifier).remembered(focus, "$id/$i"),
                detail = o.detail,
                swatch = o.swatch,
            )
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier, color: Color = AppColors.TextDim) {
    BasicText(text, modifier.widthIn(max = 520.dp), style = AppType.Body.copy(fontSize = 10.5.sp, lineHeight = 15.sp, color = color))
}

// --- Воспроизведение ---

@Composable
private fun PlaybackSection(vm: SettingsViewModel, focus: ScreenFocus) {
    val defaults by vm.defaults.collectAsStateWithLifecycle()
    fun set(d: PlaybackDefaults) = vm.setDefaults(d)

    Setting(
        "Качество по умолчанию",
        "Всё, что ниже оригинала, сервер перекодирует на лету — это нагружает его процессор. Помогает при медленной сети. " +
            "В плеере качество можно сменить для одного просмотра.",
    ) {
        val options = listOf(Option<Int?>(null, "Оригинал")) +
            QualityCaps.ALL.map { (bitrate, lines) -> Option<Int?>(bitrate, "${lines}p", PlayerLabels.mbps(bitrate)) }
        Choices("quality", options, defaults.maxBitrate, focus) { set(defaults.copy(maxBitrate = it)) }
    }
    Setting(
        "Вывод видео",
        "Видеослой ТВ — с HDR и обработкой изображения телевизора. Через GPU — без HDR и не выше 1080p, но без ошибок " +
            "видеослоя некоторых ТВ. «Авто» выбирает по файлу. Относится к основному движку (ExoPlayer).",
    ) {
        val options = listOf(
            Option(VideoPathSwitchable.Mode.AUTO, "Авто"),
            Option(VideoPathSwitchable.Mode.VIDEO_PLANE, "Видеослой ТВ"),
            Option(VideoPathSwitchable.Mode.GPU, "Через GPU"),
        )
        Choices("path", options, defaults.videoPath, focus) { set(defaults.copy(videoPath = it)) }
    }
    if (vm.dolbyVisionCapable) {
        Setting(
            "Dolby Vision",
            "Файлы Dolby Vision с базовым слоем HDR10 можно показывать как HDR10 или через DV-декодер: тогда ТВ " +
                "включает свой режим Dolby Vision. Файлы только с Dolby Vision всегда идут через DV-декодер.",
        ) {
            val options = listOf(Option(false, "Базовый слой", "HDR10"), Option(true, "DV-декодер", "режим ТВ"))
            Choices("dv", options, defaults.dolbyVision, focus) { set(defaults.copy(dolbyVision = it)) }
        }
    }
    Setting(
        "Заставки",
        "Опенинги, пересказ прошлых серий, анонсы. «Кнопка» — в начале заставки появляется «Пропустить заставку», " +
            "«Сразу» — плеер перескакивает её сам. Работает там, где сервер знает заставки (плагин Intro Skipper).",
    ) {
        Choices("skip-intro", skipOptions, defaults.skipIntro, focus) { set(defaults.copy(skipIntro = it)) }
    }
    Setting(
        "Титры",
        "«Кнопка» — с началом титров появляется карточка «Следующая серия» с отсчётом (или «Пропустить титры», если " +
            "после них ещё что-то есть). «Сразу» — следующая серия включается, как только начались титры. «Не пропускать» — " +
            "карточка за 30 с до конца серии.",
    ) {
        Choices("skip-credits", skipOptions, defaults.skipCredits, focus) { set(defaults.copy(skipCredits = it)) }
    }
}

private val skipOptions = listOf(Option(SkipMode.Button, "Кнопка"), Option(SkipMode.Auto, "Сразу"), Option(SkipMode.Off, "Не пропускать"))

// --- Языки ---

private val AudioLanguages = listOf("rus" to "Русский", "eng" to "Английский", "jpn" to "Японский")
private val SubtitleLanguages = listOf("rus" to "Русские", "eng" to "Английские")

@Composable
private fun LanguagesSection(vm: SettingsViewModel, focus: ScreenFocus) {
    val state by vm.languages.collectAsStateWithLifecycle()
    val settings = state.settings
    if (settings == null) {
        if (state.error == null) {
            Note("Загрузка настроек с сервера…")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Note("Не удалось загрузить настройки с сервера: ${state.error}", color = AppColors.Red)
                SecondaryButton("Повторить", vm::loadLanguages, Modifier.remembered(focus, "languages/retry"))
            }
        }
        return
    }
    fun set(s: LanguageSettings) = vm.setLanguages(s)

    Setting("Язык звука", "Какую дорожку включать, если в файле их несколько. «Как в файле» — дорожка, отмеченная в файле основной.") {
        val options = listOf(Option<String?>(null, "Как в файле")) +
            (AudioLanguages + listOfNotNull(settings.audio?.takeIf { a -> AudioLanguages.none { it.first == a } }?.let { it to languageName(it) }))
                .map { (code, name) -> Option<String?>(code, name) }
        Choices("audio", options, settings.audio, focus) { set(settings.copy(audio = it)) }
    }
    Setting("Субтитры", null) {
        val options = listOf(
            Option(SubtitleMode.Default, "Как в файле"),
            Option(SubtitleMode.None, "Выкл"),
            Option(SubtitleMode.OnlyForced, "Только надписи"),
            Option(SubtitleMode.Smart, "Если звук не на моём языке"),
            Option(SubtitleMode.Always, "Всегда"),
        )
        Choices("subtitles", options, settings.subtitleMode, focus) { set(settings.copy(subtitleMode = it)) }
    }
    if (settings.subtitleMode != SubtitleMode.None) {
        Setting("Язык субтитров", null) {
            val options = listOf(Option<String?>(null, "Любой")) +
                (SubtitleLanguages + listOfNotNull(settings.subtitleLanguage?.takeIf { s -> SubtitleLanguages.none { it.first == s } }?.let { it to languageName(it) }))
                    .map { (code, name) -> Option<String?>(code, name) }
            Choices("subtitle-language", options, settings.subtitleLanguage, focus) { set(settings.copy(subtitleLanguage = it)) }
        }
    }
    val account = vm.accounts.collectAsStateWithLifecycle().value.current
    Note(
        "Языки хранятся на сервере в профиле ${account?.userName.orEmpty()} и действуют во всех приложениях Jellyfin. " +
            "Дорожки, выбранные вручную для фильма или сериала, запоминаются на этом ТВ и важнее этих настроек.",
    )
    state.error?.let { Note(it, color = AppColors.Red) }
}

/** "fra" → "Французский". */
private fun languageName(code: String): String =
    java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.forLanguageTag("ru")).replaceFirstChar { it.titlecase() }.ifEmpty { code }

// --- Вид субтитров ---

@Composable
private fun SubtitlesSection(vm: SettingsViewModel, focus: ScreenFocus) {
    val style by vm.subtitleStyle.collectAsStateWithLifecycle()
    fun set(s: SubtitleStyle) = vm.setSubtitleStyle(s)

    Box(Modifier.widthIn(max = 420.dp)) { SubtitlePreview(style, on = true) }
    Setting("Размер", null) {
        Choices("sub-size", SubtitleStyle.Size.entries.map { Option(it, it.name) }, style.size, focus) { set(style.copy(size = it)) }
    }
    Setting("Цвет", null) {
        val options = SubtitleChoices.colors(style).map { Option(it.key.substringAfter('/').toInt(), it.label, swatch = it.swatch) }
        Choices("sub-color", options, style.color, focus) { set(style.copy(color = it)) }
    }
    Setting("Фон", null) {
        val options = SubtitleChoices.backgrounds(style).map { Option(SubtitleStyle.Background.valueOf(it.key.substringAfter('/')), it.label) }
        Choices("sub-background", options, style.background, focus) { set(style.copy(background = it)) }
    }
    Note(
        "Для текстовых субтитров (SRT, VTT) на этом ТВ, для всех профилей. У субтитров ASS своё оформление из файла, " +
            "графические (PGS, DVD) показываются как есть. То же можно настроить в плеере.",
    )
}

// --- Аккаунт и сервер ---

@Composable
private fun AccountSection(
    vm: SettingsViewModel,
    focus: ScreenFocus,
    onOpen: (Screen) -> Unit,
    onSignedOut: () -> Unit,
    onSwitched: () -> Unit,
) {
    val state by vm.accounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The action waiting for the second OK ("Точно выйти?"); moving focus away cancels it.
    var confirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun Modifier.confirmable(id: String) = onFocusChanged { if (!it.isFocused && confirm == id) confirm = null }

    val current = state.current ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Avatar(current.userName, current.userId.toString(), 48.dp, AppType.Hero.copy(fontSize = 20.sp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(current.userName, style = AppType.Section.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
            val server = state.server
            val name = server?.name?.ifEmpty { null } ?: current.serverName.ifEmpty { null }
            BasicText(
                listOfNotNull(name?.let { "Сервер «$it»" } ?: "Сервер", server?.version?.let { "Jellyfin $it" }).joinToString(" · "),
                style = AppType.MonoMeta,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoLine("Адрес", current.serverUrl)
        InfoLine("Дома", state.localUrl?.let { "напрямую, $it" } ?: "сервер в локальной сети не найден — через адрес выше")
    }
    Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Сменить профиль", { onOpen(Screen.Login) }, Modifier.remembered(focus, "account/switch"))
        SecondaryButton(
            when {
                busy -> "Выходим…"
                confirm == "logout" -> "Точно выйти?"
                else -> "Выйти"
            },
            {
                when {
                    busy -> Unit
                    confirm != "logout" -> confirm = "logout"
                    else -> {
                        busy = true
                        scope.launch {
                            vm.logout()
                            onSignedOut()
                        }
                    }
                }
            },
            Modifier.remembered(focus, "account/logout").confirmable("logout"),
        )
    }
    Note("«Выйти» забывает этот профиль на ТВ; войти снова — паролем или Quick Connect.")

    Setting("Другие профили на этом ТВ", if (state.others.isEmpty()) "Других нет. Добавить — «Сменить профиль» → «Другой пользователь»." else null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (a in state.others) {
                OtherAccount(
                    a,
                    confirmForget = confirm == "forget/${a.id}",
                    onSwitch = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                vm.switchTo(a.id)
                                onSwitched()
                            }
                        }
                    },
                    onForget = {
                        if (confirm != "forget/${a.id}") {
                            confirm = "forget/${a.id}"
                        } else {
                            confirm = null
                            vm.forget(a.id)
                            // The row goes away with the focused chip: focus the section instead of nothing.
                            scope.launch {
                                focus.lastKey = "section/${Section.Account.name}"
                                withFrameNanos { }
                                withFrameNanos { }
                                focus.requestFocus()
                            }
                        }
                    },
                    focus = focus,
                    modifier = Modifier.confirmable("forget/${a.id}"),
                )
            }
        }
    }
}

@Composable
private fun OtherAccount(
    account: Account,
    confirmForget: Boolean,
    onSwitch: () -> Unit,
    onForget: () -> Unit,
    focus: ScreenFocus,
    modifier: Modifier,
) {
    Row(Modifier.focusRestorer().focusGroup(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(account.userName, account.userId.toString(), 30.dp, AppType.CardTitle)
        Column(Modifier.width(190.dp)) {
            BasicText(account.userName, style = AppType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(account.serverName.ifEmpty { account.serverUrl }, style = AppType.MonoCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chip("Переключиться", false, onSwitch, Modifier.remembered(focus, "other/${account.id}/switch"))
        Chip(if (confirmForget) "Точно забыть?" else "Забыть", false, onForget, modifier.remembered(focus, "other/${account.id}/forget"))
    }
}

@Composable
private fun InfoLine(key: String, value: String) {
    Row {
        BasicText(key, Modifier.width(60.dp), style = AppType.MonoMeta.copy(color = AppColors.TextFaint))
        BasicText(value, style = AppType.MonoMeta, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// --- О приложении ---

@Composable
private fun AboutSection(vm: SettingsViewModel, focus: ScreenFocus, onOpen: (Screen) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwlMark()
            Wordmark()
        }
        BasicText("Версия ${vm.about.version}", style = AppType.MonoMeta)
        BasicText(vm.about.device, style = AppType.MonoMeta.copy(color = AppColors.TextDim))
    }
    Setting("Лицензии", "Приложение — свободное ПО под лицензией GPL-3.0. Ниже — из чего оно собрано; OK — текст лицензии.") {
        Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((i, c) in (listOf(Licenses.app) + Licenses.components).withIndex()) {
                LicenseRow(c, { onOpen(Screen.License(c.name, c.license, c.asset)) }, Modifier.remembered(focus, "license/$i"))
            }
        }
    }
}

private val RowFocus = FocusStyle(scale = 1f, corner = 7.dp, ring = 0.5.dp, glows = listOf(FocusStyle.Glow(15.dp, 0.dp, Color(0x737C5CFF))))

@Composable
private fun LicenseRow(component: Component, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .focusCard(RowFocus)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(34.dp)
            .background(if (focused) AppColors.SurfaceFocused else AppColors.Surface, shape)
            .border(0.5.dp, if (focused) AppColors.Accent else AppColors.BorderSubtle, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(component.name, style = AppType.CardTitle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
        component.note?.let {
            BasicText(it, Modifier.weight(1f), style = AppType.Body.copy(fontSize = 10.5.sp, color = AppColors.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } ?: Box(Modifier.weight(1f))
        BasicText(component.license, style = AppType.MonoCaption.copy(color = AppColors.Accent), maxLines = 1)
    }
}

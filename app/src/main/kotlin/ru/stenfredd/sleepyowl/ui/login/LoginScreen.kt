package ru.stenfredd.sleepyowl.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.KeyboardLift
import ru.stenfredd.sleepyowl.ui.components.LocalKeyboardLift
import ru.stenfredd.sleepyowl.ui.components.OwlMark
import ru.stenfredd.sleepyowl.ui.components.liftForKeyboard
import ru.stenfredd.sleepyowl.ui.components.StatusDot
import ru.stenfredd.sleepyowl.ui.components.TextInput
import ru.stenfredd.sleepyowl.ui.components.Wordmark
import ru.stenfredd.sleepyowl.ui.components.dashedBorder
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.components.rememberTextInputState
import ru.stenfredd.sleepyowl.ui.focus.Restore
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import ru.stenfredd.sleepyowl.ui.focus.remembered
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

/** Login flow: server → profile → login, see [LoginViewModel]. Sizes are the mockup's px / 2. */
@Composable
fun LoginScreen(onDone: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    // Back walks the steps; on the first one it leaves the screen (closes the app, or returns to it from the menu).
    BackHandler(enabled = state.step != LoginStep.Server) { vm.back() }

    val lift = remember { KeyboardLift() }
    Box(Modifier.fillMaxSize().liftForKeyboard(lift)) {
      CompositionLocalProvider(LocalKeyboardLift provides lift) {
        Logo(Modifier.padding(start = 60.dp, top = 29.dp))
        StepChain(state.step, Modifier.align(Alignment.TopEnd).padding(end = 60.dp, top = 35.dp))
        val focus = vm.focus.getValue(state.step)
        key(state.step) {
            when (state.step) {
                LoginStep.Server -> ServerStep(state, vm, focus)
                LoginStep.Profile -> ProfileStep(state, vm, focus)
                LoginStep.Login -> LoginStepContent(state, vm, focus)
            }
            focus.Restore(ready = true)
        }
        BasicText(
            hint(state),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            style = AppType.MonoCount.copy(fontSize = 10.sp),
        )
      }
    }
}

private fun hint(state: LoginState) = when (state.step) {
    LoginStep.Server -> "OK — подключиться · серверы находятся автоматически"
    LoginStep.Profile -> "Назад — сменить сервер"
    LoginStep.Login -> "Вход с телефона подтвердится автоматически · Назад — вернуться"
}

// ---- Header ----

/** Same as the open side rail's. */
@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OwlMark()
        Wordmark()
    }
}

@Composable
private fun StepChain(step: LoginStep, modifier: Modifier = Modifier) {
    val current = step.ordinal
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        listOf("Сервер", "Профиль", "Вход").forEachIndexed { i, label ->
            if (i > 0) Spacer(Modifier.width(20.dp).height(1.dp).background(if (i <= current) AppColors.Accent else Color(0xFF262A3A)))
            Row(
                Modifier
                    .background(AppColors.Surface, RoundedCornerShape(5.dp))
                    .border(0.5.dp, if (i == current) AppColors.SelectedBorder else Color(0xFF262A3A), RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val dot = when {
                    i < current -> AppColors.Green
                    i == current -> AppColors.Accent
                    else -> Color(0xFF33384D)
                }
                Spacer(Modifier.size(5.dp).background(dot, CircleShape))
                BasicText(
                    label,
                    style = AppType.MonoMeta.copy(fontSize = 9.sp, letterSpacing = 0.08.em, color = if (i <= current) AppColors.Text else Color(0xFF6F7592)),
                )
            }
        }
    }
}

@Composable
private fun Title(text: String, subtitle: @Composable () -> Unit = {}, top: Int = 110) {
    Column(Modifier.fillMaxWidth().padding(top = top.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(text, style = AppType.Display.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        subtitle()
    }
}

// ---- Step 1: server ----

@Composable
private fun ServerStep(state: LoginState, vm: LoginViewModel, focus: ScreenFocus) {
    var address by rememberSaveable { mutableStateOf("") }
    val input = rememberTextInputState()
    LaunchedEffect(state.manualOpen) {
        if (state.manualOpen) {
            withFrameNanos { }
            input.edit()
        }
    }
    // With nothing found yet focus starts on "Ввести адрес"; the first server found (~1.5 s) takes it over,
    // unless the user is already typing an address.
    val firstServer = state.servers.firstOrNull()?.key
    var waitingForServer by remember { mutableStateOf(firstServer == null) }
    LaunchedEffect(firstServer) {
        if (firstServer == null || !waitingForServer) return@LaunchedEffect
        waitingForServer = false
        if (focus.lastKey == MANUAL_KEY && !state.manualOpen) {
            focus.lastKey = firstServer
            withFrameNanos { }
            focus.requestFocus()
        }
    }

    Title("Выберите сервер", subtitle = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StatusDot(AppColors.Green, pulsing = state.searching)
            val text = when {
                state.searching || !state.searched -> "Ищем Jellyfin в локальной сети"
                state.servers.isEmpty() -> "В локальной сети серверов нет — введите адрес"
                else -> "Серверы Jellyfin"
            }
            BasicText(text, style = AppType.BodyLarge.copy(color = AppColors.TextMuted))
        }
    })
    Column(Modifier.fillMaxWidth().padding(top = 210.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            state.servers.take(MAX_SERVER_CARDS).forEachIndexed { i, card ->
                key(card.key) {
                    ServerCard(card, { vm.pickServer(card.key) }, Modifier.remembered(focus, card.key, isDefault = i == 0))
                }
            }
            AddServerCard(vm::openManual, Modifier.remembered(focus, MANUAL_KEY, isDefault = state.servers.isEmpty()))
        }
        if (state.manualOpen) {
            Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TextInput(
                    address,
                    { address = it },
                    input,
                    Modifier.width(360.dp).remembered(focus, "address"),
                    placeholder = "http://192.168.1.10:8096",
                    textStyle = AppType.MonoMeta.copy(fontSize = 14.sp, color = AppColors.Text),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    onAction = { vm.connectManual(address) },
                )
                GradientButton(if (state.manualBusy) "Подключение…" else "Подключиться", { vm.connectManual(address) }, Modifier.remembered(focus, "connect"))
            }
            state.manualError?.let { ErrorText(it, Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun ServerCard(card: ServerCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .focusCard(FocusStyle(scale = 1.04f, corner = 10.dp, ring = 1.dp))
            .width(230.dp)
            .height(140.dp)
            .background(AppColors.Surface, shape)
            .border(0.5.dp, Color(0xFF262A3A), shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(Color(0xFF181B27), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(if (card.where == "LAN") AppColors.Blue else AppColors.Pink, pulsing = false)
            BasicText("СЕРВЕР · ${card.where}", Modifier.weight(1f), style = AppType.MonoCaption.copy(letterSpacing = 0.1.em, color = AppColors.TextMuted))
            when {
                card.busy -> BasicText("подключение…", style = AppType.MonoCaption.copy(color = AppColors.Accent))
                card.ping != null -> BasicText(card.ping, style = AppType.MonoCaption.copy(color = AppColors.Green))
            }
        }
        Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF232637)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 13.dp).fillMaxSize()) {
            BasicText(card.name, style = AppType.Display.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            BasicText(card.address, style = AppType.MonoMeta.copy(fontSize = 11.sp, color = AppColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            card.version?.let { BasicText("Jellyfin $it", style = AppType.MonoCaption.copy(fontSize = 9.5.sp, color = Color(0xFF6F7592))) }
        }
    }
}

@Composable
private fun AddServerCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .focusCard(FocusStyle(scale = 1.04f, corner = 10.dp, ring = 1.dp))
            .width(150.dp)
            .height(140.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.background(Color(0xFF171A2A), RoundedCornerShape(10.dp)) else Modifier.dashedBorder(Color(0xFF3A3F57), 1.dp, 10.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterVertically),
    ) {
        Icon(AppIcon.Plus, AppColors.TextSecondary, Modifier.size(24.dp))
        BasicText("Ввести адрес", style = AppType.BodyLarge.copy(fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary))
    }
}

// ---- Step 2: profile ----

@Composable
private fun ProfileStep(state: LoginState, vm: LoginViewModel, focus: ScreenFocus) {
    Title("Кто смотрит?", subtitle = {
        BasicText(state.serverLine, style = AppType.MonoMeta.copy(fontSize = 11.sp, color = AppColors.TextDim))
    })
    Column(Modifier.fillMaxWidth().padding(top = 210.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            state.profiles.forEachIndexed { i, p ->
                key(p.key) { ProfileTile(p, { vm.pickProfile(p.key) }, Modifier.remembered(focus, p.key, isDefault = i == 0)) }
            }
        }
        state.profileError?.let { ErrorText(it, Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun ProfileTile(profile: ProfileModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .focusCard(FocusStyle(scale = 1.06f, corner = 12.dp, ring = 1.dp))
            .width(120.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.background(Color(0xFF171A2A), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (profile.key == LoginViewModel.OTHER_KEY) {
            Box(Modifier.size(90.dp).dashedBorder(Color(0xFF4A4F68), 1.dp, 45.dp), contentAlignment = Alignment.Center) {
                Icon(AppIcon.Plus, AppColors.TextMuted, Modifier.size(30.dp), strokeWidth = 1.8f)
            }
        } else {
            Avatar(profile.name, profile.avatarSeed, 90.dp, AppType.Display.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.em))
        }
        Spacer(Modifier.height(9.dp))
        BasicText(profile.name, style = AppType.Display.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(5.dp))
        BasicText(profile.note, style = AppType.MonoCaption.copy(fontSize = 9.5.sp), maxLines = 1)
    }
}

// ---- Step 3: login ----

@Composable
private fun LoginStepContent(state: LoginState, vm: LoginViewModel, focus: ScreenFocus) {
    var user by rememberSaveable(state.prefillUser) { mutableStateOf(state.prefillUser) }
    var password by rememberSaveable { mutableStateOf("") }
    val userInput = rememberTextInputState()
    val passwordInput = rememberTextInputState()
    val submit = { vm.loginWithPassword(user, password) }

    Title("Вход в аккаунт", top = 85)
    Row(Modifier.padding(start = 60.dp, top = 150.dp), verticalAlignment = Alignment.CenterVertically) {
        // As in the mockup, Right from the QR panel goes to the first field, not to the nearest one.
        QuickConnectPanel(state.quickConnect, Modifier.remembered(focus, "qc", isDefault = true).focusProperties { right = userInput.frame })
        BasicText("или", Modifier.width(60.dp), style = AppType.MonoMeta.copy(fontSize = 11.sp, color = Color(0xFF6F7592), textAlign = TextAlign.Center))
        Panel("ПАРОЛЬ", Color(0xFFB39DDB), focused = false) {
            Column(Modifier.padding(20.dp)) {
                FieldLabel("Имя пользователя")
                TextInput(
                    user,
                    { user = it },
                    userInput,
                    Modifier.fillMaxWidth().remembered(focus, "user"),
                    placeholder = "например, alexey",
                    textStyle = AppType.BodyLarge.copy(fontSize = 14.sp, color = AppColors.Text, fontWeight = FontWeight.Medium),
                    imeAction = ImeAction.Next,
                    onAction = { passwordInput.edit() },
                )
                FieldLabel("Пароль", Modifier.padding(top = 12.dp))
                TextInput(
                    password,
                    { password = it },
                    passwordInput,
                    Modifier.fillMaxWidth().remembered(focus, LoginViewModel.PASSWORD_KEY),
                    placeholder = "••••••••",
                    textStyle = AppType.BodyLarge.copy(fontSize = 14.sp, color = AppColors.Text, fontWeight = FontWeight.Medium),
                    password = true,
                    imeAction = ImeAction.Done,
                    onAction = submit,
                )
                GradientButton(
                    if (state.loginBusy) "Вход…" else "Войти",
                    submit,
                    Modifier.padding(top = 18.dp).fillMaxWidth().remembered(focus, "login"),
                    big = true,
                )
                state.loginError?.let { ErrorText(it, Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
private fun QuickConnectPanel(qc: QuickConnectState, modifier: Modifier = Modifier) {
    var focused by rememberSaveable { mutableStateOf(false) }
    // The panel itself takes focus (nothing to press in it): it shows where the remote is.
    Box(
        modifier
            .focusCard(FocusStyle(scale = 1f, corner = 12.dp, ring = 1.5.dp, glows = listOf(FocusStyle.Glow(40.dp, 0.dp, Color(0x737C5CFF)))))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null) {},
    ) {
        Panel("БЫСТРЫЙ ВХОД С ТЕЛЕФОНА", AppColors.Pink, focused) {
            when (qc) {
                QuickConnectState.Loading -> PanelMessage("Получаем код…")
                QuickConnectState.Disabled -> PanelMessage("Quick Connect выключен на сервере.\nВойдите по паролю.")
                is QuickConnectState.Failed -> PanelMessage(qc.message)
                is QuickConnectState.Code -> QuickConnectCode(qc)
            }
        }
    }
}

@Composable
private fun QuickConnectCode(qc: QuickConnectState.Code) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(160.dp).background(Color(0xFFF3F1FF), RoundedCornerShape(10.dp)).padding(11.dp)) {
                Image(BitmapPainter(qc.qr, filterQuality = FilterQuality.None), contentDescription = "QR-код для входа", Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Instruction(1, "Наведите камеру телефона на QR-код")
                Instruction(2, "Или откройте Jellyfin → Quick Connect и введите код")
                BasicText(
                    qc.code,
                    Modifier.padding(top = 3.dp),
                    style = AppType.MonoMeta.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em, color = Color.White),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(AppColors.Green, pulsing = true)
            BasicText("Ждём подтверждения", Modifier.weight(1f), style = AppType.Body.copy(fontSize = 11.sp, color = AppColors.TextMuted))
            Countdown(qc.expiresAt)
        }
    }
}

/** "код действует 9:58", recomposes once a second and only itself. */
@Composable
private fun Countdown(expiresAt: Long) {
    var left by rememberSaveable(expiresAt) { mutableStateOf(0L) }
    LaunchedEffect(expiresAt) {
        while (true) {
            left = ((expiresAt - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    BasicText("код действует %d:%02d".format(left / 60, left % 60), style = AppType.MonoCaption.copy(fontSize = 10.sp, color = Color(0xFF6F7592)))
}

@Composable
private fun Instruction(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(20.dp).background(AppColors.Selected, CircleShape).border(0.5.dp, AppColors.SelectedBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) { BasicText("$n", style = AppType.MonoMeta.copy(fontSize = 10.sp, color = AppColors.Text)) }
        BasicText(text, style = AppType.BodyLarge.copy(fontSize = 12.sp, lineHeight = 17.sp, color = AppColors.TextSecondary))
    }
}

@Composable
private fun Panel(title: String, dot: Color, focused: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(390.dp)
            .height(320.dp)
            .background(AppColors.Surface, shape)
            .border(0.5.dp, if (focused) AppColors.SelectedBorder else Color(0xFF262A3A), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Color(0xFF181B27), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(dot, pulsing = false)
            BasicText(title, style = AppType.MonoMeta.copy(fontSize = 9.5.sp, letterSpacing = 0.1.em))
        }
        Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF232637)))
        content()
    }
}

@Composable
private fun PanelMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = AppType.BodyLarge.copy(color = AppColors.TextMuted, textAlign = TextAlign.Center, lineHeight = 19.sp))
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(bottom = 7.dp), style = AppType.Body.copy(fontSize = 11.sp, color = AppColors.TextDim))
}

@Composable
private fun ErrorText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = AppType.Body.copy(fontSize = 12.sp, color = AppColors.Red))
}

/** Gradient action button of the login forms (rounded rectangle, unlike the pill [PrimaryButton]). */
@Composable
private fun GradientButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier
            .focusCard(FocusStyle(scale = 1.02f, corner = 8.dp, ring = 1.5.dp, ringColor = Color(0xFFD9D2FF), glows = FocusStyle.ButtonGlows))
            .height(42.dp)
            .background(AppColors.PrimaryGradient, RoundedCornerShape(8.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 17.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = AppType.Button.copy(fontSize = if (big) 14.sp else 13.sp, color = Color.White))
    }
}

private const val MANUAL_KEY = "manual"
private const val MAX_SERVER_CARDS = 3

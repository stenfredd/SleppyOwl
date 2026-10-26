package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import ru.stenfredd.sleepyowl.core.common.monotonicMs
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.components.Avatar
import ru.stenfredd.sleepyowl.ui.components.FocusStyle
import ru.stenfredd.sleepyowl.ui.components.Icon
import ru.stenfredd.sleepyowl.ui.components.OwlMark
import ru.stenfredd.sleepyowl.ui.components.StatusDot
import ru.stenfredd.sleepyowl.ui.components.Wordmark
import ru.stenfredd.sleepyowl.ui.components.dashedBorder
import ru.stenfredd.sleepyowl.ui.components.focusCard
import ru.stenfredd.sleepyowl.ui.login.LoginState
import ru.stenfredd.sleepyowl.ui.login.LoginStep
import ru.stenfredd.sleepyowl.ui.login.LoginViewModel
import ru.stenfredd.sleepyowl.ui.login.ProfileModel
import ru.stenfredd.sleepyowl.ui.login.QuickConnectState
import ru.stenfredd.sleepyowl.ui.login.ServerCardModel
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType

/**
 * Login flow on the desktop: server → profile → login, the TV's [LoginViewModel] and look, laid out for a window of
 * any size and typed on a keyboard. "Назад" walks the steps back; on the first step it leaves the screen when the
 * screen came from "сменить профиль".
 */
@Composable
fun LoginScreen(onDone: () -> Unit, vm: LoginViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler { vm.back() }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 40.dp, top = 29.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwlMark()
            Wordmark()
        }
        StepChain(state.step, Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 35.dp))
        Column(
            Modifier.fillMaxSize().padding(top = 96.dp).verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            key(state.step) {
                when (state.step) {
                    LoginStep.Server -> ServerStep(state, vm)
                    LoginStep.Profile -> ProfileStep(state, vm)
                    LoginStep.Login -> LoginStepContent(state, vm)
                }
            }
        }
        BasicText(hint(state), Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), style = AppType.MonoCount.copy(fontSize = 10.sp))
    }
}

private fun hint(state: LoginState) = when (state.step) {
    LoginStep.Server -> "серверы в локальной сети находятся сами"
    LoginStep.Profile -> "Esc — сменить сервер"
    LoginStep.Login -> "вход с телефона подтвердится сам · Esc — назад"
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
                BasicText(label, style = AppType.MonoMeta.copy(fontSize = 9.sp, letterSpacing = 0.08.em, color = if (i <= current) AppColors.Text else Color(0xFF6F7592)))
            }
        }
    }
}

@Composable
private fun Title(text: String, subtitle: @Composable () -> Unit = {}) {
    BasicText(text, style = AppType.Display.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(10.dp))
    subtitle()
    Spacer(Modifier.height(40.dp))
}

// ---- Step 1: server ----

@Composable
private fun ServerStep(state: LoginState, vm: LoginViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    val addressFocus = remember { FocusRequester() }
    LaunchedEffect(state.manualOpen) { if (state.manualOpen) runCatching { addressFocus.requestFocus() } }

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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (card in state.servers) key(card.key) { ServerCard(card) { vm.pickServer(card.key) } }
        AddServerCard(vm::openManual)
    }
    if (state.manualOpen) {
        Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                address,
                { address = it },
                Modifier.width(360.dp),
                placeholder = "https://jellyfin.example.ru или 192.168.1.10:8096",
                textStyle = AppType.MonoMeta.copy(fontSize = 13.sp, color = AppColors.Text),
                focusRequester = addressFocus,
                onSubmit = { vm.connectManual(address) },
            )
            GradientButton(if (state.manualBusy) "Подключение…" else "Подключиться", { vm.connectManual(address) })
        }
        state.manualError?.let { ErrorText(it, Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun ServerCard(card: ServerCardModel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .hoverFocus()
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
            val ping = card.ping
            when {
                card.busy -> BasicText("подключение…", style = AppType.MonoCaption.copy(color = AppColors.Accent))
                ping != null -> BasicText(ping, style = AppType.MonoCaption.copy(color = AppColors.Green))
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
private fun AddServerCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .hoverFocus()
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
private fun ProfileStep(state: LoginState, vm: LoginViewModel) {
    Title("Кто смотрит?", subtitle = {
        BasicText(state.serverLine, style = AppType.MonoMeta.copy(fontSize = 11.sp, color = AppColors.TextDim))
    })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for (p in state.profiles) key(p.key) { ProfileTile(p) { vm.pickProfile(p.key) } }
    }
    state.profileError?.let { ErrorText(it, Modifier.padding(top = 16.dp)) }
}

@Composable
private fun ProfileTile(profile: ProfileModel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .hoverFocus()
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
private fun LoginStepContent(state: LoginState, vm: LoginViewModel) {
    var user by rememberSaveable(state.prefillUser) { mutableStateOf(state.prefillUser) }
    var password by rememberSaveable { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val submit = { vm.loginWithPassword(user, password) }
    // Typing starts at once: in the password when the name is known (a listed user), else in the name.
    LaunchedEffect(Unit) { runCatching { if (state.prefillUser.isEmpty()) userFocus.requestFocus() else passwordFocus.requestFocus() } }

    Title("Вход в аккаунт")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Panel("ПАРОЛЬ", Color(0xFFB39DDB)) {
            Column(Modifier.padding(20.dp)) {
                FieldLabel("Имя пользователя")
                TextField(user, { user = it }, Modifier.fillMaxWidth(), placeholder = "например, alexey", focusRequester = userFocus, onSubmit = {
                    runCatching { passwordFocus.requestFocus() }
                })
                FieldLabel("Пароль", Modifier.padding(top = 12.dp))
                TextField(password, { password = it }, Modifier.fillMaxWidth(), placeholder = "••••••••", password = true, focusRequester = passwordFocus, onSubmit = submit)
                GradientButton(if (state.loginBusy) "Вход…" else "Войти", submit, Modifier.padding(top = 18.dp).fillMaxWidth(), big = true)
                state.loginError?.let { ErrorText(it, Modifier.padding(top = 10.dp)) }
            }
        }
        Panel("БЫСТРЫЙ ВХОД С ТЕЛЕФОНА", AppColors.Pink) {
            when (val qc = state.quickConnect) {
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
    var left by remember(expiresAt) { mutableStateOf(0L) }
    LaunchedEffect(expiresAt) {
        while (true) {
            left = ((expiresAt - monotonicMs()) / 1000).coerceAtLeast(0)
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
private fun Panel(title: String, dot: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(390.dp).height(320.dp).background(AppColors.Surface, shape).border(0.5.dp, Color(0xFF262A3A), shape)) {
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

/** Gradient action button of the login forms (rounded rectangle, unlike the pill `PrimaryButton`). */
@Composable
private fun GradientButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier
            .hoverFocus()
            .focusCard(FocusStyle(scale = 1.02f, corner = 8.dp, ring = 1.5.dp, ringColor = Color(0xFFD9D2FF), glows = FocusStyle.ButtonGlows))
            .height(40.dp)
            .background(AppColors.PrimaryGradient, RoundedCornerShape(8.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 17.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = AppType.Button.copy(fontSize = if (big) 14.sp else 13.sp, color = Color.White))
    }
}

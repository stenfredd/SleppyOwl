package ru.stenfredd.sleepyowl.ui.login

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import ru.stenfredd.sleepyowl.core.jellyfin.Account
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.core.jellyfin.PublicUser
import ru.stenfredd.sleepyowl.core.jellyfin.QuickConnectExpiredException
import ru.stenfredd.sleepyowl.core.jellyfin.Server
import ru.stenfredd.sleepyowl.core.jellyfin.ServerLogin
import ru.stenfredd.sleepyowl.core.jellyfin.ServerNotFoundException
import ru.stenfredd.sleepyowl.ui.focus.ScreenFocus
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class LoginStep { Server, Profile, Login }

@Immutable
data class ServerCardModel(
    val key: String,
    val name: String,
    val address: String,
    /** "LAN" when found on the local network, "ИНТЕРНЕТ" for a saved or typed address. */
    val where: String,
    val ping: String?,
    val version: String?,
    val busy: Boolean = false,
)

@Immutable
data class ProfileModel(val key: String, val name: String, val note: String, val avatarSeed: String)

@Immutable
sealed interface QuickConnectState {
    data object Loading : QuickConnectState
    data object Disabled : QuickConnectState
    data class Failed(val message: String) : QuickConnectState
    data class Code(val code: String, val qr: ImageBitmap, val expiresAt: Long) : QuickConnectState
}

@Immutable
data class LoginState(
    val step: LoginStep,
    val servers: List<ServerCardModel> = emptyList(),
    val searching: Boolean = false,
    /** A search has finished at least once. */
    val searched: Boolean = false,
    val manualOpen: Boolean = false,
    val manualBusy: Boolean = false,
    val manualError: String? = null,
    val serverLine: String = "",
    val profiles: List<ProfileModel> = emptyList(),
    val profileError: String? = null,
    /** Name to put into the login form when a listed user needs a password. */
    val prefillUser: String = "",
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val quickConnect: QuickConnectState = QuickConnectState.Loading,
    /** Logged in: the screen leaves to the app. */
    val done: Boolean = false,
)

/**
 * Login flow from the mockup: server (found on the LAN, saved, or typed) → profile ("Кто смотрит?": accounts saved on
 * this TV and the server's public users) → login (Quick Connect code with QR, or name and password).
 * Opened from the app's "сменить профиль" it starts at the profile step of the current server.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: JellyfinSession,
    private val login: ServerLogin,
) : ViewModel() {
    private val history = ArrayDeque<LoginStep>()
    private val _state: MutableStateFlow<LoginState>
    val state: StateFlow<LoginState>

    /** Remembered focus per step, so Back returns to the card that was chosen. */
    val focus: Map<LoginStep, ScreenFocus> = LoginStep.entries.associateWith { ScreenFocus(null) }

    private var discovered: List<Server> = emptyList()
    private var probed: Map<String, Server> = emptyMap()
    private var typed: Server? = null
    private var server: Server? = null
    private var publicUsers: List<PublicUser> = emptyList()
    private var stepJob: Job? = null

    init {
        val accounts = session.accounts.value
        val current = session.session.value?.let { s -> accounts.firstOrNull { it.id == s.accountId } } ?: accounts.firstOrNull()
        _state = MutableStateFlow(LoginState(step = LoginStep.Server))
        state = _state.asStateFlow()
        history += LoginStep.Server
        if (current != null) {
            // Straight to "Кто смотрит?" for the server used last; Back goes to the server list.
            val saved = current.toServer()
            server = saved
            history += LoginStep.Profile
            _state.update { it.copy(step = LoginStep.Profile, serverLine = serverLine(saved), profiles = profiles(saved)) }
            viewModelScope.launch { loadPublicUsers(saved) }
            // Accounts moved over from the prototype have no server name yet.
            if (saved.name.isEmpty()) viewModelScope.launch {
                login.probe(saved.requestUrl)?.let { info ->
                    if (server == saved) _state.update { it.copy(serverLine = serverLine(saved.copy(name = info.name))) }
                }
            }
        }
        enterStep()
    }

    /** Back inside the flow; false on the first step (the screen itself closes). */
    fun back(): Boolean {
        if (history.size <= 1 || _state.value.loginBusy) return false
        history.removeLast()
        _state.update { it.copy(step = history.last(), loginError = null, manualError = null) }
        enterStep()
        return true
    }

    // ---- Server step ----

    fun openManual() {
        _state.update { it.copy(manualOpen = true, manualError = null) }
    }

    fun connectManual(address: String) {
        if (_state.value.manualBusy) return
        _state.update { it.copy(manualBusy = true, manualError = null) }
        viewModelScope.launch {
            try {
                val found = login.find(address)
                typed = found
                publishServers()
                pick(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Server not found at %s", address)
                _state.update {
                    it.copy(manualError = if (e is ServerNotFoundException) "Сервер не найден. Проверьте адрес." else describe(e))
                }
            } finally {
                _state.update { it.copy(manualBusy = false) }
            }
        }
    }

    fun pickServer(key: String) {
        val target = servers().firstOrNull { cardKey(it) == key } ?: return
        if (_state.value.servers.any { it.busy }) return
        viewModelScope.launch { pick(target) }
    }

    private suspend fun pick(target: Server) {
        server = target
        markBusy(cardKey(target))
        loadPublicUsers(target)
        markBusy(null)
        val profiles = profiles(target)
        _state.update { it.copy(serverLine = serverLine(target), profiles = profiles, prefillUser = "") }
        // Nothing to choose from (users hidden, none saved): straight to the login form.
        if (profiles.size == 1) go(LoginStep.Login) else go(LoginStep.Profile)
    }

    private fun markBusy(key: String?) {
        _state.update { s -> s.copy(servers = s.servers.map { it.copy(busy = it.key == key) }) }
    }

    // ---- Profile step ----

    fun pickProfile(key: String) {
        val target = server ?: return
        when {
            key == OTHER_KEY -> {
                _state.update { it.copy(prefillUser = "") }
                go(LoginStep.Login)
            }
            key.startsWith(ACCOUNT_PREFIX) -> viewModelScope.launch {
                runCatching { session.switchTo(key.removePrefix(ACCOUNT_PREFIX)) }
                    .onSuccess { _state.update { it.copy(done = true) } }
                    .onFailure { e -> _state.update { it.copy(profileError = describe(e)) } }
            }
            key.startsWith(USER_PREFIX) -> {
                val user = publicUsers.firstOrNull { it.id.toString() == key.removePrefix(USER_PREFIX) } ?: return
                _state.update { it.copy(prefillUser = user.name) }
                focus.getValue(LoginStep.Login).lastKey = if (user.hasPassword) PASSWORD_KEY else null
                if (user.hasPassword) {
                    go(LoginStep.Login)
                } else {
                    viewModelScope.launch {
                        runCatching { login.login(target, user.name, "") }
                            .onSuccess { _state.update { it.copy(done = true) } }
                            .onFailure { go(LoginStep.Login) }
                    }
                }
            }
        }
    }

    // ---- Login step ----

    fun loginWithPassword(userName: String, password: String) {
        val target = server ?: return
        if (_state.value.loginBusy) return
        if (userName.isBlank()) {
            _state.update { it.copy(loginError = "Введите имя пользователя") }
            return
        }
        _state.update { it.copy(loginBusy = true, loginError = null) }
        viewModelScope.launch {
            try {
                login.login(target, userName, password)
                _state.update { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Login failed")
                _state.update { it.copy(loginError = describe(e)) }
            } finally {
                _state.update { it.copy(loginBusy = false) }
            }
        }
    }

    private suspend fun runQuickConnect(target: Server) {
        while (true) {
            _state.update { it.copy(quickConnect = QuickConnectState.Loading) }
            val code = try {
                login.startQuickConnect(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Quick Connect not started")
                _state.update { it.copy(quickConnect = QuickConnectState.Failed(describe(e))) }
                delay(RETRY_MS)
                continue
            }
            if (code == null) {
                _state.update { it.copy(quickConnect = QuickConnectState.Disabled) }
                return
            }
            val qr = withContext(Dispatchers.Default) { qrBitmap(quickConnectUrl(target, code.code)) }
            val expiresAt = SystemClock.elapsedRealtime() + QUICK_CONNECT_LIFETIME_MS
            _state.update { it.copy(quickConnect = QuickConnectState.Code(formatCode(code.code), qr, expiresAt)) }
            // Poll until approved; the server drops the request after its lifetime, then a new code is shown.
            while (true) {
                delay(POLL_MS)
                val approved = try {
                    login.isQuickConnectApproved(target, code.secret)
                } catch (e: QuickConnectExpiredException) {
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "Quick Connect state not read")
                    if (SystemClock.elapsedRealtime() > expiresAt) break else continue
                }
                if (approved) {
                    try {
                        login.loginQuickConnect(target, code.secret)
                        _state.update { it.copy(done = true) }
                        return
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Quick Connect login failed")
                        _state.update { it.copy(loginError = describe(e)) }
                        break
                    }
                }
            }
        }
    }

    // ---- Steps ----

    private fun go(step: LoginStep) {
        if (history.last() == step) return
        history += step
        if (step == LoginStep.Login && _state.value.prefillUser.isEmpty()) focus.getValue(LoginStep.Login).lastKey = null
        _state.update { it.copy(step = step, loginError = null, profileError = null) }
        enterStep()
    }

    /** Starts the background work of the current step: LAN search, or the Quick Connect code. */
    private fun enterStep() {
        stepJob?.cancel()
        stepJob = when (_state.value.step) {
            LoginStep.Server -> viewModelScope.launch { searchLoop() }
            LoginStep.Profile -> null
            LoginStep.Login -> server?.let { target -> viewModelScope.launch { runQuickConnect(target) } }
        }
    }

    private suspend fun searchLoop() = coroutineScope {
        publishServers()
        // Saved servers that weren't found on the LAN: their ping and version through the saved address.
        launch {
            for (s in savedServers().filter { it.id !in probed }) {
                login.probe(s.url)?.let { probed = probed + (s.id to it.copy(id = s.id, name = s.name.ifEmpty { it.name })) }
            }
            publishServers()
        }
        while (isActive) {
            _state.update { it.copy(searching = true) }
            discovered = login.discover()
            _state.update { it.copy(searching = false, searched = true) }
            publishServers()
            delay(SEARCH_INTERVAL_MS)
        }
    }

    private suspend fun loadPublicUsers(target: Server) {
        publicUsers = try {
            login.publicUsers(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Public users not loaded")
            emptyList()
        }
        if (server == target) _state.update { it.copy(profiles = profiles(target)) }
    }

    // ---- Models ----

    /** Discovered servers first, then saved ones that weren't found, then a typed one. */
    private fun servers(): List<Server> {
        val byId = LinkedHashMap<String, Server>()
        discovered.forEach { byId[it.id] = it }
        savedServers().forEach { saved -> if (saved.id !in byId) byId[saved.id] = probed[saved.id]?.copy(url = saved.url) ?: saved }
        typed?.let { if (it.id !in byId) byId[it.id] = it }
        return byId.values.toList()
    }

    private fun savedServers(): List<Server> = session.accounts.value.distinctBy { it.serverId }.map { it.toServer() }

    private fun publishServers() {
        val busy = _state.value.servers.firstOrNull { it.busy }?.key
        _state.update { s ->
            s.copy(servers = servers().map { srv ->
                val key = cardKey(srv)
                ServerCardModel(
                    key = key,
                    name = srv.name.ifEmpty { "Jellyfin" },
                    address = displayAddress(srv.localUrl ?: srv.url),
                    where = if (srv.localUrl != null && discovered.any { it.id == srv.id }) "LAN" else "ИНТЕРНЕТ",
                    ping = srv.pingMs?.let { "$it мс" },
                    version = srv.version,
                    busy = key == busy,
                )
            })
        }
    }

    private fun profiles(target: Server): List<ProfileModel> {
        val currentId = session.session.value?.accountId
        val saved = session.accounts.value.filter { it.serverId == target.id }
        val savedUsers = saved.map { it.userId }.toSet()
        return saved.map { a ->
            ProfileModel(ACCOUNT_PREFIX + a.id, a.userName, if (a.id == currentId) "сейчас" else lastUsed(a.lastUsedAt), a.userId.toString())
        } + publicUsers.filter { it.id !in savedUsers }.map { u ->
            ProfileModel(USER_PREFIX + u.id, u.name, if (u.hasPassword) "пароль" else "без пароля", u.id.toString())
        } + ProfileModel(OTHER_KEY, "Другой", "QR или пароль", "")
    }

    private fun Account.toServer() = Server(serverId, serverName, serverUrl, localUrl)

    private fun cardKey(s: Server) = "srv-" + s.id

    private fun serverLine(s: Server) = listOf(s.name, displayAddress(s.url)).filter { it.isNotEmpty() }.joinToString(" · ")

    private fun displayAddress(url: String) = url.removePrefix("http://").removePrefix("https://")

    private fun lastUsed(at: Long): String {
        if (at <= 0) return ""
        val zone = ZoneId.systemDefault()
        val days = ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), LocalDate.now(zone))
        return when {
            days <= 0 -> "сегодня"
            days == 1L -> "вчера"
            days in 2..4 -> "$days дня назад"
            days < 30 -> "$days дней назад"
            else -> "давно"
        }
    }

    /** The phone opens the server's web app on its Quick Connect page with the code filled in. */
    private fun quickConnectUrl(target: Server, code: String) = "${target.url}/web/#/quickconnect?code=$code"

    private fun formatCode(code: String) = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

    private fun describe(e: Throwable): String {
        val status = generateSequence(e) { it.cause }.filterIsInstance<InvalidStatusException>().firstOrNull()?.status
        return when (status) {
            401 -> "Неверное имя пользователя или пароль"
            403 -> "Вход запрещён для этого пользователя"
            null -> "Нет связи с сервером"
            else -> "Сервер ответил ошибкой $status"
        }
    }

    companion object {
        const val OTHER_KEY = "other"
        const val PASSWORD_KEY = "password"
        private const val ACCOUNT_PREFIX = "acc-"
        private const val USER_PREFIX = "usr-"
        private const val SEARCH_INTERVAL_MS = 8_000L
        private const val POLL_MS = 3_000L
        private const val RETRY_MS = 5_000L
        /** Jellyfin forgets a Quick Connect request after 10 minutes. */
        private const val QUICK_CONNECT_LIFETIME_MS = 10 * 60_000L
    }
}

package ru.stenfredd.sleepyowl.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import ru.stenfredd.sleepyowl.core.common.Log
import ru.stenfredd.sleepyowl.core.data.LibraryRepository
import ru.stenfredd.sleepyowl.core.data.model.LibraryType
import ru.stenfredd.sleepyowl.core.jellyfin.JellyfinSession
import ru.stenfredd.sleepyowl.ui.Screen
import ru.stenfredd.sleepyowl.ui.components.AppIcon
import ru.stenfredd.sleepyowl.ui.library.LibraryMemory
import ru.stenfredd.sleepyowl.ui.shell.RailLibrary
import ru.stenfredd.sleepyowl.ui.shell.Tab
import ru.stenfredd.sleepyowl.ui.theme.AppColors
import ru.stenfredd.sleepyowl.ui.theme.AppType
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** The main window's content: restores the session, then the login screen or the tabs, with "Назад" everywhere. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(window: java.awt.Window, keys: BackKeys) {
    val session = koinInject<JellyfinSession>()
    var stack by remember { mutableStateOf<NavStack?>(null) }
    LaunchedEffect(Unit) {
        val loggedIn = session.restore()
        Log.i("Session restored: %s", if (loggedIn) "logged in" else "login screen")
        stack = NavStack(if (loggedIn) root(session, Tab.Home) else Screen.Login)
    }
    val dispatcher = remember { BackDispatcher() }
    val entry = remember { KeyboardEntry() }
    val onBack = { stack?.let { back(dispatcher, it) } }
    BackButton(window) { onBack() }
    keys.onBack = { onBack() }
    val focusManager = LocalFocusManager.current
    keys.onArrow = { direction -> focusManager.moveFocus(direction) || entry.enter() }
    keys.onSearch = { stack?.takeIf { it.top != Screen.Login }?.resetTo(root(session, Tab.Search)) }
    Box(Modifier.fillMaxSize().background(AppColors.Background).trackPointer()) {
        val nav = stack ?: return@Box
        CompositionLocalProvider(
            LocalBackDispatcher provides dispatcher,
            LocalKeyboardEntry provides entry,
            LocalBringIntoViewSpec provides KeyboardBringIntoView,
        ) {
            WatchMenuHost { Navigation(nav, session) { onBack() } }
        }
    }
}

/** A handler of the screen first, then the stack. The last screen stays: closing the window is the window's job. */
private fun back(dispatcher: BackDispatcher, stack: NavStack) {
    if (!dispatcher.dispatch()) stack.pop()
}

/**
 * Keys for the window's `onKeyEvent`: it gets every key the focused element did not take, and keys with nothing
 * focused at all (a modifier on the content would miss those). Esc, Alt+← and Backspace go back (Backspace is a text
 * field's own while one is focused, even an empty one); Ctrl+F and / open the search. Arrows move focus as the TV's
 * remote does (Compose on the desktop moves it only with Tab); with nothing focused they enter the screen
 * ([KeyboardEntry]).
 */
class BackKeys {
    var onBack: () -> Unit = {}
    var onArrow: (FocusDirection) -> Boolean = { false }
    var onSearch: () -> Unit = {}

    /** Every key first: from now on focus moved by keys scrolls into view ([Input]). */
    fun onPreview(event: KeyEvent): Boolean {
        Input.keyboard = true
        return false
    }

    fun onKey(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        val back = event.key == Key.Escape || (event.key == Key.Backspace && !typing) || (event.isAltPressed && event.key == Key.DirectionLeft)
        if (back) {
            if (down) onBack()
            return true
        }
        val search = (event.isCtrlPressed && event.key == Key.F) || (event.key == Key.Slash && !typing)
        if (search) {
            if (down) onSearch()
            return true
        }
        val direction = Arrows[event.key]
        if (direction != null && !event.isAltPressed) return !down || onArrow(direction)
        return false
    }

    private companion object {
        val Arrows = mapOf(
            Key.DirectionUp to FocusDirection.Up,
            Key.DirectionDown to FocusDirection.Down,
            Key.DirectionLeft to FocusDirection.Left,
            Key.DirectionRight to FocusDirection.Right,
        )
    }
}

/**
 * The mouse's back button over [window]. X11 sends it as button 8, AWT passes it on as its button 6, and Compose
 * drops buttons past 5 — so it is taken from AWT directly.
 */
@Composable
private fun BackButton(window: java.awt.Window, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    DisposableEffect(window) {
        val listener = AWTEventListener { e ->
            if (e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED && e.button == 6 && SwingUtilities.getWindowAncestor(e.component) == window) {
                current.value()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}

@Composable
private fun Navigation(stack: NavStack, session: JellyfinSession, onBack: () -> Unit) {
    val player = koinInject<ProtoPlayer>()
    fun open(screen: Screen) {
        if (screen is Screen.Player) player.play(screen) else stack.push(screen)
    }
    stack.Content { screen ->
        when (screen) {
            Screen.Login -> LoginScreen(onDone = { stack.resetTo(root(session, Tab.Home)) })
            is Screen.Root -> Root(
                screen.tab,
                session,
                onTab = { stack.resetTo(root(session, it)) },
                onLibrary = { id ->
                    val account = session.session.value?.accountId.orEmpty()
                    stack.resetTo(Screen.Root(Tab.Library, account, library = id))
                },
                onOpen = ::open,
                onSignedOut = { stack.resetTo(Screen.Login) },
                onSwitched = { stack.resetTo(root(session, Tab.Home)) },
            )
            is Screen.Detail -> Page(onBack) { DetailsScreen(screen, onOpen = ::open) }
            is Screen.Series -> Page(onBack) { SeriesScreen(screen, onOpen = ::open) }
            is Screen.Person -> Page(onBack) { PersonScreen(screen, onOpen = ::open) }
            is Screen.Items -> Page(onBack) { ItemsScreen(screen, onOpen = ::open) }
            // The player opens in its own window; licences and the debug tour are the TV's.
            is Screen.Player, is Screen.License, is Screen.Tour -> Unit
        }
    }
}

/** A full-screen page (no rail) with "‹" at the top left. */
@Composable
private fun Page(onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        BackArrow(onBack, Modifier.padding(start = 14.dp, top = 10.dp))
    }
}

private fun root(session: JellyfinSession, tab: Tab) = Screen.Root(tab, session.session.value?.accountId.orEmpty())

/** Account → its libraries on the rail: the next tab's rail starts with them instead of appearing a frame later. */
private var railLibraries: Pair<String, List<RailLibrary>> = "" to emptyList()

@Composable
private fun Root(
    tab: Tab,
    session: JellyfinSession,
    onTab: (Tab) -> Unit,
    onLibrary: (java.util.UUID) -> Unit,
    onOpen: (Screen) -> Unit,
    onSignedOut: () -> Unit,
    onSwitched: () -> Unit,
) {
    val repository = koinInject<LibraryRepository>()
    val memory = koinInject<LibraryMemory>()
    val user by session.session.collectAsState()
    val account = user?.accountId.orEmpty()
    val libraries by produceState(railLibraries.takeIf { it.first == account }?.second.orEmpty(), account) {
        value = runCatching { repository.views() }.onFailure { Log.w(it, "Libraries not loaded") }.getOrNull().orEmpty().map { view ->
            val icon = when (view.type) {
                LibraryType.Movies -> AppIcon.Film
                LibraryType.Shows -> AppIcon.Tv
                else -> AppIcon.Library
            }
            RailLibrary(view.id, view.name, icon)
        }
        railLibraries = account to value
    }
    Shell(
        current = tab,
        userName = user?.userName.orEmpty(),
        userSeed = user?.userId?.toString().orEmpty(),
        libraries = libraries,
        onTab = onTab,
        onLibrary = { id ->
            memory.setLibrary(account, id)
            onLibrary(id)
        },
        onProfile = { onOpen(Screen.Login) },
    ) {
        when (tab) {
            Tab.Home -> HomeScreen(onOpen = onOpen)
            Tab.Library -> LibraryScreen(onOpen = onOpen)
            Tab.Search -> SearchScreen(onOpen = onOpen)
            Tab.Favorites -> FavoritesScreen(onOpen = onOpen)
            Tab.Settings -> SettingsScreen(onOpen = onOpen, onSignedOut = onSignedOut, onSwitched = onSwitched)
        }
    }
}

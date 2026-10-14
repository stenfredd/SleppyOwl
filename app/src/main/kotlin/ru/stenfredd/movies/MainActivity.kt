package ru.stenfredd.movies

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import ru.stenfredd.movies.playback.api.EngineId
import ru.stenfredd.movies.playback.api.StreamSelection
import ru.stenfredd.movies.ui.ItemsScreen
import ru.stenfredd.movies.ui.PrototypeScreen
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.ui.TourScreen
import ru.stenfredd.movies.ui.details.DetailsScreen
import ru.stenfredd.movies.ui.home.HomeScreen
import ru.stenfredd.movies.ui.player.PlayerScreen
import ru.stenfredd.movies.ui.series.SeriesScreen
import ru.stenfredd.movies.ui.login.LoginScreen
import ru.stenfredd.movies.ui.shell.Shell
import ru.stenfredd.movies.ui.shell.Tab
import ru.stenfredd.movies.ui.theme.AppColors
import ru.stenfredd.movies.ui.theme.AppTheme
import ru.stenfredd.movies.ui.theme.AppType
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: JellyfinSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val stack = remember { mutableStateListOf<Screen>() }
                var ready by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val loggedIn = session.restore()
                    stack += if (loggedIn) root(Tab.Home) else Screen.Login
                    if (loggedIn) intentTarget()?.let(stack::addAll)
                    ready = true
                }
                if (ready) Navigation(stack)
            }
        }
    }

    @Composable
    private fun Navigation(stack: MutableList<Screen>) {
        fun open(screen: Screen) {
            stack.add(screen)
        }
        fun back() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
        }
        /** "Страница фильма" from the player: back to the item's page, or open it if the player came from elsewhere. */
        fun showDetails(id: UUID) {
            stack.removeAt(stack.lastIndex)
            if (stack.lastOrNull() != Screen.Detail(id)) stack.add(Screen.Detail(id))
        }
        /** "К сериалу" from the player: back to the series page, or open it if the player came from elsewhere. */
        fun showSeries(seriesId: UUID, episodeId: UUID) {
            stack.removeAt(stack.lastIndex)
            if ((stack.lastOrNull() as? Screen.Series)?.seriesId != seriesId) stack.add(Screen.Series(seriesId, episodeId))
        }
        fun resetTo(screen: Screen) {
            stack.clear()
            stack.add(screen)
        }

        NavDisplay(
            backStack = stack,
            onBack = { back() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            // No screen transitions: two screens composed at once is expensive on weak TVs.
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            entryProvider = { screen ->
                NavEntry(screen) {
                    when (screen) {
                        Screen.Login -> LoginScreen(onDone = { resetTo(root(Tab.Home)) })
                        is Screen.Root -> Root(screen.tab, onTab = { resetTo(root(it)) }, onOpen = ::open, onLogout = { resetTo(Screen.Login) })
                        Screen.Prototype -> Legacy { PrototypeScreen(onOpen = ::open, onLogout = { resetTo(Screen.Login) }) }
                        is Screen.Items -> Legacy { ItemsScreen(screen, onOpen = ::open) }
                        is Screen.Detail -> DetailsScreen(screen, onOpen = ::open)
                        is Screen.Series -> SeriesScreen(screen, onOpen = ::open)
                        is Screen.Player -> PlayerScreen(
                            screen,
                            onExit = ::back,
                            onOpenDetails = { id -> showDetails(id) },
                            onOpenSeries = { seriesId, episodeId -> showSeries(seriesId, episodeId) },
                        )
                        is Screen.Tour -> TourScreen(screen.startIndex, onExit = ::back)
                    }
                }
            },
        )
    }

    private fun root(tab: Tab) = Screen.Root(tab, session.session.value?.accountId.orEmpty())

    @Composable
    private fun Root(tab: Tab, onTab: (Tab) -> Unit, onOpen: (Screen) -> Unit, onLogout: () -> Unit) {
        val user by session.session.collectAsStateWithLifecycle()
        Shell(
            current = tab,
            userName = user?.userName.orEmpty(),
            userSeed = user?.userId?.toString().orEmpty(),
            onTab = onTab,
            onProfile = { onOpen(Screen.Login) },
        ) {
            when (tab) {
                Tab.Home -> HomeScreen(onOpen = onOpen)
                // Until stage 7 the library tab is the old prototype: libraries, text search, debug tour, logout.
                Tab.Library -> Legacy { PrototypeScreen(onOpen = onOpen, onLogout = onLogout) }
                Tab.Search, Tab.Favorites -> Placeholder("${tab.label}: скоро")
            }
        }
    }

    /** Old prototype screens use tv-material components, which take their text colour from a Surface. */
    @Composable
    private fun Legacy(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = AppColors.Text)) { content() }
    }

    @Composable
    private fun Placeholder(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BasicText(text, style = AppType.Body) }
    }

    /**
     * Opens an item from the launch intent:
     * `adb shell am start -S -n ru.stenfredd.movies/.MainActivity --es item <id> [--ez play true] [--el start <ms>]
     * [--ei audio <index>] [--ei sub <index>]` (stream indexes as Jellyfin numbers them; with one given, the other is off),
     * or a series page: `--es series <id> [--es episode <id>]`.
     * Debug builds also take `--es engine exo|vlc` (implies play) and `--ez tour true [--ei tour_file N]` for the debug tour.
     */
    private fun intentTarget(): List<Screen>? {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("tour", false)) return listOf(Screen.Tour(intent.getIntExtra("tour_file", 1) - 1))
        intent.getStringExtra("series")?.let(::parseId)?.let { series ->
            return listOf(Screen.Series(series, intent.getStringExtra("episode")?.let(::parseId)))
        }
        val id = intent.getStringExtra("item")?.let(::parseId) ?: return null
        val engine = if (BuildConfig.DEBUG) intent.getStringExtra("engine")?.let(::EngineId) else null
        val play = engine != null || intent.getBooleanExtra("play", false)
        val streams = if (intent.hasExtra("audio") || intent.hasExtra("sub")) {
            StreamSelection(intent.getIntExtra("audio", -1).takeIf { it >= 0 }, intent.getIntExtra("sub", -1).takeIf { it >= 0 })
        } else {
            null
        }
        return listOf(Screen.Detail(id)) +
            if (play) listOf(Screen.Player(id, engine, intent.getLongExtra("start", 0), streams = streams)) else emptyList()
    }

    /** Jellyfin ids come with or without dashes. */
    private fun parseId(raw: String): UUID? = runCatching {
        UUID.fromString(if ('-' in raw) raw else raw.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
    }.getOrNull()
}

package ru.stenfredd.movies

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import dagger.hilt.android.AndroidEntryPoint
import ru.stenfredd.movies.core.jellyfin.JellyfinSession
import ru.stenfredd.movies.ui.DetailScreen
import ru.stenfredd.movies.ui.HomeScreen
import ru.stenfredd.movies.ui.ItemsScreen
import ru.stenfredd.movies.ui.LoginScreen
import ru.stenfredd.movies.ui.PlayerScreen
import ru.stenfredd.movies.ui.Screen
import ru.stenfredd.movies.playback.api.EngineId
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: JellyfinSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val stack = remember { mutableStateListOf<Screen>() }
                    var ready by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        stack += if (session.restore()) Screen.Home else Screen.Login
                        if (stack.last() == Screen.Home) debugTarget()?.let(stack::addAll)
                        ready = true
                    }
                    fun open(screen: Screen) = stack.add(screen)
                    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

                    if (!ready) {
                        Box(Modifier.fillMaxSize()) { Text("Загрузка…") }
                        return@Surface
                    }
                    when (val screen = stack.last()) {
                        Screen.Login -> LoginScreen(onLoggedIn = {
                            stack.clear()
                            stack += Screen.Home
                        })
                        Screen.Home -> HomeScreen(onOpen = ::open, onLogout = {
                            stack.clear()
                            stack += Screen.Login
                        })
                        is Screen.Items -> ItemsScreen(screen, onOpen = ::open)
                        is Screen.Detail -> DetailScreen(screen, onOpen = ::open)
                        is Screen.Player -> PlayerScreen(screen, onExit = { stack.removeAt(stack.lastIndex) })
                    }
                }
            }
        }
    }

    /**
     * Debug-only shortcut for testing on a TV without typing:
     * `adb shell am start -S -n ru.stenfredd.movies/.MainActivity --es item <id> [--es engine exo|vlc] [--el start <ms>]`.
     */
    private fun debugTarget(): List<Screen>? {
        if (!BuildConfig.DEBUG) return null
        val raw = intent.getStringExtra("item") ?: return null
        val id = UUID.fromString(if ('-' in raw) raw else raw.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
        val engine = intent.getStringExtra("engine")?.let(::EngineId)
        val play = intent.hasExtra("engine") || intent.getBooleanExtra("play", false)
        return listOf(Screen.Detail(id)) + if (play) listOf(Screen.Player(id, engine, intent.getLongExtra("start", 0))) else emptyList()
    }
}

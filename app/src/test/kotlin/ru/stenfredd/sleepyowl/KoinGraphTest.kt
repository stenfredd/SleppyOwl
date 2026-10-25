package ru.stenfredd.sleepyowl

import android.app.Application
import android.content.Context
import okhttp3.OkHttpClient
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import ru.stenfredd.sleepyowl.core.data.dataModule
import ru.stenfredd.sleepyowl.core.jellyfin.jellyfinModule
import ru.stenfredd.sleepyowl.playback.core.playbackModule
import ru.stenfredd.sleepyowl.playback.exo.exoEngineModule
import ru.stenfredd.sleepyowl.playback.vlc.vlcEngineModule
import ru.stenfredd.sleepyowl.ui.uiModule
import java.io.File
import java.util.UUID

/** Hilt checked the graph while compiling; with Koin this test does: every constructor argument has a definition. */
class KoinGraphTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun graphIsComplete() {
        module { includes(jellyfinModule, dataModule, playbackModule, uiModule, exoEngineModule, vlcEngineModule, appModule) }.verify(
            // Context comes from androidContext(); UUID is the id view models get through parametersOf. verify() reads
            // the constructor of every declared type, also of those built in a lambda: AppDirs (File), OkHttpClient (Builder).
            extraTypes = listOf(Context::class, Application::class, UUID::class, OkHttpClient.Builder::class, File::class),
        )
    }
}

package ru.stenfredd.movies.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code the app runs on its main path, so it is compiled ahead of time on install instead of being
 * interpreted and JIT-compiled while the user scrolls (first passes over a screen used to drop frames).
 *
 * Path: cold start → home (snapshot, then fresh data) → along "Continue watching" → down through the rows, along
 * each → open a card, down through its page → back → library tab: along the tiles, down through the rows and the grid
 * → back up. With the instrumentation argument `seriesId` also a series page:
 * along the buttons, the season chips, the episode row, the audio/subtitle tray. The device must be logged in.
 *
 * Run: `./gradlew :app:generateReleaseBaselineProfile [-Pandroid.testInstrumentationRunnerArguments.seriesId=<id>]`
 * with the TV connected over adb (`gen_profile` in tools/tv/env.sh). The result is written to
 * `app/src/release/generated/baselineProfiles/` and goes into the release APK.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Продолжить просмотр")), 15_000)) { "Home did not show: is the TV logged in?" }
        // Let the fresh data replace the snapshot.
        device.waitForIdle()
        Thread.sleep(2_000)

        repeat(3) { press { device.pressDPadRight() } }
        repeat(3) { press { device.pressDPadLeft() } }
        repeat(ROWS) {
            press { device.pressDPadDown() }
            repeat(6) { press { device.pressDPadRight() } }
        }
        repeat(ROWS) { press { device.pressDPadUp() } }

        // Item page: actions, track chips, cast, similar, and back up.
        press { device.pressDPadCenter() }
        Thread.sleep(2_000)
        repeat(3) { press { device.pressDPadRight() } }
        repeat(3) { press { device.pressDPadLeft() } }
        repeat(4) {
            press { device.pressDPadDown() }
            repeat(3) { press { device.pressDPadRight() } }
        }
        repeat(4) { press { device.pressDPadUp() } }
        press { device.pressBack() }
        Thread.sleep(1_000)

        // Library tab: from the rail (Back on home focuses the rail at "Главная", the next item is "Медиатека").
        press { device.pressBack() }
        press { device.pressDPadDown() }
        press { device.pressDPadCenter() }
        check(device.wait(Until.hasObject(By.text("БИБЛИОТЕКА")), 10_000)) { "Library did not show" }
        Thread.sleep(1_500)
        // Each tile shows its library once focus rests on it.
        repeat(2) {
            press { device.pressDPadRight() }
            Thread.sleep(1_000)
        }
        repeat(2) {
            press { device.pressDPadLeft() }
            Thread.sleep(1_000)
        }
        // Rows, the sort chips and the grid (its next page loads on the way).
        repeat(LIBRARY_ROWS) {
            press { device.pressDPadDown() }
            press { device.pressDPadRight() }
        }
        repeat(LIBRARY_ROWS) { press { device.pressDPadUp() } }

        val seriesId = InstrumentationRegistry.getArguments().getString("seriesId") ?: return@collect
        startActivityAndWait { it.putExtra("series", seriesId) }
        Thread.sleep(2_000)
        repeat(2) { press { device.pressDPadRight() } }
        repeat(2) { press { device.pressDPadLeft() } }
        press { device.pressDPadDown() }
        repeat(2) { press { device.pressDPadRight() } }
        repeat(2) { press { device.pressDPadLeft() } }
        press { device.pressDPadDown() }
        repeat(8) { press { device.pressDPadRight() } }
        repeat(8) { press { device.pressDPadLeft() } }
        press { device.pressDPadUp() }
        press { device.pressDPadUp() }
        // The tray: audio, subtitles, their look; no choice is made (it would change what the account remembers).
        press { device.pressDPadRight() }
        press { device.pressDPadCenter() }
        repeat(2) { press { device.pressDPadRight() } }
        repeat(2) { press { device.pressDPadDown() } }
        press { device.pressBack() }
        press { device.pressBack() }
        Thread.sleep(1_000)
    }

    private inline fun press(action: () -> Unit) {
        action()
        Thread.sleep(400)
    }

    private companion object {
        const val PACKAGE = "ru.stenfredd.movies"
        const val ROWS = 6
        const val LIBRARY_ROWS = 14
    }
}

package ru.stenfredd.sleepyowl.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Records which code the app runs on its main path, so it is compiled ahead of time on install instead of being
 * interpreted and JIT-compiled while the user scrolls (first passes over a screen used to drop frames).
 *
 * Path: cold start → home (snapshot, then fresh data) → along "Continue watching" → down through the rows, along
 * each → open a card, down through its page → back → library tab: along the tiles, down through the rows and the grid
 * → back up → search (type a few letters, through the results) → favourites → settings. With the instrumentation argument `seriesId` also a series page:
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

        // Search tab (two items up the rail): type on the on-screen keyboard, go through the results.
        press { device.pressBack() }
        repeat(2) { press { device.pressDPadUp() } }
        press { device.pressDPadCenter() }
        Thread.sleep(1_500)
        press { device.pressDPadCenter() }
        repeat(2) { press { device.pressDPadRight() } }
        press { device.pressDPadCenter() }
        Thread.sleep(1_500)
        repeat(6) { press { device.pressDPadRight() } }
        repeat(3) { press { device.pressDPadDown() } }
        repeat(3) { press { device.pressDPadUp() } }

        // Favourites tab (the last tab at the top of the rail).
        press { device.pressBack() }
        repeat(3) { press { device.pressDPadDown() } }
        press { device.pressDPadCenter() }
        Thread.sleep(1_500)
        press { device.pressDPadDown() }
        press { device.pressDPadUp() }

        // Settings (below the tabs and the server's libraries, as many as the server has): down the rail until it has
        // focus. Then through the sections (each is shown after a pause), along a row of choices. Nothing is picked:
        // the choices are saved at once.
        // The presses are counted from the rail's labels rather than checked one by one: on the first, uncompiled pass
        // the app lags behind the keys, and a check after each press would overshoot.
        press { device.pressBack() }
        check(device.wait(Until.hasObject(By.text("Настройки")), 5_000)) { "Rail did not open" }
        repeat(railStepsToSettings()) { press { device.pressDPadDown() } }
        check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Настройки"))), 10_000)) { "Settings not focused on the rail" }
        press { device.pressDPadCenter() }
        Thread.sleep(1_500)
        repeat(4) {
            press { device.pressDPadDown() }
            Thread.sleep(400)
        }
        repeat(4) {
            press { device.pressDPadUp() }
            Thread.sleep(400)
        }
        press { device.pressDPadRight() }
        repeat(2) { press { device.pressDPadRight() } }
        press { device.pressDPadDown() }

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

    /** Rail items from "Избранное" down to "Настройки": the labels in between are the server's libraries. */
    private fun MacrobenchmarkScope.railStepsToSettings(): Int {
        // The leftmost of each: a screen may have a title of the same name.
        fun label(text: String) = device.findObjects(By.text(text)).minBy { it.visibleBounds.left }.visibleBounds
        val favorites = label("Избранное")
        val settings = label("Настройки")
        val libraries = device.findObjects(By.text(Pattern.compile(".+"))).count {
            val b = it.visibleBounds
            b.left == favorites.left && b.centerY() > favorites.centerY() && b.centerY() < settings.centerY()
        }
        return libraries + 1
    }

    private inline fun press(action: () -> Unit) {
        action()
        Thread.sleep(400)
    }

    private companion object {
        const val PACKAGE = "ru.stenfredd.sleepyowl"
        const val ROWS = 6
        const val LIBRARY_ROWS = 14
    }
}

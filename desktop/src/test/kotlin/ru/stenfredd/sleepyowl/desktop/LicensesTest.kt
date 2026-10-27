package ru.stenfredd.sleepyowl.desktop

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of "О приложении" opens a text: the TV's ones are copied in by the build, the desktop's own are resources. */
class LicensesTest {
    @Test
    fun everyLicenceHasItsText() {
        for (c in listOf(Licenses.app) + Licenses.components) {
            val text = Licenses::class.java.getResourceAsStream("/licenses/${c.asset}")?.use { it.readBytes().size } ?: 0
            assertTrue("${c.name}: no licenses/${c.asset}", text > 500)
        }
    }
}

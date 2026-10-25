package ru.stenfredd.sleepyowl.core.common

import java.io.File

/** XDG places: settings in `~/.config/sleepyowl`, refetchable data in `~/.cache/sleepyowl`. */
fun desktopAppDirs(): AppDirs {
    fun xdg(variable: String, default: String) =
        File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + default), "sleepyowl")
    return AppDirs(preferences = xdg("XDG_CONFIG_HOME", ".config"), files = xdg("XDG_CACHE_HOME", ".cache"))
}

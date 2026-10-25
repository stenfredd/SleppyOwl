package ru.stenfredd.sleepyowl.core.common

import timber.log.Timber

internal actual fun logEnabled() = Timber.treeCount > 0

internal actual fun platformLog(priority: Int, tag: String, t: Throwable?, message: String, args: Array<out Any?>) {
    Timber.tag(tag).log(priority, t, message, *args)
}

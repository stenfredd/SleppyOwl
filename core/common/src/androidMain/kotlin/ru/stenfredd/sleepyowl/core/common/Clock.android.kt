package ru.stenfredd.sleepyowl.core.common

import android.os.SystemClock

actual fun monotonicMs(): Long = SystemClock.elapsedRealtime()

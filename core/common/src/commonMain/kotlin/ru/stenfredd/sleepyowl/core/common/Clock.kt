package ru.stenfredd.sleepyowl.core.common

/** Milliseconds of a clock that only goes forward; on Android it keeps counting while the device sleeps. */
expect fun monotonicMs(): Long

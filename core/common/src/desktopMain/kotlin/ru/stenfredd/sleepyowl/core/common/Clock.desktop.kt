package ru.stenfredd.sleepyowl.core.common

actual fun monotonicMs(): Long = System.nanoTime() / 1_000_000

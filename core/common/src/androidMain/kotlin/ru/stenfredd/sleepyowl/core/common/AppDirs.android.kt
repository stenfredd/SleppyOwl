package ru.stenfredd.sleepyowl.core.common

import android.content.Context
import java.io.File

/** The places the TV version always used: `files/datastore` (as `preferencesDataStore`) and `files`. */
fun androidAppDirs(context: Context) = AppDirs(preferences = File(context.filesDir, "datastore"), files = context.filesDir)

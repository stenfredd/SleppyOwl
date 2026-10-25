package ru.stenfredd.sleepyowl.core.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the app keeps its files on this platform.
 *
 * [preferences]: DataStore files (`<name>.preferences_pb`) — accounts, settings, remembered tracks. [files]: data that
 * can be fetched again but should outlive a restart, such as the home screen snapshot.
 */
class AppDirs(val preferences: File, val files: File)

/** The DataStore of each preferences file: DataStore allows one instance per file, so everyone gets it here. */
class PreferenceStores(private val dirs: AppDirs) {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    operator fun get(name: String): DataStore<Preferences> = stores.computeIfAbsent(name) {
        PreferenceDataStoreFactory.create(produceFile = { File(dirs.preferences, "$name.preferences_pb") })
    }
}

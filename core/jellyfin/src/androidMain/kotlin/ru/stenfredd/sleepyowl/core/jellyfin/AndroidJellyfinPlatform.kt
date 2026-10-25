package ru.stenfredd.sleepyowl.core.jellyfin

import android.content.Context
import android.os.Build
import org.jellyfin.sdk.JellyfinOptions

class AndroidJellyfinPlatform(private val context: Context) : JellyfinPlatform {
    override val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun configure(options: JellyfinOptions.Builder) {
        options.context = context
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "ru.stenfredd.sleepyowl.playback.api"
        compileSdk = 37
        minSdk = 26
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.jellyfin.sdk)
            api(libs.kotlinx.coroutines.core)
        }
    }
}

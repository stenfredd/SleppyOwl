plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "ru.stenfredd.sleepyowl.playback.core"
        compileSdk = 37
        minSdk = 26
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":playback:api"))
            api(project(":core:jellyfin"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

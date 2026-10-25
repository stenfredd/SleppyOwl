plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "ru.stenfredd.sleepyowl.core.data"
        compileSdk = 37
        minSdk = 26
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":core:jellyfin"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

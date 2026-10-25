plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Shared by the TV (android) and desktop versions: logging, clock, preference files.
kotlin {
    android {
        namespace = "ru.stenfredd.sleepyowl.core.common"
        compileSdk = 37
        minSdk = 26
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.datastore.preferences.core)
        }
        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
            implementation(libs.timber)
        }
    }
}

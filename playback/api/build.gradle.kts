plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.stenfredd.sleepyowl.playback.api"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.jellyfin.sdk)
    api(libs.kotlinx.coroutines.android)
}

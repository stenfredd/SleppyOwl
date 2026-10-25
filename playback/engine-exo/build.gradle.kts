plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.stenfredd.sleepyowl.playback.exo"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":playback:api"))
    implementation(project(":core:common"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.ass.media)
    implementation(libs.jellyfin.ffmpeg)
    implementation(libs.koin.android)
}

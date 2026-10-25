plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.stenfredd.sleepyowl.playback.vlc"
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
    implementation(libs.libvlc)
    implementation(libs.okhttp)
    implementation(libs.koin.android)
}

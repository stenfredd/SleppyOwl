plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
}

// UI shared by the TV (android) and desktop versions: view models, navigation keys, card models and labels,
// theme, icons and visual components. Screens with remote-control focus logic stay in :app.
kotlin {
    android {
        namespace = "ru.stenfredd.sleepyowl.ui.shared"
        compileSdk = 37
        minSdk = 26
        // Fonts and the logo pictures (res/font, res/drawable-xhdpi); the desktop reads the same files as resources.
        androidResources { enable = true }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":core:data"))
            api(project(":playback:core"))
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.androidx.lifecycle.viewmodel)
            implementation(libs.koin.core.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.zxing.core)
        }
        getByName("desktopMain") {
            resources.srcDir("src/androidMain/res")
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
            implementation(libs.koin.test)
            implementation(libs.cmp.desktop.linux.x64)
        }
    }
}

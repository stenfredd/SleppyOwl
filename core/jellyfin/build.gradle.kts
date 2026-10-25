plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "ru.stenfredd.sleepyowl.core.jellyfin"
        compileSdk = 37
        minSdk = 26
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(libs.jellyfin.sdk)
            api(libs.okhttp)
            api(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
            // The SDK logs through slf4j but does not ship it.
            runtimeOnly(libs.slf4j.api)
        }
    }
}

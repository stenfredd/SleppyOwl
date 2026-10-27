// The desktop's playback engine (LINUX_PLAN.md §2.3): libmpv through JNA, video drawn offscreen and shown in Compose.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":playback:api"))
    implementation(project(":playback:core"))
    implementation(project(":core:common"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(libs.jna)
    implementation(libs.koin.core)

    testImplementation(libs.junit)
}

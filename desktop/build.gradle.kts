// SleepyOwl for the Linux desktop (LINUX_PLAN.md): windows, desktop screens, later the mpv player and system integration.
// Run: ./gradlew :desktop:run, or tools/linux/desktop.sh without Gradle around the process.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":ui:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.koin.core)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)

    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
}

// XToolkit: the windows' WM_CLASS (sun.awt.X11.XToolkit.awtAppClassName), set before the first window.
val awtOpens = listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt=ALL-UNNAMED")

compose.desktop {
    application {
        mainClass = "ru.stenfredd.sleepyowl.desktop.MainKt"
        jvmArgs += awtOpens + "-Dsleepyowl.repo=${rootDir.path}"
    }
}

tasks.withType<Test>().configureEach { jvmArgs(awtOpens) }

// Classpath into a file: tools/linux/desktop.sh starts the app without Gradle.
tasks.register("desktopClasspath") {
    val cp = sourceSets.main.get().runtimeClasspath
    val out = layout.buildDirectory.file("desktop.classpath")
    inputs.files(cp)
    outputs.file(out)
    doLast { out.get().asFile.writeText(cp.joinToString(":")) }
}

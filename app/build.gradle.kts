plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// Версия из gradle.properties или `-Psleepyowl.versionName=…` (CI поднимает patch). versionCode следует за ней:
// 1.2.3 → 1_002_003, так что более новая версия всегда ставится поверх старой.
val appVersion = providers.gradleProperty("sleepyowl.versionName").get()
val appVersionCode = appVersion.split('.').mapNotNull(String::toIntOrNull).let { parts ->
    require(parts.size == 3 && parts[1] < 1000 && parts[2] < 1000) { "sleepyowl.versionName must be major.minor.patch (minor, patch < 1000): $appVersion" }
    parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
}

// Ключ подписи release вне репозитория: ANDROID_KEYSTORE (+ пароль, alias). Без него — debug-ключ этой машины.
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE").orNull?.let(::file)?.also {
    require(it.isFile) { "ANDROID_KEYSTORE is not a file: $it" }
}
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull.orEmpty()

android {
    namespace = "ru.stenfredd.sleepyowl"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.stenfredd.sleepyowl"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotEmpty() } ?: "sleepyowl"
                // У PKCS12 (по умолчанию в keytool) один пароль на хранилище и ключ.
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotEmpty() } ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            // Скорость интерфейса проверяем только на этой сборке: R8 + baseline profile.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Без ANDROID_KEYSTORE — debug-ключом, чтобы локальная сборка ставилась на ТВ.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts += "lib/*/libc++_shared.so"
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:jellyfin"))
    implementation(project(":core:data"))
    implementation(project(":playback:core"))
    implementation(project(":playback:engine-exo"))
    implementation(project(":playback:engine-vlc"))
    implementation(project(":ui:shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.timber)
    implementation(libs.zxing.core)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
}

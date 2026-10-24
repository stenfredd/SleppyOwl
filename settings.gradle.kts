pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sleepyowl"

include(":app")
include(":baselineprofile")
include(":core:jellyfin")
include(":core:data")
include(":playback:api")
include(":playback:core")
include(":playback:engine-exo")
include(":playback:engine-vlc")

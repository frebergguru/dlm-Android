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
        // youtubedl-android artifacts are published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "dlm-Android"
include(":app")
include(":core")

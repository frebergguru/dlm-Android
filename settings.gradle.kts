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
        // JitPack only for the youtubedl fork / GitHub-published artifacts. Scope
        // it so an arbitrary dependency can't be silently resolved from a git tag
        // (supply-chain risk); everything else must come from Google/Maven Central.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroupByRegex("com\\.github\\..*")
                includeGroupByRegex("io\\.github\\.junkfood02.*")
            }
        }
    }
}

rootProject.name = "dlm-Android"
include(":app")
include(":core")
include(":baselineprofile")

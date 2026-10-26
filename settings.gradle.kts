pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://jitpack.io")  // Sora Editor
    }
}

rootProject.name = "Bori-Code"

include(":app")
include(":core:ai")
include(":core:security")
include(":core:editor")
include(":core:project")
include(":core:build")
include(":core:terminal")
include(":core:linux")

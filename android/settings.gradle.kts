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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YourTube"

include(":app")
include(":core:common")
include(":core:ui")
include(":core:designsystem")
include(":core:data")
include(":core:database")
include(":core:network")
include(":core:player")
include(":feature:home")
include(":feature:search")
include(":feature:player")
include(":feature:library")
include(":feature:history")
include(":feature:settings")

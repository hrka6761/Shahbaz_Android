/** Defines plugin repositories, dependency repositories, and the Shahbaz module graph. */
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
        maven(url = "https://maven.myket.ir")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://maven.myket.ir")
    }
}

rootProject.name = "Shahbaz"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":compass")
include(":core:model")
include(":core:domain")
include(":core:designsystem")
include(":core:map")
include(":core:hardware_connection")
include(":feature:map:impl")
include(":feature:dashboard:impl")

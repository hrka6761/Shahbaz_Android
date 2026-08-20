/** Defines plugin repositories, dependency repositories, and the Shahbaz module graph. */
pluginManagement {
    repositories {
        maven(url = "https://maven.myket.ir")
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
        maven(url = "https://maven.myket.ir")
        google()
        mavenCentral()
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

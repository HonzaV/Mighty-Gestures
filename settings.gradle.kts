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
        // Build plugins only (docs/engineering/f-droid.md); everything else must come from Google Maven / Central.
        gradlePluginPortal {
            content {
                includeGroup("com.diffplug.spotless")
                includeGroup("io.gitlab.arturbosch.detekt")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MightyGestures"
include(":app")

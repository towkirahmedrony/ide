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

rootProject.name = "Forge"

// Platform-independent domain layers.
include(":core")
include(":model")
include(":agent")
include(":tools")
include(":workspace")
include(":context")
include(":git")
include(":skills")
include(":integrations")

// Android-facing layers.
include(":ui")
include(":app")

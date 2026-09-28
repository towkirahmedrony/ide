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

rootProject.name = "AgentX"

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
include(":codeintel")

// Android-facing layers.
include(":ui")
include(":codeintel-android")
include(":workspace-android")
include(":model-android")
include(":integrations-android")
include(":app")

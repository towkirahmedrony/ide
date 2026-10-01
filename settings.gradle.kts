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

// Vendored Termux terminal libraries (Apache-2.0). Kept as separate modules so upstream source
// stays unmodified and clearly attributable. See third_party/termux/README.md.
include(":termux-terminal-emulator")
include(":termux-terminal-view")

// The primary embedded developer runtime (Ubuntu ARM64 through PRoot). It depends on
// `:termux-runtime` only for the shared terminal layer; see docs/developer-runtime.md.
include(":ubuntu-runtime")

// Android-facing layers.
include(":termux-runtime")
include(":ui")
include(":codeintel-android")
include(":workspace-android")
include(":model-android")
include(":integrations-android")
include(":app")

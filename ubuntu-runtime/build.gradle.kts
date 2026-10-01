// The AgentX embedded developer runtime: a minimal Ubuntu ARM64 userland executed through
// PRoot, whose native components live in `nativeLibraryDir`.
//
// This is the primary runtime. It is deliberately separate from `:termux-runtime`, which is
// now the legacy/fallback backend: the two share the vendored terminal emulator and the
// PTY, and nothing else. See `docs/developer-runtime.md`.
//
// The module reuses `:termux-runtime` only for the terminal layer that is *not* bootstrap
// code: `TermuxShellSpec`, the session/terminal ports and the vendored `TerminalSession`.
// The legacy bootstrap catalog, installer and prefix policy are never referenced here.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.agentx.app.ubuntu"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The decision logic under test is Android-free, but the module links against
        // android.*, so stubs are required when the test JVM loads those classes.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            // PRoot and its loader must exist as real files under `nativeLibraryDir` at
            // runtime: Android can only execute a binary from the app's native library
            // directory, never from app-private `filesDir`. Legacy packaging extracts the
            // packaged `jniLibs/<abi>/*.so` to `nativeLibraryDir` on install, which is what
            // makes `libproot.so` and `libproot_loader.so` directly executable.
            //
            // The binaries are produced by `.github/workflows/android-build.yml` (NDK PRoot
            // build + vendor-native.sh) and dropped into `src/main/jniLibs/<abi>/`; none are
            // committed.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // The vendored terminal emulator/PTY and the shared terminal types (`TermuxShellSpec`,
    // `TermuxTerminalHost`, `TermuxSessionClient`). This is terminal-layer reuse, not
    // bootstrap reuse.
    api(project(":termux-runtime"))

    // A SAF project (`content://` tree) is materialised into app storage before it is bind-mounted
    // into the guest as /workspace/project. This is the same reader the workspace-android module
    // uses; it does not pull in any bootstrap code.
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

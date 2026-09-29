// Vendored Termux `terminal-emulator` library.
//
// Source is copied unmodified from termux/termux-app (see third_party/termux/README.md).
// Only this build script was rewritten so the module fits this project's Gradle setup.
// The library is licensed Apache-2.0 (see LICENSE in this directory).

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 37
    // Same NDK as upstream termux-app, so the vendored termux.c compiles the way its
    // authors verify it.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            ndkBuild {
                // Upstream flags, minus `-Werror`: a warning from a newer host clang must not
                // fail the whole IDE build.
                cFlags += listOf(
                    "-std=c11",
                    "-Wall",
                    "-Wextra",
                    "-Os",
                    "-fno-stack-protector",
                    "-Wl,--gc-sections",
                )
            }
        }

        ndk {
            abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The vendored terminal tests exercise pure emulation logic but the compiled
        // classes still reference android.* types, so stubs are required on the JVM.
        unitTests.isReturnDefaultValues = true
    }

    // `TerminalTestCase` is the shared abstract base for Termux's suites, not a suite itself.
    // Its name matches the usual test-class patterns, so the concrete `*Test` classes are
    // selected explicitly rather than relying on the runner to skip it.
    testOptions.unitTests.all {
        it.filter.includeTestsMatching("*Test")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    api(libs.androidx.annotation)

    testImplementation(kotlin("test-junit"))
}

// The embedded Termux runtime: prefix layout, userland provisioning, PTY session lifecycle and
// the clients that connect the vendored Termux terminal to this IDE.
//
// Split out from `:ui` on purpose. The runtime is Android-facing but has no Compose or IDE
// dependency, and its decision logic (prefix policy, environment, workspace binding, session
// bookkeeping) is unit tested on the JVM without a device.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.agentx.app.termux"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The unit tests cover Android-free classes, but every class in this module links
        // against android.*, so stubs are required when the test JVM loads them.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Pulls in the vendored terminal emulator and its PTY JNI.
    api(project(":termux-terminal-view"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

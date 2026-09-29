// Vendored Termux `terminal-view` library.
//
// Source and resources are copied unmodified from termux/termux-app (see
// third_party/termux/README.md). Only this build script was rewritten.
// The library is licensed Apache-2.0 (see LICENSE in this directory).

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // terminal-view renders whatever terminal-emulator emulates; upstream also exposes it as api.
    api(project(":termux-terminal-emulator"))
    api(libs.androidx.annotation)
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.agentx.app.codeintel.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        // Offset mapping and graceful degradation are tested on the JVM; the
        // grammar parsers themselves need a device.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":codeintel"))
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    // Tree-sitter behind the code-intelligence port. These artifacts are Android
    // AARs: each one packages NDK-built libraries for every Android ABI, so the
    // grammars travel inside the APK and need no download at runtime.
    implementation(libs.android.tree.sitter)
    implementation(libs.android.tree.sitter.kotlin)
    implementation(libs.android.tree.sitter.java)
    implementation(libs.android.tree.sitter.python)
    implementation(libs.android.tree.sitter.json)
    implementation(libs.android.tree.sitter.xml)

    // Android unit tests run on JUnit 4, so the JUnit variant of kotlin-test is
    // required; it also brings junit itself onto the test classpath.
    testImplementation(kotlin("test-junit"))
}

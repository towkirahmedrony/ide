plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.agentx.app.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // State holders are tested on the JVM; no device is involved.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core"))
    api(project(":workspace"))
    // The Files page publishes the editor selection to the Context Engine.
    api(project(":context"))
    // The Models screen talks to the Model Manager domain contract directly.
    api(project(":model"))
    // The Connections screen talks to the Connection Manager domain contract.
    api(project(":integrations"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Android unit tests run on JUnit 4, so the JUnit variant of kotlin-test is
    // required; it also brings junit itself onto the test classpath.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

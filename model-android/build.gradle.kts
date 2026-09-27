plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.agentx.app.model.android"
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
    api(project(":model"))
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
}

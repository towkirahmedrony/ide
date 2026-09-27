plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.forge.ide.workspace.android"
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
    api(project(":workspace"))
    implementation(project(":core"))
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
}

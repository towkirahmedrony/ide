plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.agentx.app"
    compileSdk = 37

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("AGENTX_STORE_FILE")

            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("AGENTX_STORE_PASSWORD")
                keyAlias = System.getenv("AGENTX_KEY_ALIAS")
                keyPassword = System.getenv("AGENTX_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.agentx.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            if (!System.getenv("AGENTX_STORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Platform-independent domain layers.
    implementation(project(":core"))
    implementation(project(":model"))
    implementation(project(":agent"))
    implementation(project(":tools"))
    implementation(project(":workspace"))
    implementation(project(":context"))
    implementation(project(":git"))
    implementation(project(":skills"))
    implementation(project(":integrations"))

    // Android-facing layers.
    implementation(project(":ui"))
    implementation(project(":workspace-android"))
    implementation(project(":model-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

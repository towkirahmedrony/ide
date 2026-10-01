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
        // AgentX targets a modern SDK. Executing the developer runtime no longer depends on the
        // legacy targetSdk 28 exemption: the primary backend is PRoot, and only the native
        // executables under `nativeLibraryDir` are started directly by Android, while guest
        // binaries go through the PRoot loader. `libtermux.so` (the pty JNI) is a native library
        // too, so the whole terminal path is inside the allowed execution location.
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

    // Extract packaged `jniLibs/<abi>/*.so` to `nativeLibraryDir` on install. This is what
    // makes `libproot.so` and `libproot_loader.so` real, executable files at runtime, which is
    // a hard requirement of the embedded developer runtime (and of the vendored pty JNI).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // OAuth client configuration. Client **secrets** are never read here and never
    // ship in the APK: the authorization code is exchanged by the broker URL below.
    // Owners override these with Gradle properties or environment variables.
    defaultConfig {
        fun oauthValue(property: String, environment: String, fallback: String): String =
            (providers.gradleProperty(property).orNull
                ?: providers.environmentVariable(environment).orNull
                ?: fallback)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")

        val redirectScheme = oauthValue("agentx.oauth.scheme", "AGENTX_OAUTH_SCHEME", "agentx")
        val redirectHost = oauthValue("agentx.oauth.host", "AGENTX_OAUTH_HOST", "oauth")
        val redirectPath = oauthValue("agentx.oauth.path", "AGENTX_OAUTH_PATH", "/callback")
        val httpsHost = oauthValue("agentx.oauth.httpsHost", "AGENTX_OAUTH_HTTPS_HOST", "oauth.agentx.app")

        manifestPlaceholders["oauthRedirectScheme"] = redirectScheme
        manifestPlaceholders["oauthRedirectHost"] = redirectHost
        manifestPlaceholders["oauthRedirectPath"] = redirectPath
        manifestPlaceholders["oauthHttpsHost"] = httpsHost

        buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"$redirectScheme\"")
        buildConfigField("String", "OAUTH_REDIRECT_HOST", "\"$redirectHost\"")
        buildConfigField("String", "OAUTH_REDIRECT_PATH", "\"$redirectPath\"")
        buildConfigField(
            "String",
            "OAUTH_GITHUB_CLIENT_ID",
            "\"${oauthValue("agentx.oauth.github.clientId", "AGENTX_OAUTH_GITHUB_CLIENT_ID", "")}\"",
        )
        buildConfigField(
            "String",
            "OAUTH_GITHUB_BROKER_URL",
            "\"${oauthValue("agentx.oauth.github.brokerUrl", "AGENTX_OAUTH_GITHUB_BROKER_URL", "")}\"",
        )
        buildConfigField(
            "String",
            "OAUTH_GITHUB_SCOPES",
            "\"${oauthValue("agentx.oauth.github.scopes", "AGENTX_OAUTH_GITHUB_SCOPES", "repo")}\"",
        )
        buildConfigField(
            "String",
            "OAUTH_SUPABASE_CLIENT_ID",
            "\"${oauthValue("agentx.oauth.supabase.clientId", "AGENTX_OAUTH_SUPABASE_CLIENT_ID", "")}\"",
        )
        buildConfigField(
            "String",
            "OAUTH_SUPABASE_BROKER_URL",
            "\"${oauthValue("agentx.oauth.supabase.brokerUrl", "AGENTX_OAUTH_SUPABASE_BROKER_URL", "")}\"",
        )
        buildConfigField(
            "String",
            "OAUTH_SUPABASE_SCOPES",
            "\"${oauthValue("agentx.oauth.supabase.scopes", "AGENTX_OAUTH_SUPABASE_SCOPES", "projects:read")}\"",
        )
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
    implementation(project(":codeintel"))

    // Android-facing layers.
    implementation(project(":ui"))
    // The embedded terminal: the primary Ubuntu developer runtime plus the legacy Termux
    // runtime it falls back to, and their shared vendored terminal libraries.
    implementation(project(":ubuntu-runtime"))
    implementation(project(":termux-runtime"))
    implementation(project(":codeintel-android"))
    implementation(project(":workspace-android"))
    implementation(project(":model-android"))
    implementation(project(":integrations-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

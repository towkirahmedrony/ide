plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    // Context is assembled from the workspace runtime and the conversation the
    // model layer already speaks.
    api(project(":workspace"))
    api(project(":model"))
    // Reuses the Tool System's secret redaction; no second secret policy.
    implementation(project(":tools"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

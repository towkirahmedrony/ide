plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    api(project(":model"))
    api(project(":tools"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

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
    // The execution loop drives conversation and tool-result context through
    // the Context Engine instead of a private message buffer.
    api(project(":context"))
    // Skills are resolved for an agent role through the central manager.
    api(project(":skills"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

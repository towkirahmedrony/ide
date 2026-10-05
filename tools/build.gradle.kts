plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    api(project(":workspace"))
    // Code intelligence tools are tools like any other: same registry, same
    // router, same permission policy.
    api(project(":codeintel"))
    // The git tools wrap the project's existing GitService rather than running
    // git themselves, so status/diff/log/commit stay the same implementation the
    // IDE already uses.
    api(project(":git"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

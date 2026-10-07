plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    // The GitHub push service implements the agent-facing GitPushService port and
    // resolves the active project through the existing GitProjectProvider, so the
    // authenticated push is not a second Git implementation.
    api(project(":git"))
    // Cloning a repository ends in opening it as a project, so the GitHub clone flow registers its
    // result through the existing WorkspaceManager instead of keeping a registry of its own.
    api(project(":workspace"))
    api(libs.kotlinx.coroutines.core)
    api(libs.org.eclipse.jgit)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

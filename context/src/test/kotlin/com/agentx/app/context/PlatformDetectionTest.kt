package com.agentx.app.context

import com.agentx.app.workspace.WorkspacePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic platform detection.
 *
 * Detection is a pure function of project evidence, so these cover the decisions
 * exhaustively without a filesystem. The contract under test is "a strong,
 * unambiguous signal or nothing": a wrong profile misleads a UI agent, so an
 * unrecognised or contradictory project must resolve to no profile at all.
 */
class PlatformDetectionTest {

    private fun evidence(
        root: List<String> = emptyList(),
        directories: Map<String, List<String>> = emptyMap(),
        snippets: Map<String, String> = emptyMap(),
        known: List<String> = emptyList(),
    ) = PlatformEvidence(
        listings = directories + (WorkspacePath.ROOT to root),
        snippets = snippets,
        knownFiles = known,
    )

    private val androidPlugin = """plugins { id("com.android.application") }"""
    private val composeDependency = """dependencies { implementation("androidx.compose.ui:ui") }"""

    @Test
    fun `every shipped profile has a stable id and usable guidance`() {
        assertEquals(
            setOf("WEB", "ANDROID_COMPOSE", "ANDROID_XML", "DESKTOP"),
            PlatformProfileId.entries.map { it.name }.toSet(),
        )
        assertEquals(PlatformProfileId.entries.toSet(), PlatformProfiles.ALL.map { it.id }.toSet())

        PlatformProfileId.entries.forEach { id ->
            val profile = PlatformProfiles.forId(id)
            assertEquals(id, profile.id)
            assertTrue(profile.instructions.isNotBlank(), "${id.name} has no guidance")
            assertTrue(
                profile.heading.startsWith(PlatformProfile.HEADING_PREFIX),
                "${id.name} heading is not under the stable prefix: ${profile.heading}",
            )
            assertTrue(
                profile.heading.contains(id.displayName),
                "${id.name} heading does not name the platform: ${profile.heading}",
            )
        }
    }

    @Test
    fun `a web project selects the web profile`() {
        val detection = PlatformDetector.detect(
            evidence(root = listOf("index.html", "styles.css", "README.md")),
        )

        assertEquals(PlatformProfileId.WEB, detection.platform)
        assertFalse(detection.ambiguous)
        assertTrue(detection.evidence.any { it.endsWith(".html") }, detection.evidence.toString())
    }

    @Test
    fun `a Compose project selects the Android Compose profile`() {
        val detection = PlatformDetector.detect(
            evidence(
                root = listOf("app", "build.gradle.kts", "settings.gradle.kts"),
                directories = mapOf(
                    "app/src/main" to listOf("AndroidManifest.xml", "java", "res"),
                ),
                snippets = mapOf(
                    "app/build.gradle.kts" to "$androidPlugin\n$composeDependency",
                ),
            ),
        )

        assertEquals(PlatformProfileId.ANDROID_COMPOSE, detection.platform)
        assertTrue(detection.evidence.any { it.contains("AndroidManifest.xml") }, detection.evidence.toString())
        assertTrue(detection.evidence.any { it.contains("compose") }, detection.evidence.toString())
    }

    @Test
    fun `an Android project with XML layouts selects the XML profile`() {
        val detection = PlatformDetector.detect(
            evidence(
                root = listOf("app"),
                directories = mapOf(
                    "app/src/main" to listOf("AndroidManifest.xml"),
                    "app/src/main/res/layout" to listOf("activity_main.xml", "fragment_home.xml"),
                ),
                snippets = mapOf("app/build.gradle.kts" to androidPlugin),
            ),
        )

        assertEquals(PlatformProfileId.ANDROID_XML, detection.platform)
        assertFalse(detection.ambiguous)
    }

    @Test
    fun `an Android project with neither Compose nor layouts is refused`() {
        val detection = PlatformDetector.detect(
            evidence(
                root = listOf("app"),
                directories = mapOf("app/src/main" to listOf("AndroidManifest.xml")),
                snippets = mapOf("app/build.gradle.kts" to androidPlugin),
            ),
        )

        assertNull(detection.platform)
        assertTrue(detection.ambiguous, "a manifest alone does not say how the UI is built")
    }

    @Test
    fun `a desktop project selects the desktop profile`() {
        val tauri = PlatformDetector.detect(evidence(root = listOf("src-tauri", "tauri.conf.json")))
        assertEquals(PlatformProfileId.DESKTOP, tauri.platform)

        val electron = PlatformDetector.detect(
            evidence(
                root = listOf("package.json", "index.html"),
                snippets = mapOf("package.json" to """{"name":"app","devDependencies":{"electron":"^30"}}"""),
            ),
        )
        assertEquals(PlatformProfileId.DESKTOP, electron.platform, "an Electron shell outranks its HTML")

        val dotnet = PlatformDetector.detect(evidence(root = listOf("Desktop.sln", "App.csproj")))
        assertEquals(PlatformProfileId.DESKTOP, dotnet.platform)
    }

    @Test
    fun `a project with no platform signal gets no profile`() {
        val detection = PlatformDetector.detect(
            evidence(root = listOf("README.md", "LICENSE"), known = listOf("main.kt", "util.kt")),
        )

        assertNull(detection.platform)
        assertFalse(detection.ambiguous, "nothing detected is not the same as contradictory")
        assertTrue(detection.reason.isNotBlank())
    }

    @Test
    fun `conflicting Android and desktop signals are refused`() {
        val detection = PlatformDetector.detect(
            evidence(root = listOf("tauri.conf.json", "src"), directories = mapOf("src/main" to listOf("AndroidManifest.xml"))),
        )

        assertNull(detection.platform)
        assertTrue(detection.ambiguous)
    }

    @Test
    fun `a package without UI sources is not treated as web`() {
        val detection = PlatformDetector.detect(
            evidence(
                root = listOf("package.json", "README.md"),
                snippets = mapOf("package.json" to """{"name":"lib","main":"dist/index.js"}"""),
                known = listOf("src/index.ts"),
            ),
        )

        assertNull(detection.platform, "a Node package is not evidence of a web UI")
    }

    @Test
    fun `an Android manifest outranks web assets bundled with it`() {
        val detection = PlatformDetector.detect(
            evidence(
                root = listOf("app"),
                directories = mapOf(
                    "app/src/main" to listOf("AndroidManifest.xml", "assets"),
                    "app/src/main/res/layout" to listOf("activity_main.xml"),
                    "app/src/main/assets" to listOf("index.html"),
                ),
                snippets = mapOf("app/build.gradle.kts" to "$androidPlugin\n$composeDependency"),
            ),
        )

        assertEquals(PlatformProfileId.ANDROID_COMPOSE, detection.platform)
    }
}

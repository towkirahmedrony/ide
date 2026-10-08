package com.agentx.app.context

import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

/**
 * What the project looks like, as far as platform detection is concerned.
 *
 * The probe fills this in from the workspace; the detector reads it as pure data.
 * Keeping the two apart means detection can be tested exhaustively without a
 * filesystem, and the only I/O in the whole path is one bounded probe.
 */
data class PlatformEvidence(
    /** Bounded child names per probed directory, keyed by workspace-relative directory. */
    val listings: Map<String, List<String>> = emptyMap(),
    /** Bounded contents of marker files, keyed by workspace-relative path. */
    val snippets: Map<String, String> = emptyMap(),
    /** Paths the editor already knows about (open and recent files). */
    val knownFiles: List<String> = emptyList(),
) {

    /** Child names of [directory]; the project root is [WorkspacePath.ROOT]. */
    fun filesIn(directory: String): List<String> = listings[directory].orEmpty()

    fun rootFiles(): List<String> = filesIn(WorkspacePath.ROOT)

    /** Every name seen anywhere, including the files the editor already has open. */
    val allNames: List<String> get() = listings.values.flatten() + knownFiles

    /** True when a file named [name] sits directly in the project root. */
    fun hasRootFile(name: String): Boolean =
        rootFiles().any { it.equals(name, ignoreCase = true) }

    /** True when any known file name ends with [suffix], e.g. ".html" or ".sln". */
    fun hasNameEndingWith(suffix: String): Boolean =
        allNames.any { it.endsWith(suffix, ignoreCase = true) }

    /** Marker files whose content contains [token], sorted for a stable report. */
    fun snippetsContaining(token: String): List<String> =
        snippets.filterValues { it.contains(token, ignoreCase = true) }.keys.sorted()
}

/**
 * The outcome of one detection pass.
 *
 * [platform] is null whenever the evidence is not strong enough, and [reason]
 * always says why, so "no profile" is never silent. [ambiguous] separates "the
 * project could be two different platforms" from "the project shows no platform
 * signal at all".
 */
data class PlatformDetection(
    val platform: PlatformProfileId?,
    val reason: String,
    val evidence: List<String> = emptyList(),
    val ambiguous: Boolean = false,
) {
    val detected: Boolean get() = platform != null
}

/**
 * Deterministic platform detection from project evidence.
 *
 * The rule is a strong, unambiguous signal or nothing. Nothing here asks a model,
 * reads a file, or guesses: a project that could be two things, or that shows no
 * platform signal at all, resolves to null so that no wrong guidance is injected.
 * A wrong profile actively misleads a UI agent, which is worse than no profile.
 *
 * ## Precedence
 *
 * 1. **Android** — an `AndroidManifest.xml` is definitive for "this app is
 *    Android", so it outranks web assets, which Android projects legitimately
 *    contain. Compose is chosen over XML when a Gradle file names Compose;
 *    otherwise XML layout resources decide.
 * 2. **Desktop** — an explicit desktop marker (Tauri, Electron, a .NET project
 *    file) outranks web signals, because desktop shells ship HTML.
 * 3. **Web** — UI source files (`.html`, `.css`, `.jsx`, `.tsx`, `.vue`,
 *    `.svelte`). A `package.json` alone is not a web-UI signal, so a Node library
 *    or a backend package does not collect web guidance.
 *
 * An Android-plus-desktop project is refused as ambiguous: those are two
 * independent, equally strong claims about what the user is building.
 */
object PlatformDetector {

    /** Manifest locations, root first, checked against the probe's listings. */
    val MANIFEST_PATHS: List<String> = listOf(
        "AndroidManifest.xml",
        "app/src/main/AndroidManifest.xml",
        "src/main/AndroidManifest.xml",
    )

    /** Directories whose child names are enough to reason about the project. */
    val LISTED_DIRECTORIES: List<String> = listOf(
        WorkspacePath.ROOT,
        "app/src/main",
        "src/main",
        "app/src/main/res/layout",
        "src/main/res/layout",
        "res/layout",
        "src-tauri",
    )

    /** Build and package files the probe reads, in order, until its read cap. */
    val CONTENT_MARKER_FILES: List<String> = listOf(
        "app/build.gradle.kts",
        "app/build.gradle",
        "build.gradle.kts",
        "build.gradle",
        "package.json",
        "tauri.conf.json",
        "src-tauri/tauri.conf.json",
        "Cargo.toml",
    )

    /** Gradle plugin id fragment that marks an Android module. */
    private const val ANDROID_PLUGIN_TOKEN = "com.android."

    /** Build-file token that marks a Compose UI. */
    private const val COMPOSE_TOKEN = "compose"

    private val LAYOUT_DIRECTORIES: List<String> = listOf(
        "app/src/main/res/layout",
        "src/main/res/layout",
        "res/layout",
    )

    private val DESKTOP_MARKER_FILES: List<String> = listOf(
        "tauri.conf.json",
        "src-tauri/tauri.conf.json",
        "electron-builder.json",
        "electron-builder.yml",
        "electron.vite.config.ts",
    )

    private val DESKTOP_PACKAGE_TOKENS: List<String> = listOf("\"electron\"", "\"@tauri-apps/api\"", "\"tauri\"")

    private val DESKTOP_CARGO_TOKENS: List<String> = listOf("tauri", "eframe", "egui")

    private val WEB_SUFFIXES: List<String> = listOf(".html", ".htm", ".css", ".jsx", ".tsx", ".vue", ".svelte")

    /** Longest evidence list reported; the decision never depends on more. */
    private const val MAX_EVIDENCE = 6

    fun detect(evidence: PlatformEvidence): PlatformDetection {
        val android = androidEvidence(evidence)
        val desktop = desktopEvidence(evidence)
        val web = webEvidence(evidence)

        if (android.isNotEmpty() && desktop.isNotEmpty()) {
            return PlatformDetection(
                platform = null,
                reason = "the project shows both Android and desktop signals",
                evidence = (android + desktop).take(MAX_EVIDENCE),
                ambiguous = true,
            )
        }

        if (android.isNotEmpty()) {
            val compose = evidence.snippetsContaining(COMPOSE_TOKEN)
            if (compose.isNotEmpty()) {
                return PlatformDetection(
                    platform = PlatformProfileId.ANDROID_COMPOSE,
                    reason = "an Android project whose Gradle build names Compose",
                    evidence = (android + compose.map { "$it: compose" }).take(MAX_EVIDENCE),
                )
            }
            val layouts = layoutEvidence(evidence)
            if (layouts.isNotEmpty()) {
                return PlatformDetection(
                    platform = PlatformProfileId.ANDROID_XML,
                    reason = "an Android project with XML layout resources and no Compose build signal",
                    evidence = (android + layouts).take(MAX_EVIDENCE),
                )
            }
            return PlatformDetection(
                platform = null,
                reason = "an Android project with neither a Compose build signal nor XML layout resources",
                evidence = android.take(MAX_EVIDENCE),
                ambiguous = true,
            )
        }

        if (desktop.isNotEmpty()) {
            return PlatformDetection(
                platform = PlatformProfileId.DESKTOP,
                reason = "an explicit desktop project marker",
                evidence = desktop.take(MAX_EVIDENCE),
            )
        }

        if (web.isNotEmpty()) {
            return PlatformDetection(
                platform = PlatformProfileId.WEB,
                reason = "web UI source files in the project",
                evidence = web.take(MAX_EVIDENCE),
            )
        }

        return PlatformDetection(
            platform = null,
            reason = "the project shows no platform signal this build recognises",
        )
    }

    private fun androidEvidence(evidence: PlatformEvidence): List<String> {
        val manifests = MANIFEST_PATHS.filter { hasFile(evidence, it) }
        val plugins = gradleSnippets(evidence)
            .filterValues { it.contains(ANDROID_PLUGIN_TOKEN, ignoreCase = true) }
            .keys
            .sorted()
            .map { "$it: $ANDROID_PLUGIN_TOKEN" }
        return manifests + plugins
    }

    private fun layoutEvidence(evidence: PlatformEvidence): List<String> =
        LAYOUT_DIRECTORIES.filter { directory ->
            evidence.filesIn(directory).any { it.endsWith(".xml", ignoreCase = true) }
        }

    private fun desktopEvidence(evidence: PlatformEvidence): List<String> {
        val found = mutableListOf<String>()
        found += DESKTOP_MARKER_FILES.filter { hasFile(evidence, it) }
        found += evidence.allNames
            .filter { it.endsWith(".csproj", ignoreCase = true) || it.endsWith(".sln", ignoreCase = true) }
            .take(2)

        evidence.snippets["package.json"]?.let { packageJson ->
            DESKTOP_PACKAGE_TOKENS
                .filter { packageJson.contains(it, ignoreCase = true) }
                .forEach { found += "package.json: $it" }
        }
        evidence.snippets["Cargo.toml"]?.let { cargo ->
            DESKTOP_CARGO_TOKENS
                .filter { cargo.contains(it, ignoreCase = true) }
                .forEach { found += "Cargo.toml: $it" }
        }
        return found
    }

    private fun webEvidence(evidence: PlatformEvidence): List<String> =
        WEB_SUFFIXES.flatMap { suffix ->
            evidence.allNames.filter { it.endsWith(suffix, ignoreCase = true) }.take(2)
        }

    private fun gradleSnippets(evidence: PlatformEvidence): Map<String, String> =
        evidence.snippets.filterKeys { it.endsWith(".gradle") || it.endsWith(".gradle.kts") }

    /** True when [path] exists, using only the directories the probe listed. */
    private fun hasFile(evidence: PlatformEvidence, path: String): Boolean {
        val directory = WorkspacePath.parent(path)
        val name = WorkspacePath.name(path)
        return evidence.filesIn(directory).any { it.equals(name, ignoreCase = true) }
    }
}

/**
 * The only I/O in platform detection: one bounded look at the project.
 *
 * Bounded three ways, and deliberately not recursive — a deep walk of a large
 * repository would cost more than the profile is worth. It lists a fixed set of
 * conventional directories, takes a fixed number of children from each, and reads
 * a fixed number of marker files up to a fixed size. Nothing it reads is ever put
 * into a prompt: the file contents only choose between profiles that AgentX ships.
 */
class ProjectPlatformProbe(
    private val maxDirectoryEntries: Int = MAX_DIRECTORY_ENTRIES,
    private val maxMarkerFiles: Int = MAX_MARKER_FILES,
    private val maxMarkerChars: Int = MAX_MARKER_CHARS,
) {

    suspend fun collect(
        fileSystem: WorkspaceFileSystem,
        knownFiles: List<String> = emptyList(),
    ): PlatformEvidence {
        val listings = linkedMapOf<String, List<String>>()
        PlatformDetector.LISTED_DIRECTORIES.forEach { directory ->
            val nodes = runCatching { fileSystem.list(directory) }.getOrNull()?.valueOrNull() ?: return@forEach
            if (nodes.isNotEmpty()) {
                listings[directory] = nodes.take(maxDirectoryEntries).map { it.name }
            }
        }

        val snippets = linkedMapOf<String, String>()
        for (path in PlatformDetector.CONTENT_MARKER_FILES) {
            if (snippets.size >= maxMarkerFiles) break
            val text = runCatching { fileSystem.readFile(path) }.getOrNull()?.valueOrNull() ?: continue
            snippets[path] = text.take(maxMarkerChars)
        }

        return PlatformEvidence(
            listings = listings,
            snippets = snippets,
            knownFiles = knownFiles.filter { it.isNotBlank() }.distinct().take(MAX_KNOWN_FILES),
        )
    }

    private companion object {
        /** Children taken from one directory. */
        const val MAX_DIRECTORY_ENTRIES = 200

        /** Marker files read for content. */
        const val MAX_MARKER_FILES = 6

        /** Characters kept from one marker file. */
        const val MAX_MARKER_CHARS = 1_200

        /** Editor-known paths carried as extra signals. */
        const val MAX_KNOWN_FILES = 40
    }
}

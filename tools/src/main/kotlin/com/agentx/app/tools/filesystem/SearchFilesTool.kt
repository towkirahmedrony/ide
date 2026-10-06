package com.agentx.app.tools.filesystem

import com.agentx.app.core.ForgeResult
import com.agentx.app.tools.Json
import com.agentx.app.tools.SecretRedactor
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolInputSchema
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolOutputSpec
import com.agentx.app.tools.ToolParameter
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.WorkspaceFileSystemResolver
import com.agentx.app.tools.missingWorkspace
import com.agentx.app.workspace.WorkspaceDirectory
import com.agentx.app.workspace.WorkspaceFile
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Searches file names and UTF-8 contents inside the opened workspace.
 *
 * It is built to stay fast on real projects, especially on phone storage:
 * - dependency/build/VCS folders (node_modules, .git, .next, …) are not walked;
 * - binary, minified, lock, secret (.env) and very large files are not read;
 * - the walk is breadth-first, so shallow source folders are searched first;
 * - it stops on its own after a soft time/file budget and returns what it
 *   found, instead of hitting the hard tool timeout and failing the whole turn;
 * - it cooperates with cancellation on every entry.
 *
 * Passing one of the skipped folders as `path` searches inside it explicitly.
 */
class SearchFilesTool(
    private val workspaces: WorkspaceFileSystemResolver,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Search files",
        description = "Searches file names and UTF-8 contents inside the opened workspace. " +
            "Skips dependency, build and VCS folders (node_modules, .git, .next, dist, build, …) and " +
            "binary/large files; pass such a folder as 'path' to search inside it. " +
            "Prefer a specific 'path' (for example 'src') on large projects.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    type = ToolParameterType.STRING,
                    description = "Text to search for in paths and file contents.",
                    required = true,
                ),
                ToolParameter(
                    name = "path",
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative directory to search. Defaults to the workspace root.",
                    required = false,
                ),
                ToolParameter(
                    name = "maxResults",
                    type = ToolParameterType.NUMBER,
                    description = "Maximum number of matches to return. Defaults to 50.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Matching paths and a short snippet when the query hits file contents."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.SEARCH,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fs = workspaces.resolve(context) ?: missingWorkspace(NAME)
        val query = input.string("query").orEmpty().trim()
        if (query.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "search_files needs a non-empty 'query'.",
                toolName = NAME,
            )
        }
        val root = input.string("path") ?: WorkspacePath.ROOT
        val maxResults = input.number("maxResults")?.toInt()?.coerceIn(1, MAX_RESULTS) ?: DEFAULT_MAX_RESULTS

        val scan = Scan(query, maxResults)
        search(fs, root, scan)

        val items = scan.matches.map { match ->
            Json.obj(
                buildMap {
                    put("path", Json.of(match.path))
                    put("kind", Json.of(match.kind))
                    match.snippet?.let { put("snippet", Json.of(it)) }
                },
            )
        }
        val note = scan.note()
        val display = buildString {
            if (scan.matches.isEmpty()) {
                append("No matches for \"").append(query).append("\"")
            } else {
                append(
                    scan.matches.joinToString("\n") { match ->
                        buildString {
                            append(match.path)
                            match.snippet?.let { append(": ").append(it) }
                        }
                    },
                )
            }
            if (note != null) append("\n[").append(note).append("]")
        }
        return ToolOutput(
            content = buildMap {
                put("query", Json.of(query))
                put("path", Json.of(root))
                put("matches", Json.array(items))
                put("count", Json.of(scan.matches.size))
                put("filesScanned", Json.of(scan.filesScanned))
                if (note != null) put("note", Json.of(note))
            },
            displayText = display,
        )
    }

    private suspend fun search(fs: WorkspaceFileSystem, root: String, scan: Scan) {
        val queue = ArrayDeque<String>()
        queue.addLast(root)

        while (queue.isNotEmpty() && !scan.full) {
            currentCoroutineContext().ensureActive()
            if (scan.overBudget()) return

            val directory = queue.removeFirst()
            val listing = when (val result = fs.list(directory)) {
                is ForgeResult.Success -> result.value
                is ForgeResult.Failure -> {
                    if (directory.isNotEmpty() && directory.contains(scan.query, ignoreCase = true)) {
                        scan.matches.add(Match(directory, "path", null))
                    }
                    continue
                }
            }
            scan.directoriesVisited++

            val subdirectories = mutableListOf<String>()
            for (node in listing) {
                if (scan.full) return
                currentCoroutineContext().ensureActive()
                if (scan.overBudget()) return

                val pathHit = node.path.contains(scan.query, ignoreCase = true)
                when (node) {
                    is WorkspaceDirectory -> {
                        if (node.name in IGNORED_DIRECTORIES) {
                            scan.skippedDirectories.add(node.name)
                            continue
                        }
                        if (pathHit) scan.matches.add(Match(node.path, "path", null))
                        subdirectories.add(node.path)
                    }

                    is WorkspaceFile -> {
                        if (pathHit) {
                            scan.matches.add(Match(node.path, "path", null))
                            continue
                        }
                        if (!shouldReadContent(node)) continue

                        scan.filesScanned++
                        val read = fs.readFile(node.path)
                        if (read is ForgeResult.Success) {
                            val content = read.value
                            if (looksBinary(content)) continue
                            val index = content.indexOf(scan.query, ignoreCase = true)
                            if (index >= 0) {
                                scan.matches.add(
                                    Match(node.path, "content", snippet(content, index, scan.query.length)),
                                )
                            }
                        }
                    }
                }
            }
            subdirectories.forEach { queue.addLast(it) }
        }
    }

    private fun shouldReadContent(file: WorkspaceFile): Boolean {
        val name = file.name.lowercase()
        if (name.startsWith(".env") && !name.endsWith(".example") && !name.endsWith(".sample")) return false
        if (name in SKIPPED_FILE_NAMES) return false
        if (name.endsWith(".min.js") || name.endsWith(".min.css")) return false
        val extension = name.substringAfterLast('.', "")
        if (extension in SKIPPED_EXTENSIONS) return false
        val size = file.sizeBytes
        if (size != null && size > MAX_CONTENT_BYTES) return false
        return true
    }

    private fun looksBinary(content: String): Boolean =
        content.take(BINARY_SNIFF_CHARS).indexOf('\u0000') >= 0

    /** Only the matched snippet is redacted; the whole file is never regex-scanned. */
    private fun snippet(content: String, index: Int, length: Int): String {
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + length + 40).coerceAtMost(content.length)
        return SecretRedactor.redactText(content.substring(start, end).replace('\n', ' ').trim())
    }

    private data class Match(val path: String, val kind: String, val snippet: String?)

    private class Scan(val query: String, val maxResults: Int) {
        val matches = mutableListOf<Match>()
        val skippedDirectories = linkedSetOf<String>()
        var filesScanned = 0
        var directoriesVisited = 0
        var stoppedReason: String? = null
        private val startedAt = System.currentTimeMillis()

        val full: Boolean get() = matches.size >= maxResults

        fun overBudget(): Boolean {
            if (stoppedReason != null) return true
            if (System.currentTimeMillis() - startedAt > SOFT_BUDGET_MILLIS) {
                stoppedReason = "time limit"
                return true
            }
            if (filesScanned >= MAX_FILES_READ) {
                stoppedReason = "file limit"
                return true
            }
            return false
        }

        /** A short, honest hint for the model; null when there is nothing to say. */
        fun note(): String? {
            val parts = mutableListOf<String>()
            stoppedReason?.let {
                parts.add(
                    "Search stopped early ($it) after reading $filesScanned files in " +
                        "$directoriesVisited folders; results may be incomplete. " +
                        "Use a more specific 'path' or query.",
                )
            }
            if (skippedDirectories.isNotEmpty()) {
                parts.add(
                    "Skipped folders: ${skippedDirectories.joinToString(", ")}. " +
                        "Pass one as 'path' to search inside it.",
                )
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    }

    companion object {
        const val NAME = "search_files"
        const val DEFAULT_MAX_RESULTS = 50
        const val MAX_RESULTS = 200

        /** Stop on our own well before the executor's hard file-operation timeout. */
        const val SOFT_BUDGET_MILLIS = 60_000L
        const val MAX_FILES_READ = 15_000
        const val MAX_CONTENT_BYTES = 512L * 1024L
        private const val BINARY_SNIFF_CHARS = 8_000

        private val IGNORED_DIRECTORIES = setOf(
            "node_modules", ".git", ".next", ".nuxt", ".svelte-kit", ".turbo", ".vercel", ".cache",
            ".parcel-cache", ".expo", ".gradle", ".idea", "build", "dist", "out", "coverage",
            "target", "__pycache__", ".venv", "venv", "Pods", ".dart_tool",
        )

        private val SKIPPED_FILE_NAMES = setOf(
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lockb", "composer.lock",
            "gradle.lockfile", "cargo.lock",
        )

        private val SKIPPED_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "svg", "bmp", "avif", "pdf",
            "zip", "gz", "tgz", "tar", "jar", "aar", "apk", "aab", "so", "class", "dex", "wasm",
            "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "mov", "webm", "wav",
            "map", "jks", "keystore", "pem", "key", "p12",
        )
    }
}

package com.agentx.app.tools

import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.WriteFileTool
import com.agentx.app.workspace.FileWorkspaceFileSystem
import com.agentx.app.workspace.WorkspaceFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `read_file` and `write_file` through the real registry, router, permission
 * policy and executor — the same path the agent uses — with a real on-disk
 * workspace, because the security contract (containment, symlink escape,
 * atomicity) only exists on a real filesystem.
 */
class WorkspaceFileToolsTest {

    private val readContext = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY),
    )

    private val writeContext = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
    )

    private fun router(fs: WorkspaceFileSystem?): ToolRouter {
        val registry = DefaultToolRegistry()
        BuiltinTools.filesystem(WorkspaceFileSystemResolver { fs }).forEach(registry::register)
        return DefaultToolRouter(registry = registry)
    }

    private fun tempDir(): File = Files.createTempDirectory("agentx-file-tools").toFile()

    private fun createSymlink(link: File, target: File): Boolean =
        runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess

    private fun read(fs: WorkspaceFileSystem, path: String) = runBlocking {
        router(fs).invoke(ReadFileTool.NAME, ToolInput(mapOf("path" to Json.of(path))), readContext)
    }

    private fun write(fs: WorkspaceFileSystem?, path: String, content: String, context: ToolExecutionContext) = runBlocking {
        router(fs).invoke(
            WriteFileTool.NAME,
            ToolInput(mapOf("path" to Json.of(path), "content" to Json.of(content))),
            context,
        )
    }

    // --- read_file ---------------------------------------------------------

    @Test
    fun `read_file returns the content of a workspace-relative file`() {
        val root = tempDir()
        try {
            File(root, "src").mkdirs()
            File(root, "src/App.kt").writeText("class App")

            val success = assertIs<ToolResult.Success>(read(FileWorkspaceFileSystem(root), "src/App.kt"))

            assertEquals("class App", success.output.content.stringOrNull("content"))
            assertEquals("src/App.kt", success.output.content.stringOrNull("path"))
            assertEquals(false, success.output.content.booleanOrNull("truncated"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read_file reports a missing file as a structured failure`() {
        val root = tempDir()
        try {
            val failure = assertIs<ToolResult.Failure>(read(FileWorkspaceFileSystem(root), "missing.txt"))

            assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
            assertEquals("NOT_FOUND", failure.error.details.stringOrNull("workspaceCode"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read_file refuses a directory when a file is expected`() {
        val root = tempDir()
        try {
            File(root, "src").mkdirs()

            val failure = assertIs<ToolResult.Failure>(read(FileWorkspaceFileSystem(root), "src"))

            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
            assertTrue(failure.error.message.contains("directory"), failure.error.message)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read_file rejects absolute paths, traversal and nested traversal`() {
        val root = tempDir()
        try {
            val cases = mapOf(
                "/etc/passwd" to "ABSOLUTE_PATH",
                "../secret.txt" to "PATH_TRAVERSAL",
                "src/../../etc/passwd" to "PATH_TRAVERSAL",
            )

            cases.forEach { (path, workspaceCode) ->
                val failure = assertIs<ToolResult.Failure>(read(FileWorkspaceFileSystem(root), path))
                assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code, "path '$path'")
                assertEquals(workspaceCode, failure.error.details.stringOrNull("workspaceCode"), "path '$path'")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read_file cannot follow a symlink out of the workspace`() {
        val root = tempDir()
        val outside = tempDir()
        try {
            File(outside, "secret.txt").writeText("top secret")
            if (!createSymlink(File(root, "link"), outside)) return

            val failure = assertIs<ToolResult.Failure>(read(FileWorkspaceFileSystem(root), "link/secret.txt"))

            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
            assertEquals("PATH_TRAVERSAL", failure.error.details.stringOrNull("workspaceCode"))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `read_file bounds an oversized file without modifying it`() {
        val root = tempDir()
        try {
            val big = "a".repeat(ReadFileTool.MAX_CONTENT_CHARS + 5_000)
            val file = File(root, "big.txt").apply { writeText(big) }

            val success = assertIs<ToolResult.Success>(read(FileWorkspaceFileSystem(root), "big.txt"))

            assertEquals(true, success.output.content.booleanOrNull("truncated"))
            assertEquals(ReadFileTool.MAX_CONTENT_CHARS, success.output.content.stringOrNull("content")?.length)
            assertEquals(big.length.toLong(), file.length(), "the file on disk is untouched")
        } finally {
            root.deleteRecursively()
        }
    }

    // --- write_file --------------------------------------------------------

    @Test
    fun `write_file creates a file and its missing parent directories`() {
        val root = tempDir()
        try {
            val success = assertIs<ToolResult.Success>(
                write(
                    FileWorkspaceFileSystem(root),
                    "src/new/File.kt",
                    "hello",
                    writeContext.copy(approval = ToolApproval.granted()),
                ),
            )

            assertEquals("src/new/File.kt", success.output.content.stringOrNull("path"))
            assertEquals("hello", File(root, "src/new/File.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write_file overwrites an existing file`() {
        val root = tempDir()
        try {
            File(root, "keep.txt").writeText("before")

            assertIs<ToolResult.Success>(
                write(
                    FileWorkspaceFileSystem(root),
                    "keep.txt",
                    "after",
                    writeContext.copy(approval = ToolApproval.granted()),
                ),
            )

            assertEquals("after", File(root, "keep.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write_file pauses for approval and writes nothing without a decision`() {
        val root = tempDir()
        try {
            val result = write(FileWorkspaceFileSystem(root), "src/New.kt", "x", writeContext)

            assertIs<ToolResult.ApprovalRequired>(result)
            assertFalse(File(root, "src/New.kt").exists(), "an unapproved write creates nothing")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write_file requires the workspace write grant`() {
        val root = tempDir()
        try {
            val result = write(
                FileWorkspaceFileSystem(root),
                "src/New.kt",
                "x",
                readContext.copy(approval = ToolApproval.granted()),
            )

            val failure = assertIs<ToolResult.Failure>(result)
            assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
            assertFalse(File(root, "src/New.kt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write_file rejects absolute paths and traversal`() {
        val root = tempDir()
        try {
            listOf("/tmp/evil.txt", "../evil.txt", "src/../../evil.txt").forEach { path ->
                val failure = assertIs<ToolResult.Failure>(
                    write(
                        FileWorkspaceFileSystem(root),
                        path,
                        "x",
                        writeContext.copy(approval = ToolApproval.granted()),
                    ),
                )
                assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code, "path '$path'")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write_file refuses git internals`() {
        val root = tempDir()
        try {
            File(root, ".git").mkdirs()
            File(root, ".git/config").writeText("original")

            val failure = assertIs<ToolResult.Failure>(
                write(
                    FileWorkspaceFileSystem(root),
                    ".git/config",
                    "hacked",
                    writeContext.copy(approval = ToolApproval.granted()),
                ),
            )

            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
            assertEquals("original", File(root, ".git/config").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write_file cannot escape the workspace through a symlink`() {
        val root = tempDir()
        val outside = tempDir()
        try {
            if (!createSymlink(File(root, "escape"), outside)) return

            val failure = assertIs<ToolResult.Failure>(
                write(
                    FileWorkspaceFileSystem(root),
                    "escape/evil.txt",
                    "x",
                    writeContext.copy(approval = ToolApproval.granted()),
                ),
            )

            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
            assertFalse(File(outside, "evil.txt").exists(), "nothing is written outside the workspace")
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `a failed write leaves the previous file intact`() {
        val root = tempDir()
        try {
            val directory = File(root, "sub").apply { mkdirs() }
            val target = File(directory, "keep.txt").apply { writeText("original") }

            if (!directory.setWritable(false)) return
            try {
                // A privileged test runner ignores the permission bits; skip rather than assert wrongly.
                if (runCatching { File(directory, "probe").createNewFile() }.getOrDefault(false)) {
                    File(directory, "probe").delete()
                    return
                }

                val result = write(
                    FileWorkspaceFileSystem(root),
                    "sub/keep.txt",
                    "changed",
                    writeContext.copy(approval = ToolApproval.granted()),
                )

                assertIs<ToolResult.Failure>(result)
                assertEquals("original", target.readText(), "an interrupted write must not corrupt the target")
            } finally {
                directory.setWritable(true)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // --- integration -------------------------------------------------------

    @Test
    fun `the workspace file tools are registered with valid schemas`() {
        val registry = DefaultToolRegistry()
        BuiltinTools.filesystem(WorkspaceFileSystemResolver { null }).forEach(registry::register)

        assertNotNull(registry.find(ReadFileTool.NAME))
        assertNotNull(registry.find(WriteFileTool.NAME))

        val schema = assertNotNull(registry.find(ReadFileTool.NAME)).definition.toJsonSchema()
        assertEquals("read_file", schema["name"]?.stringOrNull())
        assertEquals("object", schema["type"]?.stringOrNull())
        val required = assertIs<JsonValue.Arr>(schema["required"])
        assertTrue(required.items.any { it.stringOrNull() == ReadFileTool.ARG_PATH })
    }

    @Test
    fun `invalid arguments produce a structured error`() {
        val result = runBlocking {
            router(null).invoke(ReadFileTool.NAME, ToolInput(), readContext)
        }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertTrue(failure.error.details.containsKey("errors"))
    }

    @Test
    fun `the tools fail closed without an open workspace`() {
        val result = runBlocking {
            router(null).invoke(ReadFileTool.NAME, ToolInput(mapOf("path" to Json.of("a.txt"))), readContext)
        }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
    }
}

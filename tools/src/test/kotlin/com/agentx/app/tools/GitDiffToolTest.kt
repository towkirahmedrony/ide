package com.agentx.app.tools

import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.git.GitBranch
import com.agentx.app.git.GitChangeType
import com.agentx.app.git.GitDetection
import com.agentx.app.git.GitError
import com.agentx.app.git.GitErrorCode
import com.agentx.app.git.GitFileChange
import com.agentx.app.git.GitLogEntry
import com.agentx.app.git.GitOperationResult
import com.agentx.app.git.GitRemote
import com.agentx.app.git.GitResult
import com.agentx.app.git.GitService
import com.agentx.app.git.GitStatus
import com.agentx.app.tools.git.GitDiffTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `git_diff` through the real registry, router and permission policy, backed by
 * a fake [GitService]. The tool never selects a repository itself — it resolves
 * one from the workspace context — so these tests assert the structured result,
 * the bounded/redacted output, and that a pathspec cannot reach outside.
 */
class GitDiffToolTest {

    private val context = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY),
    )

    private class FakeGitService(
        private val diffText: String = "",
        private val statusResult: GitResult<GitStatus> = success(GitStatus(branch = "main")),
        private val diffResult: GitResult<String>? = null,
    ) : GitService {

        override suspend fun detect(workspaceId: String): GitResult<GitDetection> =
            success(GitDetection(isRepository = true))

        override suspend fun status(workspaceId: String): GitResult<GitStatus> = statusResult

        override suspend fun diff(workspaceId: String, staged: Boolean, paths: List<String>): GitResult<String> =
            diffResult ?: success(diffText)

        override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> = success(emptyList())

        override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> = success(emptyList())

        override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> = success(emptyList())

        override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))

        override suspend fun push(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(success = true, output = ""))
    }

    private fun router(git: GitService): ToolRouter {
        val registry = DefaultToolRegistry()
        BuiltinTools.git(git).forEach(registry::register)
        return DefaultToolRouter(registry = registry)
    }

    private fun invoke(git: GitService, arguments: JsonObject = emptyMap()): ToolResult = runBlocking {
        router(git).invoke(GitDiffTool.NAME, ToolInput(arguments), context)
    }

    // --- change shapes -----------------------------------------------------

    @Test
    fun `a clean repository reports no changes`() {
        val success = assertIs<ToolResult.Success>(invoke(FakeGitService(diffText = "")))

        assertEquals(true, success.output.content.booleanOrNull("clean"))
        assertEquals("(no changes)", success.output.content.stringOrNull("diff"))
        assertEquals(0.0, success.output.content.numberOrNull("changedFileCount"))
        assertEquals(0.0, success.output.content.numberOrNull("additions"))
        assertEquals(0.0, success.output.content.numberOrNull("deletions"))
    }

    @Test
    fun `a modified file is summarised with additions and deletions`() {
        val diff = """
            diff --git a/src/App.kt b/src/App.kt
            --- a/src/App.kt
            +++ b/src/App.kt
            @@ -1,2 +1,2 @@
            -old line
            +new line
        """.trimIndent()
        val status = GitStatus(
            branch = "main",
            changes = listOf(GitFileChange("src/App.kt", GitChangeType.MODIFIED, unstaged = true)),
        )

        val success = assertIs<ToolResult.Success>(invoke(FakeGitService(diffText = diff, statusResult = success(status))))

        assertEquals(1.0, success.output.content.numberOrNull("additions"))
        assertEquals(1.0, success.output.content.numberOrNull("deletions"))
        assertEquals(1.0, success.output.content.numberOrNull("changedFileCount"))
        val files = assertIs<JsonValue.Arr>(success.output.content.getValue("changedFiles"))
        assertEquals("src/App.kt", (files.items.single() as JsonValue.Obj).fields.stringOrNull("path"))
    }

    @Test
    fun `an added file is reported as additions`() {
        val diff = "diff --git a/new.txt b/new.txt\nnew file mode 100644\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+hello\n"
        val status = GitStatus(branch = "main", changes = listOf(GitFileChange("new.txt", GitChangeType.ADDED, staged = true)))

        val success = assertIs<ToolResult.Success>(invoke(FakeGitService(diffText = diff, statusResult = success(status))))

        assertEquals(1.0, success.output.content.numberOrNull("additions"))
        assertEquals(0.0, success.output.content.numberOrNull("deletions"))
    }

    @Test
    fun `a deleted file is reported as deletions`() {
        val diff = "diff --git a/gone.txt b/gone.txt\ndeleted file mode 100644\n--- a/gone.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-bye\n"
        val status = GitStatus(branch = "main", changes = listOf(GitFileChange("gone.txt", GitChangeType.DELETED, unstaged = true)))

        val success = assertIs<ToolResult.Success>(invoke(FakeGitService(diffText = diff, statusResult = success(status))))

        assertEquals(0.0, success.output.content.numberOrNull("additions"))
        assertEquals(1.0, success.output.content.numberOrNull("deletions"))
    }

    @Test
    fun `multiple changes are counted`() {
        val status = GitStatus(
            branch = "main",
            changes = listOf(
                GitFileChange("a.kt", GitChangeType.MODIFIED, unstaged = true),
                GitFileChange("b.kt", GitChangeType.ADDED, staged = true),
            ),
        )

        val success = assertIs<ToolResult.Success>(invoke(FakeGitService(statusResult = success(status))))

        assertEquals(2.0, success.output.content.numberOrNull("changedFileCount"))
    }

    // --- safety ------------------------------------------------------------

    @Test
    fun `a huge diff is truncated with metadata`() {
        val diff = ("+" + "x".repeat(99) + "\n").repeat(2_500)

        val success = assertIs<ToolResult.Success>(invoke(FakeGitService(diffText = diff)))

        assertEquals(true, success.output.content.booleanOrNull("truncated"))
        assertEquals(GitDiffTool.MAX_DIFF_CHARS, success.output.content.stringOrNull("diff")?.length)
    }

    @Test
    fun `a non-repository workspace surfaces a structured failure`() {
        val git = FakeGitService(statusResult = failure(GitError(GitErrorCode.NOT_A_REPOSITORY, "not a repo")))

        val failure = assertIs<ToolResult.Failure>(invoke(git))

        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
        assertEquals("NOT_A_REPOSITORY", failure.error.details.stringOrNull("gitCode"))
    }

    @Test
    fun `protected and unsafe pathspecs are refused`() {
        listOf(".git/config", "../etc/passwd", "/etc/passwd", ":magic").forEach { path ->
            val failure = assertIs<ToolResult.Failure>(
                invoke(FakeGitService(), mapOf("paths" to Json.array(Json.of(path)))),
            )
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code, "path '$path' must be refused")
        }
    }

    @Test
    fun `credentials in a diff are redacted`() {
        val diff = "diff --git a/x b/x\n+token=ghp_supersecret\n"

        val success = assertIs<ToolResult.Success>(invoke(FakeGitService(diffText = diff)))
        val text = success.output.content.stringOrNull("diff").orEmpty()

        assertFalse(text.contains("ghp_supersecret"), text)
        assertTrue(text.contains(SecretRedactor.REDACTED), text)
    }

    @Test
    fun `git_diff fails closed without a workspace in context`() {
        val result = runBlocking {
            router(FakeGitService()).invoke(
                GitDiffTool.NAME,
                ToolInput(),
                ToolExecutionContext(grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY)),
            )
        }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
    }

    // --- registration ------------------------------------------------------

    @Test
    fun `git_diff is registered with a valid schema and no arbitrary repository argument`() {
        val registry = DefaultToolRegistry()
        BuiltinTools.git(FakeGitService()).forEach(registry::register)

        val definition = assertNotNull(registry.find(GitDiffTool.NAME)).definition
        val schema = definition.toJsonSchema()
        assertEquals("git_diff", schema["name"]?.stringOrNull())
        assertEquals("object", schema["type"]?.stringOrNull())
        assertNull(definition.inputSchema.parameter("repository"))
        assertNull(definition.inputSchema.parameter("root"))
        assertNull(definition.inputSchema.parameter("path"))
    }
}

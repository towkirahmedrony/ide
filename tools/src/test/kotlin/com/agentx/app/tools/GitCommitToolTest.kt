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
import com.agentx.app.tools.git.GitCommitTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `git_commit` through the real registry, router and permission policy, backed by a fake
 * [GitService]. The tool resolves the repository from the workspace context — the model
 * never names one — stages only validated paths, refuses an empty commit, and is gated by
 * the existing ASK policy.
 */
class GitCommitToolTest {

    private val approvedContext = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
        approval = ToolApproval.granted(),
    )

    private val stagedStatus = success(
        GitStatus(
            branch = "main",
            changes = listOf(GitFileChange("a.kt", GitChangeType.MODIFIED, staged = true)),
        ),
    )

    private class FakeGitService(
        private val statusResult: GitResult<GitStatus> = success(GitStatus(branch = "main")),
        private val commitResult: GitResult<GitOperationResult> =
            success(GitOperationResult(true, "[main 1a2b3c4] Fix bug\n")),
    ) : GitService {

        val added = mutableListOf<String>()
        var commitMessage: String? = null
        var commitCalls = 0

        override suspend fun detect(workspaceId: String): GitResult<GitDetection> =
            success(GitDetection(isRepository = true))

        override suspend fun status(workspaceId: String): GitResult<GitStatus> = statusResult

        override suspend fun diff(workspaceId: String, staged: Boolean, paths: List<String>): GitResult<String> =
            success("")

        override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> = success(emptyList())

        override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, ""))

        override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> = success(emptyList())

        override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> = success(emptyList())

        override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> {
            added += paths
            return success(GitOperationResult(true, "added"))
        }

        override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> {
            commitCalls++
            commitMessage = message
            return commitResult
        }

        override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, ""))

        override suspend fun push(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, ""))
    }

    private fun router(git: GitService): ToolRouter {
        val registry = DefaultToolRegistry()
        registry.register(GitCommitTool(git))
        return DefaultToolRouter(registry = registry)
    }

    private fun invoke(
        git: GitService,
        arguments: JsonObject,
        context: ToolExecutionContext = approvedContext,
    ): ToolResult = runBlocking {
        router(git).invoke(GitCommitTool.NAME, ToolInput(arguments), context)
    }

    private fun messageArgs(message: String, paths: List<String> = emptyList()): JsonObject = buildMap {
        put("message", Json.of(message))
        if (paths.isNotEmpty()) put("paths", Json.array(paths.map { Json.of(it) }))
    }

    // --- valid commit ------------------------------------------------------

    @Test
    fun `a valid commit stages the paths and reports branch and sha`() {
        val git = FakeGitService(statusResult = stagedStatus)

        val success = assertIs<ToolResult.Success>(
            invoke(git, messageArgs("Fix bug", listOf("src/App.kt"))),
        )

        assertEquals(true, success.output.content.booleanOrNull("success"))
        assertEquals("main", success.output.content.stringOrNull("branch"))
        assertEquals("1a2b3c4", success.output.content.stringOrNull("commitSha"))
        assertEquals("Fix bug", success.output.content.stringOrNull("message"))
        assertEquals(listOf("src/App.kt"), git.added)
        assertEquals("Fix bug", git.commitMessage)
    }

    @Test
    fun `committing already-staged changes needs no paths`() {
        val git = FakeGitService(statusResult = stagedStatus)

        val success = assertIs<ToolResult.Success>(invoke(git, messageArgs("Fix bug")))

        assertEquals(true, success.output.content.booleanOrNull("success"))
        assertTrue(git.added.isEmpty(), "no path means stage nothing implicitly")
        assertEquals("Fix bug", git.commitMessage)
    }

    @Test
    fun `an unusual commit message is passed through unchanged`() {
        val message = "fix: handle 'quoted' and \$variables & newlines"
        val git = FakeGitService(statusResult = stagedStatus)

        invoke(git, messageArgs(message, listOf("a.kt")))

        assertEquals(message, git.commitMessage)
    }

    @Test
    fun `a message git did not format as expected yields no sha rather than a wrong one`() {
        val git = FakeGitService(
            statusResult = stagedStatus,
            commitResult = success(GitOperationResult(true, "created commit")),
        )

        val success = assertIs<ToolResult.Success>(invoke(git, messageArgs("Fix bug", listOf("a.kt"))))

        assertNull(success.output.content.stringOrNull("branch"))
        assertNull(success.output.content.stringOrNull("commitSha"))
    }

    // --- empty commit ------------------------------------------------------

    @Test
    fun `an empty commit is refused before git runs`() {
        val git = FakeGitService(statusResult = success(GitStatus(branch = "main")))

        val failure = assertIs<ToolResult.Failure>(invoke(git, messageArgs("Fix bug")))

        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code, failure.error.message)
        assertEquals(0, git.commitCalls, "git commit must not run for an empty commit")
    }

    @Test
    fun `a blank commit message is refused before git runs`() {
        val git = FakeGitService(statusResult = stagedStatus)

        val failure = assertIs<ToolResult.Failure>(invoke(git, messageArgs("   ", listOf("a.kt"))))

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertEquals(0, git.commitCalls)
    }

    // --- workspace and path safety ----------------------------------------

    @Test
    fun `commit fails closed without a workspace in context`() {
        val git = FakeGitService(statusResult = stagedStatus)

        val failure = assertIs<ToolResult.Failure>(
            invoke(git, messageArgs("Fix bug", listOf("a.kt")), approvedContext.copy(workspaceId = null)),
        )

        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
        assertEquals(0, git.commitCalls)
    }

    @Test
    fun `a repository outside the authorized workspace surfaces a structured failure`() {
        val git = FakeGitService(
            statusResult = failure(GitError(GitErrorCode.NO_WORKSPACE, "The active project changed.")),
        )

        val failure = assertIs<ToolResult.Failure>(invoke(git, messageArgs("Fix bug", listOf("a.kt"))))

        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
        assertEquals(0, git.commitCalls)
    }

    @Test
    fun `path traversal and protected paths are refused`() {
        val git = FakeGitService(statusResult = stagedStatus)

        listOf("../etc/passwd", "/etc/passwd", ".git/config", "src/../../etc/passwd", ":magic").forEach { path ->
            val failure = assertIs<ToolResult.Failure>(
                invoke(git, messageArgs("Fix bug", listOf(path))),
                "path '$path' must be refused",
            )
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code, "path '$path'")
        }
        assertEquals(0, git.commitCalls)
        assertTrue(git.added.isEmpty())
    }

    // --- approval ----------------------------------------------------------

    @Test
    fun `commit pauses for approval before git runs`() {
        val git = FakeGitService(statusResult = stagedStatus)

        val parked = assertIs<ToolResult.ApprovalRequired>(
            invoke(git, messageArgs("Fix bug", listOf("a.kt")), approvedContext.copy(approval = null)),
        )

        assertEquals(GitCommitTool.NAME, parked.toolName)
        assertEquals(0, git.commitCalls)
    }

    @Test
    fun `a denied commit never runs`() {
        val git = FakeGitService(statusResult = stagedStatus)

        val failure = assertIs<ToolResult.Failure>(
            invoke(git, messageArgs("Fix bug", listOf("a.kt")), approvedContext.copy(approval = ToolApproval.denied())),
        )

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertEquals(0, git.commitCalls)
    }

    @Test
    fun `commit without the git write grant is denied`() {
        val git = FakeGitService(statusResult = stagedStatus)

        val failure = assertIs<ToolResult.Failure>(
            invoke(
                git,
                messageArgs("Fix bug", listOf("a.kt")),
                approvedContext.copy(grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY)),
            ),
        )

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertEquals(0, git.commitCalls)
    }
}

package com.agentx.app.tools.verification

import com.agentx.app.core.success
import com.agentx.app.core.verification.VerificationCategory
import com.agentx.app.git.GitBranch
import com.agentx.app.git.GitChangeType
import com.agentx.app.git.GitDetection
import com.agentx.app.git.GitFileChange
import com.agentx.app.git.GitLogEntry
import com.agentx.app.git.GitOperationResult
import com.agentx.app.git.GitRemote
import com.agentx.app.git.GitResult
import com.agentx.app.git.GitService
import com.agentx.app.git.GitStatus
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Json
import com.agentx.app.tools.JsonObject
import com.agentx.app.tools.ToolApproval
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.booleanOrNull
import com.agentx.app.tools.git.GitCommitTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pre-write guard rails: branch, unintended changes, protected paths and
 * secret detection. The guard is pure, so each rule is asserted directly; the
 * last test proves `git_commit` actually enforces the secret rule.
 */
class CommitSafetyGuardTest {

    @Test
    fun `only main may be pushed`() {
        assertNull(CommitSafetyGuard.branchAllowsPush("main"))

        val violation = assertNotNull(CommitSafetyGuard.branchAllowsPush("feature/x"))
        assertEquals(VerificationCategory.WORKSPACE_SECURITY_FAILURE, violation.category)

        assertNotNull(CommitSafetyGuard.branchAllowsPush(null))
    }

    @Test
    fun `changes that are not part of the task are surfaced`() {
        val status = GitStatus(
            branch = "main",
            changes = listOf(
                GitFileChange("src/mine.kt", GitChangeType.MODIFIED, staged = true),
                GitFileChange("src/someone-elses.kt", GitChangeType.MODIFIED, staged = true),
            ),
        )

        val violations = CommitSafetyGuard.unintendedStagedChanges(status, intended = setOf("src/mine.kt"))

        assertEquals(1, violations.size)
        assertEquals("src/someone-elses.kt", violations.single().location)
    }

    @Test
    fun `no intended set means nothing is flagged as unrelated`() {
        val status = GitStatus(
            branch = "main",
            changes = listOf(GitFileChange("a.kt", GitChangeType.MODIFIED, staged = true)),
        )

        assertTrue(CommitSafetyGuard.unintendedStagedChanges(status, intended = emptySet()).isEmpty())
    }

    @Test
    fun `protected and escaping paths are refused`() {
        listOf(".git/config", "src/../../etc/passwd", "/etc/passwd", ":magic", "").forEach { path ->
            assertNotNull(CommitSafetyGuard.unsafePath(path), "path '$path' must be unsafe")
        }
        assertNull(CommitSafetyGuard.unsafePath("src/app/Main.kt"))
    }

    @Test
    fun `a secret in a diff becomes a secret violation`() {
        val diff = "+++ b/x.kt\n@@ -0,0 +1 @@\n+token = \"ghp_1234567890abcdef\""

        val violation = CommitSafetyGuard.scanForSecrets(diff).single()

        assertEquals(VerificationCategory.SECRET_DETECTED, violation.category)
        assertTrue(violation.location?.contains("x.kt") == true)
        assertTrue(violation.location?.contains("ghp_") != true, "the value must never be in the location")
    }

    // --- enforcement in git_commit -----------------------------------------

    private class FakeGitService(private val diffText: String) : GitService {
        var commitCalls = 0

        override suspend fun detect(workspaceId: String): GitResult<GitDetection> =
            success(GitDetection(isRepository = true))

        override suspend fun status(workspaceId: String): GitResult<GitStatus> = success(
            GitStatus(
                branch = "main",
                changes = listOf(GitFileChange("x.kt", GitChangeType.MODIFIED, staged = true)),
            ),
        )

        override suspend fun diff(workspaceId: String, staged: Boolean, paths: List<String>): GitResult<String> =
            success(diffText)

        override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> = success(emptyList())

        override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, ""))

        override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> =
            success(emptyList())

        override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> = success(emptyList())

        override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> =
            success(GitOperationResult(true, ""))

        override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> {
            commitCalls += 1
            return success(GitOperationResult(true, "[main 1a2b3c4] x\n"))
        }

        override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, ""))

        override suspend fun push(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, ""))
    }

    private fun commit(git: GitService): ToolResult {
        val registry = DefaultToolRegistry().apply { register(GitCommitTool(git)) }
        val context = ToolExecutionContext(
            workspaceId = "w1",
            grantedPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
            approval = ToolApproval.granted(),
        )
        val arguments: JsonObject = mapOf("message" to Json.of("fix"), "paths" to Json.array(Json.of("x.kt")))
        return runBlocking {
            DefaultToolRouter(registry).invoke(GitCommitTool.NAME, ToolInput(arguments), context)
        }
    }

    @Test
    fun `git_commit refuses to commit a staged secret`() {
        val git = FakeGitService("+++ b/x.kt\n@@ -0,0 +1 @@\n+api_key = \"abcdefghijklmno\"")

        val failure = assertIs<ToolResult.Failure>(commit(git))

        assertEquals(ToolErrorCode.SECRET_DETECTED, failure.error.code)
        assertEquals(0, git.commitCalls, "git commit must not run when a secret is present")
    }

    @Test
    fun `git_commit proceeds when the staged change is clean`() {
        val git = FakeGitService("+++ b/x.kt\n@@ -0,0 +1 @@\n+val x = 1")

        val success = assertIs<ToolResult.Success>(commit(git))

        assertEquals(true, success.output.content.booleanOrNull("success"))
        assertEquals(1, git.commitCalls)
    }
}

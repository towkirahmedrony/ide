package com.agentx.app.tools

import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.git.GitPushError
import com.agentx.app.git.GitPushFailure
import com.agentx.app.git.GitPushResult
import com.agentx.app.git.GitPushService
import com.agentx.app.git.GitPushSuccess
import com.agentx.app.tools.git.GitPushTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `git_push` through the real registry, router and permission policy, backed by a fake
 * [GitPushService]. The tool never chooses a repository, a remote, a branch or a
 * credential — it resolves all of them from the workspace context and the service — so
 * these tests assert the approval gate, the structured result, and that no argument can
 * widen what a push may do.
 */
class GitPushToolTest {

    private val granted = ToolApproval.granted()

    private val approvedContext = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
        approval = granted,
    )

    private val pausedContext = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
    )

    private class FakeGitPushService(
        private val result: GitPushResult = success(
            GitPushSuccess(remote = "origin", branch = "main", commitSha = "abc1234", message = "Pushed 'main'."),
        ),
    ) : GitPushService {

        val workspaces = mutableListOf<String>()
        val targets = mutableListOf<String>()

        override suspend fun push(workspaceId: String, targetBranch: String): GitPushResult {
            workspaces += workspaceId
            targets += targetBranch
            return result
        }
    }

    private fun router(push: GitPushService): ToolRouter {
        val registry = DefaultToolRegistry()
        registry.register(GitPushTool(push))
        return DefaultToolRouter(registry = registry)
    }

    private fun invoke(push: GitPushService, context: ToolExecutionContext): ToolResult = runBlocking {
        router(push).invoke(GitPushTool.NAME, ToolInput(), context)
    }

    // --- success -----------------------------------------------------------

    @Test
    fun `a granted push returns the remote, branch and commit`() {
        val push = FakeGitPushService()

        val success = assertIs<ToolResult.Success>(invoke(push, approvedContext))

        assertEquals(true, success.output.content.booleanOrNull("success"))
        assertEquals("origin", success.output.content.stringOrNull("remote"))
        assertEquals("main", success.output.content.stringOrNull("branch"))
        assertEquals("abc1234", success.output.content.stringOrNull("commitSha"))
        assertEquals(listOf("w1"), push.workspaces, "the tool pushes the workspace from context")
        assertEquals(listOf("main"), push.targets, "the tool always targets main")
    }

    @Test
    fun `a successful push never carries a credential`() {
        val push = FakeGitPushService(
            success(
                GitPushSuccess(
                    remote = "origin",
                    branch = "main",
                    commitSha = "abc1234",
                    message = "Pushed with token=gho_supersecret to origin",
                ),
            ),
        )

        val success = assertIs<ToolResult.Success>(invoke(push, approvedContext))
        val message = success.output.content.stringOrNull("message").orEmpty()

        assertFalse(message.contains("gho_supersecret"), message)
        assertTrue(message.contains(SecretRedactor.REDACTED), message)
    }

    // --- approval ----------------------------------------------------------

    @Test
    fun `push pauses for approval before the service runs`() {
        val push = FakeGitPushService()

        val parked = assertIs<ToolResult.ApprovalRequired>(invoke(push, pausedContext))

        assertEquals(GitPushTool.NAME, parked.toolName)
        assertTrue(push.workspaces.isEmpty(), "a tool that ASKed must not have pushed")
    }

    @Test
    fun `a denied push never reaches the service`() {
        val push = FakeGitPushService()

        val result = invoke(
            push,
            approvedContext.copy(approval = ToolApproval.denied("no")),
        )
        val failure = assertIs<ToolResult.Failure>(result)

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertTrue(push.workspaces.isEmpty())
    }

    @Test
    fun `push without the git write grant is denied`() {
        val push = FakeGitPushService()

        val result = invoke(
            push,
            approvedContext.copy(grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY)),
        )
        val failure = assertIs<ToolResult.Failure>(result)

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertTrue(push.workspaces.isEmpty())
    }

    @Test
    fun `push fails closed without a workspace in context`() {
        val push = FakeGitPushService()

        val result = invoke(push, approvedContext.copy(workspaceId = null))
        val failure = assertIs<ToolResult.Failure>(result)

        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
        assertTrue(push.workspaces.isEmpty())
    }

    // --- structured push failures -----------------------------------------

    @Test
    fun `every push failure maps to a structured code and keeps its category`() {
        val cases = listOf(
            GitPushFailure.AUTHENTICATION to ToolErrorCode.PERMISSION_DENIED,
            GitPushFailure.AUTHORIZATION to ToolErrorCode.PERMISSION_DENIED,
            GitPushFailure.NO_CONNECTION to ToolErrorCode.PERMISSION_DENIED,
            GitPushFailure.CREDENTIAL_UNAVAILABLE to ToolErrorCode.PERMISSION_DENIED,
            GitPushFailure.BRANCH_MISMATCH to ToolErrorCode.INVALID_ARGUMENTS,
            GitPushFailure.FORCE_PUSH_FORBIDDEN to ToolErrorCode.INVALID_ARGUMENTS,
            GitPushFailure.REMOTE_NOT_GITHUB to ToolErrorCode.INVALID_ARGUMENTS,
            GitPushFailure.NOT_A_REPOSITORY to ToolErrorCode.INVALID_ARGUMENTS,
            GitPushFailure.WORKSPACE_UNAVAILABLE to ToolErrorCode.WORKSPACE_UNAVAILABLE,
            GitPushFailure.NETWORK to ToolErrorCode.EXECUTION_FAILED,
            GitPushFailure.REPOSITORY_NOT_FOUND to ToolErrorCode.EXECUTION_FAILED,
            GitPushFailure.REMOTE_REJECTED to ToolErrorCode.EXECUTION_FAILED,
            GitPushFailure.NON_FAST_FORWARD to ToolErrorCode.EXECUTION_FAILED,
            GitPushFailure.RATE_LIMITED to ToolErrorCode.EXECUTION_FAILED,
            GitPushFailure.CANCELLED to ToolErrorCode.EXECUTION_FAILED,
            GitPushFailure.UNKNOWN to ToolErrorCode.EXECUTION_FAILED,
        )

        cases.forEach { (category, expected) ->
            val push = FakeGitPushService(failure(GitPushError(category, "boom")))
            val result = invoke(push, approvedContext)
            val failure = assertIs<ToolResult.Failure>(result)

            assertEquals(expected, failure.error.code, "category $category")
            assertEquals(category.name, failure.error.details.stringOrNull("pushCode"), "category $category")
        }
    }

    @Test
    fun `a force push is refused and never attempted`() {
        val push = FakeGitPushService(
            failure(GitPushError(GitPushFailure.FORCE_PUSH_FORBIDDEN, "force push is forbidden")),
        )

        val failure = assertIs<ToolResult.Failure>(invoke(push, approvedContext))

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertEquals("FORCE_PUSH_FORBIDDEN", failure.error.details.stringOrNull("pushCode"))
    }

    @Test
    fun `a non-fast-forward rejection is reported without forcing`() {
        val push = FakeGitPushService(
            failure(GitPushError(GitPushFailure.NON_FAST_FORWARD, "rejected non-fast-forward")),
        )

        val failure = assertIs<ToolResult.Failure>(invoke(push, approvedContext))

        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
        assertEquals("NON_FAST_FORWARD", failure.error.details.stringOrNull("pushCode"))
        assertEquals(listOf("w1"), push.workspaces, "the rejection is a normal push, not a retry with force")
    }

    // --- registration and schema ------------------------------------------

    @Test
    fun `git_push is registered with no writable arguments`() {
        val registry = DefaultToolRegistry()
        registry.register(GitPushTool(FakeGitPushService()))

        val definition = assertNotNull(registry.find(GitPushTool.NAME)).definition
        assertEquals("git_push", definition.name)
        assertEquals(ToolPermissionDecision.ASK, definition.permission)
        assertEquals(setOf(ToolPermissionLevel.GIT_WRITE), definition.requiredPermissions)
        assertTrue(definition.inputSchema.parameters.isEmpty(), "git_push must take no arguments")

        // Nothing the model could use to pick a repository, remote, branch, refspec,
        // credential or a destructive mode is part of the schema.
        listOf("repository", "path", "root", "remote", "url", "branch", "ref", "refspec", "force", "delete", "tags", "token")
            .forEach { argument -> assertNull(definition.inputSchema.parameter(argument), argument) }

        val schema = definition.toJsonSchema()
        assertEquals("git_push", schema["name"]?.stringOrNull())
        assertEquals("object", schema["type"]?.stringOrNull())
    }
}

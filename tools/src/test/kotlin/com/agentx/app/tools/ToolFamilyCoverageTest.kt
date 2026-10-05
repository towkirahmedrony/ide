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
import com.agentx.app.tools.execution.RunCommandTool
import com.agentx.app.tools.git.GitBranchesTool
import com.agentx.app.tools.git.GitCommitTool
import com.agentx.app.tools.git.GitDiffTool
import com.agentx.app.tools.git.GitLogTool
import com.agentx.app.tools.git.GitStatusTool
import com.agentx.app.tools.web.DuckDuckGoWebSearchProvider
import com.agentx.app.tools.web.HttpGetClient
import com.agentx.app.tools.web.HttpGetResponse
import com.agentx.app.tools.web.WebFetchTool
import com.agentx.app.tools.web.WebSearchItem
import com.agentx.app.tools.web.WebSearchProvider
import com.agentx.app.tools.web.WebSearchTool
import com.agentx.app.workspace.ProcessExecutor
import com.agentx.app.workspace.ProcessOutput
import com.agentx.app.workspace.ProcessRequest
import com.agentx.app.workspace.ProcessResult
import com.agentx.app.workspace.ProcessState
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The three new tool families — execution, git, web — go through the real
 * registry, router, permission policy and executor, exactly as the agent uses
 * them. Every tool is a real adapter over a port, so these tests inject fakes at
 * the port and assert on the structured [ToolResult].
 */
class ToolFamilyCoverageTest {

    // --- command execution -------------------------------------------------

    private class FakeExecutor(
        val state: ProcessState = ProcessState.COMPLETED,
        val exitCode: Int? = 0,
        val stdout: String = "",
        val stderr: String = "",
        val errorMessage: String? = null,
    ) : ProcessExecutor {
        override val allowsArbitraryExecution: Boolean = false
        val requests = mutableListOf<ProcessRequest>()

        override suspend fun execute(request: ProcessRequest, onOutput: ((ProcessOutput) -> Unit)?): ProcessResult {
            requests += request
            return ProcessResult(
                request = request,
                state = state,
                output = ProcessOutput(stdout = stdout, stderr = stderr),
                exitCode = exitCode,
                error = errorMessage?.let { WorkspaceError(WorkspaceErrorCode.PROCESS_FAILED, it) },
            )
        }
    }

    private fun router(vararg tools: Tool, policy: ToolPermissionPolicy = ToolPermissionPolicy.default()): ToolRouter {
        val registry = DefaultToolRegistry()
        tools.forEach(registry::register)
        return DefaultToolRouter(registry = registry, policy = policy)
    }

    private val hostPaths = WorkspaceHostPathResolver { "/home/user/project" }

    private val commandContext = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.COMMAND_EXECUTION),
    )

    @Test
    fun `run_command is registered and runs through the executor`() = runBlocking {
        val executor = FakeExecutor(stdout = "hello\n")
        val result = router(RunCommandTool(executor, hostPaths)).invoke(
            RunCommandTool.NAME,
            ToolInput(mapOf("command" to Json.of("echo hello"))),
            commandContext.copy(approval = ToolApproval.granted()),
        )
        val success = assertIs<ToolResult.Success>(result)
        assertEquals("hello\n", success.output.content["stdout"]?.stringOrNull())
        assertEquals(0.0, success.output.content["exitCode"]?.numberOrNull())
        // The command was interpreted by a shell in the workspace directory.
        val request = executor.requests.single()
        assertEquals("sh", request.command)
        assertEquals(listOf("-c", "echo hello"), request.arguments)
        assertEquals("/home/user/project", request.workingDirectory)
    }

    @Test
    fun `run_command reports a non-zero exit as structured output`() = runBlocking {
        val executor = FakeExecutor(exitCode = 1, stderr = "boom\n")
        val result = router(RunCommandTool(executor, hostPaths)).invoke(
            RunCommandTool.NAME,
            ToolInput(mapOf("command" to Json.of("false"))),
            commandContext.copy(approval = ToolApproval.granted()),
        )
        val success = assertIs<ToolResult.Success>(result)
        assertEquals(1.0, success.output.content["exitCode"]?.numberOrNull())
        assertEquals(false, success.output.content["success"]?.booleanOrNull())
        assertEquals("boom\n", success.output.content["stderr"]?.stringOrNull())
    }

    @Test
    fun `run_command maps a timeout to the timeout error code`() = runBlocking {
        val executor = FakeExecutor(state = ProcessState.CANCELLED, errorMessage = "Process timed out.")
        val result = router(RunCommandTool(executor, hostPaths)).invoke(
            RunCommandTool.NAME,
            ToolInput(mapOf("command" to Json.of("sleep 100"))),
            commandContext.copy(approval = ToolApproval.granted()),
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.TIMEOUT, failure.error.code)
    }

    @Test
    fun `run_command without an execution grant is denied`() = runBlocking {
        val result = router(RunCommandTool(FakeExecutor(), hostPaths)).invoke(
            RunCommandTool.NAME,
            ToolInput(mapOf("command" to Json.of("ls"))),
            ToolExecutionContext(workspaceId = "w1", grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY)),
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
    }

    @Test
    fun `run_command pauses for approval before executing`() = runBlocking {
        val executor = FakeExecutor()
        val result = router(RunCommandTool(executor, hostPaths)).invoke(
            RunCommandTool.NAME,
            ToolInput(mapOf("command" to Json.of("rm -rf build"))),
            commandContext,
        )
        assertIs<ToolResult.ApprovalRequired>(result)
        assertTrue(executor.requests.isEmpty(), "a tool that ASKed must not have run")
    }

    @Test
    fun `run_command fails closed without a workspace host path`() = runBlocking {
        val result = router(RunCommandTool(FakeExecutor(), WorkspaceHostPathResolver { null })).invoke(
            RunCommandTool.NAME,
            ToolInput(mapOf("command" to Json.of("ls"))),
            commandContext.copy(approval = ToolApproval.granted()),
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
    }

    // --- git ---------------------------------------------------------------

    private open class FakeGitService : GitService {
        val added = mutableListOf<String>()
        var commitMessage: String? = null

        override suspend fun detect(workspaceId: String): GitResult<GitDetection> =
            success(GitDetection(isRepository = true, root = "/home/user/project"))

        override suspend fun status(workspaceId: String): GitResult<GitStatus> = success(
            GitStatus(
                branch = "main",
                upstream = "origin/main",
                changes = listOf(GitFileChange("src/App.kt", GitChangeType.MODIFIED, unstaged = true)),
            ),
        )

        override suspend fun diff(workspaceId: String, staged: Boolean, paths: List<String>): GitResult<String> =
            success("--- a\n+++ b\n")

        override suspend fun branches(workspaceId: String): GitResult<List<GitBranch>> =
            success(listOf(GitBranch("main", current = true)))

        override suspend fun checkout(workspaceId: String, branch: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, "switched"))

        override suspend fun log(workspaceId: String, limit: Int): GitResult<List<GitLogEntry>> =
            success(listOf(GitLogEntry("deadbeef", "deadbee", "Ada", "2026-01-01", "initial")))

        override suspend fun remotes(workspaceId: String): GitResult<List<GitRemote>> =
            success(listOf(GitRemote("origin", "git@example.com:x/y.git")))

        override suspend fun add(workspaceId: String, paths: List<String>): GitResult<GitOperationResult> {
            added += paths
            return success(GitOperationResult(true, "added"))
        }

        override suspend fun commit(workspaceId: String, message: String): GitResult<GitOperationResult> {
            commitMessage = message
            return success(GitOperationResult(true, "committed"))
        }

        override suspend fun pull(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, "pulled"))

        override suspend fun push(workspaceId: String): GitResult<GitOperationResult> =
            success(GitOperationResult(true, "pushed"))
    }

    private val gitContext = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY),
    )

    @Test
    fun `every git tool is registered and resolves by name`() {
        val registry = DefaultToolRegistry()
        BuiltinTools.git(FakeGitService()).forEach(registry::register)
        listOf(
            GitStatusTool.NAME,
            GitDiffTool.NAME,
            GitLogTool.NAME,
            GitBranchesTool.NAME,
            GitCommitTool.NAME,
        ).forEach { name -> assertTrue(registry.find(name) != null, "'$name' must be registered") }
    }

    @Test
    fun `git_read tools return structured results`() = runBlocking {
        val git = FakeGitService()
        val router = router(GitStatusTool(git), GitLogTool(git), GitBranchesTool(git))

        val status = assertIs<ToolResult.Success>(router.invoke(GitStatusTool.NAME, ToolInput(), gitContext))
        assertEquals("main", status.output.content["branch"]?.stringOrNull())

        val log = assertIs<ToolResult.Success>(router.invoke(GitLogTool.NAME, ToolInput(), gitContext))
        assertEquals(1.0, log.output.content["count"]?.numberOrNull())

        val branches = assertIs<ToolResult.Success>(router.invoke(GitBranchesTool.NAME, ToolInput(), gitContext))
        assertEquals(1.0, branches.output.content["count"]?.numberOrNull())
    }

    @Test
    fun `git_commit stages the requested paths and is approval gated`() = runBlocking {
        val git = FakeGitService()
        val tool = GitCommitTool(git)
        val router = router(tool)
        val commitContext = ToolExecutionContext(
            workspaceId = "w1",
            grantedPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
        )

        // Without a decision the call is parked, and git was not touched.
        assertIs<ToolResult.ApprovalRequired>(
            router.invoke(
                GitCommitTool.NAME,
                ToolInput(mapOf("message" to Json.of("feat: x"), "paths" to Json.array(Json.of("a.kt")))),
                commitContext,
            ),
        )
        assertEquals(null, git.commitMessage)

        val success = assertIs<ToolResult.Success>(
            router.invoke(
                GitCommitTool.NAME,
                ToolInput(mapOf("message" to Json.of("feat: x"), "paths" to Json.array(Json.of("a.kt")))),
                commitContext.copy(approval = ToolApproval.granted()),
            ),
        )
        assertEquals(true, success.output.content["success"]?.booleanOrNull())
        assertEquals(listOf("a.kt"), git.added)
        assertEquals("feat: x", git.commitMessage)
    }

    @Test
    fun `git_commit without the git write grant is denied`() = runBlocking {
        val result = router(GitCommitTool(FakeGitService())).invoke(
            GitCommitTool.NAME,
            ToolInput(mapOf("message" to Json.of("feat: x"))),
            gitContext.copy(approval = ToolApproval.granted()),
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
    }

    @Test
    fun `a git failure maps to a structured tool error`() = runBlocking {
        val git = object : FakeGitService() {
            override suspend fun status(workspaceId: String): GitResult<GitStatus> =
                failure(GitError(GitErrorCode.NOT_A_REPOSITORY, "not a repo"))
        }
        val result = router(GitStatusTool(git)).invoke(GitStatusTool.NAME, ToolInput(), gitContext)
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
    }

    // --- web ---------------------------------------------------------------

    private class FakeHttpGetClient(
        val status: Int = 200,
        val body: String = "",
    ) : HttpGetClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpGetResponse =
            HttpGetResponse(statusCode = status, body = body, contentType = "text/html", finalUrl = url)
    }

    private val webContext = ToolExecutionContext(grantedPermissions = setOf(ToolPermissionLevel.NETWORK))

    @Test
    fun `web_fetch returns a structured body`() = runBlocking {
        val tool = WebFetchTool(FakeHttpGetClient(body = "<html>hi</html>"))
        val result = router(tool).invoke(
            WebFetchTool.NAME,
            ToolInput(mapOf("url" to Json.of("https://example.com"))),
            webContext,
        )
        val success = assertIs<ToolResult.Success>(result)
        assertEquals(200.0, success.output.content["statusCode"]?.numberOrNull())
        assertEquals("<html>hi</html>", success.output.content["body"]?.stringOrNull())
    }

    @Test
    fun `web_fetch rejects a non-http url`() = runBlocking {
        val result = router(WebFetchTool(FakeHttpGetClient())).invoke(
            WebFetchTool.NAME,
            ToolInput(mapOf("url" to Json.of("file:///etc/passwd"))),
            webContext,
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
    }

    @Test
    fun `web_search returns structured results from the provider`() = runBlocking {
        val provider = WebSearchProvider { _, _ ->
            listOf(WebSearchItem("AgentX", "https://agentx.app", "an agentic IDE"))
        }
        val result = router(WebSearchTool(provider)).invoke(
            WebSearchTool.NAME,
            ToolInput(mapOf("query" to Json.of("agentx"))),
            webContext,
        )
        val success = assertIs<ToolResult.Success>(result)
        assertEquals(1.0, success.output.content["count"]?.numberOrNull())
    }

    @Test
    fun `web_search requires the network grant`() = runBlocking {
        val result = router(WebSearchTool(WebSearchProvider { _, _ -> emptyList() })).invoke(
            WebSearchTool.NAME,
            ToolInput(mapOf("query" to Json.of("x"))),
            ToolExecutionContext(grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY)),
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
    }

    @Test
    fun `the DuckDuckGo provider parses real result anchors`() = runBlocking {
        val html = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org">Kotlin</a>
            <a class="result__snippet">The Kotlin language</a>
        """.trimIndent()
        val provider = DuckDuckGoWebSearchProvider(FakeHttpGetClient(body = html))
        val results = provider.search("kotlin", 5)
        assertEquals(1, results.size)
        assertEquals("Kotlin", results[0].title)
        assertEquals("https://kotlinlang.org", results[0].url)
        assertEquals("The Kotlin language", results[0].snippet)
    }
}

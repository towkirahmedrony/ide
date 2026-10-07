package com.agentx.app.tools.pullrequest

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.pullrequest.CreatedPullRequest
import com.agentx.app.core.pullrequest.NewPullRequest
import com.agentx.app.core.pullrequest.PullRequestError
import com.agentx.app.core.pullrequest.PullRequestRef
import com.agentx.app.core.pullrequest.PullRequestService
import com.agentx.app.core.success
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Json
import com.agentx.app.tools.JsonObject
import com.agentx.app.tools.JsonValue
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolApproval
import com.agentx.app.tools.ToolConnectionAuthorization
import com.agentx.app.tools.ToolConnectionAuthorizer
import com.agentx.app.tools.ToolConnectionCapability
import com.agentx.app.tools.ToolConnectionHandle
import com.agentx.app.tools.ToolConnectionRequirement
import com.agentx.app.tools.ToolConnectionType
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.numberOrNull
import com.agentx.app.tools.stringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The optional, approval-gated `create_pr` tool.
 *
 * These tests are about the boundaries the feature must keep: it never runs
 * without approval, it refuses a malformed or unsafe request before any network
 * call, it maps every structured failure without collapsing categories, and no
 * credential ever appears in an argument or a result.
 */
private const val TOKEN = "gho-secret-value"

private fun created(
    number: Int = 7,
    title: String = "Add the feature",
    state: String = "open",
    head: String = "feature/readme",
    base: String = "main",
    url: String = "https://github.com/octocat/hello-world/pull/7",
) = CreatedPullRequest(
    number = number,
    title = title,
    state = state,
    headBranch = head,
    baseBranch = base,
    repository = PullRequestRef("octocat", "hello-world"),
    htmlUrl = url,
)

class CreatePullRequestToolTest {

    private class RecordingPullRequestService(
        private val result: ForgeResult<CreatedPullRequest, PullRequestError> = success(created()),
    ) : PullRequestService {

        val requests = mutableListOf<NewPullRequest>()
        var throwable: Throwable? = null

        override suspend fun create(
            request: NewPullRequest,
        ): ForgeResult<CreatedPullRequest, PullRequestError> {
            throwable?.let { throw it }
            requests += request
            return result
        }
    }

    private fun provider(ref: PullRequestRef? = PullRequestRef("octocat", "hello-world")) =
        PullRequestRepositoryProvider { ref }

    private fun tool(
        service: PullRequestService = RecordingPullRequestService(),
        provider: PullRequestRepositoryProvider = provider(),
    ): Tool = CreatePullRequestTool(service, provider)

    private val grantingConnections = ToolConnectionAuthorizer { requirement, _ ->
        ToolConnectionAuthorization.Granted(
            ToolConnectionHandle(
                connectionId = "c1",
                displayName = "GitHub",
                type = requirement.type,
                capabilities = setOf(requirement.capability),
            ),
        )
    }

    private fun router(service: PullRequestService, provider: PullRequestRepositoryProvider) =
        DefaultToolRouter(
            registry = DefaultToolRegistry().apply { register(CreatePullRequestTool(service, provider)) },
            connections = grantingConnections,
        )

    private fun context(approval: ToolApproval? = ToolApproval.granted()): ToolExecutionContext =
        ToolExecutionContext(
            workspaceId = "w1",
            grantedPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
            approval = approval,
        )

    private fun arguments(vararg pairs: Pair<String, JsonValue>): JsonObject = linkedMapOf(*pairs)

    private fun invoke(
        service: PullRequestService,
        provider: PullRequestRepositoryProvider = provider(),
        args: JsonObject,
        context: ToolExecutionContext = context(),
    ): ToolResult = runBlocking {
        router(service, provider).invoke(CreatePullRequestTool.NAME, ToolInput(args), context)
    }

    private fun validArgs(extra: JsonObject = emptyMap()): JsonObject = arguments(
        "title" to Json.of("Add the feature"),
        "head" to Json.of("feature/readme"),
    ) + extra

    // --- success -----------------------------------------------------------

    @Test
    fun `an approved call creates the pull request and maps the result`() {
        val service = RecordingPullRequestService()

        val success = assertIs<ToolResult.Success>(invoke(service, args = validArgs()))

        val request = service.requests.single()
        assertEquals("Add the feature", request.title)
        assertEquals("feature/readme", request.head)
        assertEquals("main", request.base)
        assertEquals(PullRequestRef("octocat", "hello-world"), request.repository)
        assertEquals(7.0, success.output.content["number"]?.numberOrNull())
        assertEquals("https://github.com/octocat/hello-world/pull/7", success.output.content["url"]?.stringOrNull())
        assertEquals("open", success.output.content["state"]?.stringOrNull())
        assertEquals("main", success.output.content["base"]?.stringOrNull())
        assertEquals("feature/readme", success.output.content["head"]?.stringOrNull())
    }

    @Test
    fun `the tool result and arguments never carry a token`() {
        val definition = tool().definition
        assertNull(definition.inputSchema.parameter("token"))
        assertNull(definition.inputSchema.parameter("access_token"))
        assertNull(definition.inputSchema.parameter("authorization"))
        assertFalse(definition.description.contains("token"))

        val success = assertIs<ToolResult.Success>(invoke(RecordingPullRequestService(), args = validArgs()))
        assertFalse(success.output.content.containsKey("token"))
        assertFalse((success.output.displayText ?: "").contains(TOKEN))
    }

    @Test
    fun `the declared repository must match the active project`() {
        val service = RecordingPullRequestService()

        val failure = assertIs<ToolResult.Failure>(
            invoke(service, args = validArgs(mapOf("repository" to Json.of("someone/else")))),
        )

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `a named repository is accepted when it matches the active project`() {
        val service = RecordingPullRequestService()

        invoke(service, args = validArgs(mapOf("repository" to Json.of("Octocat/Hello-World"))))

        assertEquals(PullRequestRef("octocat", "hello-world"), service.requests.single().repository)
    }

    @Test
    fun `an explicit repository is used when no project is active`() {
        val service = RecordingPullRequestService()

        invoke(
            service,
            provider = provider(null),
            args = validArgs(mapOf("repository" to Json.of("someone/else"))),
        )

        assertEquals(PullRequestRef("someone", "else"), service.requests.single().repository)
    }

    @Test
    fun `no active repository and no name is a workspace failure`() {
        val service = RecordingPullRequestService()

        val failure = assertIs<ToolResult.Failure>(invoke(service, provider = provider(null), args = validArgs()))

        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
        assertTrue(service.requests.isEmpty())
    }

    // --- approval ----------------------------------------------------------

    @Test
    fun `the tool cannot run before approval is granted`() {
        val service = RecordingPullRequestService()

        val result = invoke(service, args = validArgs(), context = context(approval = null))

        assertIs<ToolResult.ApprovalRequired>(result)
        assertTrue(service.requests.isEmpty(), "a parked call must not reach GitHub")
    }

    @Test
    fun `a denied approval performs no pull-request mutation`() {
        val service = RecordingPullRequestService()

        val failure = assertIs<ToolResult.Failure>(
            invoke(service, args = validArgs(), context = context(approval = ToolApproval.denied("no"))),
        )

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `without the git write grant the tool is denied`() {
        val service = RecordingPullRequestService()

        val failure = assertIs<ToolResult.Failure>(
            invoke(
                service,
                args = validArgs(),
                context = ToolExecutionContext(
                    workspaceId = "w1",
                    grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY),
                    approval = ToolApproval.granted(),
                ),
            ),
        )

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertTrue(service.requests.isEmpty())
    }

    // --- validation --------------------------------------------------------

    @Test
    fun `a missing title is refused by the schema before the tool runs`() {
        val service = RecordingPullRequestService()

        val failure = assertIs<ToolResult.Failure>(
            invoke(service, args = arguments("head" to Json.of("feature/readme"))),
        )

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `head equal to base is a clear validation error`() {
        val service = RecordingPullRequestService()

        val failure = assertIs<ToolResult.Failure>(
            invoke(service, args = validArgs(mapOf("base" to Json.of("feature/readme")))),
        )

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertTrue(failure.error.message?.contains("must differ") == true)
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `an unsafe or malformed branch name is refused`() {
        val service = RecordingPullRequestService()

        listOf("refs/heads/main", "bad branch", "..", "feature~1", "-x", "main.lock").forEach { branch ->
            val failure = assertIs<ToolResult.Failure>(
                invoke(service, args = arguments("title" to Json.of("T"), "head" to Json.of(branch))),
            )
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code, branch)
        }
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `a malformed repository identifier is refused`() {
        val service = RecordingPullRequestService()

        val failure = assertIs<ToolResult.Failure>(
            invoke(service, args = validArgs(mapOf("repository" to Json.of("not-a-repo")))),
        )

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertTrue(service.requests.isEmpty())
    }

    // --- API error mapping -------------------------------------------------

    @Test
    fun `every structured pull-request error maps to a stable tool error`() {
        val cases = listOf(
            PullRequestError.NoConnection to ToolErrorCode.PERMISSION_DENIED,
            PullRequestError.Unauthenticated to ToolErrorCode.PERMISSION_DENIED,
            PullRequestError.Forbidden to ToolErrorCode.PERMISSION_DENIED,
            PullRequestError.NotFound("gone") to ToolErrorCode.INVALID_ARGUMENTS,
            PullRequestError.Conflict("dup") to ToolErrorCode.EXECUTION_FAILED,
            PullRequestError.ValidationFailed("bad") to ToolErrorCode.INVALID_ARGUMENTS,
            PullRequestError.RateLimited to ToolErrorCode.EXECUTION_FAILED,
            PullRequestError.NetworkFailure to ToolErrorCode.EXECUTION_FAILED,
            PullRequestError.ServerError(500) to ToolErrorCode.EXECUTION_FAILED,
            PullRequestError.MalformedResponse("bad") to ToolErrorCode.EXECUTION_FAILED,
            PullRequestError.Unknown("boom") to ToolErrorCode.EXECUTION_FAILED,
        )

        cases.forEach { (error, expected) ->
            val service = RecordingPullRequestService(result = failure(error))

            val result = invoke(service, args = validArgs())

            val failure = assertIs<ToolResult.Failure>(result, "error $error")
            assertEquals(expected, failure.error.code, "error $error")
            assertTrue(failure.error.details.containsKey("pullRequestCode"), "error $error")
        }
    }

    @Test
    fun `a failure message never carries a credential`() {
        val service = RecordingPullRequestService(
            result = failure(PullRequestError.Unknown("token=$TOKEN rejected")),
        )

        val failure = assertIs<ToolResult.Failure>(invoke(service, args = validArgs()))

        // The tool never invents a message carrying a token; it uses the sanitized one.
        assertFalse(failure.error.details.containsKey("token"))
    }

    // --- cancellation ------------------------------------------------------

    @Test
    fun `a cancelled request propagates and creates no pull request`() {
        val service = RecordingPullRequestService()
        service.throwable = CancellationException("cancelled")

        val thrown = runCatching { invoke(service, args = validArgs()) }.exceptionOrNull()

        assertIs<CancellationException>(thrown)
        assertTrue(service.requests.isEmpty(), "a cancelled call must not create a pull request")
    }

    // --- registration ------------------------------------------------------

    @Test
    fun `the tool is registered as a privileged, connection-scoped action`() {
        val definition = tool().definition

        assertEquals("create_pr", definition.name)
        assertEquals(ToolPermissionLevel.GIT_WRITE, definition.requiredPermissions.single())
        assertEquals(ToolPermissionDecision.ASK, definition.permission)
        val requirement: ToolConnectionRequirement = definition.connectionRequirement!!
        assertEquals(ToolConnectionType.GITHUB, requirement.type)
        assertEquals(ToolConnectionCapability.PULL_REQUEST, requirement.capability)
    }
}

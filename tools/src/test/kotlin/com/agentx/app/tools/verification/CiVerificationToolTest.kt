package com.agentx.app.tools.verification

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.verification.CiConclusion
import com.agentx.app.core.verification.CiJob
import com.agentx.app.core.verification.CiLogs
import com.agentx.app.core.verification.CiRepositoryRef
import com.agentx.app.core.verification.CiRun
import com.agentx.app.core.verification.CiRunState
import com.agentx.app.core.verification.CiStep
import com.agentx.app.core.verification.CiVerificationError
import com.agentx.app.core.verification.CiVerificationService
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Json
import com.agentx.app.tools.JsonObject
import com.agentx.app.tools.JsonValue
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.booleanOrNull
import com.agentx.app.tools.stringOrNull
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ci_verification` through the real registry and router, backed by a fake CI
 * service. It is read-only, resolves the repository itself and maps a run onto the
 * shared verification status; these tests assert that mapping, the structured
 * error categories and that logs are redacted.
 */
class CiVerificationToolTest {

    private val context = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY),
    )

    private val ref = CiRepositoryRef("towkirahmedrony", "ide")

    private class FakeService(
        private val run: ForgeResult<CiRun?, CiVerificationError> = success(null),
        private val detail: ForgeResult<CiRun, CiVerificationError>? = null,
        private val logs: ForgeResult<CiLogs, CiVerificationError>? = null,
    ) : CiVerificationService {
        override suspend fun latestRun(
            repository: CiRepositoryRef,
            branch: String,
            headSha: String?,
        ): ForgeResult<CiRun?, CiVerificationError> = run

        override suspend fun run(repository: CiRepositoryRef, runId: Long): ForgeResult<CiRun, CiVerificationError> =
            detail ?: failure(CiVerificationError.NotFound("missing"))

        override suspend fun logs(
            repository: CiRepositoryRef,
            runId: Long,
            jobId: Long?,
            maxChars: Int,
        ): ForgeResult<CiLogs, CiVerificationError> = logs ?: failure(CiVerificationError.NotFound("missing"))
    }

    private fun invoke(
        service: CiVerificationService,
        arguments: JsonObject = emptyMap(),
        repository: CiRepositoryRefProvider = CiRepositoryRefProvider { ref },
    ): ToolResult {
        val registry = DefaultToolRegistry().apply { register(CiVerificationTool(service, repository)) }
        return runBlocking {
            DefaultToolRouter(registry).invoke(CiVerificationTool.NAME, ToolInput(arguments), context)
        }
    }

    private fun run(
        conclusion: CiConclusion = CiConclusion.SUCCESS,
        state: CiRunState = CiRunState.COMPLETED,
        jobs: List<CiJob> = emptyList(),
    ) = CiRun(
        id = 100,
        name = "Build Android Release APK",
        workflowName = "Build Android Release APK",
        headSha = "abc",
        headBranch = "main",
        event = "push",
        state = state,
        conclusion = conclusion,
        jobs = jobs,
    )

    @Test
    fun `a successful run is reported as PASSED`() {
        val success = assertIs<ToolResult.Success>(invoke(FakeService(run = success(run()))))

        assertEquals("PASSED", success.output.content.stringOrNull("verificationStatus"))
        assertEquals(true, success.output.content.booleanOrNull("found"))
    }

    @Test
    fun `a failing run is reported as FAILED with the CI category`() {
        val job = CiJob(
            id = 1,
            name = "build",
            state = CiRunState.COMPLETED,
            conclusion = CiConclusion.FAILURE,
            steps = listOf(CiStep(1, "Build Release APK", CiConclusion.FAILURE)),
        )
        val service = FakeService(
            run = success(run(conclusion = CiConclusion.FAILURE, jobs = listOf(job))),
            detail = success(run(conclusion = CiConclusion.FAILURE, jobs = listOf(job))),
        )

        val success = assertIs<ToolResult.Success>(invoke(service))

        assertEquals("FAILED", success.output.content.stringOrNull("verificationStatus"))
        assertEquals("CI_FAILURE", success.output.content.stringOrNull("category"))
    }

    @Test
    fun `no run is reported as not found rather than a failure`() {
        val success = assertIs<ToolResult.Success>(invoke(FakeService(run = success(null))))

        assertEquals(false, success.output.content.booleanOrNull("found"))
        assertEquals("UNAVAILABLE", success.output.content.stringOrNull("verificationStatus"))
    }

    @Test
    fun `a specific run id is fetched through the service`() {
        val success = assertIs<ToolResult.Success>(
            invoke(FakeService(detail = success(run())), mapOf("run_id" to Json.of(100))),
        )

        assertEquals(true, success.output.content.booleanOrNull("found"))
    }

    @Test
    fun `a network failure is a structured execution failure, not a permission problem`() {
        val failure = assertIs<ToolResult.Failure>(
            invoke(FakeService(run = failure(CiVerificationError.NetworkFailure))),
        )

        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
        assertEquals("NETWORK_FAILURE", failure.error.details.stringOrNull("ciCode"))
    }

    @Test
    fun `no connection fails closed with a permission error`() {
        val failure = assertIs<ToolResult.Failure>(
            invoke(FakeService(run = failure(CiVerificationError.NoConnection))),
        )

        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertEquals("NO_CONNECTION", failure.error.details.stringOrNull("ciCode"))
    }

    @Test
    fun `a missing repository fails closed`() {
        val failure = assertIs<ToolResult.Failure>(
            invoke(FakeService(), repository = CiRepositoryRefProvider { null }),
        )

        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, failure.error.code)
    }

    @Test
    fun `requested logs are redacted before they reach the result`() {
        val secretLog = "step: build\ntoken = ghp_1234567890abcdefghij\nBUILD FAILED"
        val service = FakeService(
            run = success(run(conclusion = CiConclusion.FAILURE)),
            detail = success(run(conclusion = CiConclusion.FAILURE)),
            logs = success(CiLogs(runId = 100, jobId = 900, text = secretLog, truncated = false)),
        )

        val success = assertIs<ToolResult.Success>(
            invoke(service, mapOf("include_logs" to Json.of(true))),
        )
        val excerpt = success.output.content.stringOrNull("logExcerpt").orEmpty()

        assertTrue(excerpt.contains("BUILD FAILED"), excerpt)
        assertTrue(!excerpt.contains("ghp_1234567890abcdefghij"), excerpt)
    }

    @Test
    fun `the schema exposes only a branch, a run id and a log flag`() {
        val registry = DefaultToolRegistry().apply {
            register(CiVerificationTool(FakeService(), CiRepositoryRefProvider { ref }))
        }
        val definition = registry.find(CiVerificationTool.NAME)!!.definition

        assertTrue(definition.inputSchema.parameters.map { it.name }.containsAll(
            listOf("branch", "run_id", "include_logs"),
        ))
        listOf("repository", "owner", "url", "token", "credential").forEach { forbidden ->
            assertTrue(
                definition.inputSchema.parameter(forbidden) == null,
                "the model must not be able to supply '$forbidden'",
            )
        }
    }
}

package com.agentx.app.tools.verification

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.verification.CiJob
import com.agentx.app.core.verification.CiRepositoryRef
import com.agentx.app.core.verification.CiRun
import com.agentx.app.core.verification.CiVerificationError
import com.agentx.app.core.verification.CiVerificationService
import com.agentx.app.core.verification.toVerificationOutcome
import com.agentx.app.tools.Json
import com.agentx.app.tools.JsonValue
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
import com.agentx.app.tools.booleanOrNull
import com.agentx.app.tools.numberOrNull
import com.agentx.app.tools.stringOrNull

/** Resolves the repository whose CI the tool should observe. Never chosen by the model. */
fun interface CiRepositoryRefProvider {
    suspend fun resolve(): CiRepositoryRef?
}

/**
 * The agent's read-only window onto GitHub Actions.
 *
 * It reports a run's status, conclusion, job and step results and — when asked —
 * a bounded, redacted slice of logs, and it maps the run onto the shared
 * [com.agentx.app.core.verification.VerificationOutcome] so the loop and the model
 * agree on whether the build passed. The repository is resolved from the active
 * project, never from an argument, and the tool can do nothing but read: it has no
 * way to dispatch a workflow, cancel a run, or touch a secret.
 */
class CiVerificationTool(
    private val service: CiVerificationService,
    private val repository: CiRepositoryRefProvider,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "CI verification",
        description = "Reads the latest GitHub Actions run for this repository (status, conclusion, " +
            "jobs and steps) and optionally a bounded, redacted log excerpt. Read-only: it never " +
            "dispatches, cancels or edits a workflow.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_BRANCH,
                    type = ToolParameterType.STRING,
                    description = "Branch to inspect (default '$MAIN_BRANCH').",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_RUN_ID,
                    type = ToolParameterType.NUMBER,
                    description = "Specific workflow run id; when omitted the latest run for the branch is used.",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_INCLUDE_LOGS,
                    type = ToolParameterType.BOOLEAN,
                    description = "Include a bounded, redacted excerpt of the failing job's logs.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Run status, conclusion, jobs/steps and the verification status."),
        permission = ToolPermissionDecision.ALLOW,
        // Read-only, like the read-only git tools. The network call is fixed to
        // GitHub Actions through the credential gateway and the active project's
        // repository, so the model cannot redirect it — exactly as `git_status`
        // declares READ_ONLY. It never declares CREDENTIALS and never sees a token.
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none", "workflow" to "android-build"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val repositoryRef = repository.resolve()
            ?: throw ToolExecutionError(
                code = ToolErrorCode.WORKSPACE_UNAVAILABLE,
                message = "No GitHub repository is available to verify.",
                toolName = NAME,
            )
        val branch = input.string(ARG_BRANCH)?.takeIf { it.isNotBlank() } ?: MAIN_BRANCH
        val runId = input.number(ARG_RUN_ID)?.toLong()
        val includeLogs = input.boolean(ARG_INCLUDE_LOGS) ?: false

        val run = when {
            runId != null -> service.run(repositoryRef, runId).orThrowCi(NAME)
            else -> service.latestRun(repositoryRef, branch).orThrowCi(NAME)
                ?: return ToolOutput(
                    content = mapOf(
                        "repository" to Json.of(repositoryRef.fullName),
                        "branch" to Json.of(branch),
                        "found" to Json.of(false),
                        "verificationStatus" to Json.of("UNAVAILABLE"),
                    ),
                    displayText = "No GitHub Actions run found for '${repositoryRef.fullName}' on '$branch'.",
                )
        }

        val logExcerpt = if (includeLogs && run.completed && !run.succeeded) {
            when (val logs = service.logs(repositoryRef, run.id)) {
                is ForgeResult.Success -> SecretRedactor.redactText(logs.value.text).take(LOG_EXCERPT_CHARS)
                is ForgeResult.Failure -> ""
            }
        } else {
            ""
        }
        val outcome = run.toVerificationOutcome(logExcerpt)

        return ToolOutput(
            content = mapOf(
                "repository" to Json.of(repositoryRef.fullName),
                "found" to Json.of(true),
                "branch" to Json.of(branch),
                "verificationStatus" to Json.of(outcome.status.name),
                "category" to (outcome.failureCategory?.let { Json.of(it.name) } ?: JsonValue.Null),
                "run" to run.toJson(),
                "logExcerpt" to Json.of(logExcerpt),
                "truncated" to Json.of(logExcerpt.length >= LOG_EXCERPT_CHARS),
            ),
            displayText = render(run, outcome.summary),
        )
    }

    private fun CiRun.toJson(): JsonValue = Json.obj(
        "id" to Json.of(id),
        "name" to Json.of(name),
        "workflowName" to Json.of(workflowName),
        "headSha" to Json.of(headSha),
        "headBranch" to (headBranch?.let { Json.of(it) } ?: JsonValue.Null),
        "event" to (event?.let { Json.of(it) } ?: JsonValue.Null),
        "state" to Json.of(state.name),
        "conclusion" to (conclusion?.let { Json.of(it.name) } ?: JsonValue.Null),
        "completed" to Json.of(completed),
        "succeeded" to Json.of(succeeded),
        "htmlUrl" to (htmlUrl?.let { Json.of(it) } ?: JsonValue.Null),
        "jobs" to Json.array(jobs.map { it.toJson() }),
    )

    private fun CiJob.toJson(): JsonValue = Json.obj(
        "id" to Json.of(id),
        "name" to Json.of(name),
        "state" to Json.of(state.name),
        "conclusion" to (conclusion?.let { Json.of(it.name) } ?: JsonValue.Null),
        "failed" to Json.of(failed),
        "failedStep" to (failedStep?.let { Json.of(it.name) } ?: JsonValue.Null),
        "steps" to Json.array(
            steps.map { step ->
                Json.obj(
                    "number" to Json.of(step.number),
                    "name" to Json.of(step.name),
                    "conclusion" to (step.conclusion?.let { Json.of(it.name) } ?: JsonValue.Null),
                )
            },
        ),
    )

    private fun render(run: CiRun, summary: String): String = buildString {
        append("Run #").append(run.id).append(" (").append(run.workflowName).append(")\n")
        append("state=").append(run.state.name.lowercase())
        append(" conclusion=").append(run.conclusion?.name?.lowercase() ?: "none")
        append('\n').append(summary)
        val job = run.jobs.firstOrNull { it.failed }
        if (job != null) append("\nfailed job: ").append(job.name)
        job?.failedStep?.let { append(" → step '").append(it.name).append('\'') }
    }

    private fun <T> ForgeResult<T, CiVerificationError>.orThrowCi(toolName: String): T = when (this) {
        is ForgeResult.Success -> value
        is ForgeResult.Failure -> throw error.toToolExecutionError(toolName)
    }

    companion object {
        const val NAME: String = "ci_verification"
        const val ARG_BRANCH: String = "branch"
        const val ARG_RUN_ID: String = "run_id"
        const val ARG_INCLUDE_LOGS: String = "include_logs"
        const val MAIN_BRANCH: String = "main"
        const val LOG_EXCERPT_CHARS: Int = 4_000
    }
}

/** Maps a structured CI error onto the tool contract without collapsing categories. */
internal fun CiVerificationError.toToolExecutionError(toolName: String): ToolExecutionError {
    val code = when (this) {
        CiVerificationError.NoConnection,
        CiVerificationError.Unauthenticated,
        CiVerificationError.Forbidden,
        -> ToolErrorCode.PERMISSION_DENIED

        is CiVerificationError.NotFound,
        CiVerificationError.RateLimited,
        CiVerificationError.NetworkFailure,
        is CiVerificationError.MalformedResponse,
        is CiVerificationError.ServerError,
        is CiVerificationError.Unknown,
        -> ToolErrorCode.EXECUTION_FAILED
    }
    return ToolExecutionError(
        code = code,
        message = messageFor(this),
        toolName = toolName,
        details = mapOf("ciCode" to Json.of(codeName(this))),
    )
}

private fun codeName(error: CiVerificationError): String = when (error) {
    CiVerificationError.NoConnection -> "NO_CONNECTION"
    CiVerificationError.Unauthenticated -> "UNAUTHENTICATED"
    CiVerificationError.Forbidden -> "FORBIDDEN"
    is CiVerificationError.NotFound -> "NOT_FOUND"
    CiVerificationError.RateLimited -> "RATE_LIMITED"
    CiVerificationError.NetworkFailure -> "NETWORK_FAILURE"
    is CiVerificationError.MalformedResponse -> "MALFORMED_RESPONSE"
    is CiVerificationError.ServerError -> "SERVER_ERROR"
    is CiVerificationError.Unknown -> "UNKNOWN"
}

private fun messageFor(error: CiVerificationError): String = when (error) {
    CiVerificationError.NoConnection -> "No connected GitHub account is available for CI verification."
    CiVerificationError.Unauthenticated -> "The GitHub credential expired; reconnect the account."
    CiVerificationError.Forbidden -> "GitHub refused the CI request."
    is CiVerificationError.NotFound -> "The requested CI run was not found."
    CiVerificationError.RateLimited -> "GitHub rate-limited the CI request."
    CiVerificationError.NetworkFailure -> "The CI request could not reach GitHub."
    is CiVerificationError.MalformedResponse -> "GitHub sent an unreadable CI response."
    is CiVerificationError.ServerError -> "GitHub failed to answer the CI request."
    is CiVerificationError.Unknown -> error.message
}

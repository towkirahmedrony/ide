package com.agentx.app.core.verification

import com.agentx.app.core.ForgeResult

/**
 * The outcome state of one verification stage.
 *
 * Deliberately small and total: every verification either passed, failed, could
 * not be run at all ([UNAVAILABLE] — for example no CI is wired), or was
 * cancelled. There is no "unknown": a check either reached a conclusion or the
 * stage reports itself unavailable.
 */
enum class VerificationStatus {
    PASSED,
    FAILED,
    UNAVAILABLE,
    CANCELLED,
}

/**
 * Structured failure categories shared by verification, CI, Git and tool
 * failures, so the agent loop and the UI can react without parsing a message.
 *
 * This is the single vocabulary the platform uses for the categories the
 * autonomous workflow must distinguish.
 */
enum class VerificationCategory {
    /** A tool call failed while preparing or running verification. */
    TOOL_FAILURE,

    /** A verification step ran and returned a failing result. */
    VERIFICATION_FAILURE,

    /** The CI workflow completed with a failing conclusion. */
    CI_FAILURE,

    /** GitHub answered with an error that is not authentication or transport. */
    GITHUB_API_FAILURE,

    /** A credential was rejected or is missing; the account must be reconnected. */
    AUTHENTICATION_FAILURE,

    /** DNS/TLS/timeout: the remote could not be reached. */
    NETWORK_FAILURE,

    /** The user cancelled the run. */
    CANCELLATION,

    /** A required approval was denied. */
    APPROVAL_DENIED,

    /** A workspace or path security rule refused the operation. */
    WORKSPACE_SECURITY_FAILURE,

    /** A likely credential was found in the change set. */
    SECRET_DETECTED,

    /** An unexpected failure that is none of the above. */
    UNKNOWN,
}

/**
 * One verification check, in a form that is safe to show the model and the user.
 *
 * It never carries a secret: [errorOutput] is expected to be already redacted by
 * the producer, and [result] carries only a coarse, non-sensitive status such as
 * `conclusion=failure` or `exit=1`.
 */
data class VerificationCheck(
    val name: String,
    val status: VerificationStatus,
    val category: VerificationCategory? = null,
    /** Coarse result status, e.g. `conclusion=failure`, `exit=1`. Never a secret. */
    val result: String? = null,
    /** Bounded, redacted error output relevant to the failure. */
    val errorOutput: String = "",
    /** Where the failure was found, e.g. a workflow step or `path:line`. */
    val location: String? = null,
    val summary: String = "",
)

/**
 * The structured result of a verification stage.
 *
 * A failure carries safe information only: the check name, its result status, the
 * relevant (redacted) error output and a category. Nothing here is a secret, and
 * the model only ever sees this object — never raw provider or CI payloads.
 */
data class VerificationOutcome(
    val status: VerificationStatus,
    val checks: List<VerificationCheck> = emptyList(),
    val summary: String = "",
) {
    val passed: Boolean get() = status == VerificationStatus.PASSED

    val failed: Boolean get() = status == VerificationStatus.FAILED

    val unavailable: Boolean get() = status == VerificationStatus.UNAVAILABLE

    val cancelled: Boolean get() = status == VerificationStatus.CANCELLED

    /** The category of the first failing check, so the loop can classify the failure. */
    val failureCategory: VerificationCategory?
        get() = checks.firstOrNull { it.status == VerificationStatus.FAILED }?.category

    companion object {
        fun passed(summary: String, checks: List<VerificationCheck> = emptyList()): VerificationOutcome =
            VerificationOutcome(VerificationStatus.PASSED, checks, summary)

        fun failed(
            summary: String,
            checks: List<VerificationCheck> = emptyList(),
        ): VerificationOutcome = VerificationOutcome(VerificationStatus.FAILED, checks, summary)

        fun unavailable(reason: String): VerificationOutcome =
            VerificationOutcome(VerificationStatus.UNAVAILABLE, emptyList(), reason)

        fun cancelled(reason: String = "Cancelled"): VerificationOutcome =
            VerificationOutcome(VerificationStatus.CANCELLED, emptyList(), reason)
    }
}

/** Owner/name coordinates of the repository whose CI is being observed. */
data class CiRepositoryRef(val owner: String, val name: String) {
    init {
        require(owner.isNotBlank()) { "Repository owner must not be blank" }
        require(name.isNotBlank()) { "Repository name must not be blank" }
    }

    val fullName: String get() = "$owner/$name"
}

/** The lifecycle state of a workflow run or job, as GitHub reports it. */
enum class CiRunState {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    UNKNOWN,
}

/** The terminal conclusion of a workflow run, job or step. */
enum class CiConclusion {
    SUCCESS,
    FAILURE,
    CANCELLED,
    TIMED_OUT,
    STARTUP_FAILURE,
    ACTION_REQUIRED,
    NEUTRAL,
    SKIPPED,
    STALE,
    UNKNOWN,
    ;

    val isFailure: Boolean
        get() = this in setOf(FAILURE, TIMED_OUT, STARTUP_FAILURE, ACTION_REQUIRED)
}

/** One step within a CI job. */
data class CiStep(
    val number: Int,
    val name: String,
    val conclusion: CiConclusion?,
)

/** One job of a CI run, with its steps when they were fetched. */
data class CiJob(
    val id: Long,
    val name: String,
    val state: CiRunState,
    val conclusion: CiConclusion?,
    val steps: List<CiStep> = emptyList(),
) {
    val completed: Boolean get() = state == CiRunState.COMPLETED
    val failed: Boolean get() = conclusion?.isFailure == true

    /** The first failing step, when one is known. */
    val failedStep: CiStep? get() = steps.firstOrNull { it.conclusion?.isFailure == true }
}

/**
 * A CI workflow run reduced to the fields the verification loop needs: identity,
 * the commit it was triggered for, its state and conclusion, and its jobs.
 *
 * It never carries repository secrets, tokens or even full raw payloads.
 */
data class CiRun(
    val id: Long,
    val name: String,
    val workflowName: String,
    val headSha: String,
    val headBranch: String?,
    val event: String?,
    val state: CiRunState,
    val conclusion: CiConclusion?,
    val htmlUrl: String? = null,
    val jobs: List<CiJob> = emptyList(),
) {
    val completed: Boolean get() = state == CiRunState.COMPLETED
    val succeeded: Boolean get() = completed && conclusion == CiConclusion.SUCCESS
    val failed: Boolean get() = completed && conclusion?.isFailure == true
}

/** A bounded, redacted slice of build/test logs. */
data class CiLogs(
    val runId: Long,
    val jobId: Long?,
    val text: String,
    val truncated: Boolean,
)

/** A structured reason a CI verification call could not be answered. */
sealed interface CiVerificationError {
    /** No connected GitHub account with the required access is available. */
    data object NoConnection : CiVerificationError {
        override fun toString(): String = "NoConnection"
    }

    /** 401: the credential is invalid or expired. */
    data object Unauthenticated : CiVerificationError {
        override fun toString(): String = "Unauthenticated"
    }

    /** 403: GitHub refused the request. */
    data object Forbidden : CiVerificationError {
        override fun toString(): String = "Forbidden"
    }

    /** 404: the repository or run does not exist. */
    data class NotFound(val detail: String) : CiVerificationError {
        override fun toString(): String = "NotFound($detail)"
    }

    /** 429 or a depleted quota. */
    data object RateLimited : CiVerificationError {
        override fun toString(): String = "RateLimited"
    }

    /** DNS, TLS, timeout, or no route to GitHub. */
    data object NetworkFailure : CiVerificationError {
        override fun toString(): String = "NetworkFailure"
    }

    /** GitHub answered, but not with JSON this client can read. */
    data class MalformedResponse(val detail: String) : CiVerificationError {
        override fun toString(): String = "MalformedResponse($detail)"
    }

    /** A 5xx from GitHub. */
    data class ServerError(val code: Int) : CiVerificationError {
        override fun toString(): String = "ServerError($code)"
    }

    data class Unknown(val message: String) : CiVerificationError {
        override fun toString(): String = "Unknown($message)"
    }
}

/**
 * Read-only access to GitHub Actions results.
 *
 * This is the only surface the verification workflow uses to observe CI; it can
 * list the latest run for a ref, fetch one run with its jobs, and fetch a bounded
 * slice of job logs. It can never mutate a workflow, dispatch a run, or read a
 * secret: the credential is lent only into the HTTP call, exactly like the
 * existing repository service.
 */
interface CiVerificationService {

    /**
     * The most recent workflow run for [branch], optionally restricted to
     * [headSha]. Returns `null` (a successful result) when no run matches yet —
     * a run that has not appeared is not an error.
     */
    suspend fun latestRun(
        repository: CiRepositoryRef,
        branch: String,
        headSha: String? = null,
    ): ForgeResult<CiRun?, CiVerificationError>

    /** One run, with its jobs and their steps. */
    suspend fun run(
        repository: CiRepositoryRef,
        runId: Long,
    ): ForgeResult<CiRun, CiVerificationError>

    /**
     * A bounded, redacted slice of the run's logs. When [jobId] is null the first
     * failing job is chosen; when no job failed, the first job is used.
     */
    suspend fun logs(
        repository: CiRepositoryRef,
        runId: Long,
        jobId: Long? = null,
        maxChars: Int = DEFAULT_LOG_CHARS,
    ): ForgeResult<CiLogs, CiVerificationError>

    companion object {
        /** Upper bound on log characters returned, so logs cannot flood the model. */
        const val DEFAULT_LOG_CHARS: Int = 20_000
    }
}

/**
 * Maps a CI run onto a [VerificationOutcome]. Pure and shared, so the CI runner,
 * the tool and the tests all classify a run identically.
 */
fun CiRun.toVerificationOutcome(failedLogExcerpt: String = ""): VerificationOutcome {
    if (!completed) {
        return VerificationOutcome.failed(
            summary = "Workflow '$workflowName' run #$id is still ${state.name.lowercase()}",
            checks = listOf(
                VerificationCheck(
                    name = workflowName,
                    status = VerificationStatus.FAILED,
                    category = VerificationCategory.CI_FAILURE,
                    result = "state=${state.name.lowercase()}",
                    summary = "CI has not completed",
                ),
            ),
        )
    }
    if (succeeded) {
        return VerificationOutcome.passed(
            summary = "Workflow '$workflowName' run #$id succeeded",
            checks = listOf(
                VerificationCheck(
                    name = workflowName,
                    status = VerificationStatus.PASSED,
                    result = "conclusion=success",
                ),
            ),
        )
    }
    val failedJob = jobs.firstOrNull { it.failed }
    val check = VerificationCheck(
        name = failedJob?.name ?: workflowName,
        status = VerificationStatus.FAILED,
        category = VerificationCategory.CI_FAILURE,
        result = "conclusion=${(conclusion ?: CiConclusion.UNKNOWN).name.lowercase()}",
        errorOutput = failedLogExcerpt,
        location = failedJob?.failedStep?.name,
        summary = failedJob?.failedStep?.let { "Step '${it.name}' failed" }.orEmpty(),
    )
    return VerificationOutcome.failed(
        summary = "Workflow '$workflowName' run #$id failed",
        checks = listOf(check),
    )
}

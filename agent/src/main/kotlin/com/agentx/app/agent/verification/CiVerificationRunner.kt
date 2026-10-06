package com.agentx.app.agent.verification

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.verification.CiLogs
import com.agentx.app.core.verification.CiRun
import com.agentx.app.core.verification.CiVerificationError
import com.agentx.app.core.verification.CiVerificationService
import com.agentx.app.core.verification.VerificationCategory
import com.agentx.app.core.verification.VerificationCheck
import com.agentx.app.core.verification.VerificationOutcome
import com.agentx.app.core.verification.VerificationStatus
import com.agentx.app.core.verification.toVerificationOutcome
import com.agentx.app.tools.SecretRedactor
import com.agentx.app.tools.verification.CiRepositoryRefProvider
import kotlinx.coroutines.delay

/**
 * Observes GitHub Actions as the authoritative Android build verification.
 *
 * There is no local Gradle/APK build in the coding environment, so the repository's
 * existing Actions workflow is the source of truth: this runner polls for the run
 * triggered by the pushed commit, waits for it to complete, reads the failing job's
 * logs and maps the run onto the shared [VerificationOutcome].
 *
 * The repository is resolved from the active project, never from the model, and the
 * service it reads through is read-only — this runner cannot dispatch a workflow,
 * cancel a run or touch a credential.
 */
class CiVerificationRunner(
    private val service: CiVerificationService,
    private val repository: CiRepositoryRefProvider,
    private val branch: String = MAIN_BRANCH,
    /** The commit whose run should be observed; when null the latest run for the branch is used. */
    private val headShaProvider: suspend () -> String? = { null },
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    /** Injected so tests wait deterministically; production delays. */
    private val pause: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val onCancelled: () -> Boolean = { false },
) : VerificationRunner {

    override suspend fun verify(): VerificationOutcome {
        val ref = repository.resolve()
            ?: return VerificationOutcome.unavailable("No GitHub repository is available to verify.")
        val headSha = headShaProvider()

        var attempts = 0
        while (attempts < maxAttempts) {
            if (onCancelled()) return VerificationOutcome.cancelled()
            attempts += 1

            val run = when (val latest = service.latestRun(ref, branch, headSha)) {
                is ForgeResult.Success -> latest.value
                is ForgeResult.Failure -> return latest.error.toOutcome()
            }

            if (run == null) {
                if (attempts < maxAttempts) pause(pollIntervalMillis)
                continue
            }
            if (!run.completed) {
                if (attempts < maxAttempts) pause(pollIntervalMillis)
                continue
            }

            // Fetch the run with its jobs so the failing step is known, then read a
            // bounded, redacted slice of the failing job's logs for diagnosis.
            val detailed = when (val fetched = service.run(ref, run.id)) {
                is ForgeResult.Success -> fetched.value
                is ForgeResult.Failure -> run
            }
            val logs = logsExcerpt(ref, detailed)
            return detailed.toVerificationOutcome(logs)
        }

        if (onCancelled()) return VerificationOutcome.cancelled()
        return VerificationOutcome.unavailable(
            "No completed GitHub Actions run appeared for '$branch' after $maxAttempts checks.",
        )
    }

    private suspend fun logsExcerpt(
        ref: com.agentx.app.core.verification.CiRepositoryRef,
        run: CiRun,
    ): String {
        if (run.succeeded) return ""
        return when (val logs = service.logs(ref, run.id)) {
            is ForgeResult.Success -> redact(logs.value)
            is ForgeResult.Failure -> ""
        }
    }

    private fun redact(logs: CiLogs): String {
        val redacted = SecretRedactor.redactText(logs.text)
        // Prefer the end of the log: a build failure is reported there.
        val tail = if (redacted.length > LOG_TAIL_CHARS) redacted.takeLast(LOG_TAIL_CHARS) else redacted
        return tail
    }

    companion object {
        const val MAIN_BRANCH: String = "main"

        /** ~10 minutes of polling at the default interval. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 40
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 15_000
        const val LOG_TAIL_CHARS: Int = 6_000
    }
}

/** Maps a structured CI error onto a verification outcome, preserving its category. */
fun CiVerificationError.toOutcome(): VerificationOutcome = when (this) {
    CiVerificationError.NoConnection -> VerificationOutcome.unavailable(
        "No connected GitHub account is available for CI verification.",
    )

    CiVerificationError.Unauthenticated -> failedWith(
        VerificationCategory.AUTHENTICATION_FAILURE,
        "The GitHub credential expired; reconnect the account.",
    )

    CiVerificationError.Forbidden -> failedWith(
        VerificationCategory.GITHUB_API_FAILURE,
        "GitHub refused the CI request.",
    )

    is CiVerificationError.NotFound -> failedWith(
        VerificationCategory.GITHUB_API_FAILURE,
        "The CI run was not found.",
    )

    CiVerificationError.RateLimited -> failedWith(
        VerificationCategory.GITHUB_API_FAILURE,
        "GitHub rate-limited the CI request.",
    )

    CiVerificationError.NetworkFailure -> failedWith(
        VerificationCategory.NETWORK_FAILURE,
        "The CI request could not reach GitHub.",
    )

    is CiVerificationError.MalformedResponse -> failedWith(
        VerificationCategory.GITHUB_API_FAILURE,
        "GitHub sent an unreadable CI response.",
    )

    is CiVerificationError.ServerError -> failedWith(
        VerificationCategory.GITHUB_API_FAILURE,
        "GitHub failed to answer the CI request.",
    )

    is CiVerificationError.Unknown -> failedWith(
        VerificationCategory.GITHUB_API_FAILURE,
        message,
    )
}

private fun CiVerificationError.failedWith(
    category: VerificationCategory,
    message: String,
): VerificationOutcome = VerificationOutcome(
    status = VerificationStatus.FAILED,
    summary = message,
    checks = listOf(
        VerificationCheck(
            name = "ci_verification",
            status = VerificationStatus.FAILED,
            category = category,
            summary = message,
        ),
    ),
)

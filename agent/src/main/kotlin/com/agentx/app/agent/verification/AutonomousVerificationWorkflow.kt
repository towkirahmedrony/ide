package com.agentx.app.agent.verification

import com.agentx.app.agent.domain.AgentActivity
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.core.verification.VerificationCategory
import com.agentx.app.core.verification.VerificationCheck
import com.agentx.app.core.verification.VerificationOutcome
import com.agentx.app.core.verification.VerificationStatus

/**
 * Bounds for the autonomous verify → diagnose → fix → re-verify loop.
 *
 * The correction budget is deliberately small and explicit: it exists so an agent
 * cannot push speculative fixes forever. It is never raised silently — a caller
 * that wants more cycles must say so.
 */
data class VerificationPolicy(
    /** Maximum number of fix cycles after the first failed verification. */
    val maxCorrectionCycles: Int = DEFAULT_MAX_CORRECTION_CYCLES,
) {
    init {
        require(maxCorrectionCycles >= 0) { "maxCorrectionCycles must not be negative" }
    }

    companion object {
        const val DEFAULT_MAX_CORRECTION_CYCLES: Int = 2
        val DEFAULT: VerificationPolicy = VerificationPolicy()
    }
}

/** The outcome of publishing the current change set (commit + push) to the remote. */
sealed interface PublishOutcome {
    /** The change set reached the remote; CI was triggered for [commitSha] when known. */
    data class Published(val commitSha: String? = null) : PublishOutcome

    /** A safety guard or the remote refused the publish; the workflow must stop. */
    data class Blocked(val reason: String, val category: VerificationCategory) : PublishOutcome

    /** The user cancelled (or the workflow was cancelled) before anything was pushed. */
    data object Cancelled : PublishOutcome
}

/**
 * Runs a verification stage. Implementations talk to CI (or a local check) through
 * an existing service; the workflow never calls a provider directly.
 */
fun interface VerificationRunner {
    suspend fun verify(): VerificationOutcome
}

/**
 * The end state of one autonomous verification workflow.
 *
 * Every case carries the structured [VerificationOutcome] the workflow ended on, so
 * the caller (and the model) can report the real CI state rather than assume that a
 * code change completed the task.
 */
sealed interface VerificationWorkflowResult {
    /** Verification passed; [correctionCycles] fixes were needed (0 when it passed first). */
    data class Verified(
        val outcome: VerificationOutcome,
        val correctionCycles: Int,
    ) : VerificationWorkflowResult

    /** Verification kept failing and the correction budget was exhausted. */
    data class Exhausted(
        val outcome: VerificationOutcome,
        val correctionCycles: Int,
    ) : VerificationWorkflowResult

    /** No verification could be run at all (no CI, no connection). */
    data class Unavailable(val outcome: VerificationOutcome) : VerificationWorkflowResult

    /** The workflow stopped before verification, e.g. the turn failed or a guard blocked it. */
    data class Blocked(val reason: String, val category: VerificationCategory) : VerificationWorkflowResult

    /** The user cancelled; no further fix cycles run and nothing is pushed. */
    data object Cancelled : VerificationWorkflowResult
}

/**
 * The autonomous coding verification workflow: Inspect → Understand → Plan → Read →
 * Modify → Diff → Verify → Fix → Re-verify → Commit → Push.
 *
 * It does **not** introduce a second agent loop. Each "turn" is an existing agent
 * run supplied by the caller (the same loop, the same tools, the same approval
 * gate); this class only sequences those turns around verification, enforces a
 * bounded correction budget, emits safe activity events, and refuses to continue —
 * or to publish anything — once the run is cancelled or a guard blocks it.
 *
 * The workflow owns no Git, no workspace and no approval logic: publish is a
 * callback, and verification goes through a [VerificationRunner].
 */
class AutonomousVerificationWorkflow(
    private val policy: VerificationPolicy = VerificationPolicy.DEFAULT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Runs [initialTurn], then verifies and — when verification fails — diagnoses,
     * runs a fix turn, publishes it and re-verifies, up to [VerificationPolicy].
     *
     * [initialTurn] and [fixTurn] return the existing [AgentResult]; a turn that
     * did not complete fails the workflow instead of being verified as though the
     * change had landed.
     *
     * [publish] commits and pushes the current change set so CI can run. It is
     * called before every verification (including the first), because the Android
     * build is only triggered by a push. It must return [PublishOutcome.Cancelled]
     * rather than pushing when the run has been cancelled.
     */
    suspend fun run(
        sessionId: String,
        role: AgentRole,
        sink: AgentEventSink,
        initialTurn: suspend () -> AgentResult,
        fixTurn: suspend (diagnosis: String) -> AgentResult,
        runner: VerificationRunner,
        publish: suspend (message: String) -> PublishOutcome = { PublishOutcome.Published() },
        onCancelled: () -> Boolean = { false },
    ): VerificationWorkflowResult {
        val initial = initialTurn()
        if (onCancelled() || initial.status == AgentStatus.CANCELLED) {
            return finishCancelled(sessionId, role, sink)
        }
        if (initial.status != AgentStatus.COMPLETED) {
            val reason = initial.errors.firstOrNull()?.message
                ?: "The initial turn ended with ${initial.status}"
            emit(sink, sessionId, role, AgentActivity.BLOCKED, reason)
            return VerificationWorkflowResult.Blocked(reason, VerificationCategory.TOOL_FAILURE)
        }

        var cycles = 0
        var outcome = verify(sink, sessionId, role, runner, onCancelled)
            ?: return finishCancelled(sessionId, role, sink)

        while (!outcome.passed) {
            if (onCancelled() || outcome.status == VerificationStatus.CANCELLED) {
                return finishCancelled(sessionId, role, sink)
            }
            if (outcome.status == VerificationStatus.UNAVAILABLE) {
                emit(sink, sessionId, role, AgentActivity.BLOCKED, outcome.summary)
                return VerificationWorkflowResult.Unavailable(outcome)
            }

            emit(
                sink,
                sessionId,
                role,
                AgentActivity.VERIFICATION_FAILED,
                outcome.failureCategory?.name ?: "VERIFICATION_FAILED",
            )

            if (cycles >= policy.maxCorrectionCycles) {
                emit(sink, sessionId, role, AgentActivity.BLOCKED, "Correction budget exhausted")
                return VerificationWorkflowResult.Exhausted(outcome, cycles)
            }

            // Diagnose and fix, then publish the fix and re-verify. A cancelled run
            // stops here and never reaches publish.
            emit(sink, sessionId, role, AgentActivity.FIXING, diagnosisFor(outcome))
            val fix = fixTurn(diagnosisFor(outcome))
            if (onCancelled() || fix.status == AgentStatus.CANCELLED) {
                return finishCancelled(sessionId, role, sink)
            }
            if (fix.status != AgentStatus.COMPLETED) {
                val reason = fix.errors.firstOrNull()?.message ?: "The fix turn ended with ${fix.status}"
                emit(sink, sessionId, role, AgentActivity.BLOCKED, reason)
                return VerificationWorkflowResult.Blocked(reason, VerificationCategory.TOOL_FAILURE)
            }

            when (val published = publish(publishMessage(cycles + 1))) {
                is PublishOutcome.Published -> Unit
                is PublishOutcome.Blocked -> {
                    emit(sink, sessionId, role, AgentActivity.BLOCKED, published.reason)
                    return VerificationWorkflowResult.Blocked(published.reason, published.category)
                }
                PublishOutcome.Cancelled -> return finishCancelled(sessionId, role, sink)
            }

            cycles += 1
            outcome = verify(sink, sessionId, role, runner, onCancelled, reVerifying = true)
                ?: return finishCancelled(sessionId, role, sink)
        }

        emit(sink, sessionId, role, AgentActivity.COMPLETED, "Verified")
        return VerificationWorkflowResult.Verified(outcome, cycles)
    }

    /**
     * Runs one verification, emitting the safe activity. Returns null when the run
     * was cancelled around the call.
     */
    private suspend fun verify(
        sink: AgentEventSink,
        sessionId: String,
        role: AgentRole,
        runner: VerificationRunner,
        onCancelled: () -> Boolean,
        reVerifying: Boolean = false,
    ): VerificationOutcome? {
        emit(
            sink,
            sessionId,
            role,
            if (reVerifying) AgentActivity.RE_VERIFYING else AgentActivity.VERIFYING,
            null,
        )
        val outcome = runner.verify()
        if (onCancelled() || outcome.status == VerificationStatus.CANCELLED) return null
        return outcome
    }

    private fun finishCancelled(
        sessionId: String,
        role: AgentRole,
        sink: AgentEventSink,
    ): VerificationWorkflowResult {
        emit(sink, sessionId, role, AgentActivity.CANCELLED, "Cancelled")
        return VerificationWorkflowResult.Cancelled
    }

    private fun emit(
        sink: AgentEventSink,
        sessionId: String,
        role: AgentRole,
        activity: AgentActivity,
        detail: String?,
    ) {
        sink.emit(
            AgentEvent.ActivityChanged(
                sessionId = sessionId,
                role = role,
                activity = activity,
                detail = detail?.take(DETAIL_MAX_CHARS),
                timestampMillis = clock(),
            ),
        )
    }

    /**
     * A safe, bounded diagnosis for the fix prompt: the failing check names, the
     * category, the location and a truncated excerpt of the (already redacted)
     * error output. It never includes a secret, a raw provider payload, or model
     * reasoning.
     */
    fun diagnosisFor(outcome: VerificationOutcome): String = buildString {
        append("Verification failed: ").append(outcome.summary).append('\n')
        for (check in outcome.checks.filter { it.status == VerificationStatus.FAILED }) {
            append("- [").append(check.category?.name ?: "VERIFICATION_FAILURE").append("] ")
            append(check.name)
            check.result?.let { append(" (").append(it).append(')') }
            check.location?.let { append(" at ").append(it) }
            append('\n')
            appendDiagnostic(check)
        }
    }.trim()

    private fun StringBuilder.appendDiagnostic(check: VerificationCheck) {
        if (check.summary.isNotBlank()) append("  ").append(check.summary).append('\n')
        if (check.errorOutput.isNotBlank()) {
            append("  log:\n")
            check.errorOutput.take(DIAGNOSIS_MAX_CHARS).lineSequence().forEach { line ->
                append("    ").append(line).append('\n')
            }
        }
    }

    private fun publishMessage(cycle: Int): String =
        if (cycle <= 1) "fix: address CI verification failure" else "fix: address CI verification failure (cycle $cycle)"

    private companion object {
        const val DETAIL_MAX_CHARS: Int = 200
        const val DIAGNOSIS_MAX_CHARS: Int = 2_000
    }
}
